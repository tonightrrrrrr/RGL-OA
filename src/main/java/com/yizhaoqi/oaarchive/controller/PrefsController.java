package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.pipeline.ImportPrefsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导入习惯（v1.7.2）：导入识别页打开时取上次的归档目录、模板、导入方式；系统设置里可以改默认归档目录或恢复系统默认。
 * 程序只监听 127.0.0.1，这些接口只有本机能访问。
 */
@RestController
@RequestMapping("/api/prefs/import")
public class PrefsController {

    private final ImportPrefsService prefs;

    public PrefsController(ImportPrefsService prefs) {
        this.prefs = prefs;
    }

    public record ArchiveRootRequest(String path) {
    }

    @GetMapping
    public ApiResponse<ImportPrefsService.View> get() {
        return ApiResponse.ok(prefs.view());
    }

    @PutMapping("/archive-root")
    public ApiResponse<ImportPrefsService.View> setArchiveRoot(@RequestBody ArchiveRootRequest req) {
        return ApiResponse.ok(prefs.setArchiveRoot(req == null ? null : req.path()));
    }

    @DeleteMapping("/archive-root")
    public ApiResponse<ImportPrefsService.View> resetArchiveRoot() {
        return ApiResponse.ok(prefs.resetArchiveRoot());
    }
}
