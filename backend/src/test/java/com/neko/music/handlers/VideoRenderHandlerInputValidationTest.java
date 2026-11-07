package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 创建视频渲染任务时的数值入参必须强校验。
 *
 * <p>回归背景：{@code musicId}/{@code startSec} 只做 {@code isJsonPrimitive()} 判断就直接
 * {@code getAsInt()}/{@code getAsDouble()}，传字符串（如 {@code "abc"}、{@code "1 or 1=1"}）会抛出
 * 未捕获的 {@code NumberFormatException}，冒到容器层后连异常类名与用户原始输入一起被回显（CWE-209）。
 * 现在类型不符一律 400，且响应体不得出现任何内部实现细节。</p>
 */
class VideoRenderHandlerInputValidationTest {

    private static final int USER_ID = 3482;

    private Object savedConfigManager;
    private Object savedUserAuthService;

    /** 只认令牌为「已登录」，其余逻辑一律不碰。 */
    private static final class StubUserAuthService extends UserAuthService {
        StubUserAuthService() {
            super(null, null);
        }

        @Override
        public Optional<Integer> validateToken(String token) {
            return "Bearer ok".equals(token) ? Optional.of(USER_ID) : Optional.empty();
        }
    }

    @BeforeEach
    void injectMainSingletons() throws Exception {
        savedConfigManager = readStatic("configManager");
        savedUserAuthService = readStatic("userAuthService");
        writeStatic("configManager", new ConfigManager()); // 默认 videoRenderEnabled = true
        writeStatic("userAuthService", new StubUserAuthService());
    }

    @AfterEach
    void restoreMainSingletons() throws Exception {
        writeStatic("configManager", savedConfigManager);
        writeStatic("userAuthService", savedUserAuthService);
    }

    @Test
    @DisplayName("musicId 为字符串时返回 400 且不泄露异常细节")
    void stringMusicIdIsRejectedWithoutLeakingInternals() throws Exception {
        Response r = create("{\"musicId\":\"abc\",\"watermarked\":true}");
        assertEquals(400, r.status());
        assertTrue_contains(r.body(), "musicId 必须为数字");
        assertNoInternals(r.body());
    }

    @Test
    @DisplayName("musicId 注入串/布尔/数组均返回 400，不回显原始输入")
    void nonNumericMusicIdVariantsAreRejected() throws Exception {
        for (String body : new String[]{
                "{\"musicId\":\"1 or 1=1\"}",
                "{\"musicId\":\"1;id\"}",
                "{\"musicId\":true}",
                "{\"musicId\":[1,2]}"}) {
            Response r = create(body);
            assertEquals(400, r.status(), "请求体 " + body + " 应返回 400，实际：" + r.body());
            assertNoInternals(r.body());
            assertFalse(r.body().contains("1 or 1=1") || r.body().contains("1;id"),
                    "错误响应回显了攻击者原始输入：" + r.body());
        }
    }

    @Test
    @DisplayName("缺少 musicId 返回 400")
    void missingMusicIdIsRejected() throws Exception {
        Response r = create("{\"watermarked\":true}");
        assertEquals(400, r.status());
        assertTrue_contains(r.body(), "缺少 musicId");
    }

    @Test
    @DisplayName("musicId 为非正数返回 400")
    void nonPositiveMusicIdIsRejected() throws Exception {
        Response r = create("{\"musicId\":-5}");
        assertEquals(400, r.status());
        assertTrue_contains(r.body(), "musicId 无效");
    }

    @Test
    @DisplayName("startSec 为字符串返回 400 而不是 500")
    void stringStartSecIsRejected() throws Exception {
        Response r = create("{\"musicId\":40356,\"startSec\":\"nan\",\"watermarked\":true}");
        assertEquals(400, r.status());
        assertTrue_contains(r.body(), "startSec 必须为数字");
        assertNoInternals(r.body());
    }

    @Test
    @DisplayName("startSec 为负数返回 400")
    void negativeStartSecIsRejected() throws Exception {
        Response r = create("{\"musicId\":40356,\"startSec\":-1}");
        assertEquals(400, r.status());
        assertTrue_contains(r.body(), "startSec 不能为负数");
    }

    @Test
    @DisplayName("数值类型合法的请求不会因类型问题被 400（进入后续业务分支）")
    void wellTypedRequestIsNotRejectedByValidation() throws Exception {
        Response r = create("{\"musicId\":40356,\"startSec\":0,\"watermarked\":true}");
        assertNotEquals(400, r.status(), "合法数值不该被类型校验拦截，实际：" + r.body());
    }

    private void assertNoInternals(String body) {
        assertFalse(body.contains("java."), "错误响应泄露了异常类名：" + body);
        assertFalse(body.contains("Exception"), "错误响应泄露了异常类型：" + body);
        assertFalse(body.contains("com.neko"), "错误响应泄露了内部类名：" + body);
    }

    private static void assertTrue_contains(String body, String expected) {
        org.junit.jupiter.api.Assertions.assertTrue(body.contains(expected),
                "期望响应包含「" + expected + "」，实际：" + body);
    }

    private record Response(int status, String body) {
    }

    private Response create(String json) throws Exception {
        int[] status = {200};
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus" -> status[0] = (int) args[0];
                        case "setHeader", "setContentType" -> {
                        }
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
                    case "getHeader" -> "Authorization".equals(args[0]) ? "Bearer ok" : null;
                    case "getRequestURI" -> "/api/video/render/create";
                    case "getPathInfo" -> "/create";
                    case "getReader" -> new BufferedReader(new StringReader(json));
                    default -> method.getReturnType() == boolean.class ? false : null;
                });

        new VideoRenderHandler().doPost(request, response);
        writer.flush();
        return new Response(status[0], body.toString());
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
