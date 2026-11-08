package com.neko.music.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 曲库歌曲与网易云 / QQ 音乐外部 ID 的持久化映射。 */
public class MusicExternalRefDatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(MusicExternalRefDatabaseManager.class);

    private final DatabaseManager databaseManager;

    public MusicExternalRefDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public record ExternalRef(
            int musicId,
            String source,
            String externalId,
            String secondaryId,
            String title,
            String artist,
            String album,
            int durationMs
    ) {
    }

    public void upsert(int musicId, String source, String externalId, String secondaryId,
                       String title, String artist, String album, int durationMs) {
        if (musicId <= 0 || source == null || source.isBlank()
                || externalId == null || externalId.isBlank()) {
            return;
        }
        String sql = """
                INSERT INTO music_external_refs
                    (music_id, source, external_id, secondary_id, title_snapshot,
                     artist_snapshot, album_snapshot, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    secondary_id = VALUES(secondary_id),
                    title_snapshot = VALUES(title_snapshot),
                    artist_snapshot = VALUES(artist_snapshot),
                    album_snapshot = VALUES(album_snapshot),
                    duration_ms = VALUES(duration_ms)
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            stmt.setString(2, source.trim().toLowerCase());
            stmt.setString(3, externalId.trim());
            stmt.setString(4, blankToNull(secondaryId));
            stmt.setString(5, blankToNull(title));
            stmt.setString(6, blankToNull(artist));
            stmt.setString(7, blankToNull(album));
            stmt.setInt(8, Math.max(0, durationMs));
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.warn("保存外站歌曲映射失败 musicId={} source={} externalId={}: {}",
                    musicId, source, externalId, e.getMessage());
        }
    }

    public Map<String, ExternalRef> findByMusicId(int musicId) {
        String sql = """
                SELECT music_id, source, external_id, secondary_id, title_snapshot,
                       artist_snapshot, album_snapshot, duration_ms
                FROM music_external_refs
                WHERE music_id = ?
                """;
        Map<String, ExternalRef> refs = new LinkedHashMap<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ExternalRef ref = map(rs);
                    refs.put(ref.source(), ref);
                }
            }
        } catch (SQLException e) {
            logger.warn("读取外站歌曲映射失败 musicId={}: {}", musicId, e.getMessage());
        }
        return refs;
    }

    public Optional<ExternalRef> findByExternalId(String source, String externalId) {
        if (source == null || source.isBlank() || externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT music_id, source, external_id, secondary_id, title_snapshot,
                       artist_snapshot, album_snapshot, duration_ms
                FROM music_external_refs
                WHERE source = ? AND external_id = ?
                LIMIT 1
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, source.trim().toLowerCase());
            stmt.setString(2, externalId.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            logger.warn("按外站 ID 查询映射失败 source={} externalId={}: {}",
                    source, externalId, e.getMessage());
            return Optional.empty();
        }
    }

    private static ExternalRef map(ResultSet rs) throws SQLException {
        return new ExternalRef(
                rs.getInt("music_id"),
                rs.getString("source"),
                rs.getString("external_id"),
                rs.getString("secondary_id"),
                rs.getString("title_snapshot"),
                rs.getString("artist_snapshot"),
                rs.getString("album_snapshot"),
                rs.getInt("duration_ms")
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
