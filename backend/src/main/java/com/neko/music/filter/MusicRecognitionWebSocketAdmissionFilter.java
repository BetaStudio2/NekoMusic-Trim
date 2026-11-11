package com.neko.music.filter;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.handlers.MusicRecognitionHandler;
import com.neko.music.handlers.MusicRecognitionWebSocketEndpoint;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.util.ClientIpResolver;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.RequestPath;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Applies the recognition feature switch and per-source request budget at handshake time. */
public final class MusicRecognitionWebSocketAdmissionFilter implements Filter {
    private ConfigManager config;

    @Override
    public void init(FilterConfig filterConfig) {
        config = (ConfigManager) filterConfig.getServletContext().getAttribute("configManager");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || !MusicRecognitionWebSocketEndpoint.PATH.equals(RequestPath.of(httpRequest))
                || !"websocket".equalsIgnoreCase(httpRequest.getHeader("Upgrade"))) {
            chain.doFilter(request, response);
            return;
        }
        httpResponse.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        if (config == null || !config.isMusicRecognitionEnabled()) {
            writeError(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiMessages.translate("recognition.disabled"), 0);
            return;
        }

        var decision = MusicRecognitionHandler.acquireRecognitionSlot(
                ClientIpResolver.clientIp(httpRequest),
                config.getMusicRecognitionRateLimitPerMinute());
        if (!decision.allowed()) {
            writeError(httpResponse, 429,
                    ApiMessages.translate("recognition.too_frequent"), decision.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeError(
            HttpServletResponse response, int status, String message, long retryAfterSeconds) throws IOException {
        response.setStatus(status);
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=utf-8");
        Main.getObjectMapper().writeValue(response.getWriter(), MusicRecognitionHandler.errorResponse(message));
    }
}
