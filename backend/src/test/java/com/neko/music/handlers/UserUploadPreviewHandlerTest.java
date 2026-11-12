package com.neko.music.handlers;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审核试听接口的响应契约：声明支持 Range，并按单段 {@code 206} 返回内容。
 *
 * <p>该接口的媒体文件会被浏览器 / 客户端与 CDN 按 {@code Range} 分片取，源站忽略 {@code Range}
 * 直接回整包时，CDN 的分片回源拼不出完整文件，大文件会被截断（客户端表现为
 * {@code ERR_HTTP2_PROTOCOL_ERROR}）。同时预览文件按磁盘文件缓存六个月（ETag / Last-Modified /
 * 条件请求）；因为预览的是待审核文件、接口又要求管理员 token，只允许浏览器私有缓存，避免被
 * CDN 缓存成免鉴权的公共地址。用动态代理桩替代 Servlet 容器。</p>
 */
class UserUploadPreviewHandlerTest {

    @Test
    @DisplayName("无 Range：整包 200，声明 Accept-Ranges 与 Content-Length")
    void fullResponseAdvertisesRanges(@TempDir Path dir) throws Exception {
        byte[] payload = payload(4096);
        Path file = dir.resolve("music_1.flac");

        Recorder recorder = send(dir, "music_1.flac", payload, null);

        assertEquals(HttpServletResponse.SC_OK, recorder.status);
        assertEquals("bytes", recorder.headers.get("accept-ranges"));
        assertNull(recorder.headers.get("content-range"));
        assertEquals("audio/flac", recorder.contentType);
        assertEquals("inline; filename=\"music_1.flac\"", recorder.headers.get("content-disposition"));
        assertEquals(payload.length, recorder.contentLength);
        assertEquals("private, max-age=15552000, must-revalidate", recorder.headers.get("cache-control"),
                "预览文件缓存六个月，但只允许浏览器私有缓存，避免待审核文件被 CDN 免鉴权分发");
        assertNotNull(recorder.headers.get("etag"));
        assertNotNull(recorder.headers.get("last-modified"));
        assertArrayEquals(payload, recorder.body);
    }

    @Test
    @DisplayName("条件请求命中 ETag：回 304 且不带响应体")
    void conditionalRequestReturnsNotModified(@TempDir Path dir) throws Exception {
        byte[] payload = payload(4096);
        Path file = write(dir, "music_1.flac", payload);
        String etag = com.neko.music.util.HttpResourceCache.strongEtagForFile(file);

        Recorder recorder = sendFile(file, payload, null, etag);

        assertEquals(HttpServletResponse.SC_NOT_MODIFIED, recorder.status);
        assertEquals(etag, recorder.headers.get("etag"));
        assertEquals("private, max-age=15552000, must-revalidate", recorder.headers.get("cache-control"));
        assertEquals(0, recorder.body.length, "304 不应携带响应体");
    }

    @Test
    @DisplayName("带 Range：单段 206，Content-Range 与内容长度都按区间给出")
    void rangeRequestReturnsPartialContent(@TempDir Path dir) throws Exception {
        byte[] payload = payload(4096);

        Recorder recorder = send(dir, "music_1.flac", payload, "bytes=10-19");

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, recorder.status);
        assertEquals("bytes 10-19/4096", recorder.headers.get("content-range"));
        assertEquals(10, recorder.contentLength);
        assertEquals("bytes", recorder.headers.get("accept-ranges"));
        assertEquals("private, max-age=15552000, must-revalidate", recorder.headers.get("cache-control"));
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 10, 20), recorder.body);
    }

    @Test
    @DisplayName("开区间与后缀 Range 都按区间取，越界回 416")
    void openEndedSuffixAndUnsatisfiableRanges(@TempDir Path dir) throws Exception {
        byte[] payload = payload(1000);

        Recorder tail = send(dir, "a.flac", payload, "bytes=990-");
        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, tail.status);
        assertEquals("bytes 990-999/1000", tail.headers.get("content-range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 990, 1000), tail.body);

        Recorder suffix = send(dir, "a.flac", payload, "bytes=-5");
        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, suffix.status);
        assertEquals("bytes 995-999/1000", suffix.headers.get("content-range"));
        assertArrayEquals(java.util.Arrays.copyOfRange(payload, 995, 1000), suffix.body);

        Recorder beyond = send(dir, "a.flac", payload, "bytes=5000-6000");
        assertEquals(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, beyond.status);
        assertEquals("bytes */1000", beyond.headers.get("content-range"));

        // 语法非法 / 多段：按整包处理，不给客户端 500
        assertEquals(1000, send(dir, "a.flac", payload, "bytes=abc-def").contentLength);
        assertEquals(HttpServletResponse.SC_OK, send(dir, "a.flac", payload, "items=0-10").status);
    }

    @Test
    @DisplayName("预览文件按扩展名给出 Content-Type")
    void contentTypeByExtension() {
        assertEquals("audio/mpeg", UserUploadPreviewHandler.getContentType("a.mp3"));
        assertEquals("audio/flac", UserUploadPreviewHandler.getContentType("a.FLAC"));
        assertEquals("audio/wav", UserUploadPreviewHandler.getContentType("a.wav"));
        assertEquals("image/jpeg", UserUploadPreviewHandler.getContentType("cover.jpg"));
        assertEquals("application/octet-stream", UserUploadPreviewHandler.getContentType("song.lrc"),
                "歌词预览必须使用不可压缩 MIME，避免 CDN gzip 后保留未压缩 Content-Length");
        assertEquals("application/octet-stream", UserUploadPreviewHandler.getContentType("song.txt"));
        assertEquals("application/octet-stream", UserUploadPreviewHandler.getContentType("noext"));
    }

    private static byte[] payload(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 251);
        }
        return payload;
    }

    /** 写一个文件、以给定 Range 调 {@link UserUploadPreviewHandler#sendFile}，回收响应侧结果。 */
    private static Recorder send(Path dir, String name, byte[] payload, String range) throws Exception {
        return sendFile(write(dir, name, payload), payload, range, null);
    }

    private static Path write(Path dir, String name, byte[] payload) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, payload);
        return file;
    }

    private static Recorder sendFile(Path file, byte[] payload, String range, String ifNoneMatch) throws Exception {

        Recorder recorder = new Recorder();
        HttpServletResponse response = proxy(HttpServletResponse.class, (p, m, a) -> switch (m.getName()) {
            case "setStatus" -> {
                recorder.status = (int) a[0];
                yield null;
            }
            case "setHeader" -> {
                recorder.headers.put(((String) a[0]).toLowerCase(), (String) a[1]);
                yield null;
            }
            case "setContentType" -> {
                recorder.contentType = (String) a[0];
                yield null;
            }
            case "setContentLengthLong" -> {
                recorder.contentLength = (long) a[0];
                yield null;
            }
            case "setDateHeader" -> {
                recorder.headers.put(((String) a[0]).toLowerCase(), String.valueOf((long) a[1]));
                yield null;
            }
            case "getOutputStream" -> recorder;
            default -> defaultValue(m);
        });
        HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getHeader" -> switch (((String) a[0]).toLowerCase()) {
                case "range" -> range;
                case "if-none-match" -> ifNoneMatch;
                default -> null;
            };
            default -> defaultValue(m);
        });

        UserUploadPreviewHandler.sendFile(file, request, response);
        recorder.finish();
        return recorder;
    }

    /** 既是响应桩，也是输出流：记录状态码 / 响应头，并捕获写出的字节。 */
    private static final class Recorder extends ServletOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final Map<String, String> headers = new HashMap<>();
        int status = 200;
        String contentType;
        long contentLength = -1;
        byte[] body = new byte[0];

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
        }

        void finish() {
            body = buffer.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** 代理的默认返回值：基本类型给零值，其余给 null。 */
    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
