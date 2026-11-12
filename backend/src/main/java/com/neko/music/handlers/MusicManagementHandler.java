package com.neko.music.handlers;

import com.neko.music.util.HandlerResponses;
import com.neko.music.util.MusicPinyinColumns;

import com.neko.music.model.SuccessResponse;
import com.neko.music.model.ErrorResponse;
import com.neko.music.Main;
import com.neko.music.service.EmbeddedMetadataSyncService;
import com.neko.music.service.MusicIngestSupport;
import com.neko.music.util.CoverArtCache;
import com.neko.music.util.MusicAssetLocator;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.neko.music.i18n.ApiMessages;

public class MusicManagementHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicManagementHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        
        // 检查是否有音乐查看权限
        if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.MUSIC_VIEW)) {
            logger.warn("权限不足，无音乐查看权限");
            return;
        }
        
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null || "/list".equals(pathInfo)) {
            // 获取所有音乐列表
            getAllMusic(request, response);
        } else {
            // 获取特定ID的音乐
            getMusicById(pathInfo, request, response);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        
        // 检查是否有音乐添加权限
        if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.MUSIC_ADD)) {
            logger.warn("权限不足，无音乐添加权限");
            return;
        }
        
        // 对于POST请求，总是执行添加操作
        addMusic(request, response);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        
        // 检查是否有音乐编辑权限
        if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.MUSIC_EDIT)) {
            logger.warn("权限不足，无音乐编辑权限");
            return;
        }
        
        // 对于PUT请求，总是执行编辑操作，不严格区分路径
        // 因为PUT方法的语义就是更新资源
        editMusic(request, response);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        
        // 检查是否有音乐删除权限
        if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.MUSIC_DELETE)) {
            logger.warn("权限不足，无音乐删除权限");
            return;
        }
        
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null) {
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("music.id_required"));
            return;
        }
        
        // 解析路径，只处理 /delete/{id} 格式
        String idStr = "";
        if (pathInfo.startsWith("/delete/")) {
            idStr = pathInfo.substring("/delete/".length());
        } else {
            // 如果路径不是以 /delete/ 开头，返回错误
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(ApiMessages.translate("music.invalid_delete_path", pathInfo)));
            return;
        }
        
        int id;

        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(ApiMessages.translate("music.id_invalid_detail", idStr)));
            return;
        }

        // 删除数据库记录
        int rowsDeleted;
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "DELETE FROM music WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);

                rowsDeleted = stmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("删除音乐时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500, new ErrorResponse(ApiMessages.translate("music.delete_failed", e.getMessage())));
            return;
        }

        if (rowsDeleted == 0) {
            HandlerResponses.writeJson(response, HttpStatus.NOT_FOUND_404, new ErrorResponse("music.not_found_or_delete_failed"));
            return;
        }

        CoverArtCache.invalidate(id);
        MusicAssetLocator.deleteAudioVariants(id);

        Main.getLyricsDatabaseManager().delete(id);
        if (Main.getLyricsSearchIndex() != null) {
            Main.getLyricsSearchIndex().rebuildOne(id);
        }
        MusicIngestSupport.invalidateRecognitionIndex();

        HandlerResponses.writeJson(response, HttpStatus.OK_200, new SuccessResponse(true, ApiMessages.translate("music.delete.ok")));
    }
    
    // 检查管理员权限

    private void getAllMusic(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<Music> musicList = new ArrayList<>();
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, album, duration, language, tags, upload_user_id, created_at, updated_at, max_quality FROM music ORDER BY created_at DESC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                
                while (rs.next()) {
                    musicList.add(mapMusic(rs));
                }
            }
        } catch (Exception e) {
            logger.error("获取音乐列表时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500, new ErrorResponse(ApiMessages.translate("music.list_failed", e.getMessage())));
            return;
        }
        
        HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicListResponse(true, ApiMessages.translate("music.list.ok"), musicList));
    }

    private void getMusicById(String pathInfo, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String idStr = pathInfo.replace("/", "");
        int id;
        
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("music.id_invalid"));
            return;
        }
        
        Music music = null;
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, album, duration, language, tags, upload_user_id, created_at, updated_at, max_quality FROM music WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);
                
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    music = mapMusic(rs);
                }
            }
        } catch (Exception e) {
            logger.error("获取音乐详情时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500, new ErrorResponse(ApiMessages.translate("music.detail_failed", e.getMessage())));
            return;
        }
        
        if (music == null) {
            HandlerResponses.writeJson(response, HttpStatus.NOT_FOUND_404, new ErrorResponse("music.not_found"));
            return;
        }
        
        HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(true, ApiMessages.translate("music.detail.ok"), music));
    }

    private void addMusic(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 读取请求体
        String requestBody = new String(request.getInputStream().readAllBytes(), "UTF-8");

        try {
            // 解析JSON请求体
            AddMusicRequest addRequest = Main.getObjectMapper().readValue(requestBody, AddMusicRequest.class);
            
            if (addRequest.title() == null || addRequest.title().trim().isEmpty() ||
                addRequest.artist() == null || addRequest.artist().trim().isEmpty() ||
                addRequest.language() == null || addRequest.language().trim().isEmpty()) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("upload.music_fields_required"));
                return;
            }
            
            int id;
            try (Connection conn = Main.getDatabaseManager().getConnection()) {
                String sql = "INSERT INTO music (title, artist, album, duration, language, tags, upload_user_id, title_pinyin, title_pinyin_initials, title_word_initials, artist_pinyin, artist_pinyin_initials, artist_word_initials, album_pinyin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, addRequest.title());
                    stmt.setString(2, addRequest.artist());
                    stmt.setString(3, addRequest.album() != null ? addRequest.album() : "未知专辑");
                    stmt.setInt(4, addRequest.duration() != null ? addRequest.duration() : 0);
                    stmt.setString(5, addRequest.language() != null ? addRequest.language() : "未知语言");
                    stmt.setString(6, addRequest.tags() != null ? addRequest.tags() : "");
                    // 使用NULL而不是0以避免外键约束问题
                    stmt.setObject(7, null);
                    // 预计算拼音列
                    MusicPinyinColumns.bind(stmt, 8, addRequest.title(), addRequest.artist(), addRequest.album());
                    
                    int affectedRows = stmt.executeUpdate();
                    
                    if (affectedRows == 0) {
                        HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500, new ErrorResponse("music.add_failed"));
                        return;
                    }
                    
                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            id = generatedKeys.getInt(1);
                        } else {
                            throw new SQLException("获取新音乐ID失败");
                        }
                    }
                }
            }
            
            // 获取新添加的音乐信息
            Music newMusic = null;
            try (Connection conn = Main.getDatabaseManager().getConnection()) {
                String sql = "SELECT id, title, artist, album, duration, language, tags, upload_user_id, created_at, updated_at, max_quality FROM music WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, id);
                    
                    ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        newMusic = mapMusic(rs);
                    }
                }
            }

            MusicIngestSupport.invalidateRecognitionIndex();
            
            HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(true, ApiMessages.translate("music.add.ok"), newMusic));
            
        } catch (Exception e) {
            // JSON 解析异常的消息含内部类名与字段清单，只能落日志，响应只回泛化提示
            logger.warn("添加音乐请求处理失败", e);
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("auth.bad_request"));
        }
    }

    private void editMusic(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 读取请求体
        String requestBody = new String(request.getInputStream().readAllBytes(), "UTF-8");

        try {
            // 解析JSON请求体
            EditMusicRequest editRequest = Main.getObjectMapper().readValue(requestBody, EditMusicRequest.class);

            if (editRequest.id() == null) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("music.id_required"));
                return;
            }

            Music currentMusic = null;
            try (Connection conn = Main.getDatabaseManager().getConnection()) {
                String sql = "SELECT id, title, artist, album, duration, language, tags, upload_user_id, created_at, updated_at, max_quality FROM music WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, editRequest.id());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            currentMusic = mapMusic(rs);
                        }
                    }
                }
            }

            if (currentMusic == null) {
                HandlerResponses.writeJson(response, HttpStatus.NOT_FOUND_404, new ErrorResponse("music.not_found"));
                return;
            }

            // 编辑采用部分更新：未提交的字段沿用数据库当前值。
            String title = editRequest.title() != null ? editRequest.title() : currentMusic.title();
            String artist = editRequest.artist() != null ? editRequest.artist() : currentMusic.artist();
            String album = editRequest.album() != null ? editRequest.album() : currentMusic.album();
            int duration = editRequest.duration() != null ? editRequest.duration() : currentMusic.duration();
            String language = editRequest.language() != null ? editRequest.language() : currentMusic.language();
            String tags = editRequest.tags() != null ? editRequest.tags() : currentMusic.tags();
            Integer uploadUserId = editRequest.uploadUserId() != null
                    ? editRequest.uploadUserId() : currentMusic.uploadUserId();

            int rowsUpdated;
            try (Connection conn = Main.getDatabaseManager().getConnection()) {
                String sql = "UPDATE music SET title = ?, artist = ?, album = ?, duration = ?, language = ?, tags = ?, upload_user_id = ?, title_pinyin = ?, title_pinyin_initials = ?, title_word_initials = ?, artist_pinyin = ?, artist_pinyin_initials = ?, artist_word_initials = ?, album_pinyin = ?, updated_at = NOW() WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, title);
                    stmt.setString(2, artist);
                    stmt.setString(3, album);
                    stmt.setInt(4, duration);
                    stmt.setString(5, language);
                    stmt.setString(6, tags);
                    stmt.setObject(7, resolveValidUploadUserId(conn, uploadUserId));
                    // 预计算拼音列
                    MusicPinyinColumns.bind(stmt, 8, title, artist, album);
                    stmt.setInt(15, editRequest.id());
                    
                    rowsUpdated = stmt.executeUpdate();
                }
                
                // 未提交歌词时保持原歌词不变；提交后才保存并重建检索索引。
                if (rowsUpdated > 0 && editRequest.lyrics() != null) {
                    try {
                        saveLyricsToDatabase(editRequest.id(), editRequest.lyrics());
                    } catch (Exception lyricsEx) {
                        logger.error("保存歌词失败 musicId={}", editRequest.id(), lyricsEx);
                        HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                                new ErrorResponse("music.lyrics.save_failed"));
                        return;
                    }
                }
            }
            
            if (rowsUpdated == 0) {
                HandlerResponses.writeJson(response, HttpStatus.NOT_FOUND_404, new ErrorResponse(ApiMessages.translate("music.not_found_or_update_failed")));
                return;
            }

            // 标题/艺术家/专辑/歌词变更后写回音频文件（保留广告元数据）
            EmbeddedMetadataSyncService.syncOne(editRequest.id());
            
            // 获取更新后的音乐信息
            Music updatedMusic = null;
            try (Connection conn = Main.getDatabaseManager().getConnection()) {
                String sql = "SELECT id, title, artist, album, duration, language, upload_user_id, created_at, updated_at, max_quality FROM music WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, editRequest.id());
                    
                    ResultSet rs = stmt.executeQuery();
                    
                    if (rs.next()) {
                        updatedMusic = new Music(
                                rs.getInt("id"),
                                rs.getString("title"),
                                rs.getString("artist"),
                                rs.getString("album"),
                                rs.getInt("duration"),
                                MusicAssetLocator.fileApiUrl(rs.getInt("id")),
                                MusicAssetLocator.coverApiUrl(rs.getInt("id")),
                                rs.getString("language"),
                                null,
                                rs.getInt("upload_user_id"),
                                rs.getTimestamp("created_at").toString(),
                                rs.getTimestamp("updated_at").toString(),
                                MusicAssetLocator.coverApiUrl(rs.getInt("id")),
                                rs.getString("max_quality"));
                    }
                }
            }
            
            MusicIngestSupport.invalidateRecognitionIndex();

            HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(true, ApiMessages.translate("music.edit.ok"), updatedMusic));
            
        } catch (Exception e) {
            // JSON 解析异常的消息含内部类名与字段清单，只能落日志，响应只回泛化提示
            logger.warn("编辑音乐请求处理失败", e);
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("auth.bad_request"));
        }
    }

    private Integer resolveValidUploadUserId(Connection conn, Integer uploadUserId) throws SQLException {
        if (uploadUserId == null) {
            return null;
        }
        if (MusicIngestSupport.isUserExists(conn, uploadUserId)) {
            return uploadUserId;
        }
        logger.warn("提供的upload_user_id {} 不存在于users表中，将使用NULL", uploadUserId);
        return null;
    }

    /** 从 music 查询结果行映射为 Music（列与顺序一致，提取自 3 处相同内联代码）。 */
    private static Music mapMusic(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        return new Music(
                id,
                rs.getString("title"),
                rs.getString("artist"),
                rs.getString("album"),
                rs.getInt("duration"),
                MusicAssetLocator.fileApiUrl(id),
                MusicAssetLocator.coverApiUrl(id),
                rs.getString("language"),
                rs.getString("tags"),
                rs.getInt("upload_user_id"),
                rs.getTimestamp("created_at").toString(),
                rs.getTimestamp("updated_at").toString(),
                MusicAssetLocator.coverApiUrl(id),
                rs.getString("max_quality"));
    }

    // 内部类用于表示音乐对象
    // 音乐对象：只读数据载体。coverUrl 由构造时按 id 计算。
    public record Music(
            int id, String title, String artist, String album, int duration,
            String filePath, String coverFilePath, String language, String tags,
            int uploadUserId, String createdAt, String updatedAt, String coverUrl,
            String maxQuality) {
    }
    
    // 内部类用于表示添加音乐请求
    // 添加音乐请求（Jackson 反序列化，只读）
    private record AddMusicRequest(
            String title, String artist, String album, Integer duration,
            String filePath, String coverFilePath, String language, String tags,
            Integer uploadUserId) {
    }
    
    // 内部类用于表示编辑音乐请求
    // 编辑音乐请求（Jackson 反序列化，只读）
    private record EditMusicRequest(
            Integer id, String title, String artist, String album, Integer duration,
            String filePath, String coverFilePath, String language, String tags,
            Integer uploadUserId, String lyrics) {
    }
    
    // 内部类用于表示音乐列表响应
    private record MusicListResponse(boolean success, String message, List<Music> data) {
    }
    
    // 内部类用于表示单个音乐响应
    private record MusicResponse(boolean success, String message, Music data) {
    }
    
    // 保存歌词到数据库（失败时抛异常，由调用方返回明确错误）
    private void saveLyricsToDatabase(Integer musicId, String lyricsContent) {
        MusicIngestSupport.saveLyricsAndRebuild(musicId, lyricsContent, "admin", logger);
    }
    
    // 内部类用于表示成功响应
    
    // 内部类用于表示错误响应
}
