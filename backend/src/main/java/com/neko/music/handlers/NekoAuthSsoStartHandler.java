package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthOAuthService;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回 NekoAuth 的完整浏览器跳转地址，由前端直接导航到账号中心。
 *
 * <p>生产边缘路由会占用若干非 API 路径；把发起登录放在 {@code /api/user/sso/*}
 * 可以确保请求一定到达 NekoMusic 后端。这里只生成地址和一次性 state，不签发用户令牌。</p>
 */
public final class NekoAuthSsoStartHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(NekoAuthSsoStartHandler.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.LOGIN)) {
            return;
        }
        NekoAuthOAuthService oauthService = Main.getNekoAuthOAuthService();
        if (oauthService == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "登录服务暂时不可用");
            return;
        }

        try {
            String path = request.getServletPath();
            StartRequest startRequest = readStartRequest(request);
            String url = path.endsWith("/password-reset-url")
                    ? oauthService.passwordResetUrl()
                    : oauthService.authorizationUrl(
                            startRequest.returnTo(), startRequest.client(), startRequest.silent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", url);
            sendResponse(response, true, "跳转地址已生成", data);
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            logger.warn("生成 NekoAuth 跳转地址失败: code={}", exception.code());
            sendErrorResponse(response, responseStatusFor(exception.code()), exception.getMessage());
        } catch (RuntimeException exception) {
            logger.warn("生成 NekoAuth 跳转地址异常", exception);
            sendErrorResponse(response, HttpServletResponse.SC_BAD_GATEWAY, "登录服务暂时不可用");
        }
    }

    private StartRequest readStartRequest(HttpServletRequest request) throws IOException {
        String raw = readBody(request);
        if (raw == null || raw.isBlank()) {
            return new StartRequest("/", "web", false);
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(raw);
        } catch (IOException exception) {
            return new StartRequest("/", "web", false);
        }
        String returnTo = body == null ? "" : body.path("returnTo").asText("");
        String client = body == null ? "" : body.path("client").asText("");
        boolean silent = body != null && body.path("silent").asBoolean(false);
        return new StartRequest(
                returnTo.isBlank() ? "/" : returnTo,
                client.isBlank() ? "web" : client,
                silent);
    }

    private static int responseStatusFor(String code) {
        return switch (code) {
            case "oauth_disabled", "oauth_not_configured", "oauth_unavailable" -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
            case "invalid_return_to", "invalid_client_type" -> HttpServletResponse.SC_BAD_REQUEST;
            default -> HttpServletResponse.SC_BAD_REQUEST;
        };
    }

    private record StartRequest(String returnTo, String client, boolean silent) {
    }
}
