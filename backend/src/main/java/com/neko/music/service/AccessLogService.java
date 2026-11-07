package com.neko.music.service;

import com.neko.music.database.LogDatabaseManager;
import com.neko.music.model.AccessLogRecord;
import com.neko.music.model.LogPage;
import com.neko.music.model.ThreatAssessment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志：记录**每一个**请求的量、来源与客户端信息，供安全分析与访问量统计。
 *
 * <p>写入异步批量（见 {@link LogBatchWriter}），不参与请求关键路径；读取供后台按条件检索与聚合。
 * 数据落在独立日志库，不共用 {@code neko_music}。</p>
 */
public class AccessLogService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 分页上限，避免一次把日志库整表捞出来。 */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;
    /** 统计窗口上限（天）。 */
    private static final int MAX_STATS_DAYS = 90;
    /** Top 榜长度。 */
    private static final int TOP_LIMIT = 10;

    private static final String INSERT_SQL = """
            INSERT INTO access_log (occurred_at, method, path, query, category, status, duration_us,
                response_bytes, client_ip, ip_subnet, user_agent, referer, origin, accept_language, authenticated,
                user_id, user_name, admin_id, admin_username, host, scheme, protocol, thread,
                seo_render, threat_level, threat_score, threat_reasons, threat_engine_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static volatile AccessLogService current;

    private final LogDatabaseManager logDatabaseManager;
    private final LogBatchWriter<AccessLogRecord> writer;

    /** 实时订阅者（后台访问日志页的 SSE 连接）；每个订阅各持一个**有界**队列。 */
    private final CopyOnWriteArrayList<LiveSubscription> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 概览聚合的并发执行器。
     *
     * <p>概览里的每条聚合都要各自扫一遍窗口内的行，彼此又互不依赖：串行执行时接口耗时就是它们的
     * **和**（7 天窗口实测约 740ms）。并发度取 6 是给日志库连接池（上限 8）留出批量写入线程与其它
     * 后台查询的余量。</p>
     */
    private final ExecutorService statsExecutor = Executors.newFixedThreadPool(6,
            Thread.ofPlatform().daemon().name("access-log-stats-", 0).factory());

    public AccessLogService(LogDatabaseManager logDatabaseManager) {
        this.logDatabaseManager = logDatabaseManager;
        this.writer = new LogBatchWriter<>("access-log", this::insertBatch);
    }

    public void start() {
        writer.start();
    }

    /** 停止写入线程并把队列里的存量刷盘，同时断开全部实时订阅。 */
    public void close() {
        statsExecutor.shutdownNow();
        writer.close();
        for (LiveSubscription subscriber : subscribers) {
            subscriber.close();
        }
        subscribers.clear();
    }

    /** 记录一条访问日志；未启用或队列满时静默丢弃，绝不阻塞请求。 */
    public static void record(AccessLogRecord record) {
        AccessLogService service = current;
        if (service != null) {
            service.publish(record);
        }
    }

    /** 入队落库 + 广播给实时订阅者；两者都是非阻塞写，慢消费者只会丢自己的行。 */
    private void publish(AccessLogRecord record) {
        writer.offer(record);
        if (subscribers.isEmpty()) {
            return;
        }
        Map<String, Object> row = toMap(record);
        for (LiveSubscription subscriber : subscribers) {
            subscriber.offer(row);
        }
    }

    /**
     * 订阅实时访问日志（后台 SSE 流）。返回的句柄用完必须 {@link LiveSubscription#close()}，
     * 否则连接断开后广播仍会往它的队列里写。
     */
    public LiveSubscription subscribe() {
        LiveSubscription subscription = new LiveSubscription(this);
        subscribers.add(subscription);
        return subscription;
    }

    private void unsubscribe(LiveSubscription subscription) {
        subscribers.remove(subscription);
    }

    /**
     * 一条实时订阅：有界队列 + 丢弃计数。
     *
     * <p>广播由请求线程顺带完成，因此这里**只允许非阻塞写**：后台页面若因断网、标签页被挂起而
     * 消费不过来，丢弃的是它自己的实时行（列表接口仍能补全），绝不会反压到正常请求。</p>
     */
    public static final class LiveSubscription implements AutoCloseable {

        /** 单条订阅的积压上限：够覆盖页面卡顿，又小到不会占住内存。 */
        private static final int QUEUE_CAPACITY = 512;

        private final AccessLogService owner;
        private final BlockingQueue<Map<String, Object>> queue =
                new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicLong dropped = new AtomicLong();

        private LiveSubscription(AccessLogService owner) {
            this.owner = owner;
        }

        private void offer(Map<String, Object> row) {
            if (!queue.offer(row)) {
                dropped.incrementAndGet();
            }
        }

        /** 取下一行；超时返回 {@code null}（调用方据此发心跳）。 */
        public Map<String, Object> poll(long timeoutMillis) throws InterruptedException {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** 本条连接累计丢弃的行数（页面卡顿 / 断网时留下的实时缺口）。 */
        public long droppedCount() {
            return dropped.get();
        }

        @Override
        public void close() {
            owner.unsubscribe(this);
            queue.clear();
        }
    }

    public static AccessLogService current() {
        return current;
    }

    public static void setCurrent(AccessLogService service) {
        current = service;
    }

    private void insertBatch(List<AccessLogRecord> batch) throws SQLException {
        try (Connection conn = logDatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            for (AccessLogRecord record : batch) {
                bindInsert(stmt, record);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private static void bindInsert(PreparedStatement stmt, AccessLogRecord r) throws SQLException {
        int i = 1;
        stmt.setLong(i++, r.occurredAt());
        stmt.setString(i++, LogQuerySupport.truncate(r.method(), 16));
        stmt.setString(i++, LogQuerySupport.truncate(r.path(), 512));
        stmt.setString(i++, LogQuerySupport.truncate(r.query(), 2048));
        stmt.setString(i++, LogQuerySupport.truncate(r.category(), 16));
        stmt.setInt(i++, r.status());
        stmt.setLong(i++, r.durationUs());
        stmt.setLong(i++, r.responseBytes());
        stmt.setString(i++, LogQuerySupport.truncate(r.clientIp(), 64));
        stmt.setString(i++, LogQuerySupport.truncate(r.ipSubnet(), 64));
        stmt.setString(i++, LogQuerySupport.truncate(r.userAgent(), 1024));
        stmt.setString(i++, LogQuerySupport.truncate(r.referer(), 1024));
        stmt.setString(i++, LogQuerySupport.truncate(r.origin(), 255));
        stmt.setString(i++, LogQuerySupport.truncate(r.acceptLanguage(), 255));
        stmt.setBoolean(i++, r.authenticated());
        if (r.userId() == null) {
            stmt.setNull(i++, Types.INTEGER);
        } else {
            stmt.setInt(i++, r.userId());
        }
        stmt.setString(i++, LogQuerySupport.truncate(r.userName(), 255));
        if (r.adminId() == null) {
            stmt.setNull(i++, Types.INTEGER);
        } else {
            stmt.setInt(i++, r.adminId());
        }
        stmt.setString(i++, LogQuerySupport.truncate(r.adminUsername(), 255));
        stmt.setString(i++, LogQuerySupport.truncate(r.host(), 255));
        stmt.setString(i++, LogQuerySupport.truncate(r.scheme(), 16));
        stmt.setString(i++, LogQuerySupport.truncate(r.protocol(), 16));
        stmt.setString(i++, LogQuerySupport.truncate(r.thread(), 128));
        if (r.seoRender() == null) {
            stmt.setNull(i++, Types.BOOLEAN);
        } else {
            stmt.setBoolean(i++, r.seoRender());
        }
        stmt.setString(i++, LogQuerySupport.truncate(
                r.threatLevel() == null ? ThreatAssessment.NORMAL : r.threatLevel(), 16));
        stmt.setInt(i++, Math.max(0, Math.min(100, r.threatScore())));
        stmt.setString(i++, LogQuerySupport.truncate(r.threatReasons(), 512));
        stmt.setInt(i, r.threatEngineVersion());
    }

    /**
     * 记录 → 与 {@code access_log} 列同名的行（供 SSE 实时推送）。
     *
     * <p>列名与顺序必须与 {@link #INSERT_SQL} 完全一致：后台表格直接按列名取值，实时行与列表行
     * 共用同一套渲染代码，形状不一致就会在实时行上缺列。该约束由 {@code LogSqlShapeTest} 兜底。</p>
     */
    static Map<String, Object> toMap(AccessLogRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("occurred_at", r.occurredAt());
        row.put("method", r.method());
        row.put("path", r.path());
        row.put("query", r.query());
        row.put("category", r.category());
        row.put("status", r.status());
        row.put("duration_us", r.durationUs());
        row.put("response_bytes", r.responseBytes());
        row.put("client_ip", r.clientIp());
        row.put("ip_subnet", r.ipSubnet());
        row.put("user_agent", r.userAgent());
        row.put("referer", r.referer());
        row.put("origin", r.origin());
        row.put("accept_language", r.acceptLanguage());
        row.put("authenticated", r.authenticated());
        row.put("user_id", r.userId());
        row.put("user_name", r.userName());
        row.put("admin_id", r.adminId());
        row.put("admin_username", r.adminUsername());
        row.put("host", r.host());
        row.put("scheme", r.scheme());
        row.put("protocol", r.protocol());
        row.put("thread", r.thread());
        row.put("seo_render", r.seoRender());
        row.put("threat_level", r.threatLevel());
        row.put("threat_score", r.threatScore());
        row.put("threat_reasons", r.threatReasons());
        row.put("threat_engine_version", r.threatEngineVersion());
        return row;
    }

    /**
     * 按条件分页检索访问日志。
     *
     * @param ip        客户端 IP（模糊匹配）
     * @param path      路径（模糊匹配）
     * @param status    状态码（精确）
     * @param threatLevel 威胁判定（精确：normal / suspected / attack）
     * @param fromMs    起始时间（含），null 不限
     * @param toMs      结束时间（含），null 不限
     * @param keyword   关键字，命中 路径 / 查询串 / UA / IP / 登录用户昵称 / 管理员用户名 /
     *                  威胁原因 任一
     */
    public LogPage list(int page, int size, String ip, String path, Integer status,
                        String threatLevel, Long fromMs, Long toMs,
                        String keyword) throws SQLException {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendEquals(where, params, "threat_level", blankToNull(threatLevel));
        if (status != null) {
            where.append(" AND status = ?");
            params.add(status);
        }
        appendLike(where, params, "client_ip", blankToNull(ip));
        appendLike(where, params, "path", blankToNull(path));
        if (fromMs != null) {
            where.append(" AND occurred_at >= ?");
            params.add(fromMs);
        }
        if (toMs != null) {
            where.append(" AND occurred_at <= ?");
            params.add(toMs);
        }
        appendKeyword(where, params, blankToNull(keyword),
                "path", "query", "user_agent", "client_ip", "user_name", "admin_username",
                "threat_reasons");

        long total;
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = logDatabaseManager.getConnection()) {
            try (PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM access_log" + where)) {
                bind(countStmt, params);
                try (ResultSet rs = countStmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            String sql = "SELECT * FROM access_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int index = bind(stmt, params);
                stmt.setInt(index++, safeSize);
                stmt.setInt(index, (safePage - 1) * safeSize);
                try (ResultSet rs = stmt.executeQuery()) {
                    items = LogQuerySupport.toMaps(rs);
                }
            }
        }
        return new LogPage(total, safePage, safeSize, items);
    }

    /**
     * 访问量概览：总量、今日量、独立 IP，以及最近 N 天的趋势与 Top 榜。
     *
     * <p>下面这些聚合彼此独立，但**每条都要扫一遍窗口内的行**；串行执行的接口耗时就是它们的和
     * （7 天窗口实测约 740ms）。因此拆成任务并发取，墙钟时间退化成「最慢的那一条」。今日的多个
     * 指标另外合并成一次范围扫描，免得同一批行被扫 8 遍、每遍还各起一张临时表。</p>
     */
    public Map<String, Object> stats(int days) throws SQLException {
        int window = Math.min(Math.max(1, days), MAX_STATS_DAYS);
        long startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant().toEpochMilli();
        long windowStart = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant().toEpochMilli();

        List<Callable<Object>> tasks = List.of(
                () -> query(conn -> scalar(conn, "SELECT COUNT(*) FROM access_log", null)),
                () -> query(conn -> todayOverview(conn, startOfToday)),
                () -> query(conn -> dailyTrend(conn, windowStart, window)),
                () -> query(conn -> grouped(conn,
                        "SELECT client_ip AS k, COUNT(*) AS c FROM access_log WHERE occurred_at >= ? "
                                + "GROUP BY client_ip ORDER BY c DESC LIMIT " + TOP_LIMIT, windowStart)),
                // 登录用户榜：谁在频繁访问，比单看 IP 更能定位到具体账号
                () -> query(conn -> grouped(conn,
                        "SELECT CONCAT('#', user_id, ' ', COALESCE(user_name, '')) AS k, COUNT(*) AS c "
                                + "FROM access_log WHERE occurred_at >= ? AND user_id IS NOT NULL "
                                + "GROUP BY k ORDER BY c DESC LIMIT " + TOP_LIMIT, windowStart)),
                () -> query(conn -> grouped(conn,
                        "SELECT path AS k, COUNT(*) AS c FROM access_log WHERE occurred_at >= ? "
                                + "GROUP BY path ORDER BY c DESC LIMIT " + TOP_LIMIT, windowStart)),
                () -> query(conn -> countByQuery(conn,
                        "SELECT category AS k, COUNT(*) AS c FROM access_log WHERE occurred_at >= ? "
                                + "GROUP BY category ORDER BY c DESC", windowStart)),
                () -> query(conn -> grouped(conn,
                        "SELECT LEFT(COALESCE(user_agent,''), 255) AS k, COUNT(*) AS c FROM access_log "
                                + "WHERE occurred_at >= ? GROUP BY k ORDER BY c DESC LIMIT " + TOP_LIMIT,
                        windowStart)),
                () -> query(conn -> countByQuery(conn,
                        "SELECT threat_level AS k, COUNT(*) AS c FROM access_log WHERE occurred_at >= ? "
                                + "GROUP BY threat_level ORDER BY c DESC", windowStart)));

        List<Future<Object>> pending = new ArrayList<>(tasks.size());
        for (Callable<Object> task : tasks) {
            pending.add(statsExecutor.submit(task));
        }
        List<Object> done = new ArrayList<>(tasks.size());
        for (Future<Object> future : pending) {
            done.add(await(future));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests", done.get(0));
        // 今日概览自身是一个小 Map，摊平进同一层，响应结构与串行版完全一致
        stats.putAll(asMap(done.get(1)));
        stats.put("days", window);
        stats.put("dailyTrend", done.get(2));
        stats.put("topIps", done.get(3));
        stats.put("topUsers", done.get(4));
        stats.put("topPaths", done.get(5));
        stats.put("categories", done.get(6));
        stats.put("topUserAgents", done.get(7));
        stats.put("threatLevels", done.get(8));
        return stats;
    }

    /** 今日概览：指标合并成**一次**范围扫描，避免同一批行被反复扫、每遍各起一张临时表。 */
    private static Map<String, Object> todayOverview(Connection conn, long startOfToday) throws SQLException {
        String sql = "SELECT COUNT(*) AS total, COUNT(DISTINCT client_ip) AS ips, "
                + "COUNT(DISTINCT user_id) AS users, "
                + "COALESCE(SUM(status >= 400), 0) AS errors, "
                + "COALESCE(SUM(seo_render = 1), 0) AS seo, "
                + "COALESCE(SUM(threat_level = 'attack'), 0) AS attacks, "
                + "COALESCE(SUM(threat_level = 'suspected'), 0) AS suspected "
                + "FROM access_log WHERE occurred_at >= ?";
        Map<String, Object> overview = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, startOfToday);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                overview.put("todayRequests", rs.getLong("total"));
                overview.put("todayUniqueIps", rs.getLong("ips"));
                overview.put("todayLoggedUsers", rs.getLong("users"));
                overview.put("todayErrors", rs.getLong("errors"));
                overview.put("todaySeo", rs.getLong("seo"));
                overview.put("todayAttacks", rs.getLong("attacks"));
                overview.put("todaySuspected", rs.getLong("suspected"));
            }
        }
        return overview;
    }

    /** 一条概览查询：自己从池里取连接，用完归还，因此可以安全并发。 */
    private interface QueryTask {
        Object run(Connection conn) throws SQLException;
    }

    private Object query(QueryTask task) throws SQLException {
        try (Connection conn = logDatabaseManager.getConnection()) {
            return task.run(conn);
        }
    }

    /** 取回并发结果：SQL 失败原样抛出（与串行版一致），中断则恢复中断标记。 */
    private static Object await(Future<Object> future) throws SQLException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("查询访问日志统计被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("查询访问日志统计失败: " + cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** 删除超过保留期的记录，返回删除条数。 */
    public int purgeOlderThan(int retentionDays) throws SQLException {
        if (retentionDays <= 0) {
            return 0;
        }
        long cutoff = LocalDate.now(ZONE).minusDays(retentionDays).atStartOfDay(ZONE).toInstant().toEpochMilli();
        try (Connection conn = logDatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM access_log WHERE occurred_at < ?")) {
            stmt.setLong(1, cutoff);
            return stmt.executeUpdate();
        }
    }

    /** 按天聚合的访问趋势；窗口内没有数据的日期补 0，保证前端折线不断点。 */
    private static List<Map<String, Object>> dailyTrend(Connection conn, long windowStart, int days)
            throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        String sql = "SELECT DATE(FROM_UNIXTIME(occurred_at / 1000)) AS d, COUNT(*) AS c FROM access_log "
                + "WHERE occurred_at >= ? GROUP BY d";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, windowStart);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("d"), rs.getLong("c"));
                }
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = days - 1; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("count", counts.getOrDefault(date, 0L));
            trend.add(row);
        }
        return trend;
    }

    private static Map<String, Long> countByQuery(Connection conn, String sql, long param) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                return LogQuerySupport.countBy(rs, "k", "c");
            }
        }
    }

    private static long scalar(Connection conn, String sql, Long param) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (param != null) {
                stmt.setLong(1, param);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static List<Map<String, Object>> grouped(Connection conn, String sql, long param) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", rs.getString("k"));
                    row.put("count", rs.getLong("c"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static void appendEquals(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    private static void appendLike(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" LIKE ? ESCAPE '\\\\'");
            params.add(LogQuerySupport.likePattern(value));
        }
    }

    private static void appendKeyword(StringBuilder where, List<Object> params, String keyword, String... columns) {
        if (keyword == null) {
            return;
        }
        StringBuilder clause = new StringBuilder(" AND (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                clause.append(" OR ");
            }
            clause.append(columns[i]).append(" LIKE ? ESCAPE '\\\\'");
            params.add(LogQuerySupport.likePattern(keyword));
        }
        clause.append(')');
        where.append(clause);
    }

    private static int bind(PreparedStatement stmt, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            if (param instanceof Integer intValue) {
                stmt.setInt(index++, intValue);
            } else {
                stmt.setObject(index++, param);
            }
        }
        return index;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
