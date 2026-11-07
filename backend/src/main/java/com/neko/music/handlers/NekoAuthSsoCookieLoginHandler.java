package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthOAuthService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.VipUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通过父域共享 SSO Cookie 直接恢复当前子域登录，不发生 OIDC 跳转。 */
public final class NekoAuthSsoCookieLoginHandler extends ApiServlet {

    static final String SSO_SESSION_COOKIE = "nekoauth_sso_session";

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
        String ssoSession = cookie(request, SSO_SESSION_COOKIE);
        if (ssoSession.isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "未找到共享登录会话");
            return;
        }
        try {
            NekoAuthOAuthService.ClaimResult claim = oauthService.loginWithSsoSession(ssoSession);
            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", claim.user().id());
            userData.put("nickname", claim.user().nickname());
            userData.put("email", claim.user().email());
            userData.put("createdAt", claim.user().createdAt());
            userData.put("isVip", VipUtil.isVipActiveNow(claim.user().vipExpiresAt()));
            userData.put("vipExpiresAt", claim.user().vipExpiresAt() != null
                    ? claim.user().vipExpiresAt().toInstant().toString() : null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", claim.accessToken());
            data.put("returnTo", claim.returnTo());
            data.put("user", userData);
            sendResponse(response, true, "登录成功", data);
        } catch (NekoAuthOAuthService.OAuthLoginException exception) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, exception.getMessage());
        } catch (RuntimeException exception) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_GATEWAY, "登录服务暂时不可用");
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue() == null ? "" : cookie.getValue();
            }
        }
        return "";
    }
}
