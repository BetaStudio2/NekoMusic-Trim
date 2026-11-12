package com.neko.music.handlers;

import com.neko.music.util.HandlerResponses;

import com.neko.music.model.ErrorResponse;
import com.neko.music.Main;
import com.neko.music.service.EmbeddedMetadataSyncService;
import com.neko.music.service.MusicIngestSupport;
import com.neko.music.util.CoverArtCache;
import com.neko.music.util.EmbeddedCoverReader;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.util.MusicPinyinColumns;
import com.neko.music.util.RuntimeDiskGuard;
import com.neko.music.util.AudioFileValidator;
import com.neko.music.util.AudioIntegrityValidator;
import com.neko.music.util.ImageUploadValidator;
import com.neko.music.util.LyricsFileValidator;
import com.neko.music.util.MusicAdMetadataPatcher;
import com.neko.music.util.TempAudioSpool;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.TagException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import com.neko.music.i18n.ApiMessages;

public class FileUploadHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(FileUploadHandler.class);

    // 定义上传目录（相对于JAR运行目录）
    private static final String MUSIC_DIR = "Music";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        RuntimeDiskGuard.logStorageForOperation("管理员上传音乐", "POST");
        if (rejectIfLowDisk(response)) {
            return;
        }
        
        try {
            // 设置请求为multipart类型，用于文件上传
            request.setCharacterEncoding("UTF-8");
            
            // 获取所有上传的文件部分
            Collection<Part> parts = request.getParts();
            MusicForm form = parseMusicForm(request, parts, false);
            String title = form.title;
            String artist = form.artist;
            String album = form.album;
            String language = form.language;
            String tags = form.tags;
            int duration = form.duration != null ? form.duration : 0;
            Integer uploadUserId = form.uploadUserId;
            Part musicFilePart = form.musicFilePart;
            Part coverFilePart = form.coverFilePart;
            
            // 验证必要字段
            if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty() || 
                language == null || language.trim().isEmpty()) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("upload.music_fields_required"));
                return;
            }
            
            // 验证歌词文件必填、类型与格式
            if (!validateLyricsOrReject(response, form.lyricsFilePart, true)) {
                return;
            }
            
            if (musicFilePart == null) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("upload.audio_file_required"));
                return;
            }
            
            // 创建音频目录
            Path musicPath = Paths.get(MUSIC_DIR);
            Files.createDirectories(musicPath);
            
            // 检查封面文件类型（如果是上传了的话）
            ImageUploadValidator.ValidationResult coverImageValidation = null;
            if (coverFilePart != null) {
                coverImageValidation = validateCoverOrReject(response, coverFilePart);
                if (coverImageValidation == null) {
                    return;
                }
            }

            // 音乐：先写入系统临时目录校验，通过后再入库并落盘；封面/歌词属于同一次入库，
            // 任一环节失败都回滚已写入的库行与文件
            String musicFileName = getFileName(musicFilePart);
            String fileExtension = getFileExtension(musicFileName).toLowerCase();
            Path musicTemp = Files.createTempFile("neko_admin_music_", "." + fileExtension);
            Path coverTemp = null;
            boolean deleteMusicTempIfPresent = true;
            int musicId = 0;
            String musicFilePath = null;
            String fileFormat = null;
            try {
                if (coverFilePart != null) {
                    coverTemp = copyCoverToTemp(coverFilePart, coverImageValidation);
                }
                Files.copy(musicFilePart.getInputStream(), musicTemp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                AudioValidation audio = validateAudioFile(musicTemp, fileExtension, duration);
                duration = audio.duration();
                if (!audio.valid()) {
                    HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(audio.errorMessage()));
                    return;
                }
                fileFormat = audio.fileFormat();

                // 音频通过后再查重，避免无效文件也访问库
                if (isDuplicateMusic(title, artist, album)) {
                    HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(false, ApiMessages.translate("music.duplicate"), null));
                    return;
                }

                MusicAdMetadataPatcher.patchQuietly(musicTemp);

                musicId = insertMusicToDatabase(title, artist, album, language, tags, duration, uploadUserId, fileFormat);
                musicFilePath = MUSIC_DIR + File.separator + musicId + "." + fileFormat;
                TempAudioSpool.commitReplace(musicTemp, Paths.get(musicFilePath));
                deleteMusicTempIfPresent = false;
                logger.info("音乐文件已保存到: {}", musicFilePath);
                MusicIngestSupport.refreshQualityAfterIngest(musicId, Paths.get(musicFilePath), logger);

                // 歌词保存失败必须让整个请求失败，否则会返回「上传成功」但歌词没入库
                saveLyricsToDatabase(musicId, form.lyricsFilePart);

                if (duration == 0) {
                    int probedDuration = readAudioDurationFromPath(musicFilePath);
                    if (probedDuration > 0) {
                        duration = probedDuration;
                        updateDurationInDatabase(musicId, duration);
                    }
                }
                if (coverTemp != null && !EmbeddedMetadataSyncService.syncOne(musicId, coverTemp)) {
                    throw new IOException("无法将封面嵌入音频文件");
                }
            } catch (Exception e) {
                if (musicId > 0) {
                    deleteMusicRecordById(musicId);
                    deleteQuietly(musicFilePath);
                    Main.getLyricsDatabaseManager().delete(musicId);
                }
                logger.error("处理管理员上传音乐文件失败", e);
                HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                        new ErrorResponse("upload.failed"));
                return;
            } finally {
                if (deleteMusicTempIfPresent) {
                    try {
                        Files.deleteIfExists(musicTemp);
                    } catch (IOException io) {
                        logger.warn("删除临时音乐文件失败: {}", musicTemp, io);
                    }
                }
                deleteQuietly(coverTemp);
            }

            if (coverTemp == null) {
                // 歌词/基础标签同步进音频文件（保留已有内嵌封面与广告元数据）
                EmbeddedMetadataSyncService.syncOne(musicId);
            }

            // 获取完整的音乐信息
            Music music = getMusicById(musicId);
            MusicIngestSupport.invalidateRecognitionIndex();
            
            HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(true, ApiMessages.translate("music.upload.ok"), music));
            
        } catch (Exception e) {
            logger.error("上传音乐时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                    new ErrorResponse("upload.failed"));
        }
    }
    
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 检查管理员权限
        if (HandlerResponses.rejectIfUnauthorized(request, response)) {
            return;
        }
        RuntimeDiskGuard.logStorageForOperation("管理员更新音乐", "PUT");
        if (rejectIfLowDisk(response)) {
            return;
        }
        
        Path coverTempForSync = null;
        try {
            // 设置请求为multipart类型，用于文件上传
            request.setCharacterEncoding("UTF-8");
            
            // 获取所有上传的文件部分
            Collection<Part> parts = request.getParts();
            MusicForm form = parseMusicForm(request, parts, true);
            Integer id = form.id;
            if (id == null) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("music.id_required"));
                return;
            }

            // 获取当前音乐信息
            Music currentMusic = getMusicById(id);
            if (currentMusic == null) {
                HandlerResponses.writeJson(response, HttpStatus.NOT_FOUND_404, new ErrorResponse("music.not_found"));
                return;
            }

            // 编辑采用部分更新：未提交的字段沿用数据库当前值。
            String title = form.title != null ? form.title : currentMusic.getTitle();
            String artist = form.artist != null ? form.artist : currentMusic.getArtist();
            String album = form.album != null ? form.album : currentMusic.getAlbum();
            String language = form.language != null ? form.language : currentMusic.getLanguage();
            String tags = form.tags != null ? form.tags : currentMusic.getTags();
            int duration = form.duration != null ? form.duration : currentMusic.getDuration();
            Integer uploadUserId = form.uploadUserId != null ? form.uploadUserId : currentMusic.getUploadUserId();
            Part musicFilePart = form.musicFilePart;
            Part coverFilePart = form.coverFilePart;

            // 歌词文件在编辑时可选；仅在提供时校验格式。
            if (!validateLyricsOrReject(response, form.lyricsFilePart, false)) {
                return;
            }
            
            // 创建音频目录
            Path musicPath = Paths.get(MUSIC_DIR);
            Files.createDirectories(musicPath);

            if (coverFilePart != null) {
                ImageUploadValidator.ValidationResult coverImageValidation =
                        validateCoverOrReject(response, coverFilePart);
                if (coverImageValidation == null) {
                    return;
                }
                coverTempForSync = copyCoverToTemp(coverFilePart, coverImageValidation);
            } else if (musicFilePart != null) {
                // 换音频但未提供新封面时，把旧音频内嵌封面暂存后嵌入新音频。
                var existingCover = CoverArtCache.find(id);
                if (existingCover.isPresent()) {
                    coverTempForSync = writeCoverTemp(existingCover.get().data());
                }
            }
            
            // 检查是否上传了新的音乐文件
            if (musicFilePart != null) {
                String musicFileName = getFileName(musicFilePart);
                String fileExtension = getFileExtension(musicFileName).toLowerCase();
                Path musicTemp = Files.createTempFile("neko_admin_music_", "." + fileExtension);
                boolean deleteMusicTempIfPresent = true;
                String fileFormat = null;
                String musicFilePath = null;
                try {
                    Files.copy(musicFilePart.getInputStream(), musicTemp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // 换文件时未提交时长则由音频本身重新解析，不能沿用旧时长。
                    int audioDuration = form.duration != null ? form.duration : 0;
                    AudioValidation audio = validateAudioFile(musicTemp, fileExtension, audioDuration);
                    duration = audio.duration();
                    if (!audio.valid()) {
                        HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(audio.errorMessage()));
                        return;
                    }
                    fileFormat = audio.fileFormat();

                    MusicAdMetadataPatcher.patchQuietly(musicTemp);

                    musicFilePath = MUSIC_DIR + File.separator + id + "." + fileFormat;
                    // 先原子替换新音频，成功后再清理旧扩展名残留：覆盖失败时旧文件仍在
                    TempAudioSpool.commitReplace(musicTemp, Paths.get(musicFilePath));
                    deleteMusicTempIfPresent = false;
                    MusicAssetLocator.deleteAudioVariantsExcept(id, Paths.get(musicFilePath));
                    logger.info("音乐文件已保存到: {}", musicFilePath);
                    MusicIngestSupport.refreshQualityAfterIngest(id, Paths.get(musicFilePath), logger);

                    updateFileFormatInDatabase(id, fileFormat);
                } catch (Exception e) {
                    logger.error("更新音乐文件失败 id={}", id, e);
                    HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                            new ErrorResponse(ApiMessages.translate("music.update_file.failed")));
                    return;
                } finally {
                    if (deleteMusicTempIfPresent) {
                        try {
                            Files.deleteIfExists(musicTemp);
                        } catch (IOException io) {
                            logger.warn("删除临时音乐文件失败: {}", musicTemp, io);
                        }
                    }
                }
            }
            
            // 更新数据库中的音乐信息
            updateMusicInDatabase(id, title, artist, album, language, tags, duration, uploadUserId);

            // 语种先落库，随后写歌词时才能按“日语”硬性补全罗马音。
            if (form.lyricsFilePart != null) {
                saveLyricsToDatabase(id, form.lyricsFilePart);
            }

            // 歌词/基础标签与新封面同步进音频文件；未上传封面时保留音频原封面。
            boolean metadataSynced = EmbeddedMetadataSyncService.syncOne(id, coverTempForSync);
            if (coverTempForSync != null && !metadataSynced) {
                HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                        new ErrorResponse(ApiMessages.translate("music.update_file.failed")));
                return;
            }

            // 获取更新后的音乐信息
            Music updatedMusic = getMusicById(id);
            MusicIngestSupport.invalidateRecognitionIndex();
            
            HandlerResponses.writeJson(response, HttpStatus.OK_200, new MusicResponse(true, ApiMessages.translate("music.update.ok"), updatedMusic));
            
        } catch (Exception e) {
            logger.error("更新音乐时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                    new ErrorResponse(ApiMessages.translate("music.update.failed")));
        } finally {
            deleteQuietly(coverTempForSync);
        }
    }

    /** 校验歌词文件（可选时未提供直接通过；提供时校验 .lrc / .neko 扩展名与格式）。 */
    private boolean validateLyricsOrReject(HttpServletResponse response, Part lyricsFilePart,
                                           boolean required) throws IOException {
        if (lyricsFilePart == null) {
            if (!required) {
                return true;
            }
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(ApiMessages.translate("music.lyrics_file.required")));
            return false;
        }
        
        String lyricsFileName = getFileName(lyricsFilePart);
        if (!LyricsFileValidator.isSupportedFile(lyricsFileName)) {
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse("upload.lyrics_only_lrc"));
            return false;
        }
        
        // 校验歌词文件格式
        try (InputStream lyricsInputStream = lyricsFilePart.getInputStream()) {
            LyricsFileValidator.ValidationResult validationResult = LyricsFileValidator.validate(
                    lyricsFileName, lyricsInputStream, lyricsFilePart.getSize());
            if (!validationResult.isValid()) {
                HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(ApiMessages.translate("upload.lyrics_format_invalid", validationResult.getErrorMessage())));
                return false;
            }
        } catch (Exception e) {
            logger.error("校验歌词文件时出错", e);
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400,
                    new ErrorResponse(ApiMessages.translate("upload.lyrics_validate_failed_retry")));
            return false;
        }
        return true;
    }

    /** 校验封面图片，失败时写出对应响应并返回 null。 */
    private static ImageUploadValidator.ValidationResult validateCoverOrReject(HttpServletResponse response, Part coverFilePart) throws IOException {
        ImageUploadValidator.ValidationResult result =
                ImageUploadValidator.validatePart(coverFilePart, ImageUploadValidator.DEFAULT_MAX_IMAGE_BYTES);
        if (!result.isValid()) {
            HandlerResponses.writeJson(response, HttpStatus.BAD_REQUEST_400, new ErrorResponse(result.getErrorMessage()));
            return null;
        }
        return result;
    }

    private static Path copyCoverToTemp(Part coverPart,
                                        ImageUploadValidator.ValidationResult validation) throws IOException {
        Path temp = Files.createTempFile("neko_music_cover_", "." + validation.getExtensionWithoutDot());
        try (InputStream input = coverPart.getInputStream()) {
            Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static Path writeCoverTemp(byte[] data) throws IOException {
        String mime = EmbeddedCoverReader.sniffMime(data);
        String extension = switch (mime == null ? "" : mime) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        Path temp = Files.createTempFile("neko_music_cover_", extension);
        try {
            Files.write(temp, data);
            return temp;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** 音频校验结果：valid=false 时 errorMessage 为响应文案；valid=true 时 fileFormat/duration 可用。 */
    private record AudioValidation(boolean valid, String errorMessage, String fileFormat, int duration) {
    }

    /**
     * 检测实际音频格式、读取时长并做完整性校验。
     * 成功返回 fileFormat（mp3/flac/wav）与可能更新后的 duration；失败返回 errorMessage。
     */
    private AudioValidation validateAudioFile(Path musicTemp, String fileExtension, int duration) {
        AudioFileValidator.FormatDetectionResult detectionResult =
                AudioFileValidator.detectAndValidatePath(musicTemp, fileExtension);
        if (!detectionResult.isValid()) {
            return new AudioValidation(false, ApiMessages.translate("upload.audio_format_invalid", detectionResult.getErrorMessage()), null, duration);
        }
        String fileFormat;
        switch (detectionResult.getFormat()) {
            case MP3 -> fileFormat = "mp3";
            case FLAC -> fileFormat = "flac";
            case WAV -> fileFormat = "wav";
            default -> {
                return new AudioValidation(false, "upload.unsupported_audio", null, duration);
            }
        }
        logger.info("检测到文件格式: {} (实际格式: {})", fileFormat, detectionResult.getFormatDescription());

        if (duration == 0) {
            duration = readAudioDurationFromPath(musicTemp.toString());
        }

        AudioFileValidator.AudioFormat integrityFormat = switch (fileFormat) {
            case "flac" -> AudioFileValidator.AudioFormat.FLAC;
            case "wav" -> AudioFileValidator.AudioFormat.WAV;
            default -> AudioFileValidator.AudioFormat.MP3;
        };
        String audioIntegrityError = AudioIntegrityValidator.validateSavedFile(musicTemp, integrityFormat);
        if (audioIntegrityError != null) {
            return new AudioValidation(false, audioIntegrityError, null, duration);
        }
        return new AudioValidation(true, null, fileFormat, duration);
    }

    /** 解析后的 multipart 表单字段。doPost 不解析 id，doPut 解析。 */
    private static final class MusicForm {
        Integer id;
        String title;
        String artist;
        String album;
        String language;
        String tags;
        Integer duration;
        Integer uploadUserId;
        Part musicFilePart;
        Part coverFilePart;
        Part lyricsFilePart;
    }

    /**
     * 按字段名解析 multipart 表单（顺序与逐字段处理无关，行为与原内联循环一致）。
     *
     * @param parseId doPut 需要解析 id，doPost 不需要
     */
    private MusicForm parseMusicForm(HttpServletRequest request, Collection<Part> parts, boolean parseId) throws IOException {
        MusicForm form = new MusicForm();
        for (Part part : parts) {
            String fieldName = part.getName();
            
            if (parseId && "id".equals(fieldName)) {
                form.id = parseOptionalInt(getPartValue(request, part), form.id, "解析音乐ID失败");
            } else if ("title".equals(fieldName)) {
                form.title = getPartValue(request, part);
            } else if ("artist".equals(fieldName)) {
                form.artist = getPartValue(request, part);
            } else if ("album".equals(fieldName)) {
                form.album = getPartValue(request, part);
            } else if ("language".equals(fieldName)) {
                form.language = getPartValue(request, part);
            } else if ("tags".equals(fieldName)) {
                form.tags = getPartValue(request, part);
            } else if ("duration".equals(fieldName)) {
                form.duration = parseOptionalInt(getPartValue(request, part), form.duration, "解析音乐时长失败");
            } else if ("uploadUserId".equals(fieldName)) {
                form.uploadUserId = parseOptionalInt(getPartValue(request, part), form.uploadUserId, "解析上传用户ID失败");
            } else if ("musicFile".equals(fieldName) && part.getSize() > 0) {
                form.musicFilePart = part;
            } else if ("coverFile".equals(fieldName) && part.getSize() > 0) {
                form.coverFilePart = part;
            } else if ("lyricsFile".equals(fieldName) && part.getSize() > 0) {
                form.lyricsFilePart = part;
            }
        }
        return form;
    }

    private Integer parseOptionalInt(String raw, Integer fallback, String errorMessage) {
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                logger.error(errorMessage + ": " + raw, e);
            }
        }
        return fallback;
    }

    private String getPartValue(HttpServletRequest request, Part part) throws IOException {
        return new String(part.getInputStream().readAllBytes(), "UTF-8");
    }
    
    private String getFileName(Part part) {
        String contentDisposition = part.getHeader("content-disposition");
        String[] tokens = contentDisposition.split(";");
        for (String token : tokens) {
            if (token.trim().startsWith("filename")) {
                return token.substring(token.indexOf("=") + 2, token.length() - 1);
            }
        }
        return "";
    }
    
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "jpg"; // 默认扩展名
    }
    
    // 读取音频时长 - 从已保存的文件读取，避免额外临时文件
    private int readAudioDurationFromPath(String filePath) {
        try {
            File audioFile = new File(filePath);
            AudioFile af = AudioFileIO.read(audioFile);
            return af.getAudioHeader().getTrackLength();
        } catch (CannotReadException | IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
            logger.error("读取音频时长失败", e);
            return 0;
        }
    }

    // 读取音频时长 - 旧方法保留用于doPut中文件已存在的场景
    
    // 将音乐信息插入数据库
    private int insertMusicToDatabase(String title, String artist, String album, String language, String tags, int duration, Integer uploadUserId, String fileFormat) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            Integer validUploadUserId = resolveValidUploadUserId(conn, uploadUserId);
            return MusicIngestSupport.insertMusicRow(conn, title, artist,
                    album != null ? album : "未知专辑",
                    album,
                    language != null ? language : "未知语言",
                    tags != null ? tags : "",
                    duration, validUploadUserId, fileFormat,
                    "music.add_failed", "获取新音乐ID失败");
        }
    }

    private void deleteMusicRecordById(int musicId) {
        MusicIngestSupport.deleteMusicRecordById(musicId, logger, "删除无效 music 记录失败 id={}");
    }

    /**
     * 校验提供的 uploadUserId 是否存在于 users 表中；不存在则记录警告并返回 null。
     * 插入/更新共用（原两处内联逻辑一致）。
     */
    private Integer resolveValidUploadUserId(Connection conn, Integer uploadUserId) throws SQLException {
        if (uploadUserId == null) {
            return null;
        }
        if (MusicIngestSupport.isUserExists(conn, uploadUserId)) {
            return uploadUserId;
        }
        // 如果用户不存在，记录警告并使用null
        logger.warn("提供的upload_user_id {} 不存在于users表中，将使用NULL", uploadUserId);
        return null;
    }

    // 查重检查：检查是否已存在相同的音乐
    private boolean isDuplicateMusic(String title, String artist, String album) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            // 检查标题是否相同
            String sql = "SELECT artist, album FROM music WHERE title = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, title);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String existingArtist = rs.getString("artist");
                        String existingAlbum = rs.getString("album");
                        
                        // 如果标题相同，检查艺术家或专辑是否相同
                        if (artist.equals(existingArtist) || 
                            (album != null && album.equals(existingAlbum))) {
                            return true; // 发现重复
                        }
                    }
                }
            }
        }
        return false;
    }
    
    // 更新音乐信息到数据库
    private void updateMusicInDatabase(int id, String title, String artist, String album, String language, String tags, int duration,
                                      Integer uploadUserId) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            Integer validUploadUserId = resolveValidUploadUserId(conn, uploadUserId);

            String sql = "UPDATE music SET title = ?, artist = ?, album = ?, language = ?, tags = ?, duration = ?, upload_user_id = ?, title_pinyin = ?, title_pinyin_initials = ?, title_word_initials = ?, artist_pinyin = ?, artist_pinyin_initials = ?, artist_word_initials = ?, album_pinyin = ?, updated_at = NOW() WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, title);
                stmt.setString(2, artist);
                // 编辑接口做部分更新，null/空值均按调用方提交（或数据库当前值）原样写回。
                stmt.setString(3, album);
                stmt.setString(4, language);
                stmt.setString(5, tags);
                stmt.setInt(6, duration);
                stmt.setObject(7, validUploadUserId); // 使用验证后的用户ID或null
                // 预计算拼音列
                MusicPinyinColumns.bind(stmt, 8, title, artist, album);
                stmt.setInt(15, id);

                int rowsUpdated = stmt.executeUpdate();
                if (rowsUpdated == 0) {
                    throw new SQLException("更新音乐失败");
                }
            }
        }
    }

    // 更新文件格式到数据库
    private void updateFileFormatInDatabase(int id, String fileFormat) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "UPDATE music SET file_format = ?, updated_at = NOW() WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, fileFormat);
                stmt.setInt(2, id);

                int rowsUpdated = stmt.executeUpdate();
                if (rowsUpdated == 0) {
                    throw new SQLException("更新文件格式失败");
                }
            }
        }
    }

    // 更新时长到数据库
    private void updateDurationInDatabase(int id, int duration) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "UPDATE music SET duration = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, duration);
                stmt.setInt(2, id);
                stmt.executeUpdate();
            }
        }
    }
    
    // 根据ID获取音乐信息
    private Music getMusicById(int id) throws SQLException {
        Music music = null;
        
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, album, language, tags, duration, max_quality, upload_user_id, created_at, updated_at FROM music WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, id);
                
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    music = new Music();
                    music.setId(rs.getInt("id"));
                    music.setTitle(rs.getString("title"));
                    music.setArtist(rs.getString("artist"));
                    music.setAlbum(rs.getString("album"));
                    music.setLanguage(rs.getString("language"));
                    music.setTags(rs.getString("tags"));
                    music.setDuration(rs.getInt("duration"));
                    music.setMaxQuality(rs.getString("max_quality"));
                    music.setFilePath(MusicAssetLocator.fileApiUrl(music.getId()));
                    music.setCoverFilePath(MusicAssetLocator.coverApiUrl(music.getId()));
                    music.setUploadUserId(rs.getInt("upload_user_id"));
                    music.setCreatedAt(rs.getTimestamp("created_at").toString());
                    music.setUpdatedAt(rs.getTimestamp("updated_at").toString());
                }
            }
        }
        
        return music;
    }
    
    // 内部类用于表示音乐对象
    public static class Music {
        private int id;
        private String title;
        private String artist;
        private String album;
        private String language; // 语言
        private String tags; // 标签
        private int duration; // 时长，单位秒
        private String maxQuality; // 最高音质
        private String filePath;
        private String coverFilePath; // 封面路径
        private int uploadUserId;
        private String createdAt;
        private String updatedAt;
        
        // Getters and Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public int getDuration() { return duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public String getMaxQuality() { return maxQuality; }
        public void setMaxQuality(String maxQuality) { this.maxQuality = maxQuality; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getCoverFilePath() { return coverFilePath; }
        public void setCoverFilePath(String coverFilePath) { this.coverFilePath = coverFilePath; }
        public int getUploadUserId() { return uploadUserId; }
        public void setUploadUserId(int uploadUserId) { this.uploadUserId = uploadUserId; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    }
    
    // 内部类用于表示音乐响应
    private record MusicResponse(boolean success, String message, Music data) {
    }
    
    private static boolean rejectIfLowDisk(HttpServletResponse response) throws IOException {
        if (RuntimeDiskGuard.hasSufficientSpaceForMusicWrites()) {
            return false;
        }
        HandlerResponses.writeJson(response, HttpStatus.INSUFFICIENT_STORAGE_507, new ErrorResponse(RuntimeDiskGuard.uploadBlockedMessage()));
        return true;
    }

    // 保存歌词到数据库：失败时抛异常，由调用方回滚本次入库
    private void saveLyricsToDatabase(int musicId, Part lyricsFilePart) throws IOException {
        LyricsFileValidator.ValidationResult result;
        try (InputStream inputStream = lyricsFilePart.getInputStream()) {
            result = LyricsFileValidator.validate(getFileName(lyricsFilePart), inputStream, lyricsFilePart.getSize());
        }
        if (!result.isValid()) {
            throw new IOException(result.getErrorMessage());
        }
        MusicIngestSupport.saveLyricsAndRebuild(
                musicId, result.getNormalizedContent(), result.getFormat().databaseFormat(), logger);
    }

    /** 尽力删除文件，失败只打 WARN（用于回滚已落盘但不完整的产物）。 */
    private void deleteQuietly(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            logger.warn("删除文件失败: {}", filePath, e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warn("删除临时封面失败: {}", path, e);
        }
    }
}
