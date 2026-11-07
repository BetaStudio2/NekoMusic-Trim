package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthOAuthService;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回 NekoAuth 账号中心 / 注册 / 找回密码的浏览器地址。
 */
public final class NekoAuthPortalHandler extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.LOGIN)) {
            return;
        }
        NekoAuthOAuthService oauthService = Main.getNekoAuthOAuthService();
        if (oauthService == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "NekoAuth 账号服务暂时不可用");
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", oauthService.portalUrl(request.getParameter("target")));
            sendResponse(response, true, "ok", data);
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            int status = switch (exception.code()) {
                case "invalid_portal_target" -> HttpServletResponse.SC_BAD_REQUEST;
                case "oauth_not_configured", "oauth_unavailable" ->
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                default -> HttpServletResponse.SC_BAD_REQUEST;
            };
            sendErrorResponse(response, status, exception.getMessage());
        }
    }
}
