package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.util.MusicAssetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserFavoritePlaylistHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserFavoritePlaylistHandler.class);
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String token = req.getHeader("Authorization");
        
        if (token == null || token.isEmpty()) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.token_missing");
            return;
        }
        
        Integer userId = validateToken(token);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.invalid_bearer");
            return;
        }
        
        // 检查是否请求特定收藏歌单内的音乐
        String pathInfo = req.getPathInfo();
        if (pathInfo != null && !pathInfo.equals("/")) {
            // 获取收藏歌单内的音乐列表
            try {
                int playlistId = Integer.parseInt(pathInfo.substring(1));
                List<JsonObject> musicList = getPlaylistMusic(userId, playlistId);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.add("music", Main.getGson().toJsonTree(musicList));
                
                sendSuccessResponse(resp, response);
            } catch (NumberFormatException e) {
                sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "playlist.id_invalid");
            } catch (SQLException e) {
                logger.error("获取歌单音乐失败: {}", e.getMessage(), e);
                sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "playlist.favorite.music_failed");
            }
        } else {
            // 获取收藏歌单列表
            try {
                List<JsonObject> playlists = getFavoritePlaylists(userId);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.add("playlists", Main.getGson().toJsonTree(playlists));
                
                sendSuccessResponse(resp, response);
            } catch (SQLException e) {
                logger.error("获取收藏歌单列表失败: {}", e.getMessage(), e);
                sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "playlist.favorite.list_failed");
            }
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String token = req.getHeader("Authorization");
        
        if (token == null || token.isEmpty()) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.token_missing");
            return;
        }
        
        Integer userId = validateToken(token);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.invalid_bearer");
            return;
        }
        
        // 读取请求体
        String sb = readBody(req);
        
        JsonObject requestBody = Main.getGson().fromJson(sb.toString(), JsonObject.class);
        int playlistId = requestBody.get("playlistId").getAsInt();
        
        try {
            boolean success = addFavoritePlaylist(userId, playlistId);
            if (success) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", ApiMessages.translate("playlist.favorite.add_ok"));
                sendSuccessResponse(resp, response);
            } else {
                sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "playlist.favorite.add_failed_or_exists");
            }
        } catch (SQLException e) {
            logger.error("收藏歌单失败: {}", e.getMessage(), e);
            sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "playlist.favorite.add_failed");
        }
    }
    
    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String token = req.getHeader("Authorization");
        
        if (token == null || token.isEmpty()) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.token_missing");
            return;
        }
        
        Integer userId = validateToken(token);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.invalid_bearer");
            return;
        }
        
        // 从路径中获取歌单ID
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "playlist.favorite.id_required");
            return;
        }
        
        try {
            int playlistId = Integer.parseInt(pathInfo.substring(1));
            boolean success = removeFavoritePlaylist(userId, playlistId);
            
            if (success) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("message", ApiMessages.translate("playlist.favorite.cancel_ok"));
                sendSuccessResponse(resp, response);
            } else {
                sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "playlist.favorite.cancel_failed");
            }
        } catch (NumberFormatException e) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "playlist.id_invalid");
        } catch (SQLException e) {
            logger.error("取消收藏歌单失败: {}", e.getMessage(), e);
            sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "playlist.favorite.cancel_failed");
        }
    }
    
    private Integer validateToken(String token) {
        return Main.getUserAuthService().validateToken(token).orElse(null);
    }
    
    private List<JsonObject> getFavoritePlaylists(int userId) throws SQLException {
        List<JsonObject> playlists = new ArrayList<>();
        String sql = "SELECT p.id, p.name, p.description, p.music_count, p.created_at, p.updated_at, " +
                     "p.user_id as creator_id, " +
                     "ufp.created_at as favorite_time " +
                     "FROM user_favorite_playlists ufp " +
                     "JOIN playlists p ON ufp.playlist_id = p.id " +
                     "WHERE ufp.user_id = ? " +
                     "ORDER BY ufp.created_at DESC";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                JsonObject playlist = new JsonObject();
                playlist.addProperty("id", rs.getInt("id"));
                playlist.addProperty("name", rs.getString("name"));
                playlist.addProperty("description", rs.getString("description"));
                playlist.addProperty("musicCount", rs.getInt("music_count"));
                playlist.addProperty("createdAt", rs.getTimestamp("created_at").getTime());
                playlist.addProperty("updatedAt", rs.getTimestamp("updated_at").getTime());
                playlist.addProperty("favoriteTime", rs.getTimestamp("favorite_time").getTime());
                
                JsonObject creator = new JsonObject();
                creator.addProperty("id", rs.getInt("creator_id"));
                playlist.add("creator", creator);
                
                playlists.add(playlist);
            }
        }

        java.util.Map<Integer, String> nicknames = Main.getNicknameResolver().resolveByLocalUserIds(
                playlists.stream()
                        .map(playlist -> playlist.getAsJsonObject("creator").get("id").getAsInt())
                        .toList(),
                "未知用户");
        for (JsonObject playlist : playlists) {
            JsonObject creator = playlist.getAsJsonObject("creator");
            creator.addProperty("nickname", nicknames.getOrDefault(creator.get("id").getAsInt(), "未知用户"));
        }
        
        return playlists;
    }
    
    private List<JsonObject> getPlaylistMusic(int userId, int playlistId) throws SQLException {
        // 首先验证用户是否收藏了这个歌单
        String checkSql = "SELECT COUNT(*) FROM user_favorite_playlists WHERE user_id = ? AND playlist_id = ?";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            
            checkStmt.setInt(1, userId);
            checkStmt.setInt(2, playlistId);
            ResultSet rs = checkStmt.executeQuery();
            
            if (!rs.next() || rs.getInt(1) == 0) {
                throw new SQLException("用户未收藏该歌单");
            }
        }
        
        // 获取歌单内的音乐
        List<JsonObject> musicList = new ArrayList<>();
        String sql = "SELECT m.id, m.title, m.artist, m.album, m.duration, m.max_quality, pm.position " +
                     "FROM playlist_music pm " +
                     "JOIN music m ON pm.music_id = m.id " +
                     "WHERE pm.playlist_id = ? " +
                     "ORDER BY pm.position ASC";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, playlistId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                JsonObject music = new JsonObject();
                music.addProperty("id", rs.getInt("id"));
                music.addProperty("title", rs.getString("title"));
                music.addProperty("artist", rs.getString("artist"));
                music.addProperty("album", rs.getString("album"));
                music.addProperty("duration", rs.getInt("duration"));
                music.addProperty("maxQuality", rs.getString("max_quality"));
                music.addProperty("filename", MusicAssetLocator.fileApiUrl(rs.getInt("id")));
                music.addProperty("position", rs.getInt("position"));
                musicList.add(music);
            }
        }
        
        return musicList;
    }
    
    private boolean addFavoritePlaylist(int userId, int playlistId) throws SQLException {
        // 检查是否已经收藏
        String checkSql = "SELECT COUNT(*) FROM user_favorite_playlists WHERE user_id = ? AND playlist_id = ?";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            
            checkStmt.setInt(1, userId);
            checkStmt.setInt(2, playlistId);
            ResultSet rs = checkStmt.executeQuery();
            
            if (rs.next() && rs.getInt(1) > 0) {
                return false; // 已经收藏
            }
        }
        
        // 添加收藏
        String sql = "INSERT INTO user_favorite_playlists (user_id, playlist_id, created_at) VALUES (?, ?, NOW())";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            stmt.setInt(2, playlistId);
            
            return stmt.executeUpdate() > 0;
        }
    }
    
    private boolean removeFavoritePlaylist(int userId, int playlistId) throws SQLException {
        String sql = "DELETE FROM user_favorite_playlists WHERE user_id = ? AND playlist_id = ?";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            stmt.setInt(2, playlistId);
            
            return stmt.executeUpdate() > 0;
        }
    }
    
    
}
