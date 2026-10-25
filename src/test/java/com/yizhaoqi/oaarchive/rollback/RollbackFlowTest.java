package com.yizhaoqi.oaarchive.rollback;

import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.fileops.FileOpsService;
import com.yizhaoqi.oaarchive.fileops.FileScopeService;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.6 回档：每种可回档的操作执行后回档，检查数据库和磁盘都回到操作之前；
 * 只保留最近一次；文件操作只能在允许的范围内；删除放进回收站（测试里是 test-trash 目录）。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RollbackFlowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("oa.home", () -> tmp.resolve("home").toString());
        r.add("oa.archive.default-root", () -> tmp.resolve("归档").toString());
        r.add("oa.fileops.trash-dir", () -> tmp.resolve("trash").toString());
        r.add("oa.desktop.tray", () -> "false");
        r.add("oa.desktop.open-browser", () -> "false");
    }

    @Autowired
    ProjectOps ops;
    @Autowired
    RollbackService rollback;
    @Autowired
    FileOpsService fileOps;
    @Autowired
    FileScopeService scope;
    @Autowired
    BatchService batchService;
    @Autowired
    ArchiveBatchRepository batches;
    @Autowired
    FileRecordRepository files;
    @Autowired
    LedgerEntryRepository ledger;
    @Autowired
    TemplateService templates;

    static final String NOTICE = "2026/人力资源部/通知/人力资源部_通知_2026-03-15_关于张明等同志职务任免的通知.docx";

    @Test
    @Order(1)
    void archiveUndoDeleteAndRollbackEachStep() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("源"));
        try (Stream<Path> s = Files.list(Path.of("src/test/resources/samples/A"))) {
            for (Path p : s.limit(3).toList()) {
                Files.copy(p, src.resolve(p.getFileName()));
            }
        }
        Files.copy(Path.of("src/test/resources/samples/A/人力资源部_关于张明等同志职务任免的通知_2026-03-15.docx"),
                src.resolve("人力资源部_关于张明等同志职务任免的通知_2026-03-15.docx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ArchiveBatch b = ops.importFolder(src.toString(), null, null, "AI");
        waitFor(b.getId());
        int n = files.findByBatchIdOrderByIdAsc(b.getId()).size();
        assertThat(n).isGreaterThanOrEqualTo(3);
        Path root = tmp.resolve("归档");

        // 1. 归档 → 回档：归档目录里的文件删掉，文件回到“待归档”，台账清空
        ArchiveService.ArchiveSummary sum = ops.archive(b.getId(), null, false, "UI");
        assertThat(sum.archived()).isEqualTo(n);
        assertThat(root.resolve(NOTICE)).exists();
        RollbackService.Entry e = rollback.latest();
        assertThat(e.kind()).isEqualTo("ARCHIVE");
        assertThat(e.canRollback()).isTrue();
        RollbackService.Result r = rollback.rollback(e.id());
        assertThat(r.notes()).isEmpty();
        assertThat(root.resolve(NOTICE)).doesNotExist();
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).allSatisfy(f -> assertThat(f.getStatus()).isEqualTo(FileRecord.Status.READY));
        assertThat(ledger.countByUndoneFalse()).isZero();
        assertThat(rollback.latest()).as("回档只能用一次").isNull();
        assertThatThrownBy(() -> rollback.rollback(e.id())).isInstanceOf(BizException.class);

        // 2. 再归档、撤销 → 回档：被撤销删掉的文件放回归档目录，重新算作已归档，台账恢复，之后还能正常撤销
        assertThat(ops.archive(b.getId(), null, false, "UI").archived()).isEqualTo(n);
        ArchiveService.UndoSummary undo = ops.undo(b.getId(), "UI");
        assertThat(undo.undone()).isEqualTo(n);
        assertThat(root.resolve(NOTICE)).doesNotExist();
        rollback.rollback(rollback.latest().id());
        assertThat(root.resolve(NOTICE)).exists();
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).allSatisfy(f -> assertThat(f.getStatus()).isEqualTo(FileRecord.Status.ARCHIVED));
        assertThat(ledger.countByUndoneFalse()).isEqualTo(n);
        assertThat(root.resolve(LedgerExporter.MASTER_FILE)).exists();
        assertThat(ops.undo(b.getId(), "UI").undone()).as("回档后还能正常撤销").isEqualTo(n);
        assertThat(root.resolve(NOTICE)).doesNotExist();

        // 3. 移除一个文件 → 回档：记录和处理步骤恢复
        FileRecord one = files.findByBatchIdOrderByIdAsc(b.getId()).get(0);
        ops.deleteFile(one.getId(), "UI");
        assertThat(files.findById(one.getId())).isEmpty();
        rollback.rollback(rollback.latest().id());
        assertThat(files.findById(one.getId())).isPresent();
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).hasSize(n);

        // 4. 删除批次记录 → 回档：批次、文件记录、工作区副本都恢复，可以重新归档
        Path work = tmp.resolve("home/work").resolve(String.valueOf(b.getId()));
        assertThat(work).isDirectory();
        ops.deleteBatch(b.getId(), "UI");
        assertThat(batches.findById(b.getId())).isEmpty();
        assertThat(work).doesNotExist();
        rollback.rollback(rollback.latest().id());
        assertThat(batches.findById(b.getId())).isPresent();
        assertThat(files.findByBatchIdOrderByIdAsc(b.getId())).hasSize(n);
        assertThat(work).isDirectory();
        assertThat(ops.archive(b.getId(), null, true, "UI").archived()).as("恢复后能重新归档").isEqualTo(n);
        assertThat(root.resolve(NOTICE)).exists();

        // 5. 只保留最近一次：新操作覆盖旧记录，旧 id 不能再回档
        String archiveId = rollback.latest().id();
        ops.undo(b.getId(), "UI");
        assertThat(rollback.latest().kind()).isEqualTo("UNDO");
        assertThatThrownBy(() -> rollback.rollback(archiveId)).hasMessageContaining("新的操作");

        // 6. 导入 → 回档：批次记录和工作区一起删掉，原文件不动
        ArchiveBatch b2 = ops.importFolder(src.toString(), null, null, "AI");
        waitFor(b2.getId());
        rollback.rollback(rollback.latest().id());
        assertThat(batches.findById(b2.getId())).isEmpty();
        assertThat(files.findByBatchIdOrderByIdAsc(b2.getId())).isEmpty();
        assertThat(tmp.resolve("home/work").resolve(String.valueOf(b2.getId()))).doesNotExist();
        assertThat(src.resolve("人力资源部_关于张明等同志职务任免的通知_2026-03-15.docx")).exists();

        // 7. 重新归档（后面的文件操作测试要用到已归档的文件）；再归档一次时没有可归档的文件，不记录，保留上一次的回档记录
        assertThat(ops.archive(b.getId(), null, false, "UI").archived()).isEqualTo(n);
        String archivedId = rollback.latest().id();
        assertThat(ops.archive(b.getId(), null, false, "UI").archived()).isZero();
        assertThat(rollback.latest().id()).isEqualTo(archivedId);
    }

    @Test
    @Order(2)
    void templateOperationsRollBack() {
        TemplateConfig cfg = templates.parse(templates.getOrDefault(null).getContentJson());
        // 新建 → 回档：模板删除
        TemplateEntity created = ops.createTemplate("回档测试模板", cfg, "AI");
        rollback.rollback(rollback.latest().id());
        assertThat(templates.list()).noneMatch(t -> t.getId().equals(created.getId()));

        // 修改 → 回档：恢复原规则
        TemplateEntity t = templates.create("回档测试模板2", cfg);
        TemplateConfig changed = templates.parse(t.getContentJson());
        changed.setNamingPattern("{日期}_{标题}");
        ops.updateTemplate(t.getId(), changed, "AI");
        rollback.rollback(rollback.latest().id());
        assertThat(templates.parse(templates.get(t.getId()).getContentJson()).getNamingPattern()).isEqualTo(cfg.getNamingPattern());

        // 删除 → 新建同名 → 回档：恢复的模板改名，避免重名
        ops.deleteTemplate(t.getId(), "UI");
        String deleteId = rollback.latest().id();
        templates.create("回档测试模板2", cfg); // 界面上手动新建，不记录回档
        RollbackService.Result r = rollback.rollback(deleteId);
        assertThat(templates.get(t.getId()).getName()).isEqualTo("回档测试模板2（回档）");
        assertThat(r.notes()).anyMatch(x -> x.contains("（回档）"));

        // 内置模板不能删除
        assertThatThrownBy(() -> ops.deleteTemplate(templates.getOrDefault(null).getId(), "UI"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @Order(3)
    void fileOperationsStayInScopeAndRollBack() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("工作区"));
        scope.update(new FileScopeService.Update(null, null, List.of(ws.toString())));
        Files.writeString(ws.resolve("a.txt"), "A");
        Files.createDirectories(ws.resolve("旧资料/子"));
        Files.writeString(ws.resolve("旧资料/子/b.txt"), "B");

        // 新建多级文件夹 → 回档：逐级删掉
        assertThat(fileOps.previewMkdir(ws.toString(), "2026/三月").title()).contains("三月");
        fileOps.applyMkdir(ws.toString(), "2026/三月", "AI");
        assertThat(ws.resolve("2026/三月")).isDirectory();
        rollback.rollback(rollback.latest().id());
        assertThat(ws.resolve("2026")).doesNotExist();

        // 改名 → 回档
        fileOps.applyRename(ws.resolve("a.txt").toString(), "甲.txt", "AI");
        assertThat(ws.resolve("甲.txt")).hasContent("A");
        rollback.rollback(rollback.latest().id());
        assertThat(ws.resolve("a.txt")).hasContent("A");
        assertThat(ws.resolve("甲.txt")).doesNotExist();

        // 移动 → 回档
        fileOps.applyMove(List.of(ws.resolve("a.txt").toString()), ws.resolve("旧资料").toString(), "AI");
        assertThat(ws.resolve("旧资料/a.txt")).exists();
        rollback.rollback(rollback.latest().id());
        assertThat(ws.resolve("a.txt")).exists();
        assertThat(ws.resolve("旧资料/a.txt")).doesNotExist();
        assertThatThrownBy(() -> fileOps.previewMove(List.of(ws.resolve("旧资料").toString()), ws.resolve("旧资料/子").toString()))
                .hasMessageContaining("自己里面");

        // 删除文件夹：放进回收站（测试目录）；回档后原样恢复
        FileOpsService.Preview pv = fileOps.previewDelete(List.of(ws.resolve("旧资料").toString()));
        assertThat(pv.danger()).as("删除文件夹要二次确认").isTrue();
        assertThat(pv.files()).isEqualTo(1);
        fileOps.applyDelete(List.of(ws.resolve("旧资料").toString()), "AI");
        assertThat(ws.resolve("旧资料")).doesNotExist();
        try (Stream<Path> s = Files.list(tmp.resolve("trash"))) {
            assertThat(s.count()).as("删除的内容在回收站里").isEqualTo(1);
        }
        rollback.rollback(rollback.latest().id());
        assertThat(ws.resolve("旧资料/子/b.txt")).hasContent("B");

        // 范围之外、程序数据目录、范围根目录本身、非法名称都拒绝
        Path outside = Files.createDirectories(tmp.resolve("范围外"));
        Files.writeString(outside.resolve("x.txt"), "x");
        assertThatThrownBy(() -> fileOps.previewDelete(List.of(outside.resolve("x.txt").toString())))
                .hasMessageContaining("不在允许操作的文件夹范围内");
        assertThatThrownBy(() -> fileOps.list(tmp.resolve("home").toString())).hasMessageContaining("数据目录");
        assertThatThrownBy(() -> fileOps.previewDelete(List.of(ws.toString()))).hasMessageContaining("根目录本身");
        assertThatThrownBy(() -> fileOps.previewRename(ws.resolve("a.txt").toString(), "a?.txt")).hasMessageContaining("不能用的字符");
        assertThatThrownBy(() -> fileOps.previewRename(ws.resolve("a.txt").toString(), "CON.txt")).hasMessageContaining("保留");
        assertThatThrownBy(() -> fileOps.previewMkdir(ws.toString(), "../逃出去")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> scope.update(new FileScopeService.Update(null, null, List.of(System.getProperty("user.home")))))
                .hasMessageContaining("范围太大");
        assertThat(fileOps.list(ws.toString()).items()).extracting(FileOpsService.Item::name).contains("a.txt", "旧资料");

        // 已归档、记在台账里的文件不能改名或删除；归档目录里的台账文件也不能动
        Path root = tmp.resolve("归档");
        assertThat(root.resolve(NOTICE)).exists();
        assertThatThrownBy(() -> fileOps.previewDelete(List.of(root.resolve(NOTICE).toString()))).hasMessageContaining("撤销");
        assertThatThrownBy(() -> fileOps.previewDelete(List.of(root.resolve("2026").toString()))).hasMessageContaining("撤销");
        assertThatThrownBy(() -> fileOps.previewRename(root.resolve(LedgerExporter.MASTER_FILE).toString(), "x.xlsx"))
                .hasMessageContaining("台账");

        // 关掉“归档目录”后，归档目录不在范围内
        scope.update(new FileScopeService.Update(false, null, null));
        Path realRoot = root.toRealPath();
        assertThat(scope.view().effective()).noneMatch(x -> Path.of(x.path()).equals(realRoot));
        assertThatThrownBy(() -> fileOps.list(root.toString())).hasMessageContaining("不在允许操作的文件夹范围内");
        scope.update(new FileScopeService.Update(true, null, null));
    }

    private void waitFor(Long batchId) throws InterruptedException {
        for (int i = 0; i < 300 && batchService.isRunning(batchId); i++) {
            Thread.sleep(200);
        }
    }
}
