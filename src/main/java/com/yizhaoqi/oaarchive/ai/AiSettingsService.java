package com.yizhaoqi.oaarchive.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * AI 助手的连接设置，保存在数据目录的 config/ai.json，不进数据库、不进安装包。
 * API Key 只在后端使用：读取设置时只返回打码后的形式，日志里也不出现。
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        private boolean enabled;
        /** OpenAI 兼容接口地址，如 https://api.openai.com/v1、https://api.deepseek.com/v1、中转站地址 */
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        /** 是否允许把文件正文摘要发给模型；默认只发文件名、部门、类型、日期等字段 */
        private boolean allowContent;
        /** 代理，格式 host:port，留空不用代理 */
        private String proxy = "";
        private int timeoutSeconds = 90;
        /** 用户已看过“数据会发送给第三方”的提示 */
        private boolean privacyAcknowledged;
    }

    /** 返回给前端的设置：Key 打码 */
    public record View(boolean enabled, String baseUrl, boolean hasKey, String keyHint, String model,
                       boolean allowContent, String proxy, int timeoutSeconds, boolean privacyAcknowledged,
                       boolean ready) {
    }

    /** 前端提交的修改；apiKey 为空表示不改 */
    public record Update(Boolean enabled, String baseUrl, String apiKey, String model, Boolean allowContent,
                         String proxy, Integer timeoutSeconds, Boolean privacyAcknowledged) {
    }

    private final Path file;
    private final ObjectMapper mapper;
    private volatile Settings current;

    public AiSettingsService(OaProperties props, ObjectMapper mapper) {
        this.file = props.homePath().resolve("config").resolve("ai.json");
        this.mapper = mapper;
        this.current = read();
    }

    public Settings get() {
        return copy(current);
    }

    public View view() {
        return view(current);
    }

    /** 已开启并且填好了地址、Key、模型 */
    public boolean ready() {
        return ready(current);
    }

    public synchronized View update(Update u) {
        Settings s = merge(current, u);
        write(s);
        current = s;
        return view(s);
    }

    /** 测试连接用：在已保存设置的基础上套用尚未保存的修改，不写盘 */
    public Settings preview(Update u) {
        return merge(current, u);
    }

    private Settings merge(Settings base, Update u) {
        Settings s = copy(base);
        if (u == null) {
            return s;
        }
        if (u.baseUrl() != null) {
            String url = normalizeUrl(u.baseUrl());
            // 换了接口地址却没有重新填 Key 时清空 Key，防止把已保存的 Key 发到新地址
            if (!url.equals(s.getBaseUrl()) && blank(u.apiKey())) {
                s.setApiKey("");
            }
            s.setBaseUrl(url);
        }
        if (!blank(u.apiKey())) {
            s.setApiKey(u.apiKey().strip());
        }
        if (u.model() != null) {
            s.setModel(u.model().strip());
        }
        if (u.enabled() != null) {
            s.setEnabled(u.enabled());
        }
        if (u.allowContent() != null) {
            s.setAllowContent(u.allowContent());
        }
        if (u.proxy() != null) {
            String p = u.proxy().strip();
            if (!p.isEmpty() && !p.matches("[A-Za-z0-9.\\-]+:\\d{1,5}")) {
                throw new BizException("代理格式应为 主机:端口，例如 127.0.0.1:7890");
            }
            s.setProxy(p);
        }
        if (u.timeoutSeconds() != null) {
            s.setTimeoutSeconds(Math.min(Math.max(u.timeoutSeconds(), 15), 600));
        }
        if (u.privacyAcknowledged() != null) {
            s.setPrivacyAcknowledged(u.privacyAcknowledged());
        }
        return s;
    }

    static String normalizeUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty()) {
            return "";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 用户常把完整的 /chat/completions 地址粘进来，这里去掉，统一保存到 /v1 这一级
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException("接口地址格式不正确：" + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new BizException("接口地址应以 https:// 或 http:// 开头，例如 https://api.openai.com/v1");
        }
        return url;
    }

    private static View view(Settings s) {
        String key = s.getApiKey() == null ? "" : s.getApiKey();
        String hint = key.isEmpty() ? "" : key.length() <= 8 ? "****" : key.substring(0, 4) + "****" + key.substring(key.length() - 4);
        return new View(s.isEnabled(), s.getBaseUrl(), !key.isEmpty(), hint, s.getModel(), s.isAllowContent(), s.getProxy(),
                s.getTimeoutSeconds(), s.isPrivacyAcknowledged(), ready(s));
    }

    static boolean ready(Settings s) {
        return s.isEnabled() && !blank(s.getBaseUrl()) && !blank(s.getApiKey()) && !blank(s.getModel());
    }

    private Settings read() {
        if (!Files.exists(file)) {
            return new Settings();
        }
        try {
            return mapper.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            log.warn("AI 设置文件无法读取，已使用默认设置：{}", e.getMessage());
            return new Settings();
        }
    }

    private void write(Settings s) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("ai.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), s);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BizException("保存 AI 设置失败：" + e.getMessage());
        }
    }

    private Settings copy(Settings s) {
        return mapper.convertValue(s, Settings.class);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
