package com.neko.music.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 视频渲染相关临时文件统一放在 {@code /tmp/.neko}（tmpfs），避免占用业务盘。
 */
public final class VideoRenderPaths {
    public static final Path NEKO_TMP_ROOT = Paths.get("/tmp/.neko");
    public static final Path VIDEO_RENDER_DIR = NEKO_TMP_ROOT.resolve("video-render");
    private static final Path ASS_DIR = VIDEO_RENDER_DIR.resolve("ass");
    private static final Path FONTS_DIR = VIDEO_RENDER_DIR.resolve("fonts");
    private static final Path FFMPEG_ROOT = NEKO_TMP_ROOT.resolve("ffmpeg");

    private VideoRenderPaths() {
    }

    public static Path videoRenderDir() {
        return VIDEO_RENDER_DIR;
    }

    public static Path outputFile(String jobId) {
        return VIDEO_RENDER_DIR.resolve(jobId + ".mp4");
    }

    /** 视频任务所需封面是可再生成的临时文件，不属于曲库媒体存储。 */
    public static Path coverFile(String jobId, String mime) {
        String extension = switch (mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        return VIDEO_RENDER_DIR.resolve(jobId + ".cover" + extension);
    }

    public static String outputRelPath(String jobId) {
        return outputFile(jobId).toAbsolutePath().normalize().toString();
    }

    public static Path assFile(String jobId) throws IOException {
        Files.createDirectories(ASS_DIR);
        return ASS_DIR.resolve(jobId + ".ass");
    }

    public static Path fontsDir() {
        return FONTS_DIR;
    }

    public static Path watermarkFile() {
        return VIDEO_RENDER_DIR.resolve("watermark.png");
    }

    public static Path watermarkMarkerFile() {
        return VIDEO_RENDER_DIR.resolve(".watermark.ok");
    }

    public static Path ffmpegCacheDir(String platform) {
        return FFMPEG_ROOT.resolve(platform);
    }

    public static void ensureVideoDir() throws IOException {
        Files.createDirectories(VIDEO_RENDER_DIR);
        Files.createDirectories(ASS_DIR);
    }

    public static boolean isUnderNekoTmp(Path file) {
        return MusicAssetLocator.isUnderDirectory(file, NEKO_TMP_ROOT);
    }

    public static boolean isAllowedOutput(Path file) {
        return MusicAssetLocator.isUnderDirectory(file, VIDEO_RENDER_DIR)
                && file.getFileName() != null
                && file.getFileName().toString().endsWith(".mp4");
    }

    public static String truncateText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen - 1) + "…";
    }

    public static String escapeAssText(String text) {
        if (text == null || text.isEmpty()) {
            return " ";
        }
        return text
                .replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r", "")
                .replace("\n", "\\N");
    }

    public static String escapeFilterPath(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    public static String subtitlesFilterArg(Path assFile, Path fontsDir) {
        return "subtitles='" + escapeFilterPath(assFile) + "':fontsdir='" + escapeFilterPath(fontsDir) + "'";
    }
}
