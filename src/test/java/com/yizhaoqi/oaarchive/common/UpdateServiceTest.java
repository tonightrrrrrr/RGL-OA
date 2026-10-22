package com.yizhaoqi.oaarchive.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.oaarchive.config.OaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联网检查更新：用本地的假 GitHub 接口测试，不访问外网。
 * 重点是没联网时不报错、不影响使用。
 */
class UpdateServiceTest {

    private static final String REPO = "tonightrrrrrr/RGL-OA";

    @TempDir
    Path tmp;
    HttpServer server;
    volatile int status = 200;
    volatile String body = "";
    final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/" + REPO + "/releases/latest", ex -> {
            hits.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UpdateService service(String current, String api) {
        OaProperties p = new OaProperties();
        p.setHome(tmp.toString());
        return new UpdateService(new ObjectMapper(), p, REPO, api, current);
    }

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String release(String tag, String assetUrl) {
        return """
                {"tag_name":"%s","name":"RGL %s","body":"## 新增\\n- 新功能","published_at":"2026-10-26T08:00:00Z",
                 "html_url":"https://github.com/tonightrrrrrr/RGL-OA/releases/tag/%s",
                 "assets":[{"name":"readme.txt","browser_download_url":"https://github.com/tonightrrrrrr/RGL-OA/releases/download/%s/readme.txt","size":10},
                           {"name":"OA-Archive-Agent-%s-win64.zip","browser_download_url":"%s","size":179000000}]}"""
                .formatted(tag, tag, tag, tag, tag, assetUrl);
    }

    @Test
    void reportsNewerVersionWithZipDownload() {
        body = release("v1.6", "https://github.com/tonightrrrrrr/RGL-OA/releases/download/v1.6/OA-Archive-Agent-v1.6-win64.zip");
        UpdateService.Result r = service("1.5", api()).check(true);
        assertThat(r.state()).isEqualTo(UpdateService.State.AVAILABLE);
        assertThat(r.latest()).isEqualTo("1.6");
        assertThat(r.downloadUrl()).endsWith("OA-Archive-Agent-v1.6-win64.zip");
        assertThat(r.downloadSize()).isEqualTo(179000000L);
        assertThat(r.notes()).contains("新功能");
    }

    @Test
    void sameOrOlderVersionIsUpToDate() {
        body = release("v1.5", "https://github.com/tonightrrrrrr/RGL-OA/releases/download/v1.5/x.zip");
        assertThat(service("1.5", api()).check(true).state()).isEqualTo(UpdateService.State.UP_TO_DATE);
        assertThat(service("1.5.2", api()).check(true).state()).isEqualTo(UpdateService.State.UP_TO_DATE);
    }

    @Test
    void offlineDoesNotThrowAndKeepsWorking() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // 拿到一个空闲端口后立即关闭，模拟“连不上”
        }
        long t = System.currentTimeMillis();
        UpdateService.Result r = service("1.5", "http://127.0.0.1:" + port).check(true);
        assertThat(r.state()).isEqualTo(UpdateService.State.OFFLINE);
        assertThat(r.message()).contains("不影响使用");
        assertThat(System.currentTimeMillis() - t).as("连不上时要很快返回").isLessThan(8000);
    }

    @Test
    void untrustedLinksAreIgnored() {
        body = release("v1.6", "https://evil.example.com/RGL-OA.zip");
        UpdateService.Result r = service("1.5", api()).check(true);
        assertThat(r.state()).isEqualTo(UpdateService.State.AVAILABLE);
        // zip 不在本仓库下，退回到发布页
        assertThat(r.downloadUrl()).isEqualTo("https://github.com/tonightrrrrrr/RGL-OA/releases/tag/v1.6");
    }

    @Test
    void noReleaseAndRateLimitAreHandled() {
        status = 404;
        body = "{\"message\":\"Not Found\"}";
        assertThat(service("1.5", api()).check(true).state()).isEqualTo(UpdateService.State.UP_TO_DATE);
        status = 403;
        assertThat(service("1.5", api()).check(true).state()).isEqualTo(UpdateService.State.ERROR);
    }

    @Test
    void automaticCheckRunsAtMostOncePerDay() {
        body = release("v1.5", "https://github.com/tonightrrrrrr/RGL-OA/releases/download/v1.5/x.zip");
        UpdateService s = service("1.5", api());
        s.check(false);
        s.check(false);
        assertThat(hits.get()).isEqualTo(1);
        s.check(true); // 手动检查不受限制
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void autoCheckSettingIsSaved() {
        UpdateService s = service("1.5", api());
        assertThat(s.last().autoCheck()).isTrue();
        s.setAutoCheck(false);
        assertThat(s.last().state()).isEqualTo(UpdateService.State.DISABLED);
        // 重新创建（模拟重启），设置还在
        UpdateService again = service("1.5", api());
        assertThat(again.last().autoCheck()).isFalse();
        again.checkOnStartup();
        assertThat(hits.get()).as("关闭后启动时不联网").isZero();
    }

    @Test
    void comparesVersionsNumerically() {
        assertThat(UpdateService.compare("1.5.10", "1.5.9")).isPositive();
        assertThat(UpdateService.compare("v1.5", "1.5.0")).isZero();
        assertThat(UpdateService.compare("1.10", "1.9")).isPositive();
        assertThat(UpdateService.compare("1.4", "1.5.1")).isNegative();
    }
}
