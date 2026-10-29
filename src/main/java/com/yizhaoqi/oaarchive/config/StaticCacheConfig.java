package com.yizhaoqi.oaarchive.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 页面缓存策略（v1.9，技术难点 7.11）：
 * 原来所有静态文件只带 Last-Modified，浏览器会按“启发式缓存”直接用缓存里的旧 index.html。
 * 升级后旧 index.html 引用的页面文件（ImportView-旧哈希.js）在新 jar 里已经不存在，返回 404，
 * 页面主体一直空白或卡住，刷新一下才正常。
 * <ul>
 *   <li>index.html（和 /）：每次都向程序确认（no-cache），内容没变时返回 304，几乎不耗时。</li>
 *   <li>/assets/ 下带哈希的文件：文件名随内容变化，可以长期缓存。</li>
 *   <li>/api/：接口数据不缓存。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StaticCacheConfig extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        if (uri.startsWith("/assets/")) {
            resp.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else if (uri.startsWith("/api/")) {
            resp.setHeader("Cache-Control", "no-store");
        } else if (uri.equals("/") || uri.endsWith(".html")) {
            resp.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(req, resp);
    }
}
