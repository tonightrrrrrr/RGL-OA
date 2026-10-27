package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.fileops.FileScopeService;
import com.yizhaoqi.oaarchive.rollback.RollbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回档：查看最近一次可回档的操作、执行回档；以及小R文件操作的允许范围设置。
 * 程序只监听 127.0.0.1；本机浏览器里其他网站发来的跨站请求由 LocalAccessFilter 拦截（技术难点 7.5）。
 */
@RestController
public class RollbackController {

    private final RollbackService rollback;
    private final FileScopeService scope;

    public RollbackController(RollbackService rollback, FileScopeService scope) {
        this.rollback = rollback;
        this.scope = scope;
    }

    /** 没有回档记录时 data 为 null */
    @GetMapping("/api/rollback")
    public ApiResponse<RollbackService.Entry> latest() {
        return ApiResponse.ok(rollback.latest());
    }

    /** id 是回档记录的 id，防止期间有新的操作覆盖了记录，回档到了另一次操作 */
    @PostMapping("/api/rollback/{id}")
    public ApiResponse<RollbackService.Result> rollback(@PathVariable String id) {
        return ApiResponse.ok(rollback.rollback(id));
    }

    @GetMapping("/api/ai/scope")
    public ApiResponse<FileScopeService.View> scope() {
        return ApiResponse.ok(scope.view());
    }

    @PutMapping("/api/ai/scope")
    public ApiResponse<FileScopeService.View> updateScope(@RequestBody FileScopeService.Update u) {
        return ApiResponse.ok(scope.update(u));
    }
}
