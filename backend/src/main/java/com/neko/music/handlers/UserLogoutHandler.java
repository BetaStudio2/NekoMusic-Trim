package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.NekoAuthAccountService;
import com.neko.music.service.RedisTokenStore;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 用户端登出：在服务端吊销当前会话令牌。
 *
 * <p>只清浏览器 localStorage 不构成登出——令牌仍在 Redis 里存活到 TTL 到期，任何被复制走的
 * 令牌会继续可用。本接口幂等：令牌已失效 / 已被其它路径吊销时同样返回成功，便于客户端无条件
 * 「先登出再清本地」。</p>
 */
public class UserLogoutHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserLogoutHandler.class);

    private UserAuthService userAuthService;

    @Override
    public void init() throws ServletException {
        userAuthService = Main.getUserAuthService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 会话类响应一律不缓存，避免中间层缓存登录态相关结果
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        UserAuthService service = userAuthService != null ? userAuthService : Main.getUserAuthService();
        String token = RedisTokenStore.normalizeBearerToken(request.getHeader("Authorization"));
        if (service == null || token.isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }

        try {
            service.revokeToken(token);
        } catch (Exception e) {
            logger.error("用户登出时吊销令牌失败", e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "auth.logout.failed");
            return;
        }

        try {
            String ssoSession = cookie(
                    request, NekoAuthSsoCookieLoginHandler.SSO_SESSION_COOKIE);
            NekoAuthAccountService accountService = Main.getNekoAuthAccountService();
            if (!ssoSession.isEmpty() && accountService != null) {
                for (String cookieHeader : accountService.logoutSsoSession(ssoSession)) {
                    response.addHeader("Set-Cookie", cookieHeader);
                }
            }
        } catch (Exception e) {
            logger.warn("撤销 NekoAuth 共享会话失败，将按本地退出继续: {}", e.getMessage());
        }

        sendSuccessResponse(response, "auth.logout.ok");
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
