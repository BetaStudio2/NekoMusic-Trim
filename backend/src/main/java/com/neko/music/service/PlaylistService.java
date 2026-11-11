package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.model.Playlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlaylistService {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistService.class);

    public enum AppendMusicResult {
        ADDED,
        ALREADY_EXISTS,
        PLAYLIST_MISSING,
        FAILED
    }

    /**
     * 歌单搜索：先 LIMIT 候选歌单，再通过一次聚合 JOIN 取首曲，避免对每个歌单执行相关子查询。
     */
    private static final String SQL_PLAYLIST_SEARCH_FIRST_TRACK_JOIN = """
        LEFT JOIN (
            SELECT pm.playlist_id, pm.music_id AS first_music_id
            FROM playlist_music pm
            INNER JOIN music m ON m.id = pm.music_id
            INNER JOIN (
                SELECT playlist_id, MIN(position) AS min_pos
                FROM playlist_music
                GROUP BY playlist_id
            ) pm_min ON pm_min.playlist_id = pm.playlist_id AND pm_min.min_pos = pm.position
        ) ft ON ft.playlist_id = pl.id
        ORDER BY pl.created_at DESC
        """;

    private static final String SQL_PLAYLIST_COLUMNS =
            "SELECT id, user_id, name, description, music_count, created_at, updated_at FROM playlists";

    private final DatabaseManager databaseManager;

    public PlaylistService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 创建歌单
     */
    public Optional<Playlist> createPlaylist(int userId, String name, String description) {
        logger.info("创建歌单: userId={}, name={}", userId, name);

        String sql = "INSERT INTO playlists (user_id, name, description, name_pinyin, name_pinyin_initials, name_word_initials, music_count) VALUES (?, ?, ?, ?, ?, ?, 0)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setInt(1, userId);
            stmt.setString(2, name);
            stmt.setString(3, description);
            stmt.setString(4, com.neko.music.util.PinyinUtil.getPinyin(name));
            stmt.setString(5, com.neko.music.util.PinyinUtil.getPinyinInitials(name));
            stmt.setString(6, com.neko.music.util.PinyinUtil.getWordInitials(name));

            int affectedRows = stmt.executeUpdate();

            if (affectedRows > 0) {
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int playlistId = generatedKeys.getInt(1);
                        Optional<Playlist> playlistOpt = getPlaylistById(playlistId);
                        if (playlistOpt.isPresent()) {
                            logger.info("歌单创建成功: id={}", playlistId);
                            return playlistOpt;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("创建歌单失败: {}", e.getMessage(), e);
        }

        return Optional.empty();
    }

    /**
     * 获取用户的所有歌单
     */
    public List<Playlist> getUserPlaylists(int userId) {
        logger.debug("获取用户歌单: userId={}", userId);

        List<Playlist> playlists = new ArrayList<>();
        String sql = SQL_PLAYLIST_COLUMNS + " WHERE user_id = ? ORDER BY created_at DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                playlists.add(mapPlaylistRow(rs));
            }

            logger.debug("获取到 {} 个歌单", playlists.size());
        } catch (SQLException e) {
            logger.error("获取用户歌单失败: {}", e.getMessage(), e);
        }

        return playlists;
    }

    /**
     * 获取所有歌单
     */
    public List<Playlist> getAllPlaylists() {
        logger.info("获取所有歌单");

        List<Playlist> playlists = new ArrayList<>();
        String sql = SQL_PLAYLIST_COLUMNS + " ORDER BY created_at DESC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                playlists.add(mapPlaylistRow(rs));
            }

            logger.debug("获取到 {} 个歌单", playlists.size());
        } catch (SQLException e) {
            logger.error("获取所有歌单失败: {}", e.getMessage(), e);
        }

        return playlists;
    }

    /**
     * 根据ID获取歌单
     */
    public Optional<Playlist> getPlaylistById(int playlistId) {
        logger.info("获取歌单详情: id={}", playlistId);

        String sql = SQL_PLAYLIST_COLUMNS + " WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, playlistId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                Playlist playlist = mapPlaylistRow(rs);

                logger.info("歌单详情获取成功: id={}", playlistId);
                return Optional.of(playlist);
            }
        } catch (SQLException e) {
            logger.error("获取歌单详情失败: {}", e.getMessage(), e);
        }

        return Optional.empty();
    }

    /**
     * 更新歌单信息
     */
    public boolean updatePlaylist(int playlistId, String name, String description) {
        logger.info("更新歌单: id={}, name={}", playlistId, name);

        String sql = "UPDATE playlists SET name = ?, description = ?, name_pinyin = ?, name_pinyin_initials = ?, name_word_initials = ? WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, name);
            stmt.setString(2, description);
            stmt.setString(3, com.neko.music.util.PinyinUtil.getPinyin(name));
            stmt.setString(4, com.neko.music.util.PinyinUtil.getPinyinInitials(name));
            stmt.setString(5, com.neko.music.util.PinyinUtil.getWordInitials(name));
            stmt.setInt(6, playlistId);

            int affectedRows = stmt.executeUpdate();
            boolean success = affectedRows > 0;

            if (success) {
                logger.info("歌单更新成功: id={}", playlistId);
            } else {
                logger.warn("歌单更新失败: id={}", playlistId);
            }

            return success;
        } catch (SQLException e) {
            logger.error("更新歌单失败: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 添加音乐到歌单
     */
    public boolean addMusicToPlaylist(int playlistId, int musicId) {
        logger.info("添加音乐到歌单: playlistId={}, musicId={}", playlistId, musicId);

        // 检查音乐是否已经在歌单中
        String checkSql = "SELECT COUNT(*) FROM playlist_music WHERE playlist_id = ? AND music_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {

            checkStmt.setInt(1, playlistId);
            checkStmt.setInt(2, musicId);
            ResultSet rs = checkStmt.executeQuery();

            if (rs.next() && rs.getInt(1) > 0) {
                logger.warn("音乐已存在于歌单中: playlistId={}, musicId={}", playlistId, musicId);
                return false;
            }
        } catch (SQLException e) {
            logger.error("检查音乐是否存在失败: {}", e.getMessage(), e);
            return false;
        }

        // 从最大的position开始倒序更新，避免重复键冲突
        String insertSql = "INSERT INTO playlist_music (playlist_id, music_id, position) VALUES (?, ?, 1)";

        try (Connection conn = databaseManager.getConnection()) {
            // 开启事务
            conn.setAutoCommit(false);

            try {
                // 必须从大到小更新，否则中间会出现重复的 (playlist_id, position) 触发唯一约束
                String shiftSql = "UPDATE playlist_music SET position = position + 1 WHERE playlist_id = ? ORDER BY position DESC";
                try (PreparedStatement stmt = conn.prepareStatement(shiftSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.executeUpdate();
                }

                // 插入新音乐到 position = 1
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.setInt(2, musicId);
                    
                    int affectedRows = stmt.executeUpdate();
                    if (affectedRows == 0) {
                        logger.warn("音乐添加到歌单失败: playlistId={}, musicId={}", playlistId, musicId);
                        conn.rollback();
                        return false;
                    }
                }

                // 提交事务
                conn.commit();
                logger.info("音乐添加到歌单成功: playlistId={}, musicId={}, position=1", playlistId, musicId);
                
                // 更新歌单的音乐数量
                updateMusicCount(playlistId);
                return true;

            } catch (SQLException e) {
                // 发生异常，回滚事务
                conn.rollback();
                logger.error("添加音乐到歌单失败: {}", e.getMessage(), e);
                return false;
            }
        } catch (SQLException e) {
            logger.error("获取数据库连接失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 追加音乐到歌单末尾（保持调用顺序，用于外部歌单导入等需要保序的场景）。
     *
     * <p>与 {@link #addMusicToPlaylist(int, int)}（插入到首位）不同，本方法把新曲目放到现有曲目
     * 之后，因此按来源顺序依次调用即可让歌单顺序与来源一致。曲目已存在时返回 {@code false}。</p>
     */
    public boolean appendMusicToPlaylist(int playlistId, int musicId) {
        return appendMusicToPlaylistDetailed(playlistId, musicId) == AppendMusicResult.ADDED;
    }

    /**
     * 与 {@link #appendMusicToPlaylist(int, int)} 相同，但区分目标歌单已被删除与普通数据库失败，
     * 让长耗时导入能在用户删除目标歌单后及时停止并给出明确提示。
     */
    public AppendMusicResult appendMusicToPlaylistDetailed(int playlistId, int musicId) {
        logger.info("追加音乐到歌单: playlistId={}, musicId={}", playlistId, musicId);

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                if (!playlistExists(conn, playlistId)) {
                    conn.rollback();
                    logger.warn("目标歌单不存在，停止追加: playlistId={}, musicId={}", playlistId, musicId);
                    return AppendMusicResult.PLAYLIST_MISSING;
                }
                if (playlistMusicExists(conn, playlistId, musicId)) {
                    logger.warn("音乐已存在于歌单中: playlistId={}, musicId={}", playlistId, musicId);
                    conn.rollback();
                    return AppendMusicResult.ALREADY_EXISTS;
                }

                String insertSql = "INSERT INTO playlist_music (playlist_id, music_id, position) VALUES (?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.setInt(2, musicId);
                    stmt.setInt(3, nextPlaylistPosition(conn, playlistId));

                    if (stmt.executeUpdate() == 0) {
                        logger.warn("音乐追加到歌单失败: playlistId={}, musicId={}", playlistId, musicId);
                        conn.rollback();
                        return AppendMusicResult.FAILED;
                    }
                }

                conn.commit();
                logger.info("音乐追加到歌单成功: playlistId={}, musicId={}", playlistId, musicId);
                updateMusicCount(playlistId);
                return AppendMusicResult.ADDED;
            } catch (SQLException e) {
                conn.rollback();
                if (!playlistExistsAfterRollback(playlistId)) {
                    logger.warn("目标歌单已被删除，停止追加: playlistId={}, musicId={}",
                            playlistId, musicId);
                    return AppendMusicResult.PLAYLIST_MISSING;
                }
                logger.error("追加音乐到歌单失败: {}", e.getMessage(), e);
                return AppendMusicResult.FAILED;
            }
        } catch (SQLException e) {
            logger.error("获取数据库连接失败: {}", e.getMessage(), e);
            return AppendMusicResult.FAILED;
        }
    }

    private static boolean playlistExists(Connection conn, int playlistId) throws SQLException {
        String sql = "SELECT 1 FROM playlists WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, playlistId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 用独立连接复查目标歌单，避免当前事务的快照仍能看到刚被其他事务删除的歌单。
     * 复查失败时按普通数据库错误处理，不能把真实故障误报成「歌单已删除」。
     */
    private boolean playlistExistsAfterRollback(int playlistId) {
        try (Connection check = databaseManager.getConnection()) {
            return playlistExists(check, playlistId);
        } catch (SQLException e) {
            logger.warn("复查目标歌单是否存在失败: playlistId={}, {}", playlistId, e.getMessage());
            return true;
        }
    }

    private static boolean playlistMusicExists(Connection conn, int playlistId, int musicId) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM playlist_music WHERE playlist_id = ? AND music_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setInt(1, playlistId);
            stmt.setInt(2, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static int nextPlaylistPosition(Connection conn, int playlistId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(position), 0) + 1 FROM playlist_music WHERE playlist_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, playlistId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    /**
     * 删除歌单
     */
    public boolean deletePlaylist(int playlistId) {
        logger.info("删除歌单: id={}", playlistId);

        String sql = "DELETE FROM playlists WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, playlistId);

            int affectedRows = stmt.executeUpdate();
            boolean success = affectedRows > 0;

            if (success) {
                logger.info("歌单删除成功: id={}", playlistId);
            } else {
                logger.warn("歌单删除失败: id={}", playlistId);
            }

            return success;
        } catch (SQLException e) {
            logger.error("删除歌单失败: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 从歌单中移除音乐
     */
    public boolean removeMusicFromPlaylist(int playlistId, int musicId) {
        logger.info("从歌单中移除音乐: playlistId={}, musicId={}", playlistId, musicId);

        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 1. 获取要删除的音乐的position
                int removedPosition = -1;
                String getPositionSql = "SELECT position FROM playlist_music WHERE playlist_id = ? AND music_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(getPositionSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.setInt(2, musicId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            removedPosition = rs.getInt("position");
                        } else {
                            logger.warn("音乐不存在于歌单中: playlistId={}, musicId={}", playlistId, musicId);
                            conn.rollback();
                            return false;
                        }
                    }
                }

                // 2. 删除音乐
                String deleteSql = "DELETE FROM playlist_music WHERE playlist_id = ? AND music_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.setInt(2, musicId);
                    int affectedRows = stmt.executeUpdate();
                    if (affectedRows == 0) {
                        logger.warn("音乐从歌单中移除失败: playlistId={}, musicId={}", playlistId, musicId);
                        conn.rollback();
                        return false;
                    }
                }

                // 3. 重新排序 position（从小到大更新，避免减一时短暂撞键）
                String updateSql = "UPDATE playlist_music SET position = position - 1 WHERE playlist_id = ? AND position > ? ORDER BY position ASC";
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setInt(1, playlistId);
                    stmt.setInt(2, removedPosition);
                    stmt.executeUpdate();
                }

                conn.commit();
                logger.info("音乐从歌单中移除成功: playlistId={}, musicId={}", playlistId, musicId);

                // 更新歌单的音乐数量（不在事务内，非关键操作）
                updateMusicCount(playlistId);
                return true;

            } catch (SQLException e) {
                conn.rollback();
                logger.error("从歌单中移除音乐失败: {}", e.getMessage(), e);
                return false;
            }
        } catch (SQLException e) {
            logger.error("获取数据库连接失败: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 检查用户是否是歌单的所有者
     */
    public boolean isPlaylistOwner(int playlistId, int userId) {
        logger.info("检查歌单所有权: playlistId={}, userId={}", playlistId, userId);

        String sql = "SELECT COUNT(*) FROM playlists WHERE id = ? AND user_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, playlistId);
            stmt.setInt(2, userId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error("检查歌单所有权失败: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 更新歌单的音乐数量
     */
    public boolean updateMusicCount(int playlistId) {
        logger.info("更新歌单音乐数量: id={}", playlistId);

        String sql = "UPDATE playlists SET music_count = (SELECT COUNT(*) FROM playlist_music WHERE playlist_id = ?) WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, playlistId);
            stmt.setInt(2, playlistId);

            int affectedRows = stmt.executeUpdate();
            boolean success = affectedRows > 0;

            if (success) {
                logger.info("歌单音乐数量更新成功: id={}", playlistId);
            }

            return success;
        } catch (SQLException e) {
            logger.error("更新歌单音乐数量失败: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 获取歌单中的音乐列表
     */
    public List<com.google.gson.JsonObject> getPlaylistMusic(int playlistId) {
        logger.info("获取歌单音乐列表: playlistId={}", playlistId);

        List<com.google.gson.JsonObject> musicList = new ArrayList<>();
        String sql = "SELECT m.id, m.title, m.artist, m.album, m.duration, m.file_format, m.language, m.max_quality, pm.position, pm.added_at " +
                     "FROM playlist_music pm " +
                     "JOIN music m ON pm.music_id = m.id " +
                     "WHERE pm.playlist_id = ? " +
                     "ORDER BY pm.position ASC";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, playlistId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                com.google.gson.JsonObject music = new com.google.gson.JsonObject();
                music.addProperty("id", rs.getInt("id"));
                music.addProperty("title", rs.getString("title"));
                music.addProperty("artist", rs.getString("artist"));
                music.addProperty("album", rs.getString("album"));
                music.addProperty("duration", rs.getInt("duration"));
                int mid = rs.getInt("id");
                music.addProperty("coverPath", MusicAssetLocator.coverApiUrl(mid));
                music.addProperty("filePath", MusicAssetLocator.fileApiUrl(mid));
                music.addProperty("fileFormat", rs.getString("file_format"));
                music.addProperty("language", rs.getString("language"));
                music.addProperty("maxQuality", rs.getString("max_quality"));
                music.addProperty("position", rs.getInt("position"));
                music.addProperty("addedAt", rs.getString("added_at"));
                musicList.add(music);
            }

            logger.info("获取到 {} 首音乐: playlistId={}", musicList.size(), playlistId);
        } catch (SQLException e) {
            logger.error("获取歌单音乐列表失败: {}", e.getMessage(), e);
        }

        return musicList;
    }

    /**
     * 搜索歌单
     */
    public List<com.google.gson.JsonObject> searchPlaylists(String query) {
        logger.info("搜索歌单: query={}", query);

        List<com.google.gson.JsonObject> results = new ArrayList<>();
        int limit = 50;

        try {
            boolean isPinyin = com.neko.music.util.PinyinUtil.isLikelyPinyin(query);

            if (isPinyin) {
                String sql = """
                    SELECT pl.*, ft.first_music_id
                    FROM (
                        SELECT * FROM playlists p
                        WHERE (p.name LIKE ? OR p.name_pinyin LIKE ? OR p.name_pinyin_initials LIKE ? OR p.name_word_initials LIKE ?)
                        ORDER BY p.created_at DESC
                        LIMIT ?
                    ) pl
                    """ + SQL_PLAYLIST_SEARCH_FIRST_TRACK_JOIN;

                try (Connection conn = databaseManager.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {

                    String likeQuery = "%" + query.toLowerCase() + "%";
                    stmt.setString(1, likeQuery);
                    stmt.setString(2, likeQuery);
                    stmt.setString(3, likeQuery);
                    stmt.setString(4, likeQuery);
                    stmt.setInt(5, limit);

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(mapSearchPlaylistRow(rs));
                        }
                    }
                }
            } else {
                List<String> variants = com.neko.music.util.ChineseConverter.getFullSearchVariants(query);

                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("""
                    SELECT pl.*, ft.first_music_id
                    FROM (
                        SELECT * FROM playlists p
                        WHERE (""");
                List<String> conditions = new ArrayList<>();
                for (int i = 0; i < variants.size(); i++) {
                    conditions.add("(p.name LIKE ? OR p.description LIKE ?)");
                }
                sqlBuilder.append(String.join(" OR ", conditions));
                sqlBuilder.append("""
                        )
                        ORDER BY p.created_at DESC
                        LIMIT ?
                    ) pl
                    """);
                sqlBuilder.append(SQL_PLAYLIST_SEARCH_FIRST_TRACK_JOIN);

                try (Connection conn = databaseManager.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {

                    int paramIndex = 1;
                    for (String variant : variants) {
                        String like = "%" + variant + "%";
                        stmt.setString(paramIndex++, like);
                        stmt.setString(paramIndex++, like);
                    }
                    stmt.setInt(paramIndex, limit);

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(mapSearchPlaylistRow(rs));
                        }
                    }
                }
            }

            logger.info("搜索到 {} 个歌单: query={}", results.size(), query);
        } catch (SQLException e) {
            logger.error("搜索歌单失败: {}", e.getMessage(), e);
        }

        return results;
    }

    private static Playlist mapPlaylistRow(ResultSet rs) throws SQLException {
        return new Playlist(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("music_count"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static com.google.gson.JsonObject mapSearchPlaylistRow(ResultSet rs) throws SQLException {
        com.google.gson.JsonObject playlist = new com.google.gson.JsonObject();
        playlist.addProperty("id", rs.getInt("id"));
        playlist.addProperty("userId", rs.getInt("user_id"));
        playlist.addProperty("name", rs.getString("name"));
        String desc = rs.getString("description");
        playlist.addProperty("description", desc != null ? desc : "");
        playlist.addProperty("musicCount", rs.getInt("music_count"));
        playlist.addProperty("createdAt", rs.getString("created_at"));
        playlist.addProperty("updatedAt", rs.getString("updated_at"));

        int firstMusicId = rs.getInt("first_music_id");
        if (!rs.wasNull() && firstMusicId > 0) {
            playlist.addProperty("firstMusicId", firstMusicId);
            playlist.addProperty("firstMusicCover", MusicAssetLocator.coverApiUrl(firstMusicId));
        } else {
            playlist.addProperty("firstMusicCover", "/api/user/avatar/default");
        }
        return playlist;
    }

}
