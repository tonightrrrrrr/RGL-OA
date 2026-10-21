package com.yizhaoqi.oaarchive;

import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.ledger.LedgerService;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.pipeline.RecordCleanupService;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import com.yizhaoqi.oaarchive.template.TemplatePreviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端用例（技术文档 13.4 E01、E04、E06、E07、E09）：导入示例文件夹 → 识别 → 预览 → 归档 → 台账 → 搜索 → 撤销。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("oa.home", () -> tmp.resolve("home").toString());
        r.add("oa.archive.default-root", () -> tmp.resolve("归档 结果").toString());
        r.add("oa.desktop.tray", () -> "false");
        r.add("oa.desktop.open-browser", () -> "false");
    }

    @Autowired
    BatchService batches;
    @Autowired
    ArchiveService archive;
    @Autowired
    FileRecordRepository files;
    @Autowired
    LedgerService ledger;
    @Autowired
    MockMvc mvc;
    @Autowired
    RecordCleanupService cleanup;
    @Autowired
    TemplateService templates;
    @Autowired
    TemplatePreviewService previews;
    @Autowired
    LedgerEntryRepository ledgerRepo;

    @Test
    void importArchiveSearchUndo() throws Exception {
        // 源目录里混入 Office 临时文件和空文件，应被过滤（E01）
        Path src = Files.createDirectories(tmp.resolve("源 文件夹"));
        try (Stream<Path> s = Files.list(Path.of("src/test/resources/samples/A"))) {
            for (Path p : s.toList()) {
                Files.copy(p, src.resolve(p.getFileName()));
            }
        }
        Files.writeString(src.resolve("~$临时.docx"), "lock");
        Files.createFile(src.resolve("空文件.txt"));

        ArchiveBatch b = batches.createFromFolder(src.toString(), null, null);
        assertThat(b.getSkippedCount()).isEqualTo(2);
        for (int i = 0; i < 300 && batches.isRunning(b.getId()); i++) {
            Thread.sleep(200);
        }
        List<FileRecord> recs = files.findByBatchIdOrderByIdAsc(b.getId());
        assertThat(recs).hasSize(10);
        assertThat(recs).allSatisfy(f -> assertThat(f.getStatus()).as(f.getOriginalName() + " " + f.getErrorMessage())
                .isEqualTo(FileRecord.Status.READY));

        // 模板实时预览（批次）：同一模板没有变化；只改命名规则时，只有“文件名”变化；不修改任何数据
        TemplateConfig base = templates.parse(b.getTemplateSnapshot());
        var presets = new TemplatePreviewService.Presets(List.of("{类型}", "{部门}_{类型}_{日期}_{标题}"),
                List.of("{年}/{部门}/{类型}", "归档"), List.of("{年}-{部门代码}", "{年}-{部门代码}-{类型代码}-{序号4}"), List.of("yyyyMMdd"));
        TemplatePreviewService.Result same = previews.preview(b.getId(), base, base, presets);
        assertThat(same.rows()).hasSize(10).allSatisfy(r -> assertThat(r.changed()).isEmpty());
        assertThat(same.target().wrongAuto()).isZero();
        TemplateConfig renamed = templates.parse(b.getTemplateSnapshot());
        renamed.setNamingPattern("{日期}_{标题}");
        TemplatePreviewService.Result pv0 = previews.preview(b.getId(), renamed, base, null);
        assertThat(pv0.rows()).allSatisfy(r -> assertThat(r.changed()).containsExactly("文件名"));
        // 目录只按年份分时，同一年的文件放在一起，再只用 {类型} 命名，两份 2026 年的 docx 通知就会重名
        renamed.setPathPattern("{年}");
        TemplatePreviewService.Result pv = previews.preview(b.getId(), renamed, base, presets);
        assertThat(pv.rows()).allSatisfy(r -> assertThat(r.changed()).containsExactly("文件名", "目录"));
        assertThat(pv.collisions()).isEmpty();
        assertThat(pv.presets().get("naming").get(0).collisions()).isPositive();
        assertThat(pv.presets().get("naming").get(1).collisions()).isZero();
        assertThat(pv.presets().get("path").get(1).issue()).contains("同一个目录");
        assertThat(pv.presets().get("archiveNo").get(0).issue()).contains("档号重复");
        assertThat(pv.presets().get("dateFormat").get(0).examples()).allSatisfy(x -> assertThat(x).matches("\\d{8}"));
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).allSatisfy(f -> assertThat(f.getStatus()).isEqualTo(FileRecord.Status.READY));

        // 内置示例预览：有标准答案，默认模板 10/10 正确、无重名
        TemplatePreviewService.Result samples = previews.preview(null, false, base, null, null);
        assertThat(samples.source().kind()).isEqualTo("SAMPLES");
        assertThat(samples.rows()).hasSize(10).allSatisfy(r -> assertThat(r.truth()).isNotNull());
        assertThat(samples.target().wrong()).isZero();
        assertThat(samples.recommendation().threshold()).isEqualTo(0.75);
        // “乱命名”示例：只能靠正文识别，置信度高低不一；阈值调到 0.95 时会有文件进入待确认
        TemplatePreviewService.Result messy = previews.preview(null, true, base, null, null);
        assertThat(messy.rows()).allSatisfy(r -> assertThat(r.originalName()).startsWith("文档"));
        assertThat(messy.target().wrongAuto()).isZero();
        assertThat(messy.thresholds()).anySatisfy(p -> {
            assertThat(p.threshold()).isEqualTo(0.95);
            assertThat(p.review()).isPositive();
        });
        // 阈值低于安全下限 0.60 时，推荐调回 0.60
        TemplateConfig weak = templates.parse(b.getTemplateSnapshot());
        weak.setThreshold(0.5);
        TemplatePreviewService.Recommendation rec = previews.preview(null, false, weak, base, null).recommendation();
        assertThat(rec.threshold()).isEqualTo(0.60);
        assertThat(rec.reason()).contains("安全下限");

        // 预览与归档（E04）
        List<ArchiveService.PreviewItem> preview = archive.preview(b.getId(), false);
        assertThat(preview).hasSize(10).allSatisfy(p -> assertThat(p.conflict()).isNull());
        ArchiveService.ArchiveSummary sum = archive.archive(b.getId(), null, false);
        assertThat(sum.archived()).as(sum.messages().toString()).isEqualTo(10);
        Path root = tmp.resolve("归档 结果");
        assertThat(root.resolve("2026/人力资源部/通知/人力资源部_通知_2026-03-15_关于张明等同志职务任免的通知.docx")).exists();
        assertThat(root.resolve("2025/法务部/合同/法务部_合同_2025-10-01_原材料采购框架合同.pdf")).exists();
        assertThat(root.resolve(LedgerExporter.MASTER_FILE)).exists();
        assertThat(src.resolve("销售部_年度设备销售合同_2025-12-25.pdf")).as("默认复制，原文件不动").exists();

        // 搜索（E06）与导出（E07）
        var q = new LedgerService.Query("合同", null, null, null, null, null, null, false);
        assertThat(ledger.all(q)).hasSize(2);
        mvc.perform(get("/api/ledger").param("keyword", "合同"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/ledger/export").param("department", "行政部")).andExpect(status().isOk());

        // 同一批文件再导入一次，应全部判为重复
        ArchiveBatch again = batches.createFromFolder(src.toString(), null, null);
        for (int i = 0; i < 300 && batches.isRunning(again.getId()); i++) {
            Thread.sleep(200);
        }
        assertThat(files.findByBatchIdOrderByIdAsc(again.getId()))
                .allSatisfy(f -> assertThat(f.getStatus()).isEqualTo(FileRecord.Status.DUPLICATE));

        // 从批次中移除单个文件记录：原文件不受影响
        Long removedId = files.findByBatchIdOrderByIdAsc(again.getId()).get(0).getId();
        cleanup.deleteFile(removedId);
        assertThat(files.findById(removedId)).isEmpty();
        assertThat(files.findByBatchIdOrderByIdAsc(again.getId())).hasSize(9);

        // 删除重复批次的记录（没有归档过）
        cleanup.deleteBatch(again.getId());
        assertThat(files.findByBatchIdOrderByIdAsc(again.getId())).isEmpty();

        // 撤销（E09）
        ArchiveService.UndoSummary undo = archive.undo(b.getId());
        assertThat(undo.undone()).isEqualTo(10);
        assertThat(root.resolve("2026/人力资源部/通知/人力资源部_通知_2026-03-15_关于张明等同志职务任免的通知.docx")).doesNotExist();
        assertThat(ledger.all(q)).isEmpty();

        // 删除已撤销批次的记录：批次、文件记录、已撤销的台账一并清掉，原文件不动
        RecordCleanupService.Result del = cleanup.deleteBatch(b.getId());
        assertThat(del.deletedFiles()).isEqualTo(10);
        assertThat(del.keptLedger()).isZero();
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).isEmpty();
        assertThat(ledgerRepo.count()).isZero();
        assertThat(src.resolve("销售部_年度设备销售合同_2025-12-25.pdf")).exists();

        // 删除已归档批次的记录：台账和归档文件保留
        Path one = Files.createDirectories(tmp.resolve("单个文件"));
        Files.copy(src.resolve("销售部_年度设备销售合同_2025-12-25.pdf"), one.resolve("销售部_年度设备销售合同_2025-12-25.pdf"));
        ArchiveBatch c = batches.createFromFolder(one.toString(), null, null);
        for (int i = 0; i < 300 && batches.isRunning(c.getId()); i++) {
            Thread.sleep(200);
        }
        assertThat(archive.archive(c.getId(), null, false).archived()).isEqualTo(1);
        RecordCleanupService.Result kept = cleanup.deleteBatch(c.getId());
        assertThat(kept.keptLedger()).isEqualTo(1);
        assertThat(ledgerRepo.countByUndoneFalse()).isEqualTo(1);
        assertThat(root.resolve("2025/销售部/合同/销售部_合同_2025-12-25_智能工业检测设备销售合同.pdf")).exists();
    }
}
