package com.neko.music.handlers;

import com.neko.music.service.AdminAuthService;
import com.neko.music.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端登出接口：必须真的去吊销令牌（而不是只回一句成功），并且令牌缺失 / 已失效时保持幂等。
 *
 * <p>用动态代理桩替代 Servlet 容器、用桩服务替代 Main 单例，不依赖 Redis。</p>
 */
class LogoutHandlerTest {

    private static final class StubUserAuthService extends UserAuthService {
        final List<String> revoked = new ArrayList<>();
        boolean revokeResult = true;

        StubUserAuthService() {
            super(null, null);
        }

        @Override
        public boolean revokeToken(String token) {
            revoked.add(token);
            return revokeResult;
        }
    }

    private static final class StubAdminAuthService extends AdminAuthService {
        final List<String> revoked = new ArrayList<>();

        StubAdminAuthService() {
            super(null, null);
        }

        @Override
        public boolean logout(String token) {
            revoked.add(token);
            return true;
        }
    }

    private record Result(int status, String body, Map<String, String> headers) {
    }

    @Test
    @DisplayName("用户登出会吊销 Bearer 令牌并声明不缓存")
    void userLogoutRevokesToken() throws Exception {
        StubUserAuthService auth = new StubUserAuthService();
        Result result = post(new UserLogoutHandler(), "userAuthService", auth, "Bearer  tok-123  ");

        assertEquals(200, result.status());
        assertEquals(List.of("tok-123"), auth.revoked);
        assertEquals("private, no-store", result.headers().get("cache-control"));
        assertTrue(result.body().contains("已退出登录"), result.body());
    }

    @Test
    @DisplayName("用户登出幂等：令牌已失效同样返回成功，缺少令牌才 401")
    void userLogoutIsIdempotent() throws Exception {
        StubUserAuthService auth = new StubUserAuthService();
        auth.revokeResult = false; // 令牌已被其它路径吊销 / 已过期
        assertEquals(200, post(new UserLogoutHandler(), "userAuthService", auth, "Bearer gone").status());
        assertEquals(List.of("gone"), auth.revoked);

        StubUserAuthService noToken = new StubUserAuthService();
        assertEquals(401, post(new UserLogoutHandler(), "userAuthService", noToken, null).status());
        assertTrue(noToken.revoked.isEmpty(), "没有令牌时不应触碰服务端会话");
    }

    @Test
    @DisplayName("管理端登出同样吊销 admin_token")
    void adminLogoutRevokesToken() throws Exception {
        StubAdminAuthService auth = new StubAdminAuthService();
        Result result = post(new AdminLogoutHandler(), "adminAuthService", auth, "Bearer admin-tok");

        assertEquals(200, result.status());
        assertEquals(List.of("admin-tok"), auth.revoked);
        assertEquals("private, no-store", result.headers().get("cache-control"));
    }

    private Result post(Object handler, String field, Object stub, String authHeader) throws Exception {
        var declared = handler.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(handler, stub);

        int[] status = {200};
        Map<String, String> headers = new HashMap<>();
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> status[0] = (int) args[0];
                        case "setHeader" -> headers.put(((String) args[0]).toLowerCase(), (String) args[1]);
                        case "getWriter" -> {
                            return writer;
                        }
                        default -> {
                        }
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });

        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "POST";
                    case "getHeader" -> authHeader;
                    case "getRequestURI" -> "/api/user/logout";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });

        if (handler instanceof UserLogoutHandler userLogout) {
            userLogout.doPost(request, response);
        } else {
            ((AdminLogoutHandler) handler).doPost(request, response);
        }
        writer.flush();
        return new Result(status[0], body.toString(), headers);
    }
}
