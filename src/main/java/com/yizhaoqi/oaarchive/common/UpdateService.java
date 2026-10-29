package com.yizhaoqi.oaarchive.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.config.OaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 联网检查新版本：读取 GitHub Releases 的最新发布，和当前版本比较。
 * 程序完全可以离线使用：没网、超时、GitHub 打不开时只记一个状态，不报错、不弹窗、不影响任何功能。
 * 只发送一个 GET 请求，不发送任何台账或文件数据。
 */
@Service
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final Duration AUTO_INTERVAL = Duration.ofHours(24);
    /** 打开页面时，上次检查超过这么久就再查一次（v1.9）：程序常驻托盘好几天，也能及时发现新版本 */
    static final Duration OPEN_INTERVAL = Duration.ofHours(1);

    public enum State { NEVER, CHECKING, UP_TO_DATE, AVAILABLE, OFFLINE, ERROR, DISABLED }

    /** 检查结果。notes 是 GitHub 发布说明（Markdown）；downloadUrl 优先指向 zip，没有时指向发布页 */
    public record Result(State state, String current, String latest, String title, String notes, String publishedAt,
                         String releaseUrl, String downloadUrl, Long downloadSize, String message,
                         LocalDateTime checkedAt, boolean autoCheck, String repo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        public boolean autoCheck = true;
    }

    private final ObjectMapper mapper;
    private final String repo;
    private final String apiBase;
    private final String current;
    private final Path file;
    private volatile Settings settings;
    private volatile Result last;
    private final HttpClient http;

    public UpdateService(ObjectMapper mapper, OaProperties props,
                         @Value("${oa.update.repo:tonightrrrrrr/RGL-OA}") String repo,
                         @Value("${oa.update.api:https://api.github.com}") String apiBase,
                         @Value("${oa.update.current:}") String currentOverride) {
        this.mapper = mapper;
        this.repo = repo;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.current = currentOverride.isBlank() ? com.yizhaoqi.oaarchive.controller.SystemController.VERSION : currentOverride;
        this.file = props.homePath().resolve("config").resolve("update.json");
        this.settings = readSettings();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault()).sslContext(SystemTrust.context()).build();
        this.last = result(settings.autoCheck ? State.NEVER : State.DISABLED, null, null, null, null, null, null, null,
                settings.autoCheck ? "还没有检查过" : "已关闭自动检查，可以手动检查", null);
    }

    /** 自动化测试里关掉启动时的检查，测试不联网 */
    @Value("${oa.update.startup-check:true}")
    private boolean startupCheck = true;

    /** 程序启动完成后在后台检查一次；关闭了自动检查或 24 小时内检查过就跳过 */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (!settings.autoCheck || !startupCheck) {
            return;
        }
        Thread t = new Thread(() -> check(false), "update-check");
        t.setDaemon(true);
        t.start();
    }

    public Result last() {
        return last;
    }

    /** force=false 时 24 小时内只检查一次；手动点“检查更新”时 force=true */
    public synchronized Result check(boolean force) {
        return check(force, AUTO_INTERVAL);
    }

    /**
     * 打开页面时调用（v1.9）：关闭了自动检查时不联网；上次检查在 1 小时内时直接返回上次结果；
     * 否则先把状态设为“正在检查”并返回，在后台再查一次。页面不等网络，看到“正在检查”就过几秒再来读。
     */
    public Result onPageOpen() {
        synchronized (pageOpenLock) {
            Result prev = last;
            if (!settings.autoCheck || !startupCheck || prev.state() == State.CHECKING) {
                return prev;
            }
            if (prev.checkedAt() != null && prev.checkedAt().isAfter(LocalDateTime.now().minus(OPEN_INTERVAL))) {
                return prev;
            }
            last = result(State.CHECKING, null, null, null, null, null, null, null, "正在检查…", prev.checkedAt());
            Thread t = new Thread(() -> check(true), "update-check");
            t.setDaemon(true);
            t.start();
            return last;
        }
    }

    private final Object pageOpenLock = new Object();

    private synchronized Result check(boolean force, Duration interval) {
        Result prev = last;
        if (!force && prev.checkedAt() != null && prev.state() != State.OFFLINE && prev.state() != State.ERROR
                && prev.checkedAt().isAfter(LocalDateTime.now().minus(interval))) {
            return prev;
        }
        last = result(State.CHECKING, null, null, null, null, null, null, null, "正在检查…", prev.checkedAt());
        last = fetch();
        log.info("检查更新：{}（当前 v{}，最新 {}）", last.state(), current, last.latest() == null ? "-" : "v" + last.latest());
        return last;
    }

    public synchronized Result setAutoCheck(boolean on) {
        Settings s = new Settings();
        s.autoCheck = on;
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), s);
        } catch (IOException e) {
            throw new BizException("保存设置失败：" + e.getMessage());
        }
        settings = s;
        Result r = last;
        State state = r.state();
        if (on && state == State.DISABLED) {
            state = State.NEVER;
        } else if (!on && r.checkedAt() == null) {
            state = State.DISABLED;
        }
        last = new Result(state, r.current(), r.latest(), r.title(), r.notes(), r.publishedAt(), r.releaseUrl(),
                r.downloadUrl(), r.downloadSize(), r.message(), r.checkedAt(), on, repo);
        return last;
    }

    private Result fetch() {
        LocalDateTime now = LocalDateTime.now();
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/repos/" + repo + "/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RGL-OA/" + current)
                .GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return result(State.OFFLINE, null, null, null, null, null, null, null,
                    "没有连上 GitHub（可能没联网），不影响使用", now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(State.OFFLINE, null, null, null, null, null, null, null, "检查被中断", now);
        }
        if (resp.statusCode() == 404) {
            return result(State.UP_TO_DATE, current, null, null, null, null, null, null, "还没有发布过新版本", now);
        }
        if (resp.statusCode() == 403 || resp.statusCode() == 429) {
            return result(State.ERROR, null, null, null, null, null, null, null, "GitHub 暂时限制了访问次数，稍后再试", now);
        }
        if (resp.statusCode() / 100 != 2) {
            return result(State.ERROR, null, null, null, null, null, null, null, "GitHub 返回 HTTP " + resp.statusCode(), now);
        }
        try {
            JsonNode r = mapper.readTree(resp.body());
            String tag = r.path("tag_name").asText("");
            String latest = tag.replaceFirst("^[vV]", "").strip();
            if (latest.isEmpty() || !latest.matches("\\d+(\\.\\d+){0,3}")) {
                return result(State.ERROR, null, null, null, null, null, null, null, "最新发布的版本号无法识别：" + tag, now);
            }
            String releaseUrl = trusted(r.path("html_url").asText(null));
            String download = null;
            Long size = null;
            for (JsonNode a : r.path("assets")) {
                String name = a.path("name").asText("");
                String url = trusted(a.path("browser_download_url").asText(null));
                if (url != null && name.toLowerCase().endsWith(".zip")) {
                    download = url;
                    size = a.path("size").asLong();
                    break;
                }
            }
            boolean newer = compare(latest, current) > 0;
            String title = r.path("name").asText(tag);
            String notes = r.path("body").asText("");
            return new Result(newer ? State.AVAILABLE : State.UP_TO_DATE, current, latest, title,
                    notes.length() > 20000 ? notes.substring(0, 20000) : notes, r.path("published_at").asText(null),
                    releaseUrl, download != null ? download : releaseUrl, size,
                    newer ? "发现新版本 v" + latest : "已是最新版本", now, settings.autoCheck, repo);
        } catch (IOException | RuntimeException e) {
            return result(State.ERROR, null, null, null, null, null, null, null, "GitHub 返回的数据无法解析", now);
        }
    }

    /** 只接受本仓库在 github.com 上的链接，防止发布说明里的数据把用户引到别的网站 */
    String trusted(String url) {
        if (url == null) {
            return null;
        }
        String prefix = "https://github.com/" + repo + "/";
        return url.startsWith(prefix) ? url : null;
    }

    /** 按数字逐段比较版本号：1.5.10 > 1.5.9 > 1.5 */
    public static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\.");
        String[] y = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(i < x.length ? parse(x[i]) : 0, i < y.length ? parse(y[i]) : 0);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Result result(State state, String latest, String title, String notes, String publishedAt, String releaseUrl,
                          String downloadUrl, Long size, String message, LocalDateTime checkedAt) {
        return new Result(state, current, latest, title, notes, publishedAt, releaseUrl, downloadUrl, size, message, checkedAt,
                settings.autoCheck, repo);
    }

    private Settings readSettings() {
        if (!Files.exists(file)) {
            return new Settings();
        }
        try {
            return mapper.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            return new Settings();
        }
    }
}
