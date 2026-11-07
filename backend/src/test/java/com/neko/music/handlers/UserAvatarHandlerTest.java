package com.neko.music.handlers;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAvatarHandlerTest {

    @Test
    void defaultAvatarIsFetchedFromAuthAndProxied() throws Exception {
        byte[] body = new byte[]{1, 2, 3, 4};
        Recorder result = run("/default", id -> null,
                (uri, etag) -> response(200, Map.of(
                        "Content-Type", List.of("image/x-icon"),
                        "ETag", List.of("\"default\"")), body));

        assertEquals(200, result.status);
        assertEquals("https://auth.nekocore.cn/avatars/default", result.upstreamUri);
        assertEquals("image/x-icon", result.headers.get("Content-Type"));
        assertEquals("\"default\"", result.headers.get("ETag"));
        assertArrayEquals(body, result.body.toByteArray());
    }

    @Test
    void linkedUserAvatarUsesAuthUserId() throws Exception {
        Recorder result = run("/42", id -> id == 42 ? 9001L : null,
                (uri, etag) -> response(200, Map.of(
                        "Content-Type", List.of("image/png")), new byte[]{9}));

        assertEquals(200, result.status);
        assertEquals("https://auth.nekocore.cn/avatars/9001", result.upstreamUri);
    }

    @Test
    void notModifiedIsPassedThroughWithoutBody() throws Exception {
        Recorder result = run("/42", id -> 9001L,
                (uri, etag) -> response(304, Map.of(
                        "ETag", List.of("\"avatar-1\"")), new byte[0]));

        assertEquals(304, result.status);
        assertEquals("\"avatar-1\"", result.headers.get("ETag"));
        assertEquals(0, result.body.size());
    }

    @Test
    void authFailureBecomesBadGateway() throws Exception {
        Recorder result = run("/42", id -> 9001L,
                (uri, etag) -> response(500, Map.of(), new byte[0]));

        assertEquals(502, result.status);
    }

    private static Recorder run(String pathInfo, Function<Integer, Long> resolver,
                                UserAvatarHandler.AvatarFetcher fetcher) throws Exception {
        Recorder recorder = new Recorder();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                UserAvatarHandlerTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOutputStream" -> {
                            return outputStream(recorder);
                        }
                        case "setHeader" -> {
                            recorder.headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
                            return null;
                        }
                        case "setContentType" -> {
                            recorder.headers.put("Content-Type", String.valueOf(args[0]));
                            return null;
                        }
                        case "setContentLength" -> {
                            recorder.headers.put("Content-Length", String.valueOf(args[0]));
                            return null;
                        }
                        case "setStatus" -> {
                            recorder.status = (int) args[0];
                            return null;
                        }
                        case "sendError" -> {
                            recorder.status = (int) args[0];
                            return null;
                        }
                        default -> {
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                UserAvatarHandlerTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getProtocol" -> "HTTP/1.1";
                    case "getPathInfo" -> pathInfo;
                    case "getHeader" -> "If-None-Match".equalsIgnoreCase(String.valueOf(args[0]))
                            ? "\"avatar-1\"" : null;
                    default -> defaultValue(method.getReturnType());
                });
        new UserAvatarHandler(resolver, "https://auth.nekocore.cn",
                (uri, etag) -> {
                    recorder.upstreamUri = uri.toString();
                    return fetcher.fetch(uri, etag);
                }).service(request, response);
        return recorder;
    }

    private static HttpResponse<byte[]> response(int status, Map<String, List<String>> headers,
                                                  byte[] body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            @Override public byte[] body() { return body; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public java.net.URI uri() { return java.net.URI.create("https://auth.nekocore.cn"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }

    private static ServletOutputStream outputStream(Recorder recorder) {
        return new ServletOutputStream() {
            @Override public void write(int b) { recorder.body.write(b); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == char.class) return (char) 0;
        return 0;
    }

    private static final class Recorder {
        final Map<String, String> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int status = 200;
        String upstreamUri;
    }
}
