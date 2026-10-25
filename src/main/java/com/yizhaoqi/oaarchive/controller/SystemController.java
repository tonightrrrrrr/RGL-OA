package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.common.ChangelogService;
import com.yizhaoqi.oaarchive.common.UpdateService;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ocr.OcrEngine;
import com.yizhaoqi.oaarchive.parser.Block;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 系统状态、版本更新记录与 OCR 自检（技术文档 8.3、10.6）。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /** 程序版本号：和 pom.xml、打包脚本、changelog.json、技术难点文档保持一致（VersionSyncTest 检查） */
    public static final String VERSION = "1.6";

    private final OaProperties props;
    private final OcrEngine ocr;
    private final Environment env;
    private final ChangelogService changelog;
    private final UpdateService updates;

    public SystemController(OaProperties props, OcrEngine ocr, Environment env, ChangelogService changelog,
                            UpdateService updates) {
        this.props = props;
        this.ocr = ocr;
        this.env = env;
        this.changelog = changelog;
        this.updates = updates;
    }

    /** 版本更新记录，最新版本在前 */
    @GetMapping("/changelog")
    public ApiResponse<List<ChangelogService.Release>> changelog() {
        return ApiResponse.ok(changelog.releases());
    }

    public record UpdateSettings(boolean autoCheck) {
    }

    /** 最近一次联网检查更新的结果（启动后在后台自动检查，不会等待网络） */
    @GetMapping("/update")
    public ApiResponse<UpdateService.Result> update() {
        return ApiResponse.ok(updates.last());
    }

    /** 立即检查一次；没联网时返回 OFFLINE 状态，不报错 */
    @PostMapping("/update/check")
    public ApiResponse<UpdateService.Result> checkUpdate() {
        return ApiResponse.ok(updates.check(true));
    }

    @PutMapping("/update/settings")
    public ApiResponse<UpdateService.Result> updateSettings(@RequestBody UpdateSettings s) {
        return ApiResponse.ok(updates.setAutoCheck(s.autoCheck()));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appName", com.yizhaoqi.oaarchive.launcher.BrandIcon.FULL_NAME);
        m.put("shortName", com.yizhaoqi.oaarchive.launcher.BrandIcon.SHORT_NAME);
        m.put("version", VERSION);
        m.put("mode", props.getMode());
        m.put("home", props.homePath().toString());
        m.put("port", env.getProperty("local.server.port"));
        m.put("defaultArchiveRoot", props.getArchive().getDefaultRoot());
        m.put("inbox", props.getInbox());
        m.put("ocrAvailable", ocr.available());
        m.put("ocrMessage", ocr.available() ? "PP-OCRv4（离线）" : ocr.unavailableReason());
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("tmpDir", System.getProperty("java.io.tmpdir"));
        return ApiResponse.ok(m);
    }

    /** 上传一张图片，返回 OCR 结果和耗时，用于在任何环境下确认 OCR 是否可用 */
    @PostMapping("/ocr-test")
    public ApiResponse<Map<String, Object>> ocrTest(@RequestParam("file") MultipartFile file) throws IOException {
        if (!ocr.available()) {
            throw new BizException("OCR 不可用：" + ocr.unavailableReason());
        }
        BufferedImage img = ImageIO.read(file.getInputStream());
        if (img == null) {
            throw new BizException("无法读取图片，请上传 jpg、png 或 bmp 格式");
        }
        long t = System.currentTimeMillis();
        List<Block> blocks = ocr.recognize(img, 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("millis", System.currentTimeMillis() - t);
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("lines", blocks.stream().map(b -> Map.of("text", b.text(), "confidence", b.confidence())).toList());
        return ApiResponse.ok(m);
    }
}
