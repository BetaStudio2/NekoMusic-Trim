package com.neko.music.database;

import com.neko.music.util.DbTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 站内消息（收件箱）读写。
 *
 * <p>消息落库即视为送达：SSE 只是「在线时提前告知」，离线用户下次带游标补拉即可，
 * 因此这里没有投递确认与重投。分页与补拉统一用自增主键 {@code id} 当游标
 * （用时间戳在同秒并发下会漏消息）。</p>
 */
public class UserNotificationDatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(UserNotificationDatabaseManager.class);

    /** 正文最长保留长度：超长评论只截断入库，收件箱不做全文存储。 */
    private static final int MAX_BODY_LENGTH = 200;

    private final DatabaseManager databaseManager;

    public UserNotificationDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** 一行站内消息；触发者昵称由调用方从 NekoAuth 实时解析。 */
    public static final class NotificationRow {
        public int id;
        public String type;
        public String title;
        public String body;
        public String link;
        public Integer actorUserId;
        public boolean read;
        public String createdAt;
    }

    /** 查询结果：本次返回的行 + 是否还有更早的记录。 */
    public static final class Page {
        public final List<NotificationRow> rows;
        public final boolean hasMore;

        public Page(List<NotificationRow> rows, boolean hasMore) {
            this.rows = rows;
            this.hasMore = hasMore;
        }
    }

    /** 写入一条站内消息，成功返回新记录 id，失败返回 -1。 */
    public int insert(int userId, String type, String title, String body, String link, Integer actorUserId) {
        String sql = "INSERT INTO user_notifications "
                + "(user_id, type, title, body, link, actor_user_id, is_read, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, " + DbTimeUtil.SQL_NOW_SHANGHAI + ")";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, userId);
            stmt.setString(2, type);
            stmt.setString(3, clamp(title, 128));
            stmt.setString(4, clamp(body, MAX_BODY_LENGTH));
            stmt.setString(5, link == null ? "" : clamp(link, 255));
            if (actorUserId == null) {
                stmt.setNull(6, Types.INTEGER);
            } else {
                stmt.setInt(6, actorUserId);
            }
            if (stmt.executeUpdate() <= 0) {
                return -1;
            }
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            logger.error("写入站内消息失败: userId={}, type={}, {}", userId, type, e.getMessage());
            return -1;
        }
    }

    /**
     * 查询收件箱（按 id 倒序，新的在前）。
     *
     * @param sinceId  只取比它更新的消息（离线补拉），null 或 0 表示不限
     * @param beforeId 只取比它更早的消息（翻历史），null 或 0 表示不限
     */
    public Page list(int userId, Integer sinceId, Integer beforeId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT n.id, n.type, n.title, n.body, n.link, n.actor_user_id, n.is_read, n.created_at
                FROM user_notifications n
                WHERE n.user_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (sinceId != null && sinceId > 0) {
            sql.append(" AND n.id > ?");
            params.add(sinceId);
        }
        if (beforeId != null && beforeId > 0) {
            sql.append(" AND n.id < ?");
            params.add(beforeId);
        }
        sql.append(" ORDER BY n.id DESC LIMIT ?");
        params.add(limit + 1);

        List<NotificationRow> rows = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询站内消息失败: userId={}, {}", userId, e.getMessage());
            return new Page(List.of(), false);
        }
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new Page(rows, hasMore);
    }

    /** 未读条数。 */
    public int countUnread(int userId) {
        String sql = "SELECT COUNT(*) FROM user_notifications WHERE user_id = ? AND is_read = 0";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.error("统计未读站内消息失败: userId={}, {}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 标记已读：{@code ids} 为空表示全部已读。
     *
     * @return 实际更新的行数
     */
    public int markRead(int userId, List<Integer> ids) {
        boolean all = ids == null || ids.isEmpty();
        StringBuilder sql = new StringBuilder(
                "UPDATE user_notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0");
        if (!all) {
            StringJoiner placeholders = new StringJoiner(",");
            for (int i = 0; i < ids.size(); i++) {
                placeholders.add("?");
            }
            sql.append(" AND id IN (").append(placeholders).append(')');
        }
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setInt(1, userId);
            if (!all) {
                for (int i = 0; i < ids.size(); i++) {
                    stmt.setInt(i + 2, ids.get(i));
                }
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("标记站内消息已读失败: userId={}, {}", userId, e.getMessage());
            return 0;
        }
    }

    private static NotificationRow mapRow(ResultSet rs) throws SQLException {
        NotificationRow row = new NotificationRow();
        row.id = rs.getInt("id");
        row.type = rs.getString("type");
        row.title = rs.getString("title");
        row.body = rs.getString("body");
        row.link = rs.getString("link");
        int actorId = rs.getInt("actor_user_id");
        row.actorUserId = rs.wasNull() ? null : actorId;
        row.read = rs.getInt("is_read") != 0;
        row.createdAt = DbTimeUtil.formatStoredWallClock(rs.getString("created_at"));
        return row;
    }

    private static String clamp(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
