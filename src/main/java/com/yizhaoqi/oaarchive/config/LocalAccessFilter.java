package com.yizhaoqi.oaarchive.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 本机访问保护（技术难点 7.5）。程序只监听 127.0.0.1，但这挡不住“用户正在浏览的网页”：
 * <ul>
 *   <li>跨站请求：任何网站都能让浏览器向 http://127.0.0.1:18080 发表单或 multipart 上传，不需要预检，
 *       可以导入文件、撤销归档、回档、打开文件。所以修改类请求的 Origin 必须是本程序自己。</li>
 *   <li>DNS 重绑定：恶意域名先解析到外网、再解析到 127.0.0.1，浏览器就把它当成同源，可以读台账。
 *       所以桌面版的 Host 必须是 127.0.0.1、localhost 或 [::1]。</li>
 * </ul>
 * 没有 Origin 的请求（curl、Postman、程序自己）不受影响；Docker 版通过任意域名访问，不检查 Host。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private final OaProperties props;

    public LocalAccessFilter(OaProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String reason = rejectReason(req);
        if (reason != null) {
            log.warn("拒绝请求 {} {}：{}（Host={}，Origin={}）", req.getMethod(), req.getRequestURI(), reason,
                    req.getHeader("Host"), req.getHeader("Origin"));
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getOutputStream().write(("{\"code\":403,\"message\":\"" + reason + "\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(req, resp);
    }

    String rejectReason(HttpServletRequest req) {
        String host = req.getServerName() == null ? "" : req.getServerName().toLowerCase(Locale.ROOT);
        if (!props.isDocker() && !LOOPBACK.contains(host)) {
            return "只允许在本机通过 127.0.0.1 或 localhost 访问";
        }
        if (SAFE_METHODS.contains(req.getMethod())) {
            return null;
        }
        String origin = req.getHeader("Origin");
        if (origin != null) {
            return sameOrigin(origin, req, !props.isDocker()) ? null : "拒绝来自其他网站的请求";
        }
        // 老浏览器不一定带 Origin，但会带 Sec-Fetch-Site
        String site = req.getHeader("Sec-Fetch-Site");
        if (site != null && site.equals("cross-site")) {
            return "拒绝来自其他网站的请求";
        }
        return null;
    }

    /**
     * Origin 的主机（桌面版还要比较端口）必须和这次请求的 Host 相同；沙箱 iframe 的 Origin 是 "null"，一律拒绝。
     * Docker 版常放在 HTTPS 反向代理后面，Origin 是 443、Host 不带端口，所以只比较主机名。
     */
    private static boolean sameOrigin(String origin, HttpServletRequest req, boolean checkPort) {
        try {
            URI o = URI.create(origin);
            if (o.getHost() == null) {
                return false;
            }
            int port = o.getPort() != -1 ? o.getPort() : "https".equalsIgnoreCase(o.getScheme()) ? 443 : 80;
            return o.getHost().equalsIgnoreCase(req.getServerName()) && (!checkPort || port == req.getServerPort());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
