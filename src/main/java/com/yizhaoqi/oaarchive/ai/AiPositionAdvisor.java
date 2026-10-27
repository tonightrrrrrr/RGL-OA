package com.yizhaoqi.oaarchive.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.recognize.PositionAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 简历的应聘岗位没有匹配的岗位规则时，问小R属于哪个部门（v1.8）。
 * 只发岗位名称和部门名称，不发简历里的姓名、电话等内容；AI 助手没开、没配好或关掉了“岗位兜底”时不调用。
 * 同一个岗位在一次运行里只问一次（一批简历常常是同一个岗位）；回答必须是给出的部门之一，否则不用。
 */
@Component
public class AiPositionAdvisor implements PositionAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiPositionAdvisor.class);
    private static final int MAX_CACHE = 500;

    private final AiClient client;
    private final AiSettingsService settings;
    private final ObjectMapper mapper;
    private final Map<String, CompletableFuture<Suggestion>> cache = new ConcurrentHashMap<>();

    public AiPositionAdvisor(AiClient client, AiSettingsService settings, ObjectMapper mapper) {
        this.client = client;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    public Suggestion suggest(String position, List<String> departments) {
        AiSettingsService.Settings s = settings.get();
        if (!AiSettingsService.ready(s) || !s.isPositionFallback() || position == null || position.isBlank() || departments.isEmpty()) {
            return null;
        }
        String key = position.strip() + "|" + String.join(",", departments);
        CompletableFuture<Suggestion> mine = new CompletableFuture<>();
        CompletableFuture<Suggestion> f = cache.putIfAbsent(key, mine);
        if (f == null) {
            f = mine;
            if (cache.size() > MAX_CACHE) {
                cache.clear();
                cache.put(key, mine);
            }
            try {
                Suggestion r = ask(s, position.strip(), departments);
                mine.complete(r);
                if (r == null) {
                    cache.remove(key, mine); // 没问出来（比如超时）下次再问
                }
            } catch (RuntimeException e) {
                cache.remove(key, mine);
                mine.complete(null);
                log.warn("小R推测部门失败（{}）：{}", position, e.getMessage());
                return null;
            }
        }
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private Suggestion ask(AiSettingsService.Settings s, String position, List<String> departments) {
        // 这是一个很小的分类问题：超时设短一点、不需要深度思考，免得拖慢整批导入
        s.setTimeoutSeconds(Math.min(s.getTimeoutSeconds(), 30));
        s.setReasoning("low");
        ArrayNode messages = mapper.createArrayNode();
        messages.add(client.message("system", """
                你帮企业归档系统给简历分部门。根据应聘岗位，从给出的部门里选一个这个岗位最可能所属的部门。
                只能从列表里选；实在判断不了就回答“无法判断”。
                只输出一行 JSON：{"department":"部门名","reason":"不超过 20 字的理由"}"""));
        messages.add(client.message("user", "部门：" + String.join("、", departments) + "\n应聘岗位：" + position));
        StringBuilder text = new StringBuilder();
        AiClient.Reply r;
        try {
            r = client.chat(s, messages, null, text::append, () -> false);
        } catch (BizException e) {
            log.warn("小R推测部门失败（{}）：{}", position, e.getMessage());
            return null;
        }
        Suggestion sug = parse(r.content().isEmpty() ? text.toString() : r.content(), departments);
        log.info("小R推测岗位“{}”属于：{}", position, sug == null ? "无法判断" : sug.department());
        return sug;
    }

    /** 回答里取 JSON；不是 JSON 时，正好只提到一个部门也算 */
    Suggestion parse(String reply, List<String> departments) {
        if (reply == null) {
            return null;
        }
        int a = reply.indexOf('{');
        int b = reply.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                JsonNode n = mapper.readTree(reply.substring(a, b + 1));
                String d = n.path("department").asText("").strip();
                if (departments.contains(d)) {
                    return new Suggestion(d, AiClient.clip(n.path("reason").asText("").strip(), 40));
                }
                return null;
            } catch (Exception ignored) {
                // 不是合法 JSON，按纯文字处理
            }
        }
        List<String> named = departments.stream().filter(reply::contains).toList();
        return named.size() == 1 ? new Suggestion(named.get(0), "") : null;
    }
}
