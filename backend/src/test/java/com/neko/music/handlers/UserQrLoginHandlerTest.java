package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.model.User;
import com.neko.music.service.QrLoginService;
import com.neko.music.service.UserAuthService;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫码登录一次性 token 的投递语义。
 *
 * <p>回归背景：token 曾被放进 SSE 状态帧里，靠「谁先读到 confirmed 谁投递」来保证只发一次。CDN 会把
 * 同一 URL 的并发长连接合并成一次回源、再把唯一一路响应分发给全部订阅者，于是同一个 token 被同时
 * 投递给多个客户端（线上实测 8 个匿名读者全部拿到同一 token）。现在状态帧只播状态，token 由创建方
 * 带一次性投递密钥调 {@code /claim} 领取，服务端用一条 Lua 原子完成「校验密钥 + 读状态 + 删除会话」。</p>
 */
class UserQrLoginHandlerTest {

    private static final String SESSION_ID = "fVQ67uSEhtZBenMsDBCYpeFQVtnObnTC";
    private static final String DELIVERY_KEY = "3OZl0Yk8fJm2sTn6QwR1uXb4cVd7eGh9iKl0mNo2pQs";
    private static final String TOKEN = "a9f128cf85bdb0739d0eae4cb84197b8676020c89aabcf83d2ec0d6a0164d1f7";
    private static final int USER_ID = 3482;

    /** 会话永远停在 confirmed；领取权用 CAS 模拟 Redis Lua 的原子性。 */
    private static final class StubQrLoginService extends QrLoginService {
        private final AtomicBoolean claimed = new AtomicBoolean();

        StubQrLoginService() {
            super(null, null);
        }

        @Override
        public Optional<Session> load(String sessionId) {
            return Optional.of(confirmedSession());
        }

        @Override
        public ClaimResult claim(String sessionId, String deliveryKey) {
            if (!DELIVERY_KEY.equals(deliveryKey) || !claimed.compareAndSet(false, true)) {
                return new ClaimResult(ClaimOutcome.REJECTED, null);
            }
            return new ClaimResult(ClaimOutcome.OK, confirmedSession());
        }

        private static Session confirmedSession() {
            Session session = new Session();
            session.status = STATUS_CONFIRMED;
            session.userId = USER_ID;
            session.token = TOKEN;
            session.deliveryKey = DELIVERY_KEY;
            return session;
        }
    }

    private static final class StubCreateQrLoginService extends QrLoginService {
        StubCreateQrLoginService() {
            super(null, null);
        }

        @Override
        public NewSession createSession() {
            return new NewSession(SESSION_ID, DELIVERY_KEY);
        }
    }

    private static final class StubUserAuthService extends UserAuthService {
        StubUserAuthService() {
            super(null, null);
        }

        @Override
        public Optional<User> findUserById(int userId) {
            return Optional.of(new User(userId, "test", null, "test@example.com",
                    true, "2026-01-01T00:00:00Z", null));
        }
    }

    private static final StubUserAuthService AUTH = new StubUserAuthService();

    @Test
    void loginQrUsesUniformSquareImage() throws Exception {
        BufferedImage qr = createQrImage("NekoMusic-PC/1.0");
        assertEquals(320, qr.getWidth());
        assertEquals(320, qr.getHeight());
        assertEquals(255, qr.getRGB(0, 0) >>> 24);
    }

    private static final class Reader {
        private final StringWriter body = new StringWriter();
        private final CountDownLatch done = new CountDownLatch(1);
        private final PrintWriter writer = new PrintWriter(body) {
            @Override
            public void close() {
                super.close();
                done.countDown();
            }
        };

        String body() {
            return body.toString();
        }
    }

    @Test
    void statusStreamNeverCarriesTheToken() throws Exception {
        StubQrLoginService service = new StubQrLoginService();
        int readers = 8;

        List<Reader> running = new ArrayList<>();
        for (int i = 0; i < readers; i++) {
            running.add(startReader(service));
        }
        for (Reader reader : running) {
            assertTrue(reader.done.await(10, TimeUnit.SECONDS), "读者未在超时内结束");
        }

        for (Reader reader : running) {
            assertFalse(reader.body().contains(TOKEN),
                    "SSE 状态帧不得携带 token（CDN 会把同 URL 的并发长连接合并后广播给全部订阅者）："
                            + reader.body());
            assertTrue(reader.body().contains("\"status\":\"confirmed\""),
                    "读者应收到 confirmed 状态帧：" + reader.body());
        }
    }

    @Test
    void onlyOneConcurrentClaimReceivesTheOneTimeToken() throws Exception {
        assertClaimFanOut(DELIVERY_KEY, 1, 7);
    }

    @Test
    void claimWithWrongDeliveryKeyNeverReceivesTheToken() throws Exception {
        assertClaimFanOut("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 0, 8);
    }

    /** 并发发起 N 次领取：期望恰好 {@code winners} 个拿到 token，其余一律 expired。 */
    private void assertClaimFanOut(String deliveryKey, int winners, int losers) throws Exception {
        StubQrLoginService service = new StubQrLoginService();
        UserQrLoginHandler handler = newHandler(service);

        int callers = winners + losers;
        List<Reader> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Reader reader = new Reader();
            results.add(reader);
            HttpServletRequest request = claimRequest(deliveryKey);
            HttpServletResponse response = responseWith(reader);
            threads.add(new Thread(() -> {
                try {
                    handler.doPost(request, response);
                } catch (Exception e) {
                    reader.done.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Reader reader : results) {
            assertTrue(reader.done.await(10, TimeUnit.SECONDS), "领取请求未在超时内结束");
        }

        long delivered = results.stream().filter(reader -> reader.body().contains(TOKEN)).count();
        long expired = results.stream()
                .filter(reader -> reader.body().contains("\"status\":\"expired\"")).count();

        assertEquals(winners, delivered, "同一个 token 只能被领取一次：" + bodies(results));
        assertEquals(losers, expired, "其余领取者必须收到 expired：" + bodies(results));
    }

    private static List<String> bodies(List<Reader> readers) {
        List<String> out = new ArrayList<>();
        for (Reader reader : readers) {
            out.add(reader.body().replace("\n", " ").trim());
        }
        return out;
    }

    /** 以匿名读者身份建立一次 SSE 状态流；返回的 Reader 在终止帧到达后 {@code done} 归零。 */
    private Reader startReader(QrLoginService service) {
        Reader reader = new Reader();

        AsyncContext asyncContext = proxy(AsyncContext.class, (p, m, a) -> {
            if ("complete".equals(m.getName())) {
                reader.done.countDown();
            }
            return defaultValue(m);
        });

        HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getPathInfo" -> "/status";
            case "getParameter" -> SESSION_ID;
            case "startAsync" -> asyncContext;
            case "getMethod" -> "GET";
            default -> defaultValue(m);
        });

        UserQrLoginHandler handler = newHandler(service);
        Thread thread = new Thread(() -> {
            try {
                handler.doGet(request, responseWith(reader));
            } catch (Exception e) {
                reader.done.countDown();
            }
        });
        thread.start();
        return reader;
    }

    private static UserQrLoginHandler newHandler(QrLoginService service) {
        UserQrLoginHandler handler = new UserQrLoginHandler();
        try {
            inject(handler, "qrLoginService", service);
            inject(handler, "userAuthService", AUTH);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return handler;
    }

    private static HttpServletResponse responseWith(Reader reader) {
        return proxy(HttpServletResponse.class, (p, m, a) -> switch (m.getName()) {
            case "getWriter" -> reader.writer;
            default -> defaultValue(m);
        });
    }

    private static HttpServletRequest claimRequest(String deliveryKey) {
        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"deliveryKey\":\"" + deliveryKey + "\"}";
        return proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getPathInfo" -> "/claim";
            case "getReader" -> new BufferedReader(new StringReader(body));
            case "getMethod" -> "POST";
            default -> defaultValue(m);
        });
    }

    private static BufferedImage createQrImage(String userAgent) throws Exception {
        Reader reader = new Reader();
        HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
            case "getPathInfo" -> "/create";
            case "getMethod" -> "POST";
            case "getHeader" -> switch (((String) a[0]).toLowerCase()) {
                case "user-agent" -> userAgent;
                default -> null;
            };
            default -> defaultValue(m);
        });

        newHandler(new StubCreateQrLoginService()).doPost(request, responseWith(reader));
        JsonNode root = Main.getObjectMapper().readTree(reader.body());
        assertTrue(root.path("success").asBoolean(), reader.body());
        String dataUrl = root.path("data").path("qrImage").asText();
        String prefix = "data:image/png;base64,";
        assertTrue(dataUrl.startsWith(prefix), "应返回 PNG data URL");
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        var declared = UserQrLoginHandler.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
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
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
