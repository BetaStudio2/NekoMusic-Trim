package com.neko.music.database;

import com.neko.music.util.DbTimeUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 用户歌词报错与后台审核记录。 */
public final class LyricsReportDatabaseManager {
    private final DatabaseManager databaseManager;

    public LyricsReportDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public record Report(
            long id,
            int musicId,
            String title,
            String artist,
            int userId,
            String status,
            String format,
            String content,
            String originalContent,
            String originalFormat,
            String note,
            String reviewNote,
            Integer reviewerId,
            Timestamp createdAt,
            Timestamp reviewedAt
    ) {
    }

    public Optional<Long> findPendingId(int userId, int musicId) throws SQLException {
        String sql = """
                SELECT id FROM lyrics_reports
                WHERE user_id = ? AND music_id = ? AND status = 'pending'
                ORDER BY id DESC LIMIT 1
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public long create(
            int musicId,
            int userId,
            String content,
            String originalContent,
            String originalFormat,
            String note
    ) throws SQLException {
        String sql = """
                INSERT INTO lyrics_reports
                    (music_id, user_id, status, format, content, original_content, original_format, note)
                VALUES (?, ?, 'pending', 'neko-v1', ?, ?, ?, ?)
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, musicId);
            stmt.setInt(2, userId);
            stmt.setString(3, content);
            stmt.setString(4, originalContent);
            stmt.setString(5, originalFormat);
            stmt.setString(6, note);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("未返回歌词报错记录 ID");
                }
                return keys.getLong(1);
            }
        }
    }

    public List<Report> listPending() throws SQLException {
        String sql = """
                SELECT r.id, r.music_id, m.title, m.artist, r.user_id, r.status, r.format,
                       r.content, r.original_content, r.original_format, r.note, r.review_note,
                       r.reviewer_id, r.created_at, r.reviewed_at
                FROM lyrics_reports r
                JOIN music m ON m.id = r.music_id
                WHERE r.status = 'pending'
                ORDER BY r.created_at ASC, r.id ASC
                """;
        List<Report> rows = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                rows.add(map(rs));
            }
        }
        return rows;
    }

    public Optional<Report> findById(long id) throws SQLException {
        String sql = """
                SELECT r.id, r.music_id, m.title, m.artist, r.user_id, r.status, r.format,
                       r.content, r.original_content, r.original_format, r.note, r.review_note,
                       r.reviewer_id, r.created_at, r.reviewed_at
                FROM lyrics_reports r
                JOIN music m ON m.id = r.music_id
                WHERE r.id = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** 审核事务内锁定待审核记录，避免两个管理员同时通过。 */
    public Optional<Report> findPendingForUpdate(Connection conn, long id) throws SQLException {
        String sql = """
                SELECT r.id, r.music_id, m.title, m.artist, r.user_id, r.status, r.format,
                       r.content, r.original_content, r.original_format, r.note, r.review_note,
                       r.reviewer_id, r.created_at, r.reviewed_at
                FROM lyrics_reports r
                JOIN music m ON m.id = r.music_id
                WHERE r.id = ? AND r.status = 'pending'
                FOR UPDATE
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean markReviewed(
            Connection conn,
            long id,
            String status,
            int reviewerId,
            String reviewNote
    ) throws SQLException {
        String sql = """
                UPDATE lyrics_reports
                SET status = ?, reviewer_id = ?, review_note = ?,
                    reviewed_at = %s
                WHERE id = ? AND status = 'pending'
                """.formatted(DbTimeUtil.SQL_NOW_SHANGHAI);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setInt(2, reviewerId);
            stmt.setString(3, reviewNote);
            stmt.setLong(4, id);
            return stmt.executeUpdate() == 1;
        }
    }

    private static Report map(ResultSet rs) throws SQLException {
        return new Report(
                rs.getLong("id"),
                rs.getInt("music_id"),
                rs.getString("title"),
                rs.getString("artist"),
                rs.getInt("user_id"),
                rs.getString("status"),
                rs.getString("format"),
                rs.getString("content"),
                rs.getString("original_content"),
                rs.getString("original_format"),
                rs.getString("note"),
                rs.getString("review_note"),
                (Integer) rs.getObject("reviewer_id"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("reviewed_at")
        );
    }
}
