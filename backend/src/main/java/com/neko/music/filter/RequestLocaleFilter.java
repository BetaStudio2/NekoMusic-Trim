package com.neko.music.filter;

import com.neko.music.i18n.RequestLocale;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * 按 {@code Accept-Language} 决定本次响应的语言，写入 {@link RequestLocale}。
 *
 * <p>必须注册在过滤器链**最外层**（{@code Main} 里紧跟访问日志之后）：文案不只是处理器写的，
 * 限流 / 封禁等门禁也会直接写 JSON 错误体，语言得在它们之前就定好。</p>
 *
 * <p>用 {@code finally} 清理 ThreadLocal：Jetty 线程会复用，不清会把语言串给下一个请求。</p>
 */
public class RequestLocaleFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 无需初始化
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                RequestLocale.set(RequestLocale.resolve(httpRequest.getHeader("Accept-Language")));
            }
            chain.doFilter(request, response);
        } finally {
            RequestLocale.clear();
        }
    }

    @Override
    public void destroy() {
        // 无需清理
    }
}
