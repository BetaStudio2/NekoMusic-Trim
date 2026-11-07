package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.model.User;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthAccountService;
import com.neko.music.service.SharedWebSessionService;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.VipUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通过父域共享会话直接恢复 Web 登录态，不使用 OAuth 授权码。 */
public final class NekoAuthSharedSessionLoginHandler extends ApiServlet {

    private static final String SSO_COOKIE = "nekoauth_sso_session";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.LOGIN)) {
            return;
        }
        String ssoSession = cookie(request, SSO_COOKIE);
        if (ssoSession.isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "未找到共享登录会话");
            return;
        }
        NekoAuthAccountService accountService = Main.getNekoAuthAccountService();
        UserAuthService userAuthService = Main.getUserAuthService();
        SharedWebSessionService sessionService = Main.getSharedWebSessionService();
        if (accountService == null || userAuthService == null || sessionService == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "登录服务暂时不可用");
            return;
        }
        try {
            NekoAuthAccountService.SharedAccount account =
                    accountService.resolveSharedSession(ssoSession);
            User user = userAuthService.findOrLinkNekoAuthUser(
                    account.id(), account.email(), account.nickname());
            String token = sessionService.create(ssoSession);

            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("id", user.id());
            userData.put("nickname", user.nickname());
            userData.put("email", user.email());
            userData.put("createdAt", user.createdAt());
            userData.put("isVip", VipUtil.isVipActiveNow(user.vipExpiresAt()));
            userData.put("vipExpiresAt", user.vipExpiresAt() != null
                    ? user.vipExpiresAt().toInstant().toString() : null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("returnTo", "/");
            data.put("user", userData);
            sendResponse(response, true, "登录成功", data);
        } catch (NekoAuthAccountService.NekoAuthServiceException exception) {
            sendErrorResponse(response, exception.status() == 401
                    ? HttpServletResponse.SC_UNAUTHORIZED
                    : HttpServletResponse.SC_BAD_GATEWAY, exception.getMessage());
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
