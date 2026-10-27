package com.yizhaoqi.oaarchive.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.ai.AiTools;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.fileops.FileScopeService;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.TemplateRepository;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * v1.7.2 记住导入习惯：归档目录、模板、导入方式、上次导入的文件夹；重启后还在；升级后从最近的批次取；
 * 可以手动指定、恢复系统默认；归档目录所在的磁盘不存在时，导入前就提示。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportPrefsTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("oa.home", () -> tmp.resolve("home").toString());
        r.add("oa.archive.default-root", () -> tmp.resolve("系统默认").toString());
        r.add("oa.desktop.tray", () -> "false");
        r.add("oa.desktop.open-browser", () -> "false");
    }

    @Autowired
    ImportPrefsService prefs;
    @Autowired
    BatchService batches;
    @Autowired
    TemplateService templates;
    @Autowired
    ArchiveBatchRepository batchRepo;
    @Autowired
    TemplateRepository templateRepo;
    @Autowired
    OaProperties props;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    FileScopeService scope;
    @Autowired
    AiTools tools;
    @Autowired
    MockMvc mvc;

    static Path src;

    @Test
    @Order(1)
    void remembersLastArchiveRootTemplateAndFolder() throws Exception {
        String systemDefault = tmp.resolve("系统默认").toAbsolutePath().toString();
        // 新装：一个批次都没有，用系统默认
        assertThat(prefs.view().archiveRoot()).isEqualTo(systemDefault);
        assertThat(prefs.view().archiveRootSource()).isEqualTo(ImportPrefsService.Source.SYSTEM);

        // 用户选了另一个磁盘上的归档目录（这里用临时目录代替 D 盘）和自定义模板
        src = Files.createDirectories(tmp.resolve("待归档/十月"));
        Files.copy(Path.of("src/test/resources/samples/A/法务部_原材料采购框架合同_2025-10-01.pdf"), src.resolve("a.pdf"));
        Path dRoot = tmp.resolve("D盘/公司归档");
        TemplateEntity custom = templates.create("按日期命名", templates.parse(templates.getOrDefault(null).getContentJson()));
        ArchiveBatch b1 = batches.createFromFolder(src.toString(), dRoot.toString(), custom.getId());
        waitFor(b1);

        ImportPrefsService.View v = prefs.view();
        assertThat(v.archiveRoot()).isEqualTo(dRoot.toAbsolutePath().toString());
        assertThat(v.archiveRootSource()).isEqualTo(ImportPrefsService.Source.LAST_USED);
        assertThat(v.templateId()).isEqualTo(custom.getId());
        assertThat(v.mode()).isEqualTo("folder");
        assertThat(v.lastSourceDir()).isEqualTo(src.toAbsolutePath().toString());
        assertThat(v.sourceChooserStart()).as("选择窗口从上次导入的文件夹旁边打开").isEqualTo(src.getParent().toAbsolutePath().toString());
        assertThat(v.available()).isTrue();
        assertThat(Files.readString(tmp.resolve("home/config/prefs.json"))).contains("公司归档");

        // 系统状态、接口、小R的操作范围都用记住的目录
        mvc.perform(get("/api/system/status")).andExpect(jsonPath("$.data.defaultArchiveRoot").value(dRoot.toAbsolutePath().toString()));
        mvc.perform(get("/api/prefs/import")).andExpect(jsonPath("$.data.archiveRootSource").value("LAST_USED"));
        assertThat(scope.view().effective()).anyMatch(r -> Path.of(r.path()).endsWith(Path.of("D盘", "公司归档")));

        // 下次导入不指定归档目录和模板：用上次的
        Path src2 = Files.createDirectories(tmp.resolve("待归档/十一月"));
        Files.copy(Path.of("src/test/resources/samples/A/销售部_年度设备销售合同_2025-12-25.pdf"), src2.resolve("b.pdf"));
        ArchiveBatch b2 = batches.createFromFolder(src2.toString(), null, null);
        waitFor(b2);
        assertThat(b2.getTargetRoot()).isEqualTo(dRoot.toAbsolutePath().toString());
        assertThat(b2.getTemplateName()).isEqualTo("按日期命名");

        // 小R导入不指定归档目录：卡片上写的是记住的目录
        AiTools.Outcome o = tools.run("propose_import_folder", "{\"folder\":\"" + src.toString().replace("\\", "\\\\") + "\"}", false);
        assertThat(o.action()).isNotNull();
        assertThat(o.action().changes()).anyMatch(c -> c.after().equals("归档目录：" + dRoot.toAbsolutePath()));
        assertThat(o.action().changes()).anyMatch(c -> c.after().equals("模板：按日期命名"));
    }

    @Test
    @Order(2)
    void survivesRestartAndSeedsFromLatestBatchAfterUpgrade() throws Exception {
        String remembered = prefs.archiveRoot();
        // 重启：新建一个服务对象，从 prefs.json 读出来，还是上次的目录
        ImportPrefsService restarted = new ImportPrefsService(props, batchRepo, templateRepo, mapper);
        assertThat(restarted.archiveRoot()).isEqualTo(remembered);

        // 从 v1.7.1 升级：还没有 prefs.json 时，取最近一个批次用过的归档目录和模板，不用再选一次
        Path file = tmp.resolve("home/config/prefs.json");
        String saved = Files.readString(file);
        Files.delete(file);
        ImportPrefsService upgraded = new ImportPrefsService(props, batchRepo, templateRepo, mapper);
        assertThat(upgraded.archiveRoot()).isEqualTo(remembered);
        assertThat(upgraded.view().archiveRootSource()).isEqualTo(ImportPrefsService.Source.LAST_USED);
        assertThat(upgraded.view().templateId()).isNotNull();
        Files.writeString(file, saved);
    }

    @Test
    @Order(3)
    void manualSetResetAndUploadMode() throws Exception {
        Path manual = tmp.resolve("手动指定的目录");
        mvc.perform(put("/api/prefs/import/archive-root").contentType("application/json")
                        .content("{\"path\":\"" + manual.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(jsonPath("$.data.archiveRootSource").value("MANUAL"))
                .andExpect(jsonPath("$.data.archiveRoot").value(manual.toAbsolutePath().toString()));
        assertThatThrownBy(() -> prefs.setArchiveRoot(manual.getRoot().toString())).hasMessageContaining("磁盘根目录");

        // 恢复系统默认：之后不再从批次里取，新建服务对象（重启）也还是系统默认
        mvc.perform(delete("/api/prefs/import/archive-root")).andExpect(jsonPath("$.data.archiveRootSource").value("SYSTEM"));
        assertThat(prefs.archiveRoot()).isEqualTo(tmp.resolve("系统默认").toAbsolutePath().toString());
        assertThat(new ImportPrefsService(props, batchRepo, templateRepo, mapper).view().archiveRootSource())
                .isEqualTo(ImportPrefsService.Source.SYSTEM);

        // 上传文件：记住导入方式是“上传”，上次导入的文件夹不变
        String lastDir = prefs.view().lastSourceDir();
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/samples/A/行政部_2026 年五一劳动节放假安排通知_2026-04-27.docx"));
        ArchiveBatch up = batches.createFromUpload(List.of(new MockMultipartFile("files", "通知.docx", null, bytes)),
                tmp.resolve("上传归档").toString(), null);
        waitFor(up);
        assertThat(prefs.view().mode()).isEqualTo("upload");
        assertThat(prefs.view().lastSourceDir()).isEqualTo(lastDir);
        assertThat(prefs.archiveRoot()).isEqualTo(tmp.resolve("上传归档").toAbsolutePath().toString());

        // 记住的模板被删掉了：回到默认模板
        Long remembered = prefs.view().templateId();
        assertThat(remembered).isNotNull();
        TemplateEntity t = templates.get(remembered);
        if (!t.isBuiltin()) {
            templates.delete(remembered);
            assertThat(prefs.templateId()).isNull();
            ArchiveBatch b = batches.createFromFolder(src.toString(), null, null);
            waitFor(b);
            assertThat(b.getTemplateName()).isEqualTo("默认模板");
        }
    }

    @Test
    @Order(4)
    void missingDriveIsReportedBeforeImport() throws Exception {
        String missing = null;
        for (char c = 'Z'; c >= 'G'; c--) {
            if (!Files.exists(Path.of(c + ":\\"))) {
                missing = c + ":\\公司归档";
                break;
            }
        }
        Assumptions.assumeTrue(missing != null, "这台电脑上没有空着的盘符");
        String m = missing;
        // 手动指定到不存在的磁盘：拒绝
        assertThatThrownBy(() -> prefs.setArchiveRoot(m)).isInstanceOf(BizException.class).hasMessageContaining("找不到归档目录所在的磁盘");
        // 导入时填了不存在的磁盘：导入前就提示，不等到归档时才失败
        assertThatThrownBy(() -> batches.createFromFolder(src.toString(), m, null)).hasMessageContaining("移动硬盘");

        // 记住的目录所在的移动硬盘后来拔掉了：界面上标出来；不指定目录导入时提示，不会悄悄改用系统默认
        ArchiveBatch fake = new ArchiveBatch();
        fake.setTargetRoot(m);
        fake.setSourceType(ArchiveBatch.SourceType.UPLOAD);
        prefs.recordImport(fake);
        ImportPrefsService.View v = prefs.view();
        assertThat(v.archiveRoot()).isEqualTo(m);
        assertThat(v.available()).isFalse();
        assertThat(v.unavailableReason()).contains(Path.of(m).getRoot().toString());
        assertThatThrownBy(() -> batches.createFromFolder(src.toString(), null, null)).hasMessageContaining("找不到归档目录所在的磁盘");
        prefs.resetArchiveRoot();
    }

    private void waitFor(ArchiveBatch b) throws InterruptedException {
        for (int i = 0; i < 300 && batches.isRunning(b.getId()); i++) {
            Thread.sleep(200);
        }
    }
}
