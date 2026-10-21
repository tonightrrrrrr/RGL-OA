package com.yizhaoqi.oaarchive.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.oaarchive.ai.AiActions;
import com.yizhaoqi.oaarchive.ai.AiChatService;
import com.yizhaoqi.oaarchive.ai.AiClient;
import com.yizhaoqi.oaarchive.ai.AiSettingsService;
import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 助手“小R”：连接设置、测试连接、对话（SSE 流式）、确认或取消小R提出的修改。
 * 程序只监听 127.0.0.1，这些接口只有本机能访问；API Key 不会通过任何接口返回。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiSettingsService settings;
    private final AiClient client;
    private final AiChatService chat;
    private final AiActions actions;
    private final AtomicInteger seq = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-chat-" + seq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public AiController(AiSettingsService settings, AiClient client, AiChatService chat, AiActions actions) {
        this.settings = settings;
        this.client = client;
        this.chat = chat;
        this.actions = actions;
    }

    public record ChatRequest(JsonNode messages) {
    }

    @GetMapping("/settings")
    public ApiResponse<AiSettingsService.View> getSettings() {
        return ApiResponse.ok(settings.view());
    }

    @PutMapping("/settings")
    public ApiResponse<AiSettingsService.View> saveSettings(@RequestBody AiSettingsService.Update u) {
        return ApiResponse.ok(settings.update(u));
    }

    /** 用表单上还没保存的设置测试，不保存 */
    @PostMapping("/test")
    public ApiResponse<AiClient.TestResult> test(@RequestBody(required = false) AiSettingsService.Update u) {
        return ApiResponse.ok(client.test(settings.preview(u)));
    }

    @PostMapping("/models")
    public ApiResponse<List<String>> models(@RequestBody(required = false) AiSettingsService.Update u) {
        AiSettingsService.Settings s = settings.preview(u);
        if (s.getBaseUrl().isBlank() || s.getApiKey().isBlank()) {
            throw new BizException("请先填写接口地址和 API Key");
        }
        return ApiResponse.ok(client.models(s));
    }

    /** 事件：delta（文字片段）、tool（工具状态）、action（确认卡片）、download（下载按钮）、done、error */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(10 * 60_000L);
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));
        AiChatService.Sink sink = new AiChatService.Sink() {
            @Override
            public void send(String event, Object data) {
                if (cancelled.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    cancelled.set(true); // 页面已关闭或点了停止
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
        pool.submit(() -> {
            try {
                chat.chat(req.messages(), sink);
            } catch (BizException e) {
                sink.send("error", Map.of("message", e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("AI 对话出错", e);
                sink.send("error", Map.of("message", "小R遇到了问题：" + e.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/actions/{id}/confirm")
    public ApiResponse<AiActions.Result> confirm(@PathVariable String id) {
        return ApiResponse.ok(actions.confirm(id));
    }

    @PostMapping("/actions/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id) {
        actions.cancel(id);
        return ApiResponse.ok();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
