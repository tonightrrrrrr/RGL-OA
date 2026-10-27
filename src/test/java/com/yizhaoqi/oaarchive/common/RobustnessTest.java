package com.yizhaoqi.oaarchive.common;

import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.8.1 接口测试中发现的问题（技术难点 7.5–7.8）：跨站请求与 DNS 重绑定、异常导入卡死批次、
 * 程序中断后的恢复、请求参数错误返回 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RobustnessTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("oa.home", () -> tmp.resolve("home").toString());
        r.add("oa.archive.default-root", () -> tmp.resolve("归档").toString());
        r.add("oa.import.max-files", () -> "5");
        r.add("oa.desktop.tray", () -> "false");
        r.add("oa.desktop.open-browser", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    BatchService batches;
    @Autowired
    ArchiveBatchRepository batchRepo;
    @Autowired
    FileRecordRepository fileRepo;
    @Autowired
    TemplateService templates;

    @Test
    void crossSiteWriteIsRejected() throws Exception {
        MockMultipartFile f = new MockMultipartFile("files", "a.txt", "text/plain", "行政部 通知".getBytes());
        mvc.perform(multipart("/api/batches/upload").file(f).header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/rollback/x").header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(post("/api/rollback/x").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        // 同源和不带 Origin 的请求（curl、Postman）照常处理
        mvc.perform(post("/api/rollback/x").header("Origin", "http://localhost")).andExpect(status().isOk());
        mvc.perform(post("/api/rollback/x")).andExpect(status().isOk());
        // GET 不改数据，跨站也能访问，但浏览器拿不到响应（没有 CORS 头）
        mvc.perform(get("/api/system/status").header("Origin", "http://evil.example.com")).andExpect(status().isOk());
    }

    @Test
    void dnsRebindingIsRejected() throws Exception {
        mvc.perform(get("/api/ledger").header("Host", "evil.example.com")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ledger").header("Host", "127.0.0.1:18080")).andExpect(status().isOk());
    }

    @Test
    void clientErrorsAre4xxWithoutJavaDetails() throws Exception {
        mvc.perform(get("/api/batches/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数“id”的值不正确：abc"));
        mvc.perform(get("/api/ledger").param("dateFrom", "2026-13-45")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/system/status")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/batches").contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/batches/upload").param("targetRoot", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请选择要上传的文件"));
        mvc.perform(post("/api/templates/analyze").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("模板内容不能为空"));
    }

    @Test
    void abnormalExtensionDoesNotBreakImport() throws Exception {
        assertThat(ParserRegistry.extension("缓存.a6af95a209b2e652ed6766804b9b8ad6b6a68f2c610b8f14713cd40df0d62bf9")).isEmpty();
        assertThat(ParserRegistry.extension("会议纪要 v2.final draft")).isEmpty();
        assertThat(ParserRegistry.extension("报表.XLSX")).isEqualTo("xlsx");
        Path src = Files.createDirectories(tmp.resolve("超长扩展名"));
        Files.writeString(src.resolve("行政部_通知_2026-05-01.txt"), "行政部\n关于放假的通知\n2026年5月1日");
        Files.writeString(src.resolve("缓存." + "a".repeat(64)), "cache");
        ArchiveBatch b = batches.createFromFolder(src.toString(), tmp.resolve("归档").toString(), null);
        assertThat(fileRepo.findByBatchIdOrderByIdAsc(b.getId())).hasSize(2);
        for (int i = 0; i < 300 && batches.isRunning(b.getId()); i++) {
            Thread.sleep(100); // 等处理完，避免影响下面的启动恢复测试
        }
        assertThat(batches.isRunning(b.getId())).isFalse();
        assertThat(fileRepo.findByBatchIdOrderByIdAsc(b.getId()))
                .noneMatch(f -> f.getStatus() == FileRecord.Status.QUEUED || f.getStatus() == FileRecord.Status.PARSING);
    }

    @Test
    void tooManyFilesIsRejectedWithoutLeavingBatch() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("文件太多"));
        for (int i = 0; i < 6; i++) {
            Files.writeString(src.resolve("n" + i + ".txt"), "通知 " + i);
        }
        long before = batchRepo.count();
        assertThatThrownBy(() -> batches.createFromFolder(src.toString(), tmp.resolve("归档").toString(), null))
                .isInstanceOf(BizException.class).hasMessageContaining("上限（5 个）");
        assertThat(batchRepo.count()).isEqualTo(before);
    }

    @Test
    void interruptedFilesAreRecoveredOnStartup() throws Exception {
        for (int i = 0; i < 300 && batchRepo.findAll().stream().anyMatch(x -> batches.isRunning(x.getId())); i++) {
            Thread.sleep(100); // 有批次在处理时恢复会跳过（见 recoverInterrupted），先等其他用例的批次处理完
        }
        ArchiveBatch b = new ArchiveBatch();
        b.setSourceType(ArchiveBatch.SourceType.FOLDER);
        b.setTargetRoot(tmp.resolve("归档").toString());
        b.setStatus(ArchiveBatch.Status.RUNNING);
        b.setTemplateSnapshot(templates.getOrDefault(null).getContentJson());
        b.setCreatedAt(LocalDateTime.now());
        b = batchRepo.save(b);
        FileRecord f = new FileRecord();
        f.setBatchId(b.getId());
        f.setOriginalName("中断.docx");
        f.setOriginalPath(tmp.resolve("中断.docx").toString());
        f.setStatus(FileRecord.Status.PARSING);
        f.setCreatedAt(LocalDateTime.now());
        f = fileRepo.save(f);

        batches.recoverInterrupted();

        FileRecord after = fileRepo.findById(f.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(FileRecord.Status.FAILED);
        assertThat(after.getErrorMessage()).contains("重试");
        assertThat(batchRepo.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(ArchiveBatch.Status.DONE);

        // 整批重试：失败的文件重新排队处理（原文件不存在，所以会再次失败，但不会卡住）
        assertThat(batches.retryFailed(b.getId())).isEqualTo(1);
        for (int i = 0; i < 100 && batches.isRunning(b.getId()); i++) {
            Thread.sleep(100);
        }
        assertThat(fileRepo.findById(f.getId()).orElseThrow().getStatus()).isEqualTo(FileRecord.Status.FAILED);
        assertThat(batchRepo.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(ArchiveBatch.Status.DONE);
    }
}
