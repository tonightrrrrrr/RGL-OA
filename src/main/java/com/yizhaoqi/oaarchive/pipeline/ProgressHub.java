package com.yizhaoqi.oaarchive.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 处理进度推送（SSE，技术文档 4.4）：每个文件状态变化推送一次 file 事件，批次完成推送 done 事件。
 */
@Component
public class ProgressHub {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long batchId) {
        SseEmitter e = new SseEmitter(0L); // 不超时，由前端关闭
        List<SseEmitter> list = emitters.computeIfAbsent(batchId, k -> new CopyOnWriteArrayList<>());
        list.add(e);
        Runnable remove = () -> list.remove(e);
        e.onCompletion(remove);
        e.onTimeout(remove);
        e.onError(t -> remove.run());
        return e;
    }

    public void publish(Long batchId, String event, Object data) {
        List<SseEmitter> list = emitters.get(batchId);
        if (list == null) {
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException ex) {
                list.remove(e);
            }
        }
    }
}
