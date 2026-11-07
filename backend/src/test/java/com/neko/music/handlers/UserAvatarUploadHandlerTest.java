package com.neko.music.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAvatarUploadHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void successfulUploadForwardsFileToNekoAuthAndCleansTemporaryFile() throws Exception {
        byte[] image = pngBytes();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        UserAvatarUploadHandler handler = new UserAvatarUploadHandler(
                request -> 7,
                (userId, file) -> uploaded.set(Files.readAllBytes(file)),
                tempDir);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(part(image)), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"success\":true"));
        assertTrue(capture.body().contains("\"avatarUrl\":\"/api/user/avatar/7\""));
        assertArrayEquals(image, uploaded.get());
        assertEquals(0, fileCount());
    }

    @Test
    void authFailureReturns500AndCleansTemporaryFile() throws Exception {
        UserAvatarUploadHandler handler = new UserAvatarUploadHandler(
                request -> 7,
                (userId, file) -> {
                    throw new IllegalStateException("auth unavailable");
                },
                tempDir);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(part(pngBytes())), responseProxy(capture));

        assertEquals(500, capture.status);
        assertEquals(0, fileCount());
    }

    @Test
    void missingLoginDoesNotWriteFiles() throws Exception {
        UserAvatarUploadHandler handler = new UserAvatarUploadHandler(
                request -> null,
                (userId, file) -> {
                },
                tempDir);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(part(pngBytes())), responseProxy(capture));

        assertEquals(401, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertEquals(0, fileCount());
    }

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    private static HttpServletRequest request(Part avatarPart) {
        return proxy(HttpServletRequest.class, (ignored, method, args) -> switch (method.getName()) {
            case "getPart" -> "avatar".equals(args[0]) ? avatarPart : null;
            case "setCharacterEncoding" -> null;
            case "getHeader" -> null;
            case "getParameter" -> null;
            default -> defaultValue(method);
        });
    }

    private static Part part(byte[] bytes) {
        return proxy(Part.class, (ignored, method, args) -> switch (method.getName()) {
            case "getSize" -> (long) bytes.length;
            case "getInputStream" -> new ByteArrayInputStream(bytes);
            case "getSubmittedFileName" -> "avatar.png";
            case "getContentType" -> "image/png";
            default -> defaultValue(method);
        });
    }

    private static byte[] pngBytes() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static final class CapturingResponse {
        private final StringWriter output = new StringWriter();
        private final PrintWriter writer = new PrintWriter(output);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int status = 200;

        private String body() {
            writer.flush();
            return output.toString();
        }
    }

    private static HttpServletResponse responseProxy(CapturingResponse capture) {
        return proxy(HttpServletResponse.class, (ignored, method, args) -> switch (method.getName()) {
            case "getWriter" -> capture.writer;
            case "setStatus" -> {
                capture.status = (Integer) args[0];
                yield null;
            }
            case "setHeader" -> {
                capture.headers.put((String) args[0], (String) args[1]);
                yield null;
            }
            case "setContentType" -> null;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }
}
