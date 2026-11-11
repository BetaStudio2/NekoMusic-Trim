package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.neko.music.Main;
import com.neko.music.model.UserUpload;
import com.neko.music.service.EmbeddedMetadataSyncService;
import com.neko.music.service.MusicIngestSupport;
import com.neko.music.service.UploadStorageRecovery;
import com.neko.music.util.AtomicFiles;
import com.neko.music.util.MusicAdMetadataPatcher;
import com.neko.music.util.MusicPinyinColumns;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminUploadAuditHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(AdminUploadAuditHandler.class);
    private static final String MUSIC_AUDIO_DIR = "Music";

    /** 审核提交后的歌词索引、声纹刷新与通知均不阻塞接口响应。 */
    private static final ExecutorService POST_AUDIT_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("audit-post-", 0).factory());

    /** 审核结果站内消息类型，与「站内消息」文档中的 type 取值一致。 */
    private static final String NOTIFY_TYPE_UPLOAD_APPROVED = "upload_approved";
    private static final String NOTIFY_TYPE_UPLOAD_REJECTED = "upload_rejected";

    /** 未填拒绝原因时的兜底文案（写入站内消息与邮件共用）。 */
    private static final String DEFAULT_REJECT_REASON = "管理员拒绝审核";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 验证管理员权限
        if (!verifyAdmin(request, response)) {
            return;
        }
        
        // 检查是否有审核查看权限
        if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.AUDIT_VIEW)) {
            logger.warn("权限不足，无审核查看权限");
            return;
        }
        
        String pathInfo = request.getPathInfo();
        
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.equals("/pending")) {
            // 获取待审核列表
            handleGetPendingUploads(response);
        } else {
            sendErrorResponse(response, 404, "admin.upload_record.resource_not_found");
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 验证管理员权限
        if (!verifyAdmin(request, response)) {
            return;
        }
        
        String pathInfo = request.getPathInfo();
        
        if (pathInfo != null && pathInfo.startsWith("/approve/")) {
            // 审核通过
            // 检查是否有审核通过权限
            if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.AUDIT_APPROVE)) {
                logger.warn("权限不足，无审核通过权限");
                return;
            }
            int uploadId = parseUploadId(pathInfo.substring(9), response);
            if (uploadId < 0) {
                return;
            }
            handleApproveUpload(uploadId, request, response);
        } else if (pathInfo != null && pathInfo.startsWith("/reject/")) {
            // 审核拒绝
            // 检查是否有审核拒绝权限
            if (!com.neko.music.util.PermissionHelper.checkPermission(request, response, com.neko.music.util.AdminPermissionUtil.Permission.AUDIT_REJECT)) {
                logger.warn("权限不足，无审核拒绝权限");
                return;
            }
            int uploadId = parseUploadId(pathInfo.substring(8), response);
            if (uploadId < 0) {
                return;
            }
            handleRejectUpload(uploadId, request, response);
        } else {
            sendErrorResponse(response, 404, "admin.upload_record.resource_not_found");
        }
    }
    
    /**
     * 验证管理员权限
     */
    private boolean verifyAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendErrorResponse(response, 401, "auth.unauthorized");
            return false;
        }
        
        String token = authHeader.substring(7);
        boolean isValid = Main.getAdminAuthService().validateAdminToken(token);
        if (!isValid) {
            sendErrorResponse(response, 401, "auth.unauthorized");
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取待审核列表
     */
    private void handleGetPendingUploads(HttpServletResponse response) throws IOException {
        try {
            String sql = """
                SELECT u.id, u.user_id, u.title, u.artist, u.album, u.language, u.tags, u.duration,
                       u.music_file_path, u.cover_file_path, u.lyrics_file_path, u.status, u.created_at
                FROM user_uploads u
                WHERE u.status = 'pending'
                ORDER BY u.created_at DESC
                """;
            
            List<Map<String, Object>> pendingUploads = new ArrayList<>();
            
            try (Connection conn = Main.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    Map<String, Object> upload = new HashMap<>();
                    upload.put("id", rs.getInt("id"));
                    upload.put("userId", rs.getInt("user_id"));
                    upload.put("title", rs.getString("title"));
                    upload.put("artist", rs.getString("artist"));
                    upload.put("album", rs.getString("album"));
                    upload.put("language", rs.getString("language"));
                    upload.put("tags", rs.getString("tags"));
                    upload.put("duration", rs.getInt("duration"));
                    upload.put("musicFilePath", rs.getString("music_file_path"));
                    upload.put("coverFilePath", rs.getString("cover_file_path"));
                    upload.put("lyricsFilePath", rs.getString("lyrics_file_path"));
                    upload.put("status", rs.getString("status"));
                    // 将 Timestamp 转换为字符串
                    java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                    upload.put("createdAt", createdAt != null ? createdAt.toString() : null);
                    
                    pendingUploads.add(upload);
                }
            }

            java.util.Map<Integer, String> nicknames = Main.getNicknameResolver().resolveByLocalUserIds(
                    pendingUploads.stream()
                            .map(upload -> (Integer) upload.get("userId"))
                            .toList(),
                    "未知用户");
            for (Map<String, Object> upload : pendingUploads) {
                int uploaderId = (Integer) upload.get("userId");
                upload.put("userNickname", nicknames.getOrDefault(uploaderId, "未知用户"));
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", pendingUploads);
            
            sendJsonResponse(response, result);
            logger.info("获取待审核列表成功，共 {} 条记录", pendingUploads.size());
            
        } catch (Exception e) {
            logger.error("获取待审核列表失败: " + e.getMessage(), e);
            sendErrorResponse(response, 500, "admin.upload_audit.list_failed");
        }
    }
    
    /**
     * 审核通过 - 将文件从审核目录迁移到正常目录，并插入到music表
     */
    private void handleApproveUpload(int uploadId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Connection conn = null;
        int musicId = 0;
        boolean committed = false;
        // 记录正式文件的创建操作：事务失败时只删除新建副本；上传源文件在提交前始终保留
        Deque<Runnable> fileUndoLog = new ArrayDeque<>();
        
        try {
            // 获取管理员ID
            int adminId = requireAdminId(request, response);
            if (adminId <= 0) {
                return;
            }
            
            // 获取上传记录
            com.neko.music.database.UserUploadDatabaseManager uploadManager = 
                new com.neko.music.database.UserUploadDatabaseManager(Main.getDatabaseManager());
            UserUpload upload = findPendingUpload(uploadManager, uploadId, response);
            if (upload == null) {
                return;
            }
            
            // 旧版本可能在事务提交前把源文件移到了 temp_ 路径；重试时先原位恢复。
            UploadStorageRecovery.restoreLegacyTempFiles(upload);

            Path sourceMusicPath = Paths.get(upload.getMusicFilePath());
            Path sourceCoverPath = upload.getCoverFilePath() == null || upload.getCoverFilePath().isBlank()
                    ? null : Paths.get(upload.getCoverFilePath());
            if (!Files.isRegularFile(sourceMusicPath)) {
                sendErrorResponse(response, 500, "admin.upload_audit.audio_missing");
                return;
            }
            if (sourceCoverPath != null && !Files.isRegularFile(sourceCoverPath)) {
                sendErrorResponse(response, 500, "admin.upload_audit.cover_missing");
                return;
            }

            // 创建曲库音频目录
            File audioDir = new File(MUSIC_AUDIO_DIR);
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            // 使用事务确保原子性
            conn = Main.getDatabaseManager().getConnection();
            conn.setAutoCommit(false);
            
            // 在事务内插入到music表获取音乐ID（与 FileUploadHandler 一致，写入拼音检索列）
            String albumVal = upload.getAlbum() != null && !upload.getAlbum().isEmpty()
                    ? upload.getAlbum()
                    : "未知专辑";
            String title = upload.getTitle();
            String artist = upload.getArtist();

            String insertMusicSql = """
                INSERT INTO music (title, artist, album, duration, file_format, language, tags, upload_user_id,
                    title_pinyin, title_pinyin_initials, title_word_initials,
                    artist_pinyin, artist_pinyin_initials, artist_word_initials, album_pinyin)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement pstmt = conn.prepareStatement(insertMusicSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {

                pstmt.setString(1, title);
                pstmt.setString(2, artist);
                pstmt.setString(3, albumVal);
                pstmt.setInt(4, upload.getDuration());
                pstmt.setString(5, getFileExtensionWithoutDot(upload.getMusicFilePath()));
                pstmt.setString(6, upload.getLanguage());
                pstmt.setString(7, upload.getTags());
                pstmt.setInt(8, upload.getUserId());
                // 预计算拼音列
                MusicPinyinColumns.bind(pstmt, 9, title, artist, albumVal);

                int affectedRows = pstmt.executeUpdate();

                if (affectedRows > 0) {
                    try (java.sql.ResultSet rs = pstmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            musicId = rs.getInt(1);
                            logger.info("音乐已插入到music表，ID: {}", musicId);
                        }
                    }
                }
            }

            if (musicId == 0) {
                conn.rollback();
                undoFileMoves(fileUndoLog);
                sendErrorResponse(response, 500, "admin.upload_record.write_failed");
                return;
            }

            // 使用音乐ID生成最终的文件名
            String newMusicFileName = musicId + getFileExtension(upload.getMusicFilePath());

            // 从仍保留的源文件生成正式文件；数据库提交前绝不删除源文件，因此重启后仍可重试。
            String newMusicPath = Paths.get(MUSIC_AUDIO_DIR, newMusicFileName).toString();
            AtomicFiles.linkOrCopyAndReplace(sourceMusicPath, Paths.get(newMusicPath));
            fileUndoLog.push(() -> deleteQuietly(Paths.get(newMusicPath)));
            logger.info("生成正式音乐文件: {} -> {}", sourceMusicPath, newMusicPath);

            // 在事务提交和删除待审源图前先把封面写入正式音频；失败时保留上传记录供重试。
            if (sourceCoverPath != null) {
                MusicAdMetadataPatcher.embedCoverOnly(Paths.get(newMusicPath), sourceCoverPath);
            }

            if (upload.getLyricsFilePath() != null && !upload.getLyricsFilePath().isEmpty()
                    && Files.exists(Paths.get(upload.getLyricsFilePath()))) {
                String lyricsContent = Files.readString(Paths.get(upload.getLyricsFilePath()), StandardCharsets.UTF_8);
                Main.getLyricsDatabaseManager().upsert(conn, musicId, lyricsContent, "user_upload");
                logger.info("审核通过歌词已保存到数据库 musicId={}", musicId);
            }

            // 状态更新必须复用同一连接，否则它会在 conn.commit() 之前单独提交，
            // 后续提交失败时就会出现「music 行回滚了、上传记录却已是 approved」
            if (!uploadManager.approveUpload(conn, uploadId)) {
                throw new IOException("更新审核状态失败 uploadId=" + uploadId);
            }
            
            // 提交事务
            conn.commit();
            committed = true;
            fileUndoLog.clear();
            int approvedMusicId = musicId;

            // 事务已提交，正式文件已独立存在，再清理审核目录中的源文件。
            deleteUploadFiles(upload);

            // 音频可能马上被播放，标签重写必须在响应前完成，避免与读取并发。
            syncEmbeddedMetadataQuietly(approvedMusicId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", ApiMessages.translate("admin.upload_audit.approved"));
            result.put("data", Map.of(
                "uploadId", uploadId,
                "status", "approved"
            ));

            // 先回应请求；以下均为事务提交后的尽力而为动作，失败不影响审核结果
            sendJsonResponse(response, result);
            logger.info("审核通过成功，上传ID: {}", uploadId);

            submitPostAuditTask(uploadId, "通过", () -> {
                scheduleReviewApprovedEmail(upload);
                notifyUploadReview(upload, NOTIFY_TYPE_UPLOAD_APPROVED,
                        "《" + upload.getTitle() + "》审核通过",
                        "你上传的音乐" + artistSuffix(upload.getArtist()) + "已加入曲库，现在可以播放了。",
                        "/detail/" + approvedMusicId);

                try {
                    if (Main.getLyricsSearchIndex() != null) {
                        Main.getLyricsSearchIndex().rebuildOne(approvedMusicId);
                    }
                } catch (Exception e) {
                    logger.warn("重建歌词索引失败 musicId={}", approvedMusicId, e);
                }

                // 仅在审核通过并正式进入曲库后，异步生成并发布新的声纹索引。
                MusicIngestSupport.invalidateRecognitionIndex();
            });
            
        } catch (Exception e) {
            logger.error("审核通过失败 uploadId={}", uploadId, e);
            
            // 回滚事务
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.rollback();
                }
            } catch (Exception rollbackEx) {
                logger.error("事务回滚失败 uploadId={}", uploadId, rollbackEx);
            }

            if (!committed) {
                undoFileMoves(fileUndoLog);
            }
            if (!response.isCommitted()) {
                sendErrorResponse(response, 500, "admin.upload_audit.approve_failed");
            }
        } finally {
            // 关闭连接
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.setAutoCommit(true);
                    conn.close();
                }
            } catch (Exception closeEx) {
                logger.error("关闭数据库连接失败: " + closeEx.getMessage(), closeEx);
            }
        }
    }
    
    /**
     * 审核拒绝
     */
    private void handleRejectUpload(int uploadId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 获取管理员ID
            int adminId = requireAdminId(request, response);
            if (adminId <= 0) {
                return;
            }
            
            // 读取拒绝原因
            StringBuilder body = new StringBuilder();
            java.io.BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            
            Map<String, String> requestData = Main.getObjectMapper().readValue(body.toString(), Map.class);
            String reason = requestData.getOrDefault("reason", DEFAULT_REJECT_REASON);
            
            // 获取上传记录
            com.neko.music.database.UserUploadDatabaseManager uploadManager = 
                new com.neko.music.database.UserUploadDatabaseManager(Main.getDatabaseManager());
            UserUpload upload = findPendingUpload(uploadManager, uploadId, response);
            if (upload == null) {
                return;
            }
            
            // 1. 先删数据库记录：删记录失败时文件还在，重试仍可处理；
            //    反过来先删文件再删记录，失败就会留下一条再也无法处理的 pending 记录
            if (!uploadManager.deleteUserUpload(uploadId)) {
                sendErrorResponse(response, 500, "admin.upload_record.delete_failed");
                return;
            }

            // 2. 记录已删除，文件属于尽力清理
            deleteUploadFiles(upload);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", ApiMessages.translate("admin.upload_audit.rejected"));
            result.put("data", Map.of(
                "uploadId", uploadId,
                "status", "rejected",
                "reason", reason
            ));

            // 先回应请求；邮件与站内消息均为后置通知，失败不影响审核结果
            sendJsonResponse(response, result);
            logger.info("审核拒绝成功，上传ID: {}, 原因: {}", uploadId, reason);

            submitPostAuditTask(uploadId, "拒绝", () -> {
                scheduleReviewRejectedEmail(upload, reason);
                notifyUploadReview(upload, NOTIFY_TYPE_UPLOAD_REJECTED,
                        "《" + upload.getTitle() + "》审核未通过",
                        "你上传的音乐" + artistSuffix(upload.getArtist()) + "未通过审核。原因：" + reason,
                        "/upload");
            });
            
        } catch (Exception e) {
            logger.error("审核拒绝失败 uploadId={}", uploadId, e);
            if (!response.isCommitted()) {
                sendErrorResponse(response, 500, "admin.upload_audit.reject_failed");
            }
        }
    }
    
    /**
     * 删除上传的文件
     */
    private void deleteUploadFiles(UserUpload upload) {
        try {
            // 删除音乐文件
            if (upload.getMusicFilePath() != null && !upload.getMusicFilePath().isEmpty()) {
                Path musicPath = Paths.get(upload.getMusicFilePath());
                if (Files.exists(musicPath)) {
                    Files.delete(musicPath);
                    logger.info("已删除音乐文件: {}", upload.getMusicFilePath());
                }
            }
            
            // 删除封面文件
            if (upload.getCoverFilePath() != null && !upload.getCoverFilePath().isEmpty()) {
                Path coverPath = Paths.get(upload.getCoverFilePath());
                if (Files.exists(coverPath)) {
                    Files.delete(coverPath);
                    logger.info("已删除封面文件: {}", upload.getCoverFilePath());
                }
            }
            
            // 删除歌词文件
            if (upload.getLyricsFilePath() != null && !upload.getLyricsFilePath().isEmpty()) {
                Path lyricsPath = Paths.get(upload.getLyricsFilePath());
                if (Files.exists(lyricsPath)) {
                    Files.delete(lyricsPath);
                    logger.info("已删除歌词文件: {}", upload.getLyricsFilePath());
                }
            }
        } catch (Exception e) {
            logger.error("删除文件失败: " + e.getMessage(), e);
        }
    }
    
    private String getFileExtension(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        int lastDotIndex = filePath.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return filePath.substring(lastDotIndex);
    }
    
    private String getFileExtensionWithoutDot(String filePath) {
        String ext = getFileExtension(filePath);
        return ext.isEmpty() ? "" : ext.substring(1);
    }

    /**
     * 解析路径中的上传 ID，非法时写出 400 并返回 -1，避免 {@link NumberFormatException}
     * 冒泡成容器默认的 500 响应。
     */
    private int parseUploadId(String raw, HttpServletResponse response) throws IOException {
        try {
            int uploadId = Integer.parseInt(raw.trim());
            if (uploadId <= 0) {
                throw new NumberFormatException("非正数");
            }
            return uploadId;
        } catch (NumberFormatException e) {
            sendErrorResponse(response, 400, "admin.upload_record.bad_id");
            return -1;
        }
    }

    /** 回滚时删除本次新建的正式文件；失败只记日志，源文件不受影响。 */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("回滚时删除正式文件失败: {}", path, e);
        }
    }

    /** 按逆序执行登记的回滚操作，删除本次事务产生的正式文件副本。 */
    private void undoFileMoves(Deque<Runnable> undoLog) {
        while (!undoLog.isEmpty()) {
            undoLog.pop().run();
        }
    }

    /** 内嵌元数据同步是后置增强，失败只记日志，不影响审核结果。 */
    private void syncEmbeddedMetadataQuietly(int musicId) {
        try {
            EmbeddedMetadataSyncService.syncOne(musicId);
        } catch (Exception e) {
            logger.warn("同步内嵌元数据失败 musicId={}", musicId, e);
        }
    }

    /** 审核通过/拒绝共用的前置校验：令牌有效。返回 adminId；无效时写出 401 并返回 -1。 */
    private int requireAdminId(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authHeader = request.getHeader("Authorization");
        String token = authHeader.substring(7);
        int adminId = getAdminIdByToken(token);
        if (adminId <= 0) {
            sendErrorResponse(response, 401, "auth.unauthorized");
        }
        return adminId;
    }

    /** 加载待审核上传记录；不存在/已审核时写出 404/400 并返回 null（与原两处内联一致）。 */
    private UserUpload findPendingUpload(
            com.neko.music.database.UserUploadDatabaseManager uploadManager,
            int uploadId, HttpServletResponse response) throws IOException {
        UserUpload upload = uploadManager.getUserUploadById(uploadId);
        if (upload == null) {
            sendErrorResponse(response, 404, "admin.upload_record.not_found");
            return null;
        }
        if (!"pending".equals(upload.getStatus())) {
            sendErrorResponse(response, 400, "admin.upload_record.already_audited");
            return null;
        }
        return upload;
    }
    
    /**
     * 审核结果站内消息：给上传者写一条消息（写入失败只记日志，不影响审核结果与邮件）。
     *
     * <p>消息落库即视为送达：在线用户由 {@code /api/user/notifications/stream} 实时推送，
     * 离线用户下次打开消息中心补拉即可看到。</p>
     */
    private void notifyUploadReview(UserUpload upload, String type, String title, String body, String link) {
        try {
            int created = Main.getUserNotificationService().notify(
                    upload.getUserId(), type, title, body, link, null);
            if (created <= 0) {
                logger.warn("写入审核结果站内消息失败: uploadId={}, type={}", upload.getId(), type);
            }
        } catch (Exception e) {
            logger.error("写入审核结果站内消息异常: uploadId={}, type={}", upload.getId(), type, e);
        }
    }

    /** 提交后置任务；任何异常只记日志，不再影响已经返回的审核响应。 */
    private void submitPostAuditTask(int uploadId, String action, Runnable task) {
        try {
            POST_AUDIT_EXECUTOR.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("审核{}后置任务失败 uploadId={}", action, uploadId, e);
                }
            });
        } catch (RuntimeException e) {
            logger.error("提交审核{}后置任务失败 uploadId={}", action, uploadId, e);
        }
    }

    /** 审核通过邮件交给后台邮件队列发送；响应已在调用前写出。 */
    private void scheduleReviewApprovedEmail(UserUpload upload) {
        String userEmail = getUserEmailById(upload.getUserId());
        if (userEmail == null || userEmail.isBlank()) {
            logger.warn("用户 {} 没有邮箱地址，无法发送审核通过邮件", upload.getUserId());
            return;
        }
        Main.getEmailService().scheduleReviewApprovedEmail(
                userEmail, upload.getTitle(), upload.getArtist());
    }

    /** 审核拒绝邮件交给后台邮件队列发送；响应已在调用前写出。 */
    private void scheduleReviewRejectedEmail(UserUpload upload, String reason) {
        String userEmail = getUserEmailById(upload.getUserId());
        if (userEmail == null || userEmail.isBlank()) {
            logger.warn("用户 {} 没有邮箱地址，无法发送审核拒绝邮件", upload.getUserId());
            return;
        }
        Main.getEmailService().scheduleReviewRejectedEmail(
                userEmail, upload.getTitle(), upload.getArtist(), reason);
    }

    /** 站内消息正文里的歌手片段：没有歌手时返回空串，避免出现空括号。 */
    private static String artistSuffix(String artist) {
        return artist == null || artist.isBlank() ? "" : "（" + artist.trim() + "）";
    }

    /**
     * 根据token获取管理员ID
     */
    private int getAdminIdByToken(String token) {
        return Main.getAdminAuthService().getAdminIdByToken(token).orElse(-1);
    }
    
    /**
     * 根据用户ID获取用户邮箱
     */
    private String getUserEmailById(int userId) {
        String sql = "SELECT email FROM users WHERE id = ?";
        
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, userId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("email");
                }
            }
        } catch (java.sql.SQLException e) {
            logger.error("获取用户邮箱失败: " + e.getMessage(), e);
        }
        return null;
    }
    
    
}
