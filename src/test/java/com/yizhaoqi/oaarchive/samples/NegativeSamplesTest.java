package com.yizhaoqi.oaarchive.samples;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplatePreviewService;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反面案例（samples-negative）：先归档 10 份成功示例，再导入反面案例文件夹，
 * 每个文件的处理结果必须和 cases.json 里写的一致（待确认、失败、重复、重名、跳过）。
 */
@SpringBootTest
class NegativeSamplesTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("oa.home", () -> tmp.resolve("home").toString());
        r.add("oa.archive.default-root", () -> tmp.resolve("归档").toString());
        r.add("oa.desktop.tray", () -> "false");
        r.add("oa.desktop.open-browser", () -> "false");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Case(String file, String expect, String group, String why, String shows) {
    }

    @Autowired
    BatchService batches;
    @Autowired
    FileRecordRepository files;
    @Autowired
    ArchiveService archive;
    @Autowired
    TemplateService templates;
    @Autowired
    TemplatePreviewService previews;

    @Test
    void everyCaseEndsUpAsDocumented() throws Exception {
        List<Case> cases = new ObjectMapper().readValue(NegativeSampleGenerator.OUT.resolve("cases.json").toFile(),
                new TypeReference<>() { });
        try (Stream<Path> s = Files.list(NegativeSampleGenerator.OUT)) {
            List<String> names = s.map(p -> p.getFileName().toString()).filter(n -> !n.equals("cases.json")).sorted().toList();
            assertThat(names).as("cases.json 要覆盖文件夹里的每个文件").containsExactlyInAnyOrderElementsOf(cases.stream().map(Case::file).toList());
        }
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.why()).isNotBlank();
            assertThat(c.shows()).isNotBlank();
        });

        // 第一步：导入并归档 10 份成功示例，台账里有了采购合同，后面的副本才会被判为重复
        ArchiveBatch good = batches.createFromFolder(Path.of("src/test/resources/samples/A").toAbsolutePath().toString(), null, null);
        waitFor(good);
        assertThat(archive.archive(good.getId(), null, false).archived()).isEqualTo(10);

        // 第二步：导入反面案例
        Path src = Files.createDirectories(tmp.resolve("反面案例"));
        try (Stream<Path> s = Files.list(NegativeSampleGenerator.OUT)) {
            for (Path p : s.toList()) {
                Files.copy(p, src.resolve(p.getFileName()));
            }
        }
        Files.delete(src.resolve("cases.json"));
        ArchiveBatch bad = batches.createFromFolder(src.toString(), null, null);
        waitFor(bad);

        Map<String, FileRecord> got = files.findByBatchIdOrderByIdAsc(bad.getId()).stream()
                .collect(Collectors.toMap(FileRecord::getOriginalName, Function.identity()));
        // 同一批里内容相同的两份：并行处理，先处理完的那份正常，另一份标为重复
        List<FileRecord> pair = cases.stream().filter(c -> c.expect().equals("DUPLICATE_PAIR")).map(c -> got.get(c.file())).toList();
        assertThat(pair).hasSize(2).doesNotContainNull();
        assertThat(pair).filteredOn(f -> f.getStatus() == FileRecord.Status.DUPLICATE).as("恰好一份是重复").hasSize(1);
        assertThat(pair).filteredOn(f -> f.getStatus() == FileRecord.Status.READY).as("另一份正常识别").hasSize(1);
        for (Case c : cases) {
            if (c.expect().equals("DUPLICATE_PAIR")) {
                continue;
            }
            if (c.expect().equals("SKIPPED")) {
                assertThat(got).as(c.file() + " 应该在导入时被跳过").doesNotContainKey(c.file());
                assertThat(bad.getSkippedSummary()).contains(c.file());
                continue;
            }
            FileRecord f = got.get(c.file());
            assertThat(f).as(c.file() + " 应该被导入").isNotNull();
            assertThat(f.getStatus().name()).as(c.file() + "（" + c.why() + "）" + f.getErrorMessage() + " " + f.getReviewReasons())
                    .isEqualTo(c.expect());
        }

        // 重点情况再看细节
        assertThat(got.get("财务部_加密的薪酬明细_2026-03-31.pdf").getErrorMessage()).contains("密码");
        assertThat(got.get("损坏的合同_2026-01-15.docx").getErrorMessage()).contains("损坏");
        assertThat(got.get("手机拍照_IMG_2045.png").getReviewReasons()).contains("没有读到文字");
        assertThat(got.get("原材料采购框架合同（重复上传的副本）.pdf").getDuplicateOf()).as("指向台账里已有的记录").isNotNull();
        assertThat(got.get("研发部_规划报告_2026-06-01.docx").getNewName()).doesNotContainPattern("[\\\\/:*?\"<>|]");
        var preview = archive.preview(bad.getId(), true);
        assertThat(preview).anySatisfy(p -> assertThat(p.conflict()).contains("重名"));

        // 模板预览也能选“反面案例”：失败和跳过的文件不参与，其余正常计算
        TemplateConfig tpl = templates.parse(bad.getTemplateSnapshot());
        TemplatePreviewService.Result pv = previews.preview(null, TemplatePreviewService.SampleSet.NEGATIVE, tpl, null, null);
        assertThat(pv.source().kind()).isEqualTo("SAMPLES_NEGATIVE");
        assertThat(pv.target().review()).isGreaterThanOrEqualTo(5);
        assertThat(pv.skipped()).anySatisfy(s -> assertThat(s).contains("加密"));
        assertThat(pv.skipped()).anySatisfy(s -> assertThat(s).contains("导入时会被跳过"));
    }

    private void waitFor(ArchiveBatch b) throws InterruptedException {
        for (int i = 0; i < 600 && batches.isRunning(b.getId()); i++) {
            Thread.sleep(200);
        }
    }
}
