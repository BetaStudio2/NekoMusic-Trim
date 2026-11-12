package com.neko.music.database;

import com.neko.music.util.JapaneseRomanizer;
import com.neko.music.util.LrcValidator;
import com.neko.music.util.LyricsPlaceholderFilter;
import com.neko.music.util.LyricsPlainTextExtractor;
import com.neko.music.util.NekoLyrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class LyricsDatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(LyricsDatabaseManager.class);

    /**
     * 进程内歌词缓存条目上限与兜底 TTL。
     *
     * <p>失效有三条路：{@link #upsert} / {@link #delete} 主动失效；{@code music} 行被级联删除时
     * 不经过本类，靠 TTL 自愈；条目数超限时随机淘汰一条（歌词是只读热点，不需要 LRU 精度）。
     */
    private static final int CACHE_MAX_ENTRIES = 2048;
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private final DatabaseManager databaseManager;
    private final Map<Integer, CachedLyrics> lyricsCache = new ConcurrentHashMap<>();

    private record CachedLyrics(StoredLyrics lyrics, long expiresAt) {
    }

    public LyricsDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 带进程内缓存的读取：公开歌词接口按同一个 musicId 高频复读，命中可省掉一次连接池借出 + SELECT。
     * 管理端读改写路径仍用 {@link #findByMusicId}，保证拿到的一定是库里的最新值。
     */
    public Optional<StoredLyrics> findByMusicIdCached(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        CachedLyrics cached = lyricsCache.get(musicId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return Optional.of(cached.lyrics());
        }
        Optional<StoredLyrics> loaded = findByMusicId(musicId);
        loaded.ifPresent(lyrics -> {
            if (lyricsCache.size() >= CACHE_MAX_ENTRIES) {
                Iterator<Integer> it = lyricsCache.keySet().iterator();
                if (it.hasNext()) {
                    lyricsCache.remove(it.next());
                }
            }
            lyricsCache.put(musicId, new CachedLyrics(lyrics, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        });
        return loaded;
    }

    private void invalidateCached(int musicId) {
        lyricsCache.remove(musicId);
    }

    public Optional<StoredLyrics> findByMusicId(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        String sql = """
                SELECT music_id, content, plain_text, content_hash, format, is_placeholder,
                       size_bytes, created_at, updated_at
                FROM music_lyrics
                WHERE music_id = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapStoredLyrics(rs));
            }
        } catch (SQLException e) {
            logger.warn("读取歌词失败 musicId={}: {}", musicId, e.toString());
            return Optional.empty();
        }
    }

    public List<AdminLyricsMeta> findAllForAdmin() {
        String sql = """
                SELECT ml.music_id, m.title, m.artist, ml.size_bytes, ml.updated_at, ml.is_placeholder
                FROM music_lyrics ml
                LEFT JOIN music m ON m.id = ml.music_id
                ORDER BY ml.music_id
                """;
        List<AdminLyricsMeta> rows = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                rows.add(new AdminLyricsMeta(
                        rs.getInt("music_id"),
                        rs.getString("title"),
                        rs.getString("artist"),
                        rs.getLong("size_bytes"),
                        updatedAt,
                        rs.getBoolean("is_placeholder"),
                        rs.getString("title") != null
                ));
            }
        } catch (SQLException e) {
            logger.warn("读取管理端歌词列表失败: {}", e.toString());
        }
        return rows;
    }

    public boolean upsert(int musicId, String content) {
        return upsert(musicId, content, "lrc");
    }

    public boolean upsert(int musicId, String content, String format) {
        try (Connection conn = databaseManager.getConnection()) {
            upsert(conn, musicId, content, format);
            return true;
        } catch (SQLException e) {
            logger.warn("保存歌词失败 musicId={}: {}", musicId, e.toString());
            return false;
        }
    }

    public void upsert(Connection conn, int musicId, String content) throws SQLException {
        upsert(conn, musicId, content, "lrc");
    }

    public void upsert(Connection conn, int musicId, String content, String format) throws SQLException {
        if (musicId <= 0 || content == null) {
            throw new SQLException("无效的歌词参数");
        }
        String normalizedFormat = format == null || format.isBlank() ? "lrc" : format.trim();
        boolean japanese = JapaneseRomanizer.isJapaneseLanguage(findLanguage(conn, musicId));
        String plain;
        boolean placeholder;
        if (NekoLyrics.isNekoFormat(normalizedFormat)) {
            NekoLyrics.Document document = NekoLyrics.deserialize(content)
                    .orElseThrow(() -> new SQLException("Neko 歌词格式无效"));
            if (japanese) {
                document = JapaneseRomanizer.enforceRomanization(document);
            }
            content = NekoLyrics.serialize(document);
            plain = NekoLyrics.plainText(document);
            placeholder = LyricsPlainTextExtractor.isPlaceholder(plain);
        } else {
            content = LrcValidator.normalizeDuplicateTimestampTranslations(
                    LyricsPlaceholderFilter.filterLrc(content));
            content = LyricsPlaceholderFilter.filterLrc(content);
            if (japanese) {
                NekoLyrics.Document document = NekoLyrics.fromLrc(content)
                        .orElseThrow(() -> new SQLException("日语歌词无法解析为可补全罗马音的结构"));
                document = JapaneseRomanizer.enforceRomanization(document);
                normalizedFormat = NekoLyrics.FORMAT;
                content = NekoLyrics.serialize(document);
                plain = NekoLyrics.plainText(document);
                placeholder = LyricsPlainTextExtractor.isPlaceholder(plain);
            } else {
                plain = LyricsPlainTextExtractor.fromLrc(content);
                placeholder = LyricsPlainTextExtractor.isPlaceholder(plain);
            }
        }
        int sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
        String hash = sha256Hex(content);
        String sql = """
                INSERT INTO music_lyrics
                    (music_id, content, plain_text, content_hash, format, is_placeholder, size_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    content = VALUES(content),
                    plain_text = VALUES(plain_text),
                    content_hash = VALUES(content_hash),
                    format = VALUES(format),
                    is_placeholder = VALUES(is_placeholder),
                    size_bytes = VALUES(size_bytes),
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            stmt.setString(2, content);
            stmt.setString(3, plain);
            stmt.setString(4, hash);
            stmt.setString(5, normalizedFormat);
            stmt.setBoolean(6, placeholder);
            stmt.setInt(7, sizeBytes);
            stmt.executeUpdate();
        }
        invalidateCached(musicId);
    }

    private static String findLanguage(Connection conn, int musicId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT language FROM music WHERE id = ?")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("language") : null;
            }
        }
    }

    public boolean delete(int musicId) {
        if (musicId <= 0) {
            return false;
        }
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM music_lyrics WHERE music_id = ?")) {
            stmt.setInt(1, musicId);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("删除数据库歌词失败 musicId={}: {}", musicId, e.toString());
            return false;
        } finally {
            invalidateCached(musicId);
        }
    }

    private static StoredLyrics mapStoredLyrics(ResultSet rs) throws SQLException {
        return new StoredLyrics(
                rs.getInt("music_id"),
                rs.getString("content"),
                rs.getString("plain_text"),
                rs.getString("content_hash"),
                rs.getString("format"),
                rs.getBoolean("is_placeholder"),
                rs.getInt("size_bytes"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at")
        );
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record StoredLyrics(
            int musicId,
            String content,
            String plainText,
            String contentHash,
            String format,
            boolean placeholder,
            int sizeBytes,
            Timestamp createdAt,
            Timestamp updatedAt
    ) {
        public String lrcContent() {
            return NekoLyrics.toLrc(content, format);
        }

        public Optional<NekoLyrics.Document> richLyrics() {
            return NekoLyrics.isNekoFormat(format)
                    ? NekoLyrics.deserialize(content)
                    : Optional.empty();
        }
    }

    public record AdminLyricsMeta(
            int musicId,
            String title,
            String artist,
            long sizeBytes,
            Timestamp updatedAt,
            boolean placeholder,
            boolean existsInDb
    ) {
    }
}
