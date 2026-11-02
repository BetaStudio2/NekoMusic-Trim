package com.neko.music.util;

import com.neko.music.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对外公开的歌单元数据查询（无需登录），供 {@code /playlist/{id}} 的服务端 SEO 渲染使用。
 *
 * <p>与 {@link PublicMusicLookup} 一样只做只读查询；歌单在库中没有可见性字段，
 * 所有歌单都可被任何访客读取，因此这里不做权限判断。</p>
 */
public final class PublicPlaylistLookup {
    private static final Logger logger = LoggerFactory.getLogger(PublicPlaylistLookup.class);

    private PublicPlaylistLookup() {
    }

    /**
     * 读取歌单及其前 {@code trackLimit} 首曲目；歌单不存在时返回 empty。
     */
    public static Optional<PublicPlaylist> findById(int playlistId, int trackLimit) {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            PublicPlaylist playlist = loadPlaylist(conn, playlistId);
            if (playlist == null) {
                return Optional.empty();
            }
            loadTracks(conn, playlistId, Math.max(1, trackLimit), playlist.tracks);
            return Optional.of(playlist);
        } catch (Exception e) {
            logger.error("查询歌单 id={} 失败", playlistId, e);
            return Optional.empty();
        }
    }

    private static PublicPlaylist loadPlaylist(Connection conn, int playlistId) throws Exception {
        String sql = "SELECT p.id, p.user_id, p.name, p.description, p.music_count, "
                + "p.created_at, p.updated_at FROM playlists p WHERE p.id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, playlistId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                PublicPlaylist p = new PublicPlaylist();
                p.id = rs.getInt("id");
                p.userId = rs.getInt("user_id");
                p.name = rs.getString("name");
                p.description = rs.getString("description");
                p.musicCount = rs.getInt("music_count");
                p.createdAt = iso(rs.getTimestamp("created_at"));
                p.updatedAt = iso(rs.getTimestamp("updated_at"));
                p.creatorName = Main.getNicknameResolver()
                        .resolveByLocalUserId(p.userId, "未知用户");
                return p;
            }
        }
    }

    private static void loadTracks(Connection conn, int playlistId, int limit, List<Track> out) throws Exception {
        String sql = "SELECT m.id, m.title, m.artist, m.album, m.duration "
                + "FROM playlist_music pm JOIN music m ON pm.music_id = m.id "
                + "WHERE pm.playlist_id = ? ORDER BY pm.position ASC LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, playlistId);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Track t = new Track();
                    t.id = rs.getInt("id");
                    t.title = rs.getString("title");
                    t.artist = rs.getString("artist");
                    t.album = rs.getString("album");
                    t.duration = rs.getInt("duration");
                    out.add(t);
                }
            }
        }
    }

    private static String iso(Timestamp ts) {
        return ts == null ? "" : ts.toInstant().toString();
    }

    /** 只读歌单载体；字段由 JDBC 行映射后不再修改。 */
    public static final class PublicPlaylist {
        public int id;
        public int userId;
        public String name;
        public String description;
        public int musicCount;
        public String createdAt;
        public String updatedAt;
        public String creatorName;
        public final List<Track> tracks = new ArrayList<>();
    }

    /** 歌单内单曲的精简信息（曲序、标题、歌手、专辑、时长）。 */
    public static final class Track {
        public int id;
        public String title;
        public String artist;
        public String album;
        public int duration;
    }
}
