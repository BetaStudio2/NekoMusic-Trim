package com.neko.music.filter;

import com.neko.music.seo.BrowserEvidence;
import com.neko.music.seo.StaticPageSeoRenderer;
import com.neko.music.seo.UserAgentClassifier;
import com.neko.music.util.SeoAudit;
import com.neko.music.util.SiteUrlResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 为静态路由（首页 / 落雪导入 / 下载 / 关于 / 隐私 / 排行榜 / 最新）的爬虫请求返回服务端 HTML。
 *
 * <p>普通浏览器直接放行到 SPA；只有爬虫 / 链接预览 / AI 抓取器会拿到
 * {@link StaticPageSeoRenderer} 生成的完整正文与 JSON-LD。需在 {@code Main} 中显式注册
 * （嵌入式 Jetty 不处理 {@code @WebFilter}）。
 */
public class StaticPageSeoFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(StaticPageSeoFilter.class);

    private final StaticPageSeoRenderer renderer = StaticPageSeoRenderer.create();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !"GET".equalsIgnoreCase(httpRequest.getMethod())
                || !StaticPageSeoRenderer.isStaticPage(httpRequest.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // 同一 URL 对爬虫与浏览器有两种表现，必须声明 Vary，避免代理串味。
        httpResponse.setHeader("Vary", "User-Agent");
        if (!UserAgentClassifier.shouldRenderSeo(
                httpRequest.getHeader("User-Agent"), BrowserEvidence.hasFetchEvidence(httpRequest))) {
            // 浏览器版：同一个 URL 走 SPA，明确记 false，好和爬虫拿走的 SEO 版区分开
            SeoAudit.markNotSeo(httpRequest);
            chain.doFilter(request, response);
            return;
        }

        String html;
        try {
            html = renderer.render(httpRequest.getRequestURI(), SiteUrlResolver.resolvePublicSiteBase(httpRequest));
        } catch (Exception e) {
            logger.error("生成静态页 SEO HTML 失败 path={}", httpRequest.getRequestURI(), e);
            chain.doFilter(request, response);
            return;
        }
        if (html == null) {
            chain.doFilter(request, response);
            return;
        }

        SeoAudit.mark(httpRequest);
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setContentType("text/html;charset=utf-8");
        httpResponse.setHeader("Cache-Control", "private, no-store");
        httpResponse.getWriter().write(html);
    }
}
