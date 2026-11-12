package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.neko.music.Main;
import com.neko.music.util.AudioFileValidator;
import com.neko.music.util.RuntimeDiskGuard;
import com.neko.music.util.AudioIntegrityValidator;
import com.neko.music.util.TempAudioSpool;
import com.neko.music.util.LyricsFileValidator;
import com.neko.music.util.ImageUploadValidator;
import com.neko.music.util.SensitiveWordUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class UserUploadHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserUploadHandler.class);

    private static final String UPLOAD_DIR = "user_upload";
    
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 启用 multipart 支持
        request.setAttribute("org.eclipse.jetty.multipartConfig", new jakarta.servlet.MultipartConfigElement(System.getProperty("java.io.tmpdir")));
        
        // 验证用户登录
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendErrorResponse(response, 401, "auth.unauthorized");
            return;
        }
        
        String token = authHeader.substring(7);
        java.util.Optional<Integer> userIdOpt = Main.getUserAuthService().validateToken(token);
        if (userIdOpt.isEmpty()) {
            sendErrorResponse(response, 401, "auth.unauthorized");
            return;
        }
        
        int userId = userIdOpt.get();

        RuntimeDiskGuard.logStorageForOperation("用户上传音乐", "userId=" + userId);
        if (!RuntimeDiskGuard.hasSufficientSpaceForMusicWrites()) {
            sendErrorResponse(response, 507, RuntimeDiskGuard.uploadBlockedMessage());
            return;
        }
        
        try {
            // 获取表单数据
            String title = getPartString(request, "title");
            String artist = getPartString(request, "artist");
            String language = getPartString(request, "language");
            String tags = getPartString(request, "tags");
            String album = getPartString(request, "album");
            String durationStr = getPartString(request, "duration");
            String uploadUserIdStr = getPartString(request, "uploadUserId");
            
            // 验证必填字段
            if (title == null || title.isEmpty() || artist == null || artist.isEmpty() || language == null || language.isEmpty()) {
                sendErrorResponse(response, 400, "upload.fields_required");
                return;
            }

            // 验证违禁词
            if (SensitiveWordUtil.contains(title)) {
                sendErrorResponse(response, 400, "upload.title_contains_banned");
                return;
            }
            else logger.info("标题不包含违禁词");
            if (SensitiveWordUtil.contains(artist)) {
                sendErrorResponse(response, 400, "upload.artist_contains_banned");
                return;
            }
            else logger.info("歌手名不包含违禁词");
            if (album != null && !album.isEmpty() && SensitiveWordUtil.contains(album)) {
                sendErrorResponse(response, 400, "upload.album_contains_banned");
                return;
            }
            // 验证语言字段白名单
            if (!java.util.Set.of("中文", "粤语", "上海语", "英文", "日语", "韩语", "法语", "德语", "俄语", "纯音乐").contains(language)) {
                sendErrorResponse(response, 400, "upload.language_invalid");
                return;
            }
            if (tags != null && !tags.isEmpty() && SensitiveWordUtil.contains(tags)) {
                sendErrorResponse(response, 400, "upload.tags_contains_banned");
                return;
            }
            else logger.info("所有内容未包含违禁词");
            
            int duration = 0;
            if (durationStr != null && !durationStr.isEmpty()) {
                try {
                    duration = Integer.parseInt(durationStr);
                } catch (NumberFormatException e) {
                    // 忽略解析错误，使用默认值0
                }
            }
            
            // 获取文件
            Part musicFilePart = request.getPart("musicFile");
            Part coverFilePart = request.getPart("coverFile");
            Part lyricsFilePart = request.getPart("lyricsFile");
            
            if (musicFilePart == null || musicFilePart.getSize() == 0) {
                sendErrorResponse(response, 400, "upload.audio_required");
                return;
            }
            
            // 音乐：临时文件上先完成魔数、假无损/完整性校验，通过后再查重并落盘 user_upload（无效文件不打查重库、不占业务盘）
            String uploadBaseDir = UPLOAD_DIR;

            String musicFileExtension = getFileExtension(musicFilePart.getSubmittedFileName()).toLowerCase();
            String dotExt = musicFileExtension.startsWith(".") ? musicFileExtension : "." + musicFileExtension;
            String extNoDot = dotExt.substring(1);
            String timestamp = String.valueOf(System.currentTimeMillis());
            Path musicTemp = Files.createTempFile("neko_user_music_", "." + extNoDot);
            boolean deleteTempIfPresent = true;
            String musicFilePath;
            String fileFormat;
            AudioFileValidator.AudioFormat detectedFormat;
            try {
                Files.copy(musicFilePart.getInputStream(), musicTemp, StandardCopyOption.REPLACE_EXISTING);
                AudioFileValidator.FormatDetectionResult detectionResult =
                        AudioFileValidator.detectAndValidatePath(musicTemp, musicFileExtension);
                if (!detectionResult.isValid()) {
                    sendErrorResponse(response, 400, ApiMessages.translate("upload.audio_format_invalid", detectionResult.getErrorMessage()));
                    return;
                }
                detectedFormat = detectionResult.getFormat();
                switch (detectedFormat) {
                    case MP3:
                        fileFormat = "mp3";
                        break;
                    case FLAC:
                        fileFormat = "flac";
                        break;
                    case WAV:
                        fileFormat = "wav";
                        break;
                    default:
                        sendErrorResponse(response, 400, "upload.unsupported_audio");
                        return;
                }
                logger.info("检测到文件格式: {} (实际格式: {})", fileFormat, detectionResult.getFormatDescription());

                String audioProblem = AudioIntegrityValidator.validateSavedFile(musicTemp, detectedFormat);
                if (audioProblem != null) {
                    sendErrorResponse(response, 400, audioProblem);
                    return;
                }

                String duplicateType = isDuplicateMusic(title, artist, album);
                if (duplicateType != null) {
                    if ("pending".equals(duplicateType)) {
                        sendErrorResponse(response, 409, "upload.duplicate_submission");
                    } else {
                        sendErrorResponse(response, 409, "upload.duplicate_music");
                    }
                    return;
                }

                File uploadDir = new File(uploadBaseDir);
                if (!uploadDir.exists()) {
                    uploadDir.mkdirs();
                }
                String musicFileName = "music_" + timestamp + "." + fileFormat;
                musicFilePath = Paths.get(uploadBaseDir, musicFileName).toString();
                TempAudioSpool.commitReplace(musicTemp, Paths.get(musicFilePath));
                deleteTempIfPresent = false;
            } catch (Exception e) {
                logger.error("处理音乐文件时出错", e);
                sendErrorResponse(response, 400, ApiMessages.translate("upload.process_failed", e.getMessage()));
                return;
            } finally {
                if (deleteTempIfPresent) {
                    try {
                        Files.deleteIfExists(musicTemp);
                    } catch (IOException ex) {
                        logger.warn("删除临时音乐失败: {}", musicTemp);
                    }
                }
            }
            
            // 保存封面文件（可选）
            String coverFilePath = null;
            if (coverFilePart != null && coverFilePart.getSize() > 0) {
                ImageUploadValidator.ValidationResult imageValidation =
                        ImageUploadValidator.validatePart(coverFilePart, ImageUploadValidator.DEFAULT_MAX_IMAGE_BYTES);
                if (!imageValidation.isValid()) {
                    sendErrorResponse(response, 400, imageValidation.getErrorMessage());
                    deleteFileIfExists(musicFilePath);
                    return;
                }

                String coverFileName = "cover_" + timestamp + imageValidation.getExtension();
                coverFilePath = Paths.get(uploadBaseDir, coverFileName).toString();
                saveFile(coverFilePart, coverFilePath);
            }
            
            // 保存歌词文件（可选）
            String lyricsFilePath = null;
            if (lyricsFilePart != null && lyricsFilePart.getSize() > 0) {
                // .lrc 走行级歌词校验；.neko 走 neko-v1 JSON 校验，二者共用同一个上传入口。
                String lyricsExtension = getFileExtension(lyricsFilePart.getSubmittedFileName());
                String lyricsFileName = "lyrics_" + timestamp + lyricsExtension;
                if (!LyricsFileValidator.isSupportedFile(lyricsFileName)) {
                    sendErrorResponse(response, 400, "upload.lyrics_only_lrc");
                    // 删除已上传的音乐和封面文件
                    deleteFileIfExists(musicFilePath);
                    deleteFileIfExists(coverFilePath);
                    return;
                }

                byte[] normalizedLyrics;
                try (InputStream lyricsInputStream = lyricsFilePart.getInputStream()) {
                    LyricsFileValidator.ValidationResult validationResult =
                            LyricsFileValidator.validate(lyricsFileName, lyricsInputStream, lyricsFilePart.getSize());
                    if (!validationResult.isValid()) {
                        sendErrorResponse(response, 400, ApiMessages.translate("upload.lyrics_format_invalid", validationResult.getErrorMessage()));
                        // 删除已上传的音乐和封面文件
                        deleteFileIfExists(musicFilePath);
                        deleteFileIfExists(coverFilePath);
                        return;
                    }
                    normalizedLyrics = validationResult.getNormalizedContent().getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    logger.error("校验歌词文件时出错", e);
                    sendErrorResponse(response, 400, ApiMessages.translate("upload.lyrics_validate_failed", e.getMessage()));
                    // 删除已上传的音乐和封面文件
                    deleteFileIfExists(musicFilePath);
                    deleteFileIfExists(coverFilePath);
                    return;
                }

                lyricsFilePath = Paths.get(uploadBaseDir, lyricsFileName).toString();
                Files.write(Paths.get(lyricsFilePath), normalizedLyrics);
            } else {
                // 如果没有上传歌词文件，使用资源目录中的 no_lrc.lrc
                String lyricsFileName = "lyrics_" + timestamp + ".lrc";
                lyricsFilePath = Paths.get(uploadBaseDir, lyricsFileName).toString();
                copyResourceFile("no_lrc.lrc", lyricsFilePath);
            }
            
            // 创建上传记录
            com.neko.music.model.UserUpload upload = new com.neko.music.model.UserUpload();
            upload.setUserId(userId);
            upload.setTitle(title);
            upload.setArtist(artist);
            upload.setLanguage(language);
            upload.setTags(tags != null ? tags : "");
            upload.setAlbum(album != null ? album : "");
            upload.setDuration(duration);
            upload.setMusicFilePath(musicFilePath);
            upload.setCoverFilePath(coverFilePath);
            upload.setLyricsFilePath(lyricsFilePath);
            upload.setStatus("pending");
            upload.setCreatedAt(java.time.LocalDateTime.now());
            
            // 保存到数据库
            com.neko.music.database.UserUploadDatabaseManager uploadDatabaseManager = 
                new com.neko.music.database.UserUploadDatabaseManager(Main.getDatabaseManager());
            int uploadId = uploadDatabaseManager.createUserUpload(upload);
            
            if (uploadId > 0) {
                // 发送通知
                try {
                    boolean notificationSent = Main.getNotificationService().sendMusicUploadNotification(
                        title,
                        artist,
                        userId
                    );
                    
                    if (notificationSent) {
                        logger.info("上传通知已发送");
                    } else {
                        logger.warn("上传通知发送失败");
                    }
                } catch (Exception e) {
                    logger.error("发送上传通知失败: " + e.getMessage(), e);
                    // 通知发送失败不影响上传操作
                }
                
                // 返回成功响应
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("message", ApiMessages.translate("upload.ok_pending_review"));
                result.put("data", Map.of(
                    "id", uploadId,
                    "status", "pending",
                    "createdAt", upload.getCreatedAt().toString()
                ));
                
                sendJsonResponse(response, result);
                logger.info("用户ID {} 上传音乐成功，记录ID: {}", userId, uploadId);
            } else {
                // 保存失败，删除已上传的文件
                deleteFileIfExists(musicFilePath);
                deleteFileIfExists(coverFilePath);
                deleteFileIfExists(lyricsFilePath);
                
                sendErrorResponse(response, 500, "upload.save_record_failed");
            }
            
        } catch (Exception e) {
            logger.error("处理用户上传失败: " + e.getMessage(), e);
            sendErrorResponse(response, 500, ApiMessages.translate("common.server_error", e.getMessage()));
        }
    }
    
    private String getPartString(HttpServletRequest request, String partName) throws ServletException, IOException {
        Part part = request.getPart(partName);
        if (part == null || part.getSize() == 0) {
            return null;
        }
        return new String(part.getInputStream().readAllBytes(), "UTF-8").trim();
    }
    
    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }

        String extension = fileName.substring(lastDotIndex).toLowerCase(Locale.ROOT);

        // 只允许简单扩展名（单一路径组件），禁止路径分隔符、父目录引用及异常字符
        if (extension.contains("/") || extension.contains("\\") || extension.contains("..")) {
            return "";
        }
        if (!extension.matches("^\\.[a-z0-9]{1,10}$")) {
            return "";
        }

        return extension;
    }
    
    private void saveFile(Part part, String filePath) throws IOException {
        Path targetPath = Paths.get(filePath);
        Files.copy(part.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
    }
    
    private void copyResourceFile(String resourceName, String targetPath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream != null) {
                Files.copy(inputStream, Paths.get(targetPath), StandardCopyOption.REPLACE_EXISTING);
            } else {
                logger.warn("资源文件不存在: {}", resourceName);
            }
        }
    }
    
    private void deleteFileIfExists(String filePath) {
        if (filePath != null) {
            try {
                Files.deleteIfExists(Paths.get(filePath));
            } catch (IOException e) {
                logger.warn("删除文件失败: " + filePath);
            }
        }
    }
    
    // 查重检查：检查是否已存在相同的音乐（包括已审核通过的和等待审核的）
    // 返回值：null 表示不重复，"music" 表示 music 表重复，"pending" 表示等待审核列表重复
    private String isDuplicateMusic(String title, String artist, String album) {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            // 1. 检查 user_uploads 表（等待审核的音乐）- 优先检查，防止重复提交
            if (hasDuplicateRow(conn,
                    "SELECT artist, album FROM user_uploads WHERE title = ? AND status = 'pending'",
                    title, artist, album)) {
                return "pending"; // 发现重复（等待审核中）
            }
            
            // 2. 检查 music 表（已审核通过的音乐）
            if (hasDuplicateRow(conn, "SELECT artist, album FROM music WHERE title = ?",
                    title, artist, album)) {
                return "music"; // 发现重复（已审核通过）
            }
        } catch (SQLException e) {
            logger.error("查重检查失败: " + e.getMessage(), e);
        }
        return null;
    }

    // 按标题查询候选行，命中相同的艺术家或专辑即视为重复
    private boolean hasDuplicateRow(Connection conn, String sql, String title, String artist, String album)
            throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, title);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String existingArtist = rs.getString("artist");
                    String existingAlbum = rs.getString("album");
                    
                    // 如果标题相同，检查艺术家或专辑是否相同
                    if (artist.equals(existingArtist) || 
                        (album != null && !album.isEmpty() && album.equals(existingAlbum))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    
}
