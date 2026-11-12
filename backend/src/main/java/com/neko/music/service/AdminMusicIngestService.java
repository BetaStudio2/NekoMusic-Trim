package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.util.AudioFileValidator;
import com.neko.music.util.AudioIntegrityValidator;
import com.neko.music.util.LrcValidator;
import com.neko.music.util.MusicAdMetadataPatcher;
import com.neko.music.util.BatchMusicMatchUtil;
import com.neko.music.util.ChineseConverter;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.util.TempAudioSpool;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 管理员后台入库：校验临时音频/歌词/封面后将封面嵌入音频，再写入曲库与数据库。
 */
public class AdminMusicIngestService {
    private static final Logger logger = LoggerFactory.getLogger(AdminMusicIngestService.class);

    /** 批量搜索：有歌手时站内模糊匹配最低分 */
    private static final int MIN_BATCH_MATCH_SCORE_WITH_ARTIST = 100;
    /** 批量搜索：仅歌名时站内模糊匹配最低分 */
    private static final int MIN_BATCH_MATCH_SCORE_TITLE_ONLY = 90;
    private static final int BATCH_CANDIDATE_LIMIT = 80;

    public record IngestedMusic(
            int id,
            String title,
            String artist,
            String album,
            int duration,
            int uploadUserId,
            String createdAt,
            /** 该曲最高音质（standard/hq/sq/hires）；未探测过为 null。 */
            String maxQuality
    ) {}

    /**
     * @return 入库成功后的记录；若曲库已存在重复则 empty
     */
    public java.util.Optional<IngestedMusic> ingestFromTempFiles(
            Path musicTemp,
            Path coverTempOrNull,
            Path lyricsTemp,
            String title,
            String artist,
            String album,
            String language,
            String tags,
            int durationSec,
            Integer uploadUserId
    ) throws IOException, SQLException {
        return ingestFromTempFiles(musicTemp, coverTempOrNull, lyricsTemp, title, artist, album, language,
                tags, durationSec, uploadUserId, false);
    }

    /**
     * @param exactTitleOnly 判重时是否只认「规范化后完整歌名一致」，见
     *                       {@link #findSameTitleDuplicate(String, String, String)}。
     *                       站外歌单导入必须传 {@code true}，否则同名不同版本会被并成一条而少曲目。
     */
    public java.util.Optional<IngestedMusic> ingestFromTempFiles(
            Path musicTemp,
            Path coverTempOrNull,
            Path lyricsTemp,
            String title,
            String artist,
            String album,
            String language,
            String tags,
            int durationSec,
            Integer uploadUserId,
            boolean exactTitleOnly
    ) throws IOException, SQLException {
        String albumVal = album == null || album.isBlank() ? "未知专辑" : album.trim();
        String lang = language == null || language.isBlank() ? "未知语言" : language.trim();
        String tagVal = tags == null ? "" : tags.trim();

        Optional<IngestedMusic> duplicate = exactTitleOnly
                ? findSameTitleDuplicate(title, artist, albumVal)
                : findExistingDuplicate(title, artist, albumVal);
        if (duplicate.isPresent()) {
            logger.info("跳过入库，曲库已有重复 id={}: title={} artist={}",
                    duplicate.get().id(), title, artist);
            return java.util.Optional.empty();
        }

        String ext = extensionFromPath(musicTemp);
        AudioFileValidator.FormatDetectionResult detection =
                AudioFileValidator.detectAndValidatePath(musicTemp, ext);
        if (!detection.isValid()) {
            throw new IOException("音频校验失败: " + detection.getErrorMessage());
        }

        String fileFormat = switch (detection.getFormat()) {
            case MP3 -> "mp3";
            case FLAC -> "flac";
            case WAV -> "wav";
            default -> throw new IOException("upload.unsupported_audio");
        };

        try (InputStream lrcIn = Files.newInputStream(lyricsTemp)) {
            long lrcSize = Files.size(lyricsTemp);
            LrcValidator.ValidationResult lrcResult = LrcValidator.validate(lrcIn, lrcSize);
            if (!lrcResult.isValid()) {
                throw new IOException("歌词校验失败: " + lrcResult.getErrorMessage());
            }
        }

        int duration = durationSec;
        if (duration <= 0) {
            duration = readAudioDurationSeconds(musicTemp);
        }

        AudioFileValidator.AudioFormat integrityFormat = switch (fileFormat) {
            case "flac" -> AudioFileValidator.AudioFormat.FLAC;
            case "wav" -> AudioFileValidator.AudioFormat.WAV;
            default -> AudioFileValidator.AudioFormat.MP3;
        };
        String integrityError = AudioIntegrityValidator.validateSavedFile(musicTemp, integrityFormat);
        if (integrityError != null) {
            throw new IOException(integrityError);
        }

        MusicAdMetadataPatcher.patchQuietly(musicTemp);

        int musicId = 0;
        String musicRelPath = null;
        try {
            musicId = insertMusicToDatabase(title, artist, albumVal, lang, tagVal, duration, uploadUserId, fileFormat);
            musicRelPath = MusicAssetLocator.AUDIO_REL_DIR + File.separator + musicId + "." + fileFormat;
            Path musicDest = Paths.get(musicRelPath);
            TempAudioSpool.commitReplace(musicTemp, musicDest);
            MusicIngestSupport.refreshQualityAfterIngest(musicId, musicDest, logger);

            String lyricsContent = Files.readString(lyricsTemp, StandardCharsets.UTF_8);
            if (!Main.getLyricsDatabaseManager().upsert(musicId, lyricsContent)) {
                throw new SQLException("保存歌词到数据库失败");
            }
            SearchIndexService.notifyMusicChanged(musicId);

            // 曲库落盘后把歌词/封面/基础标签同步进音频文件；封面写入失败则回滚本次入库。
            if (!EmbeddedMetadataSyncService.syncOne(musicId, coverTempOrNull)) {
                throw new IOException("同步音频内嵌元数据失败");
            }

            IngestedMusic row = loadIngestedMusic(musicId);
            MusicIngestSupport.invalidateRecognitionIndex();
            logger.info("网易云补全入库成功 id={} title={}", musicId, title);
            return java.util.Optional.of(row);
        } catch (Exception e) {
            if (musicId > 0) {
                deleteMusicRecordById(musicId);
                if (musicRelPath != null) {
                    Files.deleteIfExists(Paths.get(musicRelPath));
                }
                Main.getLyricsDatabaseManager().delete(musicId);
            }
            throw e;
        }
    }

    /**
     * 批量搜索：先在站内找最匹配（精确 → 模糊打分），无合格结果再由调用方走网易云。
     */
    public Optional<IngestedMusic> findBestLocalMatchForBatchItem(String title, String artist) throws SQLException {
        Optional<IngestedMusic> exact = findExactMatch(title, artist);
        if (exact.isPresent()) {
            return exact;
        }
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String reqTitle = title.trim();
        String reqArtist = artist == null ? "" : artist.trim();

        List<IngestedMusic> candidates = loadBatchSearchCandidates(reqTitle, reqArtist);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        IngestedMusic best = null;
        int bestScore = 0;
        for (IngestedMusic candidate : candidates) {
            int score = scoreBatchItemMatch(candidate, reqTitle, reqArtist);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        int minScore = reqArtist.isEmpty() ? MIN_BATCH_MATCH_SCORE_TITLE_ONLY : MIN_BATCH_MATCH_SCORE_WITH_ARTIST;
        if (best != null && bestScore >= minScore) {
            logger.debug("批量站内模糊命中: title={} artist={} id={} score={}",
                    reqTitle, reqArtist, best.id(), bestScore);
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * 按歌名（及可选歌手）精确匹配曲库，多条时取 id 最大的一条。
     * 仅歌名且无歌手时：同标题下必须唯一歌手，否则视为歧义不返回。
     */
    public Optional<IngestedMusic> findExactMatch(String title, String artist) throws SQLException {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String reqTitle = title.trim();
        String reqArtist = artist == null ? "" : artist.trim();
        List<IngestedMusic> candidates = loadByTitleVariants(reqTitle);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (!reqArtist.isEmpty()) {
            List<IngestedMusic> matched = new ArrayList<>();
            for (IngestedMusic m : candidates) {
                if (BatchMusicMatchUtil.artistsRelate(reqArtist, m.artist())) {
                    matched.add(m);
                }
            }
            if (matched.size() == 1) {
                return Optional.of(matched.get(0));
            }
            if (matched.size() > 1) {
                IngestedMusic best = null;
                int bestScore = 0;
                for (IngestedMusic m : matched) {
                    int s = BatchMusicMatchUtil.scoreTitleContribution(reqTitle, m.title())
                            + BatchMusicMatchUtil.scoreArtistContribution(reqArtist, m.artist());
                    if (s > bestScore) {
                        bestScore = s;
                        best = m;
                    }
                }
                if (best != null && bestScore >= MIN_BATCH_MATCH_SCORE_WITH_ARTIST) {
                    return Optional.of(best);
                }
            }
            return Optional.empty();
        }
        Set<String> distinctArtists = new HashSet<>();
        for (IngestedMusic m : candidates) {
            distinctArtists.add(normalizeForExact(m.artist()));
        }
        if (distinctArtists.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(0));
    }

    private static String normalizeForExact(String s) {
        if (s == null) {
            return "";
        }
        return ChineseConverter.toSimplified(s.trim()).toLowerCase(Locale.ROOT);
    }

    private List<IngestedMusic> loadByTitleVariants(String title) throws SQLException {
        List<String> variants = ChineseConverter.getFullSearchVariants(title);
        if (variants.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, artist, album, duration, upload_user_id, created_at, max_quality FROM music WHERE ");
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            conditions.add("title = ?");
        }
        sql.append(String.join(" OR ", conditions));
        sql.append(" ORDER BY id DESC");

        List<IngestedMusic> rows = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String variant : variants) {
                stmt.setString(idx++, variant);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String rowTitle = rs.getString("title");
                    if (rowTitle == null || !normalizeForExact(rowTitle).equals(normalizeForExact(title))) {
                        continue;
                    }
                    rows.add(mapIngestedRow(rs));
                }
            }
        }
        return rows;
    }

    private List<IngestedMusic> loadBatchSearchCandidates(String title, String artist) throws SQLException {
        List<String> titleVariants = ChineseConverter.getFullSearchVariants(title);
        if (titleVariants.isEmpty()) {
            return List.of();
        }

        List<String> conditions = new ArrayList<>();
        List<String> params = new ArrayList<>();

        for (String variant : titleVariants) {
            conditions.add("title LIKE ?");
            params.add("%" + variant + "%");
        }
        String coreTitle = BatchMusicMatchUtil.coreTitle(title);
        if (!coreTitle.isBlank() && !normalizeForExact(coreTitle).equals(normalizeForExact(title))) {
            for (String variant : ChineseConverter.getFullSearchVariants(coreTitle)) {
                conditions.add("title LIKE ?");
                params.add("%" + variant + "%");
            }
        }
        if (artist != null && !artist.isBlank()) {
            for (String variant : ChineseConverter.getFullSearchVariants(artist)) {
                conditions.add("artist LIKE ?");
                params.add("%" + variant + "%");
            }
            for (String token : BatchMusicMatchUtil.artistTokens(artist)) {
                if (token.length() >= 2) {
                    conditions.add("artist LIKE ?");
                    params.add("%" + token + "%");
                }
            }
        }

        String sql = "SELECT id, title, artist, album, duration, upload_user_id, created_at, max_quality FROM music WHERE ("
                + String.join(" OR ", conditions)
                + ") ORDER BY id DESC LIMIT ?";

        List<IngestedMusic> rows = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String param : params) {
                stmt.setString(idx++, param);
            }
            stmt.setInt(idx, BATCH_CANDIDATE_LIMIT);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapIngestedRow(rs));
                }
            }
        }
        return rows;
    }

    /**
     * 批量条目站内匹配打分：歌名权重高于歌手，要求歌名至少有一定相似度。
     */
    static int scoreBatchItemMatch(IngestedMusic music, String reqTitle, String reqArtist) {
        if (music == null || reqTitle == null || reqTitle.isBlank()) {
            return 0;
        }
        int score = BatchMusicMatchUtil.scoreTitleContribution(reqTitle, music.title());
        if (score <= 0) {
            return 0;
        }
        score += BatchMusicMatchUtil.scoreArtistContribution(reqArtist, music.artist());
        return Math.max(score, 0);
    }

    private static IngestedMusic mapIngestedRow(ResultSet rs) throws SQLException {
        int uploadUserId = rs.getInt("upload_user_id");
        if (rs.wasNull()) {
            uploadUserId = 0;
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new IngestedMusic(
                rs.getInt("id"),
                rs.getString("title"),
                rs.getString("artist"),
                rs.getString("album"),
                rs.getInt("duration"),
                uploadUserId,
                createdAt != null ? createdAt.toString() : "",
                rs.getString("max_quality")
        );
    }

    public Optional<IngestedMusic> findBestLocalMatchForQuery(String query) throws SQLException {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String q = query.trim();
        String like = "%" + q + "%";
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = """
                    SELECT id, title, artist, album, duration, upload_user_id, created_at, max_quality
                    FROM music
                    WHERE title LIKE ? OR artist LIKE ? OR album LIKE ?
                    ORDER BY id DESC
                    LIMIT 1
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, like);
                stmt.setString(2, like);
                stmt.setString(3, like);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapIngestedRow(rs));
                }
            }
        }
    }

    public boolean isDuplicateMusic(String title, String artist, String album) throws SQLException {
        return findExistingDuplicate(title, artist, album).isPresent();
    }

    /**
     * 按规范化歌名 + 歌手关联（及同标题同专辑）查找曲库中已存在的同一首。
     * 用于网易云补全、上传入库等，避免「查询词不同但实为同一曲」重复写入。
     */
    public Optional<IngestedMusic> findExistingDuplicate(String title, String artist, String album)
            throws SQLException {
        return findExistingDuplicate(title, artist, album, false);
    }

    /**
     * 站外歌单导入专用的去重：**只认「规范化后歌名完全一致」的曲目**，不走下面那条
     * 「去掉括号备注的核心标题」模糊合并。
     *
     * <p>模糊合并会把同名不同版本（`Live` / `Remix` / `Ver.` / `Demo`）当成同一首，导入时后出现的那首
     * 直接复用前一首的曲库记录、既不入库也进不了歌单，导致导入曲目数少于来源歌单。导入要的是
     * 一比一还原，所以这里只按完整歌名判重。</p>
     */
    public Optional<IngestedMusic> findSameTitleDuplicate(String title, String artist, String album)
            throws SQLException {
        return findExistingDuplicate(title, artist, album, true);
    }

    private Optional<IngestedMusic> findExistingDuplicate(String title, String artist, String album,
                                                          boolean exactTitleOnly) throws SQLException {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String reqTitle = title.trim();
        String reqArtist = artist == null ? "" : artist.trim();
        String reqAlbum = album == null || album.isBlank() ? "" : album.trim();

        Map<Integer, IngestedMusic> candidates = new LinkedHashMap<>();
        for (IngestedMusic m : loadByTitleVariants(reqTitle)) {
            candidates.putIfAbsent(m.id(), m);
        }
        String coreTitle = BatchMusicMatchUtil.coreTitle(reqTitle);
        if (!coreTitle.equals(reqTitle)) {
            for (IngestedMusic m : loadByTitleVariants(coreTitle)) {
                candidates.putIfAbsent(m.id(), m);
            }
        }
        if (!reqArtist.isEmpty()) {
            for (IngestedMusic m : loadBatchSearchCandidates(reqTitle, reqArtist)) {
                candidates.putIfAbsent(m.id(), m);
            }
        }

        for (IngestedMusic m : candidates.values()) {
            if (!titlesMatchForDuplicate(reqTitle, m.title())) {
                continue;
            }
            if (exactTitleOnly && !sameTitleForImport(reqTitle, m.title())) {
                continue;
            }
            if (!reqArtist.isEmpty()) {
                if (BatchMusicMatchUtil.artistsRelate(reqArtist, m.artist())) {
                    return Optional.of(m);
                }
                if (!reqAlbum.isEmpty() && reqAlbum.equals(m.album())) {
                    return Optional.of(m);
                }
                continue;
            }
            if (!reqAlbum.isEmpty() && reqAlbum.equals(m.album())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** 导入路径的判重：歌名规范化后必须完全一致（简繁、大小写、首尾空格差异不算差异）。 */
    static boolean sameTitleForImport(String reqTitle, String dbTitle) {
        if (dbTitle == null || dbTitle.isBlank()) {
            return false;
        }
        return normalizeForExact(reqTitle).equals(normalizeForExact(dbTitle));
    }

    private static boolean titlesMatchForDuplicate(String reqTitle, String dbTitle) {
        if (dbTitle == null || dbTitle.isBlank()) {
            return false;
        }
        String reqCore = normalizeForExact(BatchMusicMatchUtil.coreTitle(reqTitle));
        String dbCore = normalizeForExact(BatchMusicMatchUtil.coreTitle(dbTitle));
        if (!reqCore.isEmpty() && reqCore.equals(dbCore)) {
            return true;
        }
        return normalizeForExact(reqTitle).equals(normalizeForExact(dbTitle));
    }

    private static int insertMusicToDatabase(
            String title, String artist, String album, String language, String tags,
            int duration, Integer uploadUserId, String fileFormat
    ) throws SQLException {
        Integer validUploadUserId = null;
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            if (uploadUserId != null && MusicIngestSupport.isUserExists(conn, uploadUserId)) {
                validUploadUserId = uploadUserId;
            } else if (uploadUserId != null) {
                logger.warn("upload_user_id {} 不存在，将使用 NULL", uploadUserId);
            }
            return MusicIngestSupport.insertMusicRow(conn, title, artist, album, album, language, tags,
                    duration, validUploadUserId, fileFormat, "插入 music 失败", "未获取到新音乐 ID");
        }
    }

    private static IngestedMusic loadIngestedMusic(int musicId) throws SQLException {
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, album, duration, upload_user_id, created_at, max_quality FROM music WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, musicId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("音乐记录不存在 id=" + musicId);
                    }
                    return mapIngestedRow(rs);
                }
            }
        }
    }

    private static void deleteMusicRecordById(int musicId) {
        MusicIngestSupport.deleteMusicRecordById(musicId, logger, "回滚删除 music 失败 id={}");
    }

    private static int readAudioDurationSeconds(Path path) {
        try {
            AudioFile af = AudioFileIO.read(path.toFile());
            return af.getAudioHeader().getTrackLength();
        } catch (Exception e) {
            logger.warn("读取音频时长失败: {}", path, e);
            return 0;
        }
    }

    private static String extensionFromPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "mp3";
    }
}
