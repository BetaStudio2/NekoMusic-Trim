package com.neko.music.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用安全头过滤器的判定测试：所有响应（含被拒的 TRACE）都要带 CSP 与其余加固头，
 * 且策略内容被锁定——既不许悄悄放宽关键项，也不许收掉前端依赖的放行（PixiJS 的
 * {@code new Function}、内联样式、第三方封面直链）。用动态代理桩替代 Servlet 容器。
 */
class SecurityHeadersFilterTest {

    private record Outcome(int status, boolean chained, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    @Test
    @DisplayName("普通响应：CSP 与其余安全头齐全，且继续放行")
    void normalResponseCarriesHardeningHeaders() throws Exception {
        Outcome outcome = run("GET");

        assertTrue(outcome.chained(), "非 TRACE/TRACK 必须放行到后续过滤器");
        assertEquals(SecurityHeadersFilter.CONTENT_SECURITY_POLICY, outcome.header("content-security-policy"));
        assertEquals("nosniff", outcome.header("x-content-type-options"));
        assertEquals("strict-origin-when-cross-origin", outcome.header("referrer-policy"));
        assertEquals("SAMEORIGIN", outcome.header("x-frame-options"));
        assertEquals("camera=(), microphone=(), geolocation=()", outcome.header("permissions-policy"));
        assertEquals("NekoMusic", outcome.header("server"));
    }

    @Test
    @DisplayName("策略被锁定：关键项收紧、前端依赖项缺失都在这里被拦下")
    void contentSecurityPolicyIsPinned() {
        assertEquals("default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data: blob: https: http:; media-src 'self' blob:; "
                        + "font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; "
                        + "child-src 'self'; frame-src 'self'; manifest-src 'self'; object-src 'none'; "
                        + "base-uri 'self'; form-action 'self'; frame-ancestors 'self'",
                SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("TRACE：405 + Allow + no-store，且仍然带 CSP")
    void traceIsRejectedWithHardeningHeaders() throws Exception {
        Outcome outcome = run("TRACE");

        assertFalse(outcome.chained(), "TRACE 不应继续走到业务处理");
        assertEquals(HttpServletResponse.SC_METHOD_NOT_ALLOWED, outcome.status());
        assertEquals("GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS", outcome.header("allow"));
        assertEquals("private, no-store", outcome.header("cache-control"));
        assertEquals(SecurityHeadersFilter.CONTENT_SECURITY_POLICY, outcome.header("content-security-policy"));
    }

    private Outcome run(String method) throws Exception {
        Map<String, String> responseHeaders = new HashMap<>();
        int[] status = {HttpServletResponse.SC_OK};
        HttpServletResponse response = proxy(HttpServletResponse.class, (p, m, a) -> {
            switch (m.getName()) {
                case "setStatus" -> status[0] = (int) a[0];
                case "setHeader" -> responseHeaders.put(((String) a[0]).toLowerCase(), (String) a[1]);
                default -> {
                }
            }
            return defaultValue(m);
        });

        HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getMethod" -> method;
            default -> defaultValue(m);
        });

        boolean[] chained = {false};
        FilterChain chain = proxy(FilterChain.class, (p, m, a) -> {
            if ("doFilter".equals(m.getName())) {
                chained[0] = true;
            }
            return defaultValue(m);
        });

        new SecurityHeadersFilter().doFilter(request, response, chain);
        return new Outcome(status[0], chained[0], responseHeaders);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }
}
