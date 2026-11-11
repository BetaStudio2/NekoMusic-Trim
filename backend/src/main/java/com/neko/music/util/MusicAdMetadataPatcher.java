package com.neko.music.util;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture;
import org.jaudiotagger.audio.wav.WavOptions;
import org.jaudiotagger.audio.wav.WavSaveOptions;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTPUB;
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT;
import org.jaudiotagger.tag.mp4.Mp4FieldKey;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 曲库音频标签写入：平台广告元数据 + 曲库元数据（标题/艺术家/专辑/歌词/封面）。
 * <ul>
 * <li>评论 / 发行方 / 出版者：删除旧值后写入平台文案（整段替换，不拼接）</li>
 * <li>内嵌歌词：在已有歌词正文最上方插入 LRC 横幅；无内嵌歌词则仅写入横幅；已含同首行则跳过</li>
 * <li>{@link #syncLibraryMetadata(Path, LibraryMetadata)}：按曲库数据重写标题/艺术家/专辑/歌词/封面，
 * 同时重新写入上述广告元数据，因此广告字段不会因重写而丢失</li>
 * </ul>
 */
public final class MusicAdMetadataPatcher {

    private static final Logger logger = LoggerFactory.getLogger(MusicAdMetadataPatcher.class);
    private static final Object WAV_TAG_OPTIONS_LOCK = new Object();

    public static final String COMMENT
            = "更多免费无损音乐就来Neko歌姬计划 https://music.nekocore.cn "
            + "For more free lossless music, visit Neko Cloud Music: https://music.nekocore.cn";

    public static final String ORGANIZATION = "Neko Music";
    public static final String PUBLISHER = "music.nekocore.cn";

    public static final String LYRICS_BANNER_FIRST
            = "[00:00.00]资源来自Neko歌姬计划 Resources from Neko Cloud Music";

    public static final String LYRICS_BANNER_SECOND
            = "[00:00.00]获取更多无损音乐https://music.nekocore.cn/ Get more lossless music at https://music.nekocore.cn/";

    public static final String LYRICS_BANNER = LYRICS_BANNER_FIRST + "\n" + LYRICS_BANNER_SECOND;

    private MusicAdMetadataPatcher() {
    }

    public static void patchQuietly(Path audioPath) {
        if (audioPath == null || !Files.isRegularFile(audioPath)) {
            return;
        }
        try {
            patch(audioPath);
            logger.info("已写入曲库广告元数据: {}", audioPath);
        } catch (Exception e) {
            logger.warn("写入曲库广告元数据失败 path={}: {}", audioPath, e.toString());
        }
    }

    public static void patch(Path audioPath) throws Exception {
        ensureUserWritable(audioPath);

        String ext = extension(audioPath);
        if (".wav".equals(ext) || ".wave".equals(ext)) {
            patchWav(audioPath);
            return;
        }
        if (".mp3".equals(ext)) {
            try {
                patchMp3(audioPath);
            } catch (CannotReadException | InvalidAudioFrameException e) {
                patchMp4(audioPath);
            }
            return;
        }
        if (".flac".equals(ext)) {
            try {
                patchFlac(audioPath);
            } catch (CannotReadException e) {
                patchMp4(audioPath);
            }
            return;
        }
        throw new IllegalArgumentException("不支持的扩展名: " + ext);
    }

    /** 曲库元数据快照；封面源仅为即将嵌入音频的临时文件，字段为空时保留音频内已有封面。 */
    public record LibraryMetadata(String title, String artist, String album, String lyrics, Path coverFile) {

    }

    /**
     * 写入标题 / 艺术家 / 专辑 / 内嵌歌词 / 内嵌封面，并保持广告元数据（评论、发行方、出版者、歌词横幅）。
     * 内嵌歌词整段以曲库正文为准重写，横幅始终位于最上方且不重复。
     */
    public static void syncLibraryMetadata(Path audioPath, LibraryMetadata metadata) throws Exception {
        ensureUserWritable(audioPath);

        String ext = extension(audioPath);
        if (".wav".equals(ext) || ".wave".equals(ext)) {
            withWavId3TagOptions(() -> {
                syncTaggedAudio(audioPath, metadata, false);
                return null;
            });
            return;
        }
        if (".flac".equals(ext)) {
            syncTaggedAudio(audioPath, metadata, true);
            return;
        }
        if (".mp3".equals(ext) || ".m4a".equals(ext) || ".mp4".equals(ext) || ".aac".equals(ext)
                || ".ogg".equals(ext) || ".oga".equals(ext)) {
            syncTaggedAudio(audioPath, metadata, false);
            return;
        }
        throw new IllegalArgumentException("不支持的扩展名: " + ext);
    }

    /**
     * @param flacLyrics true 时按 FLAC 约定写入 {@code lyrics}
     * 字段（deleteField(FieldKey.LYRICS) 在部分实现上不生效）
     */
    private static void syncTaggedAudio(Path path, LibraryMetadata metadata, boolean flacLyrics) throws Exception {
        AudioFile audio = AudioFileIO.read(path.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        applyAdTags(tag);
        if (tag instanceof FlacTag flac && flacLyrics) {
            replaceFlacEmbeddedLyrics(flac, metadata.lyrics());
        } else {
            replaceEmbeddedLyrics(tag, metadata.lyrics());
        }
        setCoreTags(tag, metadata);
        setCoverArt(tag, metadata.coverFile());
        AudioFileIO.write(audio);
    }

    /**
     * 写入广告字段：评论、发行方、出版者。各格式自行选择字段，不触碰其他标签。
     */
    private static void applyAdTags(Tag tag) throws TagException {
        replaceComment(tag);
        if (tag instanceof AbstractID3v2Tag id3) {
            replaceId3OrganizationAndPublisher(id3);
        } else if (tag instanceof FlacTag flac) {
            flac.setField("ORGANIZATION", ORGANIZATION);
            flac.setField("PUBLISHER", PUBLISHER);
        } else if (tag instanceof Mp4Tag mp4) {
            mp4.setField(Mp4FieldKey.LABEL, ORGANIZATION);
            mp4.setField(Mp4FieldKey.MM_PUBLISHER, PUBLISHER);
        }
    }

    /** 仅替换音频内嵌封面，供审核事务在删除待审源图片前调用。 */
    public static void embedCoverOnly(Path audioPath, Path coverFile) throws Exception {
        if (audioPath == null || !Files.isRegularFile(audioPath)
                || coverFile == null || !Files.isRegularFile(coverFile)) {
            throw new IOException("音频或封面文件不存在");
        }
        ensureUserWritable(audioPath);
        if (isWav(audioPath)) {
            withWavId3TagOptions(() -> {
                AudioFile audio = AudioFileIO.read(audioPath.toFile());
                setCoverArt(audio.getTagOrCreateAndSetDefault(), coverFile);
                AudioFileIO.write(audio);
                return null;
            });
            return;
        }
        AudioFile audio = AudioFileIO.read(audioPath.toFile());
        setCoverArt(audio.getTagOrCreateAndSetDefault(), coverFile);
        AudioFileIO.write(audio);
    }

    private static boolean isWav(Path path) {
        String ext = extension(path);
        return ".wav".equals(ext) || ".wave".equals(ext);
    }

    @FunctionalInterface
    private interface WavTagOperation<T> {
        T run() throws Exception;
    }

    /**
     * WAV 的封面/歌词保存在 ID3 chunk；临时切换 JAudiotagger 全局选项并串行执行，避免 INFO tag
     * 对 COVER_ART / LYRICS 的不支持导致同步失败，完成后恢复进程原设置。
     */
    private static <T> T withWavId3TagOptions(WavTagOperation<T> operation) throws Exception {
        synchronized (WAV_TAG_OPTIONS_LOCK) {
            TagOptionSingleton options = TagOptionSingleton.getInstance();
            WavOptions previousRead = options.getWavOptions();
            WavSaveOptions previousSave = options.getWavSaveOptions();
            try {
                options.setWavOptions(WavOptions.READ_ID3_ONLY);
                options.setWavSaveOptions(WavSaveOptions.SAVE_EXISTING_AND_ACTIVE);
                return operation.run();
            } finally {
                options.setWavOptions(previousRead);
                options.setWavSaveOptions(previousSave);
            }
        }
    }

    /**
     * 内嵌歌词整段替换为「横幅 + 曲库正文」。
     */
    private static void replaceEmbeddedLyrics(Tag tag, String lyricsBody) throws TagException {
        tag.deleteField(FieldKey.LYRICS);
        tag.setField(FieldKey.LYRICS, mergeLyricsWithBanner(stripBannerLines(lyricsBody)));
    }

    private static void replaceFlacEmbeddedLyrics(FlacTag flac, String lyricsBody) throws TagException {
        flac.deleteField("lyrics");
        flac.deleteField("LYRICS");
        flac.deleteField(FieldKey.LYRICS);
        flac.setField("lyrics", mergeLyricsWithBanner(stripBannerLines(lyricsBody)));
    }

    private static void setCoreTags(Tag tag, LibraryMetadata metadata) {
        setFieldIfPresent(tag, FieldKey.TITLE, metadata.title());
        setFieldIfPresent(tag, FieldKey.ARTIST, metadata.artist());
        setFieldIfPresent(tag, FieldKey.ALBUM, metadata.album());
    }

    private static void setFieldIfPresent(Tag tag, FieldKey key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            tag.setField(key, value.trim());
        } catch (Exception e) {
            logger.debug("写入标签失败 key={}: {}", key, e.toString());
        }
    }

    /** 内嵌封面：成功读取新图后再替换旧图，失败时向入库流程报告，禁止静默丢封面。 */
    private static void setCoverArt(Tag tag, Path coverFile) throws Exception {
        if (coverFile == null) {
            return;
        }
        if (!Files.isRegularFile(coverFile)) {
            throw new IOException("封面文件不存在: " + coverFile);
        }
        byte[] imageData = Files.readAllBytes(coverFile);
        String mime = EmbeddedCoverReader.sniffMime(imageData);
        if (tag instanceof FlacTag flac && "image/webp".equals(mime)) {
            flac.deleteArtworkField();
            flac.setField(new MetadataBlockDataPicture(imageData, 3, mime, "", 0, 0, 0, 0));
            return;
        }
        Artwork artwork = ArtworkFactory.createArtworkFromFile(coverFile.toFile());
        tag.deleteArtworkField();
        tag.setField(artwork);
    }

    private static void patchMp3(Path path) throws Exception {
        AudioFile audio = AudioFileIO.read(path.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        applyId3AdMetadata(tag);
        AudioFileIO.write(audio);
    }

    private static void patchFlac(Path path) throws Exception {
        AudioFile audio = AudioFileIO.read(path.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        replaceComment(tag);
        if (tag instanceof FlacTag flacTag) {
            flacTag.setField("ORGANIZATION", ORGANIZATION);
            flacTag.setField("PUBLISHER", PUBLISHER);
            prependFlacEmbeddedLyrics(flacTag);
        } else {
            prependEmbeddedLyrics(tag);
        }
        AudioFileIO.write(audio);
    }

    private static void patchMp4(Path path) throws Exception {
        AudioFile audio = AudioFileIO.read(path.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        if (!(tag instanceof Mp4Tag mp4)) {
            replaceComment(tag);
            prependEmbeddedLyrics(tag);
            AudioFileIO.write(audio);
            return;
        }
        mp4.setField(Mp4FieldKey.COMMENT, COMMENT);
        mp4.setField(Mp4FieldKey.LABEL, ORGANIZATION);
        mp4.setField(Mp4FieldKey.MM_PUBLISHER, PUBLISHER);
        prependMp4Lyrics(mp4);
        AudioFileIO.write(audio);
    }

    private static void patchWav(Path path) throws Exception {
        String ffmpeg = findFfmpeg();
        if (ffmpeg != null) {
            try {
                patchWavWithFfmpeg(path, ffmpeg);
                return;
            } catch (Exception e) {
                logger.debug("WAV ffmpeg 元数据写入失败，回退 ID3: {}", e.toString());
            }
        }
        patchWavId3Only(path);
    }

    private static void patchWavId3Only(Path path) throws Exception {
        AudioFile audio = AudioFileIO.read(path.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        try {
            applyId3AdMetadata(tag);
            AudioFileIO.write(audio);
        } catch (Exception e) {
            if (isPermissionIssue(e)) {
                patchWavId3ViaTemp(path);
            } else {
                throw e;
            }
        }
    }

    private static void patchWavId3ViaTemp(Path path) throws Exception {
        Path tmp = path.getParent().resolve("." + path.getFileName() + ".ad." + ProcessHandle.current().pid() + ".wav");
        try {
            Files.copy(path, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            AudioFile audio = AudioFileIO.read(tmp.toFile());
            applyId3AdMetadata(audio.getTagOrCreateAndSetDefault());
            AudioFileIO.write(audio);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void patchWavWithFfmpeg(Path path, String ffmpeg) throws Exception {
        Path tmp = path.getParent().resolve("." + path.getFileName() + ".ff." + ProcessHandle.current().pid() + ".wav");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                    "-i", path.toString(),
                    "-c:a", "copy",
                    "-metadata", "comment=" + COMMENT,
                    tmp.toString());
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) {
                String err = new String(p.getErrorStream().readAllBytes());
                throw new IOException("ffmpeg 退出码 " + code + (err.isBlank() ? "" : ": " + err.trim()));
            }
            patchWavId3Only(tmp);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void applyId3AdMetadata(Tag tag) throws TagException {
        replaceComment(tag);
        if (tag instanceof AbstractID3v2Tag id3) {
            replaceId3OrganizationAndPublisher(id3);
            prependId3Lyrics(id3);
        } else {
            prependEmbeddedLyrics(tag);
        }
    }

    /**
     * 评论：删除全部旧 COMM/comment 后写入平台文案（不追加到旧评论后）。
     */
    private static void replaceComment(Tag tag) throws TagException {
        tag.deleteField(FieldKey.COMMENT);
        if (tag instanceof AbstractID3v2Tag id3) {
            stripId3Frames(id3, ID3v24Frames.FRAME_ID_COMMENT);
            ID3v24Frame comm = new ID3v24Frame(ID3v24Frames.FRAME_ID_COMMENT);
            comm.setBody(new FrameBodyCOMM((byte) 3, "zho", "", COMMENT));
            id3.setFrame(comm);
        } else if (tag instanceof FlacTag flacTag) {
            flacTag.setField(FieldKey.COMMENT, COMMENT);
        } else {
            tag.setField(FieldKey.COMMENT, COMMENT);
        }
    }

    private static void replaceId3OrganizationAndPublisher(AbstractID3v2Tag id3) {
        stripId3TxxxOrganization(id3);
        stripId3Frames(id3, ID3v24Frames.FRAME_ID_PUBLISHER);

        ID3v24Frame tpub = new ID3v24Frame(ID3v24Frames.FRAME_ID_PUBLISHER);
        tpub.setBody(new FrameBodyTPUB((byte) 3, PUBLISHER));
        id3.setFrame(tpub);

        ID3v24Frame org = new ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
        org.setBody(new FrameBodyTXXX((byte) 3, "ORGANIZATION", ORGANIZATION));
        id3.setFrame(org);
    }

    private static void stripId3Frames(AbstractID3v2Tag id3, String frameId) {
        while (id3.hasFrame(frameId)) {
            id3.removeFrame(frameId);
        }
    }

    private static void stripId3TxxxOrganization(AbstractID3v2Tag id3) {
        boolean removed;
        do {
            removed = false;
            List<org.jaudiotagger.tag.TagField> frames
                    = id3.getFrame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
            if (frames == null) {
                break;
            }
            for (org.jaudiotagger.tag.TagField f : frames) {
                if (f instanceof org.jaudiotagger.tag.id3.AbstractID3v2Frame frame
                        && frame.getBody() instanceof FrameBodyTXXX body
                        && "ORGANIZATION".equals(body.getDescription())) {
                    id3.removeFrame(frame.getIdentifier());
                    removed = true;
                    break;
                }
            }
        } while (removed);
    }

    /**
     * 内嵌歌词：在已有正文最前插入横幅（不删除原歌词正文）。
     */
    private static void prependId3Lyrics(AbstractID3v2Tag id3) {
        String existing = collectId3Lyrics(id3);
        if (hasBannerFirstLine(existing)) {
            return;
        }
        stripId3Frames(id3, ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        String text = mergeLyricsWithBanner(existing);
        ID3v24Frame uslt = new ID3v24Frame(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        uslt.setBody(new FrameBodyUSLT((byte) 3, "zho", "", text));
        id3.setFrame(uslt);
    }

    private static String collectId3Lyrics(AbstractID3v2Tag id3) {
        List<org.jaudiotagger.tag.TagField> frames
                = id3.getFrame(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (org.jaudiotagger.tag.TagField f : frames) {
            if (!(f instanceof org.jaudiotagger.tag.id3.AbstractID3v2Frame frame)) {
                continue;
            }
            if (frame.getBody() instanceof FrameBodyUSLT body) {
                String lyric = body.getLyric();
                if (lyric != null && !lyric.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(lyric);
                }
            }
        }
        return sb.toString();
    }

    private static void prependEmbeddedLyrics(Tag tag) throws TagException {
        String existing = safeFirst(tag, FieldKey.LYRICS);
        if (hasBannerFirstLine(existing)) {
            return;
        }
        tag.deleteField(FieldKey.LYRICS);
        tag.setField(FieldKey.LYRICS, mergeLyricsWithBanner(existing));
    }

    private static void prependFlacEmbeddedLyrics(FlacTag flac) throws TagException {
        String existing = collectFlacLyrics(flac);
        if (hasBannerFirstLine(existing)) {
            return;
        }
        flac.deleteField("lyrics");
        flac.deleteField("LYRICS");
        flac.deleteField(FieldKey.LYRICS);
        flac.setField("lyrics", mergeLyricsWithBanner(existing));
    }

    private static String collectFlacLyrics(FlacTag flac) {
        String fromLyrics = flac.getFirst("lyrics");
        if (fromLyrics != null && !fromLyrics.isBlank()) {
            return fromLyrics;
        }
        String fromUpper = flac.getFirst("LYRICS");
        return fromUpper != null ? fromUpper : "";
    }

    private static void prependMp4Lyrics(Mp4Tag mp4) throws TagException {
        String existing = "";
        try {
            existing = mp4.getFirst(Mp4FieldKey.LYRICS);
        } catch (Exception ignored) {
            // no lyrics
        }
        if (hasBannerFirstLine(existing)) {
            return;
        }
        mp4.deleteField(Mp4FieldKey.LYRICS);
        mp4.setField(Mp4FieldKey.LYRICS, mergeLyricsWithBanner(existing));
    }

    /**
     * 无旧歌词 → 仅横幅；有旧歌词 → 横幅 + 原正文（原样保留）。
     */
    static String mergeLyricsWithBanner(String existingBody) {
        if (existingBody == null || existingBody.isBlank()) {
            return LYRICS_BANNER.trim();
        }
        return (LYRICS_BANNER + existingBody).trim();
    }

    /**
     * 去掉正文中已存在的横幅行（管理员可能直接粘贴带横幅的歌词），避免重写后横幅叠加。
     */
    static String stripBannerLines(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String first = LYRICS_BANNER_FIRST.trim();
        String second = LYRICS_BANNER_SECOND.trim();
        return body.lines()
                .filter(line -> {
                    String trimmed = line.trim();
                    return !first.equals(trimmed) && !second.equals(trimmed);
                })
                .collect(java.util.stream.Collectors.joining("\n"))
                .strip();
    }

    private static String safeFirst(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    static boolean hasBannerFirstLine(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String first = body.replace("\uFEFF", "").lines().findFirst().orElse("").trim();
        return first.equals(LYRICS_BANNER_FIRST.trim());
    }

    private static void ensureUserWritable(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            perms = EnumSet.copyOf(perms);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            path.toFile().setWritable(true);
        }
    }

    private static boolean isPermissionIssue(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof IOException io && io.getMessage() != null
                    && io.getMessage().toLowerCase(Locale.ROOT).contains("permission")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String findFfmpeg() {
        String configured = System.getenv("FFMPEG_PATH");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (String name : List.of("ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg")) {
            Path p = Path.of(name);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }
}
