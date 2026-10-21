package com.yizhaoqi.oaarchive.ai;

import com.yizhaoqi.oaarchive.common.BizException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 小R 提出的修改（改模板、修正识别结果）先放在这里，界面上显示确认卡片；用户点“确认”才真正执行。
 * 模型拿不到执行入口，只能“提议”，所以即使模型被文件内容里的文字误导，也改不了任何数据。
 */
@Component
public class AiActions {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX = 50;

    /** 一行“改前 → 改后” */
    public record Change(String label, String before, String after) {
    }

    public record Example(String from, String to) {
    }

    /** 确认卡片的内容 */
    public record View(String id, String kind, String title, String summary, List<Change> changes,
                       Map<String, Object> stats, List<Example> examples, List<String> warnings) {
    }

    /** 执行结果：message 显示给用户，link 是执行后可以跳转查看的页面 */
    public record Result(String message, String link) {
    }

    private record Pending(View view, Supplier<Result> apply, Instant created) {
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public synchronized View add(String kind, String title, String summary, List<Change> changes, Map<String, Object> stats,
                                 List<Example> examples, List<String> warnings, Supplier<Result> apply) {
        purge();
        String id = UUID.randomUUID().toString();
        View v = new View(id, kind, title, summary, changes, stats, examples, warnings);
        pending.put(id, new Pending(v, apply, Instant.now()));
        return v;
    }

    /** 执行并移除；同一张卡片只能执行一次 */
    public Result confirm(String id) {
        Pending p;
        synchronized (this) {
            purge();
            p = pending.remove(id);
        }
        if (p == null) {
            throw new BizException("这项操作已经执行、取消或过期（30 分钟），请让小R重新生成");
        }
        return p.apply().get();
    }

    public synchronized void cancel(String id) {
        pending.remove(id);
    }

    private void purge() {
        Instant limit = Instant.now().minus(TTL);
        Iterator<Pending> it = pending.values().iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.created().isBefore(limit) || pending.size() > MAX) {
                it.remove();
            }
        }
    }
}
