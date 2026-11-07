package com.neko.music.service;

import com.neko.music.database.LogDatabaseManager;
import com.neko.music.model.LogPage;
import com.neko.music.model.OperationLogRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 操作日志：记录管理后台的写操作（谁在什么时间、对哪个模块做了什么、结果如何）。
 *
 * <p>写入异步批量，读取供管理员及以上在后台检索。与访问日志同库不同表，落在独立日志库。</p>
 */
public class OperationLogService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private static final String INSERT_SQL = """
            INSERT INTO operation_log (occurred_at, admin_id, admin_username, admin_role, module, action,
                method, path, query, status, success, duration_us, client_ip, user_agent, detail)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static volatile OperationLogService current;

    private final LogDatabaseManager logDatabaseManager;
    private final LogBatchWriter<OperationLogRecord> writer;

    public OperationLogService(LogDatabaseManager logDatabaseManager) {
        this.logDatabaseManager = logDatabaseManager;
        this.writer = new LogBatchWriter<>("operation-log", this::insertBatch);
    }

    public void start() {
        writer.start();
    }

    /** 停止写入线程并把队列里的存量刷盘。 */
    public void close() {
        writer.close();
    }

    /** 记录一条操作日志；未启用或队列满时静默丢弃，绝不阻塞管理操作。 */
    public static void record(OperationLogRecord record) {
        OperationLogService service = current;
        if (service != null) {
            service.writer.offer(record);
        }
    }

    public static OperationLogService current() {
        return current;
    }

    public static void setCurrent(OperationLogService service) {
        current = service;
    }

    private void insertBatch(List<OperationLogRecord> batch) throws SQLException {
        try (Connection conn = logDatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            for (OperationLogRecord record : batch) {
                bindInsert(stmt, record);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private static void bindInsert(PreparedStatement stmt, OperationLogRecord r) throws SQLException {
        int i = 1;
        stmt.setLong(i++, r.occurredAt());
        if (r.adminId() == null) {
            stmt.setNull(i++, Types.INTEGER);
        } else {
            stmt.setInt(i++, r.adminId());
        }
        stmt.setString(i++, LogQuerySupport.truncate(r.adminUsername(), 255));
        stmt.setString(i++, LogQuerySupport.truncate(r.adminRole(), 32));
        stmt.setString(i++, LogQuerySupport.truncate(r.module(), 64));
        stmt.setString(i++, LogQuerySupport.truncate(r.action(), 64));
        stmt.setString(i++, LogQuerySupport.truncate(r.method(), 16));
        stmt.setString(i++, LogQuerySupport.truncate(r.path(), 512));
        stmt.setString(i++, LogQuerySupport.truncate(r.query(), 2048));
        stmt.setInt(i++, r.status());
        stmt.setBoolean(i++, r.success());
        stmt.setLong(i++, r.durationUs());
        stmt.setString(i++, LogQuerySupport.truncate(r.clientIp(), 64));
        stmt.setString(i++, LogQuerySupport.truncate(r.userAgent(), 1024));
        stmt.setString(i, LogQuerySupport.truncate(r.detail(), 1024));
    }

    /** 按条件分页检索操作日志。 */
    public LogPage list(int page, int size, Integer adminId, String module,
                        Long fromMs, Long toMs, String keyword) throws SQLException {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (adminId != null) {
            where.append(" AND admin_id = ?");
            params.add(adminId);
        }
        String normalizedModule = blankToNull(module);
        if (normalizedModule != null) {
            where.append(" AND module = ?");
            params.add(normalizedModule);
        }
        if (fromMs != null) {
            where.append(" AND occurred_at >= ?");
            params.add(fromMs);
        }
        if (toMs != null) {
            where.append(" AND occurred_at <= ?");
            params.add(toMs);
        }
        String normalizedKeyword = blankToNull(keyword);
        if (normalizedKeyword != null) {
            where.append(" AND (path LIKE ? ESCAPE '\\\\' OR query LIKE ? ESCAPE '\\\\'")
                    .append(" OR admin_username LIKE ? ESCAPE '\\\\' OR detail LIKE ? ESCAPE '\\\\')");
            String pattern = LogQuerySupport.likePattern(normalizedKeyword);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        long total;
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = logDatabaseManager.getConnection()) {
            try (PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM operation_log" + where)) {
                bind(countStmt, params);
                try (ResultSet rs = countStmt.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            String sql = "SELECT * FROM operation_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
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

    /** 删除超过保留期的记录，返回删除条数。 */
    public int purgeOlderThan(int retentionDays) throws SQLException {
        if (retentionDays <= 0) {
            return 0;
        }
        long cutoff = LocalDate.now(ZONE).minusDays(retentionDays).atStartOfDay(ZONE).toInstant().toEpochMilli();
        try (Connection conn = logDatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM operation_log WHERE occurred_at < ?")) {
            stmt.setLong(1, cutoff);
            return stmt.executeUpdate();
        }
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
