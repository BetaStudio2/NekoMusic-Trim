package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.model.User;
import com.neko.music.service.QrLoginService;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.QrCodeRenderer;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.VipUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码登录（PC 显示二维码，手机端 NekoMusic App 扫码确认）。
 *
 * <ul>
 *   <li>{@code POST /api/user/qrlogin/create} — 新建会话，返回 sessionId 与二维码图片（无需登录）</li>
 *   <li>{@code GET  /api/user/qrlogin/status?sessionId=} — PC 的 SSE 长连接，状态变化即时推（无需登录）</li>
 *   <li>{@code POST /api/user/qrlogin/claim} — PC 凭创建时拿到的一次性投递密钥领取登录 token（无需登录）</li>
 *   <li>{@code POST /api/user/qrlogin/scan} — 手机扫码后标记已扫描（需登录）</li>
 *   <li>{@code POST /api/user/qrlogin/confirm} — 手机确认/拒绝登录（需登录）</li>
 * </ul>
 */
public class UserQrLoginHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserQrLoginHandler.class);

    /** 状态变更轮询间隔 */
    private static final long POLL_INTERVAL_MS = 700L;
    /** 心跳间隔，防止反向代理掐掉闲置长连接 */
    private static final long PING_INTERVAL_MS = 15_000L;

    private final ExecutorService statusExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("qr-login-sse-", 0).factory());

    private QrLoginService qrLoginService;
    private UserAuthService userAuthService;

    @Override
    public void init() {
        qrLoginService = Main.getQrLoginService();
        userAuthService = Main.getUserAuthService();
    }

    @Override
    public void destroy() {
        statusExecutor.shutdownNow();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"/status".equals(path(request))) {
            sendJson(response, HttpServletResponse.SC_NOT_FOUND, false, "admin.lyrics.endpoint_not_found", null);
            return;
        }
        handleStatusStream(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (path(request)) {
            case "/create" -> handleCreate(request, response);
            case "/scan" -> handleScan(request, response);
            case "/confirm" -> handleConfirm(request, response);
            case "/claim" -> handleClaim(request, response);
            default -> sendJson(response, HttpServletResponse.SC_NOT_FOUND, false, "admin.lyrics.endpoint_not_found", null);
        }
    }

    // ──────────────────────────── 接口实现 ────────────────────────────

    /** 客户端新建扫码会话。 */
    private void handleCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        QrLoginService.NewSession session = qrLoginService.createSession();
        if (session == null) {
            sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "qr.service_unavailable", null);
            return;
        }

        String sessionId = session.sessionId();
        String qrContent = QrLoginService.qrContentFor(sessionId);
        String qrImage;
        try {
            // 纯服务端渲染：直接产出带软件图标的成品二维码
            qrImage = QrCodeRenderer.toPngDataUrl(qrContent, 320);
        } catch (Exception e) {
            logger.error("登录二维码图片渲染失败", e);
            sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "qr.generate_failed", null);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("qrImage", qrImage);
        data.put("expiresIn", QrLoginService.sessionTtlSeconds());
        // 一次性投递密钥：只发给创建方，领取 token 时回传；不写进二维码、不写进 SSE 帧
        data.put("deliveryKey", session.deliveryKey());
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", data);
    }

    /** PC 建立 SSE 长连接；状态一变就推，到终态后关闭连接。token 不在这条流里投递。 */
    private void handleStatusStream(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String sessionId = request.getParameter("sessionId");
        if (!QrLoginService.isValidSessionId(sessionId)) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, false, "qr.session_invalid", null);
            return;
        }

        AsyncContext asyncContext;
        try {
            asyncContext = request.startAsync();
        } catch (IllegalStateException e) {
            logger.warn("扫码登录 SSE 异步上下文创建失败", e);
            sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "qr.sse_unsupported", null);
            return;
        }
        asyncContext.setTimeout(0);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = response.getWriter();
            writer.print(": connected\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            logger.warn("扫码登录 SSE 初始化失败: {}", e.getMessage());
            completeQuietly(asyncContext);
            return;
        }

        statusExecutor.execute(() -> streamSessionStatus(sessionId, asyncContext, writer));
    }

    /**
     * 连接存续期间读 Redis，状态一变就推。
     *
     * <p>会话只在 Redis 里，扫码/确认可能落在另一台实例上，所以这里轮询共享状态而不是听本地事件；
     * 客户端因此只需一条连接，不再自己定时轮询。</p>
     */
    private void streamSessionStatus(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
        long deadline = System.currentTimeMillis() + (QrLoginService.sessionTtlSeconds() + 10L) * 1000L;
        long lastPing = System.currentTimeMillis();
        String lastStatus = null;
        try {
            while (true) {
                Optional<QrLoginService.Session> sessionOpt = qrLoginService.load(sessionId);
                if (sessionOpt.isEmpty()) {
                    if (!QrLoginService.STATUS_EXPIRED.equals(lastStatus)) {
                        writeStatusEvent(writer, statusData(QrLoginService.STATUS_EXPIRED));
                    }
                    break;
                }

                QrLoginService.Session session = sessionOpt.get();
                if (!session.status.equals(lastStatus)) {
                    if (QrLoginService.STATUS_CONFIRMED.equals(session.status)) {
                        // 状态帧只播状态：token 由创建方带一次性投递密钥调 /claim 单独领取。
                        // 把 token 写进这一帧，会被 CDN 的并发长连接合并机制同时投递给全部订阅者。
                        writeStatusEvent(writer, statusData(QrLoginService.STATUS_CONFIRMED));
                        break;
                    }
                    writeStatusEvent(writer, statusData(session.status));
                    lastStatus = session.status;
                    if (QrLoginService.STATUS_CANCELED.equals(session.status)) {
                        break;
                    }
                }

                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    writeStatusEvent(writer, statusData(QrLoginService.STATUS_EXPIRED));
                    break;
                }
                if (now - lastPing >= PING_INTERVAL_MS) {
                    writer.print(": ping\n\n");
                    writer.flush();
                    lastPing = now;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (IOException e) {
            logger.debug("扫码登录 SSE 连接已断开: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            completeQuietly(asyncContext);
        }
    }

    /** confirmed 帧：带一次性 token 与用户信息；数据不完整时退化为 expired，避免 PC 卡在已确认态。 */
    private Map<String, Object> confirmedData(QrLoginService.Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", QrLoginService.STATUS_CONFIRMED);
        Map<String, Object> userData = session.userId == null
                ? null
                : userAuthService.findUserById(session.userId).map(this::toUserMap).orElse(null);
        if (session.token == null || userData == null) {
            data.put("status", QrLoginService.STATUS_EXPIRED);
        } else {
            data.put("token", session.token);
            data.put("user", userData);
        }
        return data;
    }

    /** 写一帧 {@code status} 事件并立即 flush。 */
    private static void writeStatusEvent(PrintWriter writer, Object data) throws IOException {
        writer.print("event: status\n");
        writer.print("data: " + Main.getObjectMapper().writeValueAsString(data) + "\n\n");
        writer.flush();
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 已结束
        }
    }

    /** 手机端扫码后标记（用于让 PC 提示“已扫码，请在手机上确认”） */
    private void handleScan(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer userId = RequestAuthUtil.authenticate(request);
        if (userId == null) {
            sendJson(response, HttpServletResponse.SC_UNAUTHORIZED, false, "auth.login_required", null);
            return;
        }

        String sessionId = readSessionId(request);
        Optional<QrLoginService.Session> sessionOpt = qrLoginService.load(sessionId);
        if (sessionOpt.isEmpty()) {
            sendJson(response, HttpServletResponse.SC_GONE, false, "qr.expired", null);
            return;
        }

        QrLoginService.Session session = sessionOpt.get();
        if (QrLoginService.STATUS_CONFIRMED.equals(session.status)) {
            sendJson(response, HttpServletResponse.SC_OK, true, "qr.confirmed", statusData(session.status));
            return;
        }
        if (session.userId != null && !session.userId.equals(userId)) {
            sendJson(response, HttpServletResponse.SC_CONFLICT, false, "qr.scanned_by_other", null);
            return;
        }

        session.userId = userId;
        session.status = QrLoginService.STATUS_SCANNED;
        qrLoginService.save(sessionId, session);
        logger.info("扫码登录：用户 {} 已扫码，等待确认", userId);
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", statusData(session.status));
    }

    /** 手机端确认或拒绝 */
    private void handleConfirm(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer userId = RequestAuthUtil.authenticate(request);
        if (userId == null) {
            sendJson(response, HttpServletResponse.SC_UNAUTHORIZED, false, "auth.login_required", null);
            return;
        }

        JsonNode body = readJsonBody(request);
        String sessionId = body == null ? null : text(body, "sessionId");
        boolean approve = body == null || !body.has("approve") || body.get("approve").asBoolean(true);

        Optional<QrLoginService.Session> sessionOpt = qrLoginService.load(sessionId);
        if (sessionOpt.isEmpty()) {
            sendJson(response, HttpServletResponse.SC_GONE, false, "qr.expired", null);
            return;
        }

        QrLoginService.Session session = sessionOpt.get();
        if (session.userId != null && !session.userId.equals(userId)) {
            sendJson(response, HttpServletResponse.SC_CONFLICT, false, "qr.scanned_by_other", null);
            return;
        }
        if (QrLoginService.STATUS_CONFIRMED.equals(session.status)) {
            sendJson(response, HttpServletResponse.SC_OK, true, "qr.confirmed", statusData(session.status));
            return;
        }

        session.userId = userId;
        if (!approve) {
            session.status = QrLoginService.STATUS_CANCELED;
            session.token = null;
            qrLoginService.save(sessionId, session);
            logger.info("扫码登录：用户 {} 拒绝了登录请求", userId);
            sendJson(response, HttpServletResponse.SC_OK, true, "qr.cancelled", statusData(session.status));
            return;
        }

        String currentToken = RequestAuthUtil.extractToken(request);
        String token;
        try {
            token = userAuthService.exchangeAccessToken(currentToken);
        } catch (RuntimeException exception) {
            logger.warn("扫码登录签发 NekoAuth 令牌失败: userId={} error={}",
                    userId, exception.getMessage());
            sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "qr.login_failed", null);
            return;
        }

        session.status = QrLoginService.STATUS_CONFIRMED;
        session.token = token;
        qrLoginService.save(sessionId, session);
        logger.info("扫码登录：用户 {} 已确认登录", userId);
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", statusData(session.status));
    }

    /**
     * PC 凭创建时拿到的一次性投递密钥领取登录 token。
     *
     * <p>服务端在一条 Lua 里原子完成「校验密钥 + 读状态 + 删除会话」，因此并发调用里最多只有一个成功，
     * 且必须是创建方本人；密钥不符或会话未确认都不会消耗会话。</p>
     */
    private void handleClaim(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode body = readJsonBody(request);
        String sessionId = body == null ? null : text(body, "sessionId");
        String deliveryKey = body == null ? null : text(body, "deliveryKey");
        if (!QrLoginService.isValidSessionId(sessionId) || deliveryKey == null || deliveryKey.isBlank()) {
            sendJson(response, HttpServletResponse.SC_BAD_REQUEST, false, "qr.session_or_key_invalid", null);
            return;
        }

        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        QrLoginService.ClaimResult result = qrLoginService.claim(sessionId, deliveryKey);
        switch (result.outcome()) {
            case OK -> {
                Map<String, Object> data = confirmedData(result.session());
                // 会话缺 token 或用户已不存在时按过期处理，避免 PC 卡在「已确认」
                if (!QrLoginService.STATUS_CONFIRMED.equals(data.get("status"))) {
                    data = statusData(QrLoginService.STATUS_EXPIRED);
                }
                sendJson(response, HttpServletResponse.SC_OK, true, "ok", data);
            }
            case NOT_CONFIRMED -> sendJson(response, HttpServletResponse.SC_OK, true, "ok",
                    statusData(result.session() != null && result.session().status != null
                            ? result.session().status : QrLoginService.STATUS_PENDING));
            case REJECTED, MISSING -> sendJson(response, HttpServletResponse.SC_OK, true, "ok",
                    statusData(QrLoginService.STATUS_EXPIRED));
            case ERROR -> sendJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "qr.service_unavailable", null);
        }
    }

    // ──────────────────────────── 工具方法 ────────────────────────────

    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? "" : pathInfo;
    }

    private static Map<String, Object> statusData(String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        return data;
    }

    private Map<String, Object> toUserMap(User user) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.id());
        userData.put("nickname", user.nickname());
        userData.put("email", user.email());
        userData.put("createdAt", user.createdAt());
        userData.put("isVip", VipUtil.isVipActiveNow(user.vipExpiresAt()));
        userData.put("vipExpiresAt",
                user.vipExpiresAt() != null ? user.vipExpiresAt().toInstant().toString() : null);
        return userData;
    }

    private static JsonNode readJsonBody(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            return null;
        }
        if (sb.length() == 0) {
            return null;
        }
        try {
            return Main.getObjectMapper().readTree(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSessionId(HttpServletRequest request) {
        JsonNode body = readJsonBody(request);
        if (body != null) {
            String fromBody = text(body, "sessionId");
            if (fromBody != null && !fromBody.isBlank()) {
                return fromBody;
            }
        }
        return request.getParameter("sessionId");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

}
