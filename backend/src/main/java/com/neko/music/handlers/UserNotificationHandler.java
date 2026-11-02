package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.database.UserNotificationDatabaseManager;
import com.neko.music.database.UserNotificationDatabaseManager.NotificationRow;
import com.neko.music.database.UserNotificationDatabaseManager.Page;
import com.neko.music.service.NotificationPushBus;
import com.neko.music.service.UserNotificationService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.RequestAuthUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 站内消息（收件箱）。
 *
 * <ul>
 *   <li>{@code GET  /api/user/notifications} — 列表（含未读数）；{@code since} 补拉离线期间的新消息，
 *       {@code before} 翻更早的历史，两者互斥（同时给时以 {@code since} 为准）</li>
 *   <li>{@code GET  /api/user/notifications/stream} — 实时推送（SSE 长连接，连上即带未读数）</li>
 *   <li>{@code POST /api/user/notifications/read} — 标记已读，body {@code {ids:[...]}}；
 *       {@code ids} 为空表示全部已读</li>
 * </ul>
 *
 * <p><b>离线兜底：</b>消息写库即视为送达，列表接口才是「上线后看到消息」的主路径；
 * {@code /stream} 只是在线时的提前告知，断开与重连都不影响消息完整性。</p>
 *
 * <p><b>SSE 契约：</b>连上先发一帧 {@code ready}（含未读数与当前最大 id），此后每有新消息推一帧
 * {@code message}（负载与列表里的单条消息一致），15 秒发一次注释心跳。连接最长存活
 * {@value #STREAM_MAX_LIFETIME_MS} 毫秒后主动断开、由客户端重连；重连期间漏掉的消息由客户端
 * 拿 {@code ready.latestId} 与本地游标比对后走列表接口补拉，服务端不做重放。</p>
 *
 * <p><b>推送模型（单机）：</b>连接空闲时阻塞在进程内信号上（见 {@link NotificationPushBus}），
 * 本地写入会立刻唤醒对应连接，不再按秒轮询 Redis；只有长时间没有本地写入时才回落到一次兜底的
 * 版本号比对。因此单连接成本与「在线连接数」解耦，单机不再需要用一个全局连接上限去挡住后来者。</p>
 */
public class UserNotificationHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(UserNotificationHandler.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /** 单次 SSE 连接最长存活时间：到点主动断开，避免长连接与游标状态无限累积。 */
    private static final long STREAM_MAX_LIFETIME_MS = 300_000L;
    /** 心跳间隔：防止反向代理 / CDN 掐掉长时间没有数据的连接。 */
    private static final long STREAM_PING_INTERVAL_MS = 15_000L;
    /**
     * 兜底比对间隔：正常情况下靠 {@link NotificationPushBus} 进程内即时唤醒，只有长时间没有本地
     * 写入时才回落到「读一次 Redis 版本号」。取较大的值，让空闲连接的成本真正接近 0，而不是
     * 「每条连接每秒一次」。
     */
    private static final long STREAM_FALLBACK_POLL_INTERVAL_MS = 30_000L;
    /** 单次补推的最大条数，避免突发批量把一条连接堵死。 */
    private static final int STREAM_BATCH_LIMIT = MAX_LIMIT;
    /**
     * 每个用户同时保留的流连接数（多标签页 / 多端）。
     *
     * <p>单机部署刻意<b>不设全局连接上限</b>：全局上限会让「第 N+1 个用户无论他是谁都连不上」，
     * 与「所有用户都要能连上」直接冲突。这里按用户计数，但<b>不作为拒绝理由</b>——超出时淘汰该用户
     * 最旧的一条连接，让新连接照常建立。这样单个来源始终无法靠堆连接把单机句柄 / 内存打满，而正常
     * 用户不会因为「开了几个标签页」就被挡在外面：{@code EventSource} 拿到非 2xx 会直接终止连接且
     * 不再自动重连，一次拒绝等于那个标签页再也收不到实时消息。</p>
     *
     * <p>取 8 是给「多标签页 + 多端 + 到点重连时新旧短暂并存」留余量。值太小会让正常用户频繁进入
     * 「建连 → 被淘汰 → 重连」的循环，反而比放开更糟。</p>
     */
    private static final int MAX_STREAMS_PER_USER = 8;

    /** 被淘汰的连接在收尾前改用的重连间隔：压住「连接数超出上限」时多标签页之间的抢连接。 */
    private static final int EVICTED_STREAM_RETRY_MS = 15_000;

    /** 每个用户已建立的流连接，按建立顺序排列；超出上限时淘汰最旧的一条。 */
    private static final ConcurrentHashMap<Integer, ArrayDeque<StreamEntry>> STREAMS_BY_USER =
            new ConcurrentHashMap<>();

    /**
     * 一条在建 / 存续中的 SSE 连接。
     *
     * <p>淘汰只置位 {@code evicted} 并唤醒它的等待，收尾（写结束帧、注销槽位、关闭异步上下文）
     * 仍由持有这条连接的那个线程完成，避免在两处同时操作同一个 {@code Writer} / {@code AsyncContext}。</p>
     */
    private static final class StreamEntry {
        private final AtomicBoolean evicted = new AtomicBoolean(false);
        private final NotificationPushBus.StreamSignal signal;

        private StreamEntry(NotificationPushBus.StreamSignal signal) {
            this.signal = signal;
        }
    }

    private final ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("notification-sse-", 0).factory());

    @Override
    public void destroy() {
        streamExecutor.shutdownNow();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 收件箱是逐用户的动态数据：显式声明不可缓存，避免任何中间层串味
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = RequestAuthUtil.authenticate(req);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }
        String path = path(req);
        if ("".equals(path) || "/".equals(path)) {
            handleList(req, resp, userId);
        } else if ("/stream".equals(path)) {
            handleStream(req, resp, userId);
        } else {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "admin.lyrics.endpoint_not_found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = RequestAuthUtil.authenticate(req);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }
        if ("/read".equals(path(req))) {
            handleMarkRead(req, resp, userId);
        } else {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "admin.lyrics.endpoint_not_found");
        }
    }

    // ──────────────────────────── 列表 / 未读 / 已读 ────────────────────────────

    /** 收件箱列表：新的在前；返回未读数与「是否还有更早的」，便于一次请求同时刷新红点。 */
    private void handleList(HttpServletRequest req, HttpServletResponse resp, int userId) throws IOException {
        Integer since = intParam(req, "since");
        Integer before = intParam(req, "before");
        int limit = clampLimit(intParam(req, "limit"));

        UserNotificationDatabaseManager db = Main.getUserNotificationDatabaseManager();
        Page page = db.list(userId, since, before, limit);

        JsonArray items = new JsonArray();
        int latestId = since == null ? 0 : since;
        Map<Integer, String> nicknames = resolveNicknames(page.rows);
        for (NotificationRow row : page.rows) {
            items.add(toJson(row, nicknames));
            latestId = Math.max(latestId, row.id);
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("unread", db.countUnread(userId));
        data.addProperty("hasMore", page.hasMore);
        data.addProperty("latestId", latestId);

        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", ApiMessages.translate("notify.list_ok"));
        body.add("data", data);
        sendSuccessResponse(resp, body);
    }

    private void handleMarkRead(HttpServletRequest req, HttpServletResponse resp, int userId) throws IOException {
        List<Integer> ids = readIds(req);

        UserNotificationDatabaseManager db = Main.getUserNotificationDatabaseManager();
        int updated = db.markRead(userId, ids);

        JsonObject data = new JsonObject();
        data.addProperty("updated", updated);
        data.addProperty("unread", db.countUnread(userId));

        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", ApiMessages.translate("notify.marked_read"));
        body.add("data", data);
        sendSuccessResponse(resp, body);
    }

    /** 解析 {@code {ids:[...]}}；空 / 缺省 / 非法元素都按「全部已读」或跳过处理。 */
    private List<Integer> readIds(HttpServletRequest req) throws IOException {
        List<Integer> ids = new ArrayList<>();
        String raw = readBody(req);
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        JsonObject request;
        try {
            request = Main.getGson().fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return ids;
        }
        if (request == null || !request.has("ids") || !request.get("ids").isJsonArray()) {
            return ids;
        }
        for (JsonElement element : request.getAsJsonArray("ids")) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            try {
                ids.add(element.getAsInt());
            } catch (NumberFormatException ignored) {
                // 非数字元素跳过，不因为坏参数整体失败
            }
        }
        return ids;
    }

    // ──────────────────────────── 实时推送（SSE） ────────────────────────────

    private void handleStream(HttpServletRequest req, HttpServletResponse resp, int userId) throws IOException {
        // 连接数超限不再拒绝：新连接照常建立，只把该用户最旧的一条挤掉（见 registerStream）。
        // 必须先订阅再登记，淘汰时才拿得到唤醒句柄，被挤掉的连接就不用等兜底轮询才发现。
        NotificationPushBus.StreamSignal signal = Main.getUserNotificationService().subscribeStream(userId);
        StreamEntry entry = registerStream(userId, signal);

        AsyncContext asyncContext;
        try {
            asyncContext = req.startAsync();
        } catch (IllegalStateException e) {
            releaseStream(userId, entry);
            signal.close();
            logger.warn("站内消息 SSE 异步上下文创建失败", e);
            sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "qr.sse_unsupported");
            return;
        }
        asyncContext.setTimeout(0);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        resp.setHeader("Connection", "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = resp.getWriter();
            // 先告诉浏览器重连间隔，避免它拿默认值猛打
            writer.print("retry: 3000\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            releaseStream(userId, entry);
            signal.close();
            completeQuietly(asyncContext);
            return;
        }

        streamExecutor.execute(() -> {
            try {
                streamNotifications(userId, writer, entry, signal);
            } finally {
                releaseStream(userId, entry);
                completeQuietly(asyncContext);
            }
        });
    }

    /**
     * 连接存续期间阻塞等待进程内推送信号，被唤醒或兜底超时后才去数据库取新消息。
     *
     * <p>单机下写入与连接同进程，本地写库会直接唤醒这条连接（见 {@link NotificationPushBus}）；
     * Redis 版本号只在兜底周期上比对一次，用于覆盖信号丢失或未来的旁路写入。</p>
     */
    private void streamNotifications(int userId, PrintWriter writer, StreamEntry entry,
                                     NotificationPushBus.StreamSignal signal) {
        UserNotificationDatabaseManager db = Main.getUserNotificationDatabaseManager();
        UserNotificationService notificationService = Main.getUserNotificationService();

        long deadline = System.currentTimeMillis() + STREAM_MAX_LIFETIME_MS;
        long lastPing = System.currentTimeMillis();
        long lastVersion = notificationService.currentVersion(userId);
        int cursor = latestId(db, userId);

        try (signal) {
            writeEvent(writer, "ready", readyPayload(db, userId, cursor));
            while (true) {
                if (entry.evicted.get()) {
                    break;
                }
                // 有本地写入时立刻被唤醒；空闲时只在兜底周期上醒来比对一次版本号
                boolean woken = signal.await(STREAM_FALLBACK_POLL_INTERVAL_MS);
                if (entry.evicted.get()) {
                    break;
                }
                long version = notificationService.currentVersion(userId);
                if (woken || version != lastVersion) {
                    lastVersion = version;
                    cursor = pushNewItems(db, writer, userId, cursor);
                }

                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    break;
                }
                if (now - lastPing >= STREAM_PING_INTERVAL_MS) {
                    writer.print(": ping\n\n");
                    writer.flush();
                    lastPing = now;
                }
            }
            if (entry.evicted.get()) {
                // 让这台客户端把重连间隔调大，避免多标签页之间以 3 秒一轮互相抢连接
                writer.print("retry: " + EVICTED_STREAM_RETRY_MS + "\n\n");
                writer.flush();
            }
        } catch (IOException e) {
            logger.debug("站内消息 SSE 连接已断开: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把游标之后的新消息按 id 升序推完，返回推进后的游标。 */
    private int pushNewItems(UserNotificationDatabaseManager db, PrintWriter writer, int userId, int cursor)
            throws IOException {
        int latest = cursor;
        while (true) {
            Page page = db.list(userId, latest, null, STREAM_BATCH_LIMIT);
            if (page.rows.isEmpty()) {
                return latest;
            }
            List<NotificationRow> ascending = new ArrayList<>(page.rows);
            Collections.reverse(ascending);
            Map<Integer, String> nicknames = resolveNicknames(ascending);
            for (NotificationRow row : ascending) {
                writeEvent(writer, "message", toJson(row, nicknames), row.id);
                latest = Math.max(latest, row.id);
            }
            if (!page.hasMore) {
                return latest;
            }
        }
    }

    /** 连接建立时的当前最大 id：客户端拿它和本地游标比对，落后就自己补拉。 */
    private static int latestId(UserNotificationDatabaseManager db, int userId) {
        Page page = db.list(userId, null, null, 1);
        return page.rows.isEmpty() ? 0 : page.rows.get(0).id;
    }

    private static JsonObject readyPayload(UserNotificationDatabaseManager db, int userId, int latestId) {
        JsonObject data = new JsonObject();
        data.addProperty("unread", db.countUnread(userId));
        data.addProperty("latestId", latestId);
        return data;
    }

    /**
     * 写一帧 SSE。{@code data} 必须是单行 JSON（Gson 的 toString 天然单行），
     * 否则折行会被客户端当成新的字段解析。
     */
    private static void writeEvent(PrintWriter writer, String event, JsonObject data) {
        writeEvent(writer, event, data, null);
    }

    private static void writeEvent(PrintWriter writer, String event, JsonObject data, Integer id) {
        if (id != null) {
            writer.print("id: " + id + "\n");
        }
        writer.print("event: " + event + "\n");
        writer.print("data: " + data + "\n\n");
        writer.flush();
    }

    /**
     * 登记一条新连接；该用户的连接已到上限时，淘汰最旧的一条而不是拒绝新连接。
     *
     * <p>{@code signal} 必须已就绪：淘汰靠它立刻唤醒对方。唤醒与写帧都放在 {@code compute} 之外，
     * 不在 map 的桶锁里做 I/O。</p>
     */
    private static StreamEntry registerStream(int userId, NotificationPushBus.StreamSignal signal) {
        StreamEntry entry = new StreamEntry(signal);
        List<StreamEntry> evicted = new ArrayList<>();
        STREAMS_BY_USER.compute(userId, (key, streams) -> {
            ArrayDeque<StreamEntry> queue = streams == null ? new ArrayDeque<>() : streams;
            while (queue.size() >= MAX_STREAMS_PER_USER) {
                StreamEntry oldest = queue.pollFirst();
                if (oldest == null) {
                    break;
                }
                evicted.add(oldest);
            }
            queue.addLast(entry);
            return queue;
        });
        for (StreamEntry oldest : evicted) {
            if (oldest.evicted.compareAndSet(false, true)) {
                logger.debug("站内消息 SSE 连接数超过每用户上限，淘汰最旧的一条: userId={}", userId);
                oldest.signal.wake();
            }
        }
        return entry;
    }

    /** 注销一条连接；该用户的连接全部结束后移除条目，避免 map 随历史用户无限增长。 */
    private static void releaseStream(int userId, StreamEntry entry) {
        STREAMS_BY_USER.computeIfPresent(userId, (key, streams) -> {
            streams.remove(entry);
            return streams.isEmpty() ? null : streams;
        });
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 连接已经被容器收回，无需处理
        }
    }

    // ──────────────────────────── 工具 ────────────────────────────

    /** 单条消息的对外结构；字段名即客户端契约，改动要同步 API 文档。 */
    static JsonObject toJson(NotificationRow row, Map<Integer, String> nicknames) {
        JsonObject json = new JsonObject();
        json.addProperty("id", row.id);
        json.addProperty("type", row.type);
        json.addProperty("title", row.title);
        json.addProperty("body", row.body);
        json.addProperty("link", row.link);
        json.addProperty("read", row.read);
        json.addProperty("createdAt", row.createdAt);
        if (row.actorUserId != null) {
            JsonObject actor = new JsonObject();
            actor.addProperty("id", row.actorUserId);
            actor.addProperty("nickname", nicknames.getOrDefault(row.actorUserId, "有人"));
            json.add("actor", actor);
        }
        return json;
    }

    private static Map<Integer, String> resolveNicknames(List<NotificationRow> rows) {
        List<Integer> actorIds = rows.stream()
                .map(row -> row.actorUserId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Main.getNicknameResolver().resolveByLocalUserIds(actorIds, "有人");
    }

    private static Integer intParam(HttpServletRequest req, String name) {
        String raw = req.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? "" : pathInfo;
    }
}
