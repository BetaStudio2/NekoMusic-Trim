package com.neko.music.handlers;

import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.service.NekoAuthOAuthService;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 浏览器跳转端点：{@code /sso/nekocore/start}、{@code /sso/nekocore/callback}
 * 与 {@code /sso/nekocore/forgot-password}。
 *
 * <p>这是 OAuth 浏览器导航，不是 JSON API；回调使用一次性 state 做请求关联与 CSRF 校验。
 * 保证。回调只跳转到前端完成页并携带一次性 handoff，不把 Token 放进 URL。</p>
 *
 * <p>规范入口全部位于 {@code /sso/nekocore/*}；旧 {@code /oauth/nekocore/*} 路径仅作为兼容别名保留。</p>
 */
public final class NekoAuthOAuthHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(NekoAuthOAuthHandler.class);
    private NekoAuthOAuthService oauthService;

    @Override
    public void init() {
        oauthService = Main.getNekoAuthOAuthService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");

        String path = request.getServletPath();
        if (path.endsWith("/start")) {
            handleStart(request, response);
        } else if (path.endsWith("/callback")) {
            handleCallback(request, response);
        } else if (path.endsWith("/forgot-password")) {
            handlePasswordReset(response);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private void handleStart(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (oauthService == null) {
            sendError(response, "oauth_unavailable", "NekoAuth 登录暂时不可用");
            return;
        }
        try {
            response.sendRedirect(oauthService.authorizationUrl(request.getParameter("return_to")));
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            logger.warn("NekoAuth 登录发起失败: code={} message={}", exception.code(), exception.getMessage());
            sendError(response, exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            logger.warn("NekoAuth 登录发起失败: 配置中的地址格式不正确");
            sendError(response, "oauth_not_configured", "NekoAuth 登录配置不正确");
        }
    }

    private void handleCallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String oauthError = request.getParameter("error");
        if (oauthError != null && !oauthError.isBlank()) {
            String code = "access_denied".equals(oauthError) ? "access_denied" : "authorization_failed";
            logger.info("NekoAuth 授权未完成: error={}", oauthError);
            sendError(response, code, "NekoAuth 授权未完成");
            return;
        }
        if (oauthService == null) {
            sendError(response, "oauth_unavailable", "NekoAuth 登录暂时不可用");
            return;
        }
        try {
            NekoAuthOAuthService.LoginResult result = oauthService.complete(
                    request.getParameter("code"), request.getParameter("state"),
                    request.getParameter("iss"));
            String handoff = URLEncoder.encode(result.handoff(), StandardCharsets.UTF_8);
            String location;
            if ("android".equals(result.client()) || "pc".equals(result.client())) {
                location = "/sso/native-complete?client="
                        + URLEncoder.encode(result.client(), StandardCharsets.UTF_8)
                        + "&handoff=" + handoff;
            } else {
                location = "/sso/complete?handoff=" + handoff;
            }
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", location);
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            logger.warn("NekoAuth 登录回调失败: code={} message={}", exception.code(), exception.getMessage());
            sendError(response, exception.code(), exception.getMessage());
        }
    }

    private void handlePasswordReset(HttpServletResponse response) throws IOException {
        if (oauthService == null) {
            sendError(response, "oauth_unavailable", "NekoAuth 账号服务暂时不可用");
            return;
        }
        try {
            response.sendRedirect(oauthService.passwordResetUrl());
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            logger.warn("NekoAuth 找回密码入口跳转失败: code={}", exception.code());
            sendError(response, exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            logger.warn("NekoAuth 找回密码入口跳转失败: 配置中的地址格式不正确");
            sendError(response, "oauth_not_configured", "NekoAuth 登录配置不正确");
        }
    }

    private void sendError(HttpServletResponse response, String code, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("success", false);
        body.addProperty("error", code);
        body.addProperty("message", message == null || message.isBlank() ? "NekoAuth 登录失败" : message);
        writeJson(response, statusFor(code), GSON.toJson(body));
    }

    private static int statusFor(String code) {
        return switch (code) {
            case "oauth_disabled", "oauth_not_configured", "oauth_unavailable" -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
            case "access_denied", "account_not_linked", "email_mismatch" -> HttpServletResponse.SC_FORBIDDEN;
            case "token_exchange_failed", "userinfo_failed", "id_token_invalid" ->
                    HttpServletResponse.SC_BAD_GATEWAY;
            case "token_issue_failed" -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            default -> HttpServletResponse.SC_BAD_REQUEST;
        };
    }
}
