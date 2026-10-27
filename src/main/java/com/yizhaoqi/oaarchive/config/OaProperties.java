package com.yizhaoqi.oaarchive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 应用自定义配置（application.yml 中 oa.* 部分）。
 */
@Data
@ConfigurationProperties(prefix = "oa")
public class OaProperties {

    /** 数据目录：数据库、工作区、日志、设置 */
    private String home = "./data";
    /** desktop 或 docker */
    private String mode = "desktop";
    /** Docker 模式下的收件箱目录 */
    private String inbox;

    private Desktop desktop = new Desktop();
    private Archive archive = new Archive();
    private Import importConfig = new Import();
    private Pipeline pipeline = new Pipeline();
    private Ocr ocr = new Ocr();
    private Recognize recognize = new Recognize();

    public Path homePath() {
        return Path.of(home).toAbsolutePath().normalize();
    }

    public Path workRoot() {
        return homePath().resolve("work");
    }

    public boolean isDocker() {
        return "docker".equalsIgnoreCase(mode);
    }

    /** 让 yml 中的 oa.import 映射到 importConfig（import 是 Java 关键字） */
    public Import getImport() {
        return importConfig;
    }

    public void setImport(Import value) {
        this.importConfig = value;
    }

    @Data
    public static class Desktop {
        private boolean openBrowser = true;
        private boolean tray = true;
    }

    @Data
    public static class Archive {
        private String defaultRoot;
    }

    @Data
    public static class Import {
        private int maxFileSizeMb = 200;
        private int maxDepth = 10;
        /** 单次导入最多的文件数：选错文件夹（如整个磁盘）时尽早提示，不会建出几万条记录 */
        private int maxFiles = 5000;
    }

    @Data
    public static class Pipeline {
        private int threads = 4;
        private int fileTimeoutSeconds = 120;
    }

    @Data
    public static class Ocr {
        private boolean enabled = true;
        private int concurrency = 1;
        private int renderDpi = 300;
        private int minTextChars = 20;
    }

    @Data
    public static class Recognize {
        private double threshold = 0.75;
    }
}
