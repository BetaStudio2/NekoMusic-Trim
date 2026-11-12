package com.neko.music.handlers;

import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCoverHandlerTest {

    private record Result(int status, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void dynamicDefaultCoverRequiresRevalidationInsteadOfImmutableCaching() throws Exception {
        Result result = run(null);

        assertEquals(200, result.status());
        assertEquals(HttpResourceCache.CACHE_CONTROL_COVER, result.header("cache-control"));
        assertEquals(HttpResourceCache.DEFAULT_ICON_ETAG, result.header("etag"));
        assertTrue(result.body().length > 0);
    }

    @Test
    void defaultCoverConditionalRequestUsesRevalidatingCachePolicy() throws Exception {
        String etag = run(null).header("etag");
        Result result = run(etag);

        assertEquals(304, result.status());
        assertEquals(HttpResourceCache.CACHE_CONTROL_COVER, result.header("cache-control"));
        assertEquals(0, result.body().length);
    }

    private Result run(String ifNoneMatch) throws Exception {
        int[] status = {200};
        Map<String, String> headers = new HashMap<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ServletOutputStream output = new ServletOutputStream() {
            @Override
            public void write(int value) {
                body.write(value);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }
        };

        HttpServletResponse response = proxy(HttpServletResponse.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getOutputStream" -> {
                    return output;
                }
                case "setStatus" -> {
                    status[0] = (int) args[0];
                    return null;
                }
                case "setHeader" -> {
                    headers.put(((String) args[0]).toLowerCase(Locale.ROOT), (String) args[1]);
                    return null;
                }
                case "setContentType" -> {
                    headers.put("content-type", (String) args[0]);
                    return null;
                }
                case "setContentLength", "setContentLengthLong" -> {
                    headers.put("content-length", Long.toString(((Number) args[0]).longValue()));
                    return null;
                }
                default -> {
                }
            }
            return defaultValue(method);
        });

        HttpServletRequest request = proxy(HttpServletRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPathInfo" -> null;
            case "getHeader" -> "If-None-Match".equalsIgnoreCase((String) args[0]) ? ifNoneMatch : null;
            default -> defaultValue(method);
        });

        new MusicCoverHandler().doGet(request, response);
        return new Result(status[0], headers, body.toByteArray());
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
