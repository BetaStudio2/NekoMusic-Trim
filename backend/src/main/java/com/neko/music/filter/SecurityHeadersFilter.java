package com.neko.music.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 通用安全加固过滤器（最外层，先于限流注册）。
 *
 * <ul>
 *   <li>禁用 {@code TRACE}/{@code TRACK}（防 XST），命中返回 405 并给出 Allow。</li>
 *   <li>所有响应统一带 {@code Content-Security-Policy}，以及 {@code X-Content-Type-Options}、
 *       {@code Referrer-Policy}、{@code X-Frame-Options}、{@code Permissions-Policy}。</li>
 *   <li>覆盖 {@code Server} 为不含版本号的名称（配合连接器 {@code setSendServerVersion(false)}），
 *       避免泄露 Jetty 版本。</li>
 * </ul>
 *
 * <p>需在 {@link com.neko.music.Main} 中显式注册（嵌入式 Jetty 不处理 {@code @WebFilter}）。</p>
 */
public class SecurityHeadersFilter implements Filter {

    private static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS";

    /**
     * 全站内容安全策略：静态资源、页面、API、SSE、报错页一视同仁。
     *
     * <p>取值受现有前端实现约束，收紧前先确认这三点（已构建产物与运行时都会踩到）：</p>
     * <ul>
     *   <li>{@code script-src 'unsafe-eval'}：播放页可视化用 PixiJS 在运行期以 {@code new Function}
     *       生成 uniform 同步代码，去掉它可视化直接抛错。</li>
     *   <li>{@code style-src 'unsafe-inline'}：{@code index.html} 带内联首屏样式，组件库也会注入样式。</li>
     *   <li>{@code img-src https: http:}：歌曲 / 歌单封面可能是第三方（网易云 / QQ / 酷狗）直链。</li>
     * </ul>
     *
     * <p>按 AGENTS.md 约定：防护策略只写代码常量，不新增 {@code config.yml} 键、不进
     * {@code system_settings}。</p>
     */
    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-eval'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob: https: http:",
            "media-src 'self' blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "worker-src 'self' blob:",
            "child-src 'self'",
            "frame-src 'self'",
            "manifest-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            // 先铺安全头：下面提前返回的 405 也必须带上（「所有响应都有 CSP」）
            applySecurityHeaders(httpResponse);

            String method = httpRequest.getMethod();
            if ("TRACE".equalsIgnoreCase(method) || "TRACK".equalsIgnoreCase(method)) {
                httpResponse.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                httpResponse.setHeader("Allow", ALLOWED_METHODS);
                httpResponse.setHeader("Cache-Control", "private, no-store");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** 写入全站统一的安全响应头（CSP + 常见加固头 + 隐藏服务端版本）。 */
    static void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Server", "NekoMusic");
    }
}
