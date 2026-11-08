package com.neko.music.handlers;

import com.neko.music.model.ErrorResponse;
import com.neko.music.Main;
import com.neko.music.util.NekoLyrics;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Pattern;
import com.neko.music.i18n.ApiMessages;

public class MusicInfoHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicInfoHandler.class);

    /** LRC 时间戳补毫秒（{@code [00:12:34]} → {@code [00:12.34]}）。 */
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[(\\d{2}):(\\d{2}):(\\d{2,3})\\]");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null || pathInfo.equals("/")) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setContentType("application/json;charset=utf-8");
            ErrorResponse errorResponse = new ErrorResponse("music.id_required");
            response.getWriter().println(Main.getObjectMapper().writeValueAsString(errorResponse));
            return;
        }
        
        // 解析音乐ID (路径格式: /{id})
        String idStr = pathInfo.replace("/", "");
        int musicId;
        
        try {
            musicId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setContentType("application/json;charset=utf-8");
            ErrorResponse errorResponse = new ErrorResponse("music.id_invalid");
            response.getWriter().println(Main.getObjectMapper().writeValueAsString(errorResponse));
            return;
        }
        
        // 获取音乐信息（无需管理员权限）
        Music music = getMusicById(musicId);
        
        if (music == null) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            response.setContentType("application/json;charset=utf-8");
            ErrorResponse errorResponse = new ErrorResponse("music.not_found");
            response.getWriter().println(Main.getObjectMapper().writeValueAsString(errorResponse));
            return;
        }
        
        response.setStatus(HttpStatus.OK_200);
        response.setContentType("application/json;charset=utf-8");
        MusicResponse musicResponse = new MusicResponse(true, ApiMessages.translate("music.detail.ok"), music);
        response.getWriter().println(Main.getObjectMapper().writeValueAsString(musicResponse));
    }
    
    /**
     * 根据ID获取音乐信息（无需管理员权限）
     */
    private Music getMusicById(int musicId) {
        Music music = null;
        LyricsView lyrics = getLyricsById(musicId);

        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT m.id, m.title, m.artist, m.album, m.duration, m.language, m.tags, " +
                    "m.upload_user_id, m.created_at, m.updated_at, m.max_quality, " +
                    "(SELECT COUNT(*) FROM user_favorites uf WHERE uf.music_id = m.id) AS favorite_count " +
                    "FROM music m WHERE m.id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, musicId);
                
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    int id = rs.getInt("id");
                    music = new Music(
                            id,
                            rs.getString("title"),
                            rs.getString("artist"),
                            rs.getString("album"),
                            rs.getInt("duration"),
                            rs.getString("language"),
                            rs.getString("tags"),
                            rs.getInt("upload_user_id"),
                            rs.getTimestamp("created_at").toString(),
                            rs.getTimestamp("updated_at").toString(),
                            rs.getString("max_quality"),
                            rs.getInt("favorite_count"),
                            lyrics.format(),
                            lyrics.lrc(),
                            lyrics.rich());
                }
            }
        } catch (Exception e) {
            logger.error("获取音乐详情时出错", e);
        }
        
        return music;
    }

    /**
     * 根据音乐ID获取歌词；没有歌词时返回空字符串。
     */
    private LyricsView getLyricsById(int musicId) {
        try {
            var stored = Main.getLyricsDatabaseManager().findByMusicIdCached(musicId);
            if (stored.isPresent()) {
                var lyrics = stored.get();
                return new LyricsView(
                        lyrics.format(),
                        normalizeLrc(lyrics.lrcContent()),
                        lyrics.richLyrics().orElse(null));
            }
            logger.debug("数据库歌词不存在 musicId={}", musicId);
        } catch (Exception e) {
            logger.error("获取歌词失败 musicId={}", musicId, e);
        }
        return new LyricsView("lrc", "", null);
    }

    private String normalizeLrc(String lyricsContent) {
        if (lyricsContent == null) {
            return "";
        }
        return LRC_TIMESTAMP.matcher(lyricsContent).replaceAll("[$1:$2.$3]");
    }

    // 音乐对象：只读数据载体。
    public record Music(
            int id,
            String title,
            String artist,
            String album,
            int duration,
            String language,
            String tags,
            int uploadUserId,
            String createdAt,
            String updatedAt,
            String maxQuality,
            int favoriteCount,
            String lyricsFormat,
            String lyrics,
            NekoLyrics.Document lyricsRich) {
    }

    private record LyricsView(String format, String lrc, NekoLyrics.Document rich) {
    }
    
    // 内部类用于表示单个音乐响应
    private record MusicResponse(boolean success, String message, Music data) {
    }
    
    // 内部类用于表示错误响应
}
