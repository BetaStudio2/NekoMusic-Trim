package com.neko.music.database;

import com.neko.music.util.DbTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌曲评论的读写。
 *
 * <p>结构上只有两层：{@code parent_id IS NULL} 为顶层楼层，否则为该楼层的回复；
 * 回复再回复仍然挂回同一楼层，用 {@code reply_to_user_id} 记录被 @ 的人。
 * 删除是物理删除：删楼层会连带删掉该楼层下全部回复，删回复只删该条。
 */
public class CommentDatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(CommentDatabaseManager.class);

    private static final String SQL_SELECT_BASE = """
            SELECT c.id, c.music_id, c.user_id, c.parent_id, c.reply_to_user_id,
                   c.content, c.ip_region, c.client_ip, c.created_at
            FROM music_comments c
            """;

    private final DatabaseManager databaseManager;

    public CommentDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /** 一行评论；昵称由调用方从 NekoAuth 实时解析。 */
    public static final class CommentRow {
        public int id;
        public int musicId;
        public int userId;
        public Integer parentId;
        public Integer replyToUserId;
        public String content;
        public String ipRegion;
        public String clientIp;
        public String createdAt;
    }

    /** 顶层楼层数量（分页用）。 */
    public int countTopLevel(int musicId) {
        String sql = "SELECT COUNT(*) FROM music_comments WHERE music_id = ? AND parent_id IS NULL";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.error("统计评论楼层失败: musicId={}, {}", musicId, e.getMessage());
            return 0;
        }
    }

    /** 评论总数（顶层 + 回复）。 */
    public int countAll(int musicId) {
        String sql = "SELECT COUNT(*) FROM music_comments WHERE music_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            logger.error("统计评论总数失败: musicId={}, {}", musicId, e.getMessage());
            return 0;
        }
    }

    /** 分页查询顶层楼层，按时间倒序（新评论在前）。 */
    public List<CommentRow> listTopLevel(int musicId, int limit, int offset) {
        String sql = SQL_SELECT_BASE + """
                WHERE c.music_id = ? AND c.parent_id IS NULL
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT ? OFFSET ?
                """;
        List<CommentRow> rows = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询评论楼层失败: musicId={}, {}", musicId, e.getMessage());
        }
        return rows;
    }

    /** 查询若干楼层下的全部回复，按时间正序（先回复的在前）。 */
    public List<CommentRow> listReplies(int musicId, List<Integer> rootIds) {
        if (rootIds == null || rootIds.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < rootIds.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        String sql = SQL_SELECT_BASE + "WHERE c.music_id = ? AND c.parent_id IN (" + in
                + ") ORDER BY c.created_at ASC, c.id ASC";
        List<CommentRow> rows = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setInt(idx++, musicId);
            for (Integer id : rootIds) {
                stmt.setInt(idx++, id);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询评论回复失败: musicId={}, {}", musicId, e.getMessage());
        }
        return rows;
    }

    /** 各楼层的回复数量（key 为楼层 id）。 */
    public Map<Integer, Integer> countReplies(int musicId, List<Integer> rootIds) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (rootIds == null || rootIds.isEmpty()) {
            return counts;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < rootIds.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT parent_id, COUNT(*) FROM music_comments "
                + "WHERE music_id = ? AND parent_id IN (" + in + ") GROUP BY parent_id";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setInt(idx++, musicId);
            for (Integer id : rootIds) {
                stmt.setInt(idx++, id);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            logger.error("统计楼层回复数失败: musicId={}, {}", musicId, e.getMessage());
        }
        return counts;
    }

    /** 按 id 查询单条评论，不存在时返回 null。 */
    public CommentRow findById(int commentId) {
        String sql = SQL_SELECT_BASE + "WHERE c.id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, commentId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            logger.error("查询评论失败: id={}, {}", commentId, e.getMessage());
            return null;
        }
    }

    /** 发表评论或回复，成功返回新评论 id，失败返回 -1。 */
    public int insert(int musicId, int userId, Integer parentId, Integer replyToUserId,
                      String content, String ipRegion, String clientIp) {
        String sql = "INSERT INTO music_comments "
                + "(music_id, user_id, parent_id, reply_to_user_id, content, ip_region, client_ip, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, " + DbTimeUtil.SQL_NOW_SHANGHAI + ")";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, musicId);
            stmt.setInt(2, userId);
            if (parentId == null) {
                stmt.setNull(3, java.sql.Types.INTEGER);
            } else {
                stmt.setInt(3, parentId);
            }
            if (replyToUserId == null) {
                stmt.setNull(4, java.sql.Types.INTEGER);
            } else {
                stmt.setInt(4, replyToUserId);
            }
            stmt.setString(5, content);
            stmt.setString(6, ipRegion == null ? "" : ipRegion);
            stmt.setString(7, clientIp == null ? "" : clientIp);
            int affected = stmt.executeUpdate();
            if (affected <= 0) {
                return -1;
            }
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            logger.error("插入评论失败: musicId={}, userId={}, {}", musicId, userId, e.getMessage());
            return -1;
        }
    }

    /**
     * 物理删除评论：本人可删，管理员 {@code admin=true} 可删任意一条。
     *
     * <p>{@code parentId} 为 null 表示楼层，删除时会在同一事务里把该楼层下的回复一并删除，
     * 避免留下查不到、却仍计入总数的孤儿回复。
     *
     * @return 实际删除的行数（楼层含被连带删除的回复），无权限或不存在返回 0
     */
    public int delete(int commentId, Integer parentId, int userId, boolean admin) {
        String sql = admin
                ? "DELETE FROM music_comments WHERE id = ?"
                : "DELETE FROM music_comments WHERE id = ? AND user_id = ?";
        try (Connection conn = databaseManager.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int affected;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, commentId);
                    if (!admin) {
                        stmt.setInt(2, userId);
                    }
                    affected = stmt.executeUpdate();
                }
                if (affected <= 0) {
                    conn.rollback();
                    return 0;
                }
                if (parentId == null) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM music_comments WHERE parent_id = ?")) {
                        stmt.setInt(1, commentId);
                        affected += stmt.executeUpdate();
                    }
                }
                conn.commit();
                return affected;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            logger.error("删除评论失败: id={}, {}", commentId, e.getMessage());
            return 0;
        }
    }

    private static CommentRow mapRow(ResultSet rs) throws SQLException {
        CommentRow row = new CommentRow();
        row.id = rs.getInt("id");
        row.musicId = rs.getInt("music_id");
        row.userId = rs.getInt("user_id");
        int parent = rs.getInt("parent_id");
        row.parentId = rs.wasNull() ? null : parent;
        int replyTo = rs.getInt("reply_to_user_id");
        row.replyToUserId = rs.wasNull() ? null : replyTo;
        row.content = rs.getString("content");
        row.ipRegion = rs.getString("ip_region");
        row.clientIp = rs.getString("client_ip");
        row.createdAt = DbTimeUtil.formatStoredWallClock(rs.getString("created_at"));
        return row;
    }
}
