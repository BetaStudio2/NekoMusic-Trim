package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.NekoAuthOAuthService;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回 NekoAuth 全局登出所需的公开配置。
 *
 * <p>这些值本就会出现在浏览器跳转与 OIDC 发现文档中，不包含客户端密钥或用户令牌。</p>
 */
public final class NekoAuthSsoLogoutConfigHandler extends ApiServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        NekoAuthOAuthService oauthService = Main.getNekoAuthOAuthService();
        if (oauthService == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "登录服务暂时不可用");
            return;
        }
        try {
            NekoAuthOAuthService.LogoutConfig config = oauthService.logoutConfig();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("endpoint", config.endpoint());
            data.put("clientId", config.clientId());
            data.put("postLogoutRedirectUri", config.postLogoutRedirectUri());
            sendResponse(response, true, "登出配置已加载", data);
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            int status = switch (exception.code()) {
                case "oauth_disabled", "oauth_not_configured", "oauth_unavailable" ->
                        HttpServletResponse.SC_SERVICE_UNAVAILABLE;
                default -> HttpServletResponse.SC_BAD_REQUEST;
            };
            sendErrorResponse(response, status, exception.getMessage());
        } catch (RuntimeException exception) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_GATEWAY, "登录服务暂时不可用");
        }
    }
}
