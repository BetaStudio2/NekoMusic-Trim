package com.neko.music.filter;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.handlers.VideoRenderHandler;
import com.neko.music.service.UserAuthService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Jetty 服务器上的端到端行为：未捕获异常的 5xx 错误页必须脱敏，数值入参类型不符必须 400。
 *
 * <p>回归背景：站点用 Jetty 默认 {@code ErrorHandler}，一个未捕获的 {@code NumberFormatException}
 * 会把 {@code java.lang.*} 类名、异常消息（含攻击者原始输入）与请求 URL 一起回显（CWE-209）。这里在
 * 真实容器里跑一遍，锁定「容器级错误页只给泛化文案」与「类型校验先于业务处理」两个不变量。</p>
 */
class SanitizedErrorHandlerIntegrationTest {

    private static Object savedConfigManager;
    private static Object savedUserAuthService;
    private static Server server;
    private static String base;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void startServer() throws Exception {
        savedConfigManager = readStatic("configManager");
        savedUserAuthService = readStatic("userAuthService");
        writeStatic("configManager", new ConfigManager()); // 默认 videoRenderEnabled = true
        writeStatic("userAuthService", new StubUserAuthService());

        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(new ServletHolder("video", new VideoRenderHandler()), "/api/video/render/*");
        context.addServlet(new ServletHolder("boom", new ThrowingServlet()), "/boom");

        server = new Server(0);
        server.setErrorHandler(new SanitizedErrorHandler());
        server.setHandler(context);
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
        writeStatic("configManager", savedConfigManager);
        writeStatic("userAuthService", savedUserAuthService);
    }

    @Test
    @DisplayName("未捕获异常返回脱敏 500，不含类名/原始输入/请求 URL")
    void uncaughtExceptionYieldsSanitizedJsonError() throws Exception {
        HttpResponse<String> r = get("/boom", "application/json");

        assertEquals(500, r.statusCode());
        assertEquals("{\"success\":false,\"message\":\"服务器内部错误\"}\n", r.body());
        assertNoInternals(r.body());
    }

    @Test
    @DisplayName("纯文本 / HTML 协商下的错误页同样脱敏")
    void uncaughtExceptionIsSanitizedForOtherContentTypes() throws Exception {
        assertNoInternals(get("/boom", "text/plain").body());
        assertNoInternals(get("/boom", "text/html").body());
    }

    @Test
    @DisplayName("musicId 为字符串时返回 400 而不是 500")
    void stringMusicIdYields400() throws Exception {
        HttpResponse<String> r = postCreate("{\"musicId\":\"abc\",\"watermarked\":true}", true);

        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("musicId 必须为数字"), r.body());
        assertNoInternals(r.body());
    }

    @Test
    @DisplayName("startSec 为字符串时返回 400；未登录仍为 401")
    void stringStartSecYields400AndAnonymousYields401() throws Exception {
        HttpResponse<String> bad = postCreate("{\"musicId\":40356,\"startSec\":\"nan\"}", true);
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("startSec 必须为数字"), bad.body());

        HttpResponse<String> anon = postCreate("{\"musicId\":\"abc\"}", false);
        assertEquals(401, anon.statusCode());
    }

    private static void assertNoInternals(String body) {
        assertFalse(body.contains("java."), "错误页泄露了异常类名：" + body);
        assertFalse(body.contains("Exception"), "错误页泄露了异常类型：" + body);
        assertFalse(body.contains("1 or 1=1") || body.contains("abc"), "错误页回显了原始输入：" + body);
        assertFalse(body.contains("\"url\""), "错误页泄露了请求 URL：" + body);
    }

    private static HttpResponse<String> get(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> postCreate(String body, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/video/render/create"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authenticated) {
            builder.header("Authorization", "Bearer ok");
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 抛出与线上完全同型的未捕获异常，用来逼近真实的 500 场景。 */
    private static final class ThrowingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            throw new NumberFormatException("For input string: \"1 or 1=1\"");
        }
    }

    private static final class StubUserAuthService extends UserAuthService {
        StubUserAuthService() {
            super(null, null);
        }

        @Override
        public Optional<Integer> validateToken(String token) {
            return "Bearer ok".equals(token) ? Optional.of(3482) : Optional.empty();
        }
    }

    private static Object readStatic(String name) throws Exception {
        Field f = Main.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void writeStatic(String name, Object value) throws Exception {
        Field f = Main.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
