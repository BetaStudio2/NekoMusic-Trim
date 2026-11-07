package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.model.Admin;
import com.neko.music.model.LogPage;
import com.neko.music.service.AccessLogService;
import com.neko.music.service.OperationLogService;
import com.neko.music.util.AdminPermissionUtil;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.PermissionHelper;
import com.neko.music.util.RequestAuthUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台日志查询接口（仅管理后台使用，不写入对外 API 文档）。
 *
 * <ul>
 *   <li>{@code GET /api/admin/logs/access} —— 分页检索访问日志</li>
 *   <li>{@code GET /api/admin/logs/access/stats} —— 访问量概览与聚合榜</li>
 *   <li>{@code GET /api/admin/logs/access/stream} —— 访问日志实时流（SSE）</li>
 *   <li>{@code GET /api/admin/logs/operations} —— 分页检索操作日志</li>
 * </ul>
 *
 * <p>权限：访问日志对「除普通用户外」的角色开放（审核员及以上）；操作日志仅管理员及以上。</p>
 *
 * <p><b>SSE 契约（{@code /access/stream}）：</b>{@code EventSource} 无法携带请求头，因此该端点
 * 同时接受 {@code Authorization: Bearer <token>} 与 {@code ?token=<token>}（浏览器走后者），
 * 连上先发一帧 {@code ready}
 * （含服务端时间），此后每产生一条访问日志推一帧 {@code record}（负载与列表里的单行同一形状，
 * 仅缺自增 {@code id}），空闲 15 秒发一次注释心跳；连接最长存活 5 分钟后主动断开、由客户端重连。
 * 实时流只是「提前告知」，断线期间的行由列表接口补全，服务端不做重放。</p>
 */
public class AdminLogHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(AdminLogHandler.class);

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_STATS_DAYS = 7;
    private static final int MAX_STATS_DAYS = 90;

    /** 单次 SSE 连接最长存活时间：到点主动断开，避免长连接与订阅对象无限累积。 */
    private static final long STREAM_MAX_LIFETIME_MS = 300_000L;
    /** 心跳间隔：没有新日志时也要发注释帧，防止反向代理掐掉空闲连接。 */
    private static final long STREAM_PING_INTERVAL_MS = 15_000L;
    /** 全局同时允许的实时连接数：后台页面本就寥寥，超限直接拒绝。 */
    private static final int MAX_STREAMS = 8;

    private static final AtomicInteger STREAM_TOTAL = new AtomicInteger();

    private final ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("access-log-sse-", 0).factory());

    @Override
    public void destroy() {
        streamExecutor.shutdownNow();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 动态接口显式声明不缓存（CacheControlFilter 也兜底，这里保持契约显式）
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        String path = request.getPathInfo();
        if (path == null) {
            path = "";
        }
        try {
            if (path.startsWith("/access/stream")) {
                handleAccessStream(request, response);
            } else if (path.startsWith("/access/stats") || path.equals("/access/stats/")) {
                handleAccessStats(request, response);
            } else if (path.equals("/access") || path.equals("/access/")) {
                handleAccessList(request, response);
            } else if (path.equals("/operations") || path.equals("/operations/")) {
                handleOperationList(request, response);
            } else {
                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "admin.log.unknown_endpoint");
            }
        } catch (SQLException e) {
            logger.error("查询日志失败: {}", e.getMessage());
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "admin.log.query_failed");
        }
    }

    private void handleAccessStats(HttpServletRequest request, HttpServletResponse response) throws IOException, SQLException {
        if (!PermissionHelper.checkPermission(request, response,
                AdminPermissionUtil.Permission.ACCESS_LOG_VIEW)) {
            return;
        }
        AccessLogService service = AccessLogService.current();
        if (service == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin.log.access_log_disabled");
            return;
        }
        int days = parseInt(request.getParameter("days"), DEFAULT_STATS_DAYS, 1, MAX_STATS_DAYS);
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", service.stats(days));
    }

    private void handleAccessList(HttpServletRequest request, HttpServletResponse response) throws IOException, SQLException {
        if (!PermissionHelper.checkPermission(request, response,
                AdminPermissionUtil.Permission.ACCESS_LOG_VIEW)) {
            return;
        }
        AccessLogService service = AccessLogService.current();
        if (service == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin.log.access_log_disabled");
            return;
        }
        LogPage page = service.list(
                parseInt(request.getParameter("page"), 1, 1, Integer.MAX_VALUE),
                parseInt(request.getParameter("size"), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE),
                request.getParameter("ip"),
                request.getParameter("path"),
                parseInteger(request.getParameter("status")),
                request.getParameter("threatLevel"),
                parseLong(request.getParameter("from")),
                parseLong(request.getParameter("to")),
                request.getParameter("keyword"));
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", toData(page));
    }

    private void handleOperationList(HttpServletRequest request, HttpServletResponse response) throws IOException, SQLException {
        if (!PermissionHelper.checkPermission(request, response,
                AdminPermissionUtil.Permission.OPERATION_LOG_VIEW)) {
            return;
        }
        OperationLogService service = OperationLogService.current();
        if (service == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin.log.audit_disabled");
            return;
        }
        LogPage page = service.list(
                parseInt(request.getParameter("page"), 1, 1, Integer.MAX_VALUE),
                parseInt(request.getParameter("size"), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE),
                parseInteger(request.getParameter("adminId")),
                request.getParameter("module"),
                parseLong(request.getParameter("from")),
                parseLong(request.getParameter("to")),
                request.getParameter("keyword"));
        sendJson(response, HttpServletResponse.SC_OK, true, "ok", toData(page));
    }

    /**
     * 访问日志实时流（SSE）。
     *
     * <p>鉴权同时兼容请求头与查询串（{@code EventSource} 带不了头）；订阅来自
     * {@link AccessLogService}，它给每条连接一个有界队列，页面消费不过来时丢的是页面自己的实时行，
     * 不会反压到产生日志的请求线程。</p>
     */
    private void handleAccessStream(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requirePermission(request, response, AdminPermissionUtil.Permission.ACCESS_LOG_VIEW)) {
            return;
        }
        AccessLogService service = AccessLogService.current();
        if (service == null) {
            sendErrorResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin.log.access_log_disabled");
            return;
        }
        if (STREAM_TOTAL.get() >= MAX_STREAMS) {
            response.setHeader("Retry-After", "30");
            sendErrorResponse(response, 429, "admin.log.sse_limit");
            return;
        }
        STREAM_TOTAL.incrementAndGet();
        AccessLogService.LiveSubscription subscription = service.subscribe();

        AsyncContext asyncContext;
        try {
            asyncContext = request.startAsync();
        } catch (IllegalStateException e) {
            releaseStreamSlot(subscription);
            logger.warn("访问日志 SSE 异步上下文创建失败", e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "qr.sse_unsupported");
            return;
        }
        asyncContext.setTimeout(0);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = response.getWriter();
            // 先告诉浏览器重连间隔，避免它拿默认值猛打
            writer.print("retry: 3000\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            releaseStreamSlot(subscription);
            completeQuietly(asyncContext);
            return;
        }

        streamExecutor.execute(() -> {
            try {
                streamAccessLogs(writer, subscription);
            } finally {
                releaseStreamSlot(subscription);
                completeQuietly(asyncContext);
            }
        });
    }

    /** 连接存续期间只做两件事：把新行推给页面、空闲时发心跳，到时限主动断开。 */
    private void streamAccessLogs(PrintWriter writer, AccessLogService.LiveSubscription subscription) {
        long deadline = System.currentTimeMillis() + STREAM_MAX_LIFETIME_MS;
        try {
            writeEvent(writer, "ready", "{\"serverTime\":" + System.currentTimeMillis() + "}");
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> record = subscription.poll(STREAM_PING_INTERVAL_MS);
                if (record == null) {
                    // 注释行（以 ':' 开头）不是事件，仅用于保活；顺带把队列丢弃量带出去，
                    // 便于从连接侧看出页面是否跟得上（补全由列表接口负责）
                    writer.print(": ping dropped=" + subscription.droppedCount() + "\n\n");
                    writer.flush();
                } else {
                    writeEvent(writer, "record", Main.getGson().toJson(record));
                }
                if (writer.checkError()) {
                    // 客户端已断开：立即收敛，不再空转
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 写一帧 SSE；{@code json} 必须是单行（Gson 的 Map 序列化天然单行）。 */
    private static void writeEvent(PrintWriter writer, String event, String json) {
        writer.print("event: " + event + "\n");
        writer.print("data: " + json + "\n\n");
        writer.flush();
    }

    private static void releaseStreamSlot(AccessLogService.LiveSubscription subscription) {
        subscription.close();
        STREAM_TOTAL.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 连接已被容器收回，无需处理
        }
    }

    /**
     * 校验管理端权限。
     *
     * <p>与 {@link PermissionHelper#checkPermission} 的差别只在于令牌来源：这里额外接受
     * {@code ?token=}，供浏览器 {@code EventSource} 使用；面向 fetch 的接口仍走原来的辅助方法。</p>
     */
    private boolean requirePermission(HttpServletRequest request, HttpServletResponse response,
                                      AdminPermissionUtil.Permission permission) throws IOException {
        String token = RequestAuthUtil.extractToken(request);
        Admin admin = token == null || Main.getAdminAuthService() == null
                ? null
                : Main.getAdminAuthService().getAdminByToken(token);
        if (admin == null) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.unauthorized");
            return false;
        }
        if (!AdminPermissionUtil.hasPermission(admin, permission)) {
            sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "common.forbidden");
            return false;
        }
        return true;
    }

    private static Map<String, Object> toData(LogPage page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", page.total());
        data.put("page", page.page());
        data.put("size", page.size());
        data.put("items", page.items());
        return data;
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.min(Math.max(value, min), max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
