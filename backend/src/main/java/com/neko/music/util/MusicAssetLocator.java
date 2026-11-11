package com.neko.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 在运行目录下按 {@code Music/{id}.*} 定位音频，不依赖数据库路径列；曲库封面保存在音频标签内。
 */
public final class MusicAssetLocator {
    private static final Logger log = LoggerFactory.getLogger(MusicAssetLocator.class);

    public static final String AUDIO_REL_DIR = "Music";

    private static final List<String> AUDIO_EXT_PRIORITY = List.of(
            "flac", "wav", "mp3", "m4a", "aac", "ogg");

    private MusicAssetLocator() {
    }

    /**
     * 运行目录（{@code user.dir}）在进程内不变。写成静态常量，省掉每次资产请求都做一遍
     * {@code System.getProperty} + {@code Paths.get}：音频请求里 {@link #audioDir()}
     * 一类方法会被调用好几次。
     */
    private static final Path BASE_DIR = Paths.get(System.getProperty("user.dir"));

    public static Path baseDir() {
        return BASE_DIR;
    }

    public static Path audioDir() {
        return baseDir().resolve(AUDIO_REL_DIR);
    }

    public static Path derivedDir(int musicId) {
        return audioDir().resolve("derived").resolve(Integer.toString(musicId));
    }

    public static Optional<Path> findAudioFile(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        try {
            return findIdNamedFile(audioDir(), musicId, AUDIO_EXT_PRIORITY);
        } catch (IOException e) {
            log.warn("定位音乐文件失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 批量定位音频文件，只遍历一次音乐目录。声纹全量构建使用此方法，避免按歌曲重复扫描目录。
     */
    public static Map<Integer, Path> findAudioFiles(Collection<Integer> musicIds) throws IOException {
        if (musicIds == null || musicIds.isEmpty() || !Files.isDirectory(audioDir())) {
            return Map.of();
        }
        Set<Integer> wanted = new HashSet<>(musicIds);
        Map<Integer, List<Path>> candidates = new HashMap<>();
        try (Stream<Path> stream = Files.list(audioDir())) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                int dot = name.indexOf('.');
                if (dot <= 0 || name.indexOf('.', dot + 1) >= 0) {
                    return;
                }
                int musicId;
                try {
                    musicId = Integer.parseInt(name.substring(0, dot));
                } catch (NumberFormatException ignored) {
                    return;
                }
                if (wanted.contains(musicId)) {
                    candidates.computeIfAbsent(musicId, ignored -> new ArrayList<>()).add(path);
                }
            });
        }
        Map<Integer, Path> selected = new HashMap<>();
        candidates.forEach((musicId, files) -> pickOne(files, AUDIO_EXT_PRIORITY)
                .ifPresent(path -> selected.put(musicId, path)));
        return Map.copyOf(selected);
    }

    public static String fileApiUrl(int musicId) {
        return "/api/music/file/" + musicId;
    }

    public static String coverApiUrl(int musicId) {
        return "/api/music/cover/" + musicId;
    }

    /** 公开静态封面地址：与 {@code /media/music/*} 同源同缓存语义，供 SEO 页与链接预览直接引用。 */
    public static String mediaCoverUrl(int musicId) {
        return "/media/cover/" + musicId;
    }

    /** 公开静态音频地址：og:audio 与 JSON-LD 使用，避免引用只返回 JSON 的解析接口。 */
    public static String mediaMusicUrl(int musicId) {
        return "/media/music/" + musicId;
    }

    /** 尽力删除 {@code Music/{musicId}.*}；单文件失败只打 WARN，不抛异常。 */
    public static void deleteAudioVariants(int musicId) {
        deleteIdNamedFiles(audioDir(), musicId, "audio");
        deleteDerivedVariants(musicId);
    }

    /**
     * 尽力删除 {@code Music/{musicId}.*} 中除 {@code keep} 之外的变体。
     *
     * <p>替换音频时先写入新文件、再清理旧扩展名的残留，避免新文件写失败时旧文件已被删除。
     */
    public static void deleteAudioVariantsExcept(int musicId, Path keep) {
        deleteIdNamedFilesExcept(audioDir(), musicId, keep, "audio");
        deleteDerivedVariants(musicId);
    }

    public static void deleteDerivedVariants(int musicId) {
        Path dir = derivedDir(musicId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除派生音频失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("列出派生音频失败: {}", dir, e);
        }
    }

    /** 防止路径逃逸：仅当 {@code file} 规范化后位于 {@code expectedDir} 下时返回 true。 */
    public static boolean isUnderDirectory(Path file, Path expectedDir) {
        Path base = expectedDir.toAbsolutePath().normalize();
        Path p = file.toAbsolutePath().normalize();
        return p.startsWith(base);
    }

    /**
     * 形如 {@code 12.mp3}（id 后仅一段扩展名），排除 {@code 12.a.mp3} 等。
     */
    static boolean isSingleSegmentExtension(String fileName, String idPrefix) {
        if (!fileName.startsWith(idPrefix)) {
            return false;
        }
        String rest = fileName.substring(idPrefix.length());
        return !rest.isEmpty() && !rest.contains(".");
    }

    /**
     * 定位 {@code {id}.<ext>}：按扩展名优先级逐个直接探测，不做全目录扫描。
     *
     * <p>{@code Music} 有数万个条目，先前每次请求都 {@code Files.list} 整个目录，
     * 在并发下（目录不在页缓存时要真读盘）会放大到秒级；
     * 直接拼路径后正常情况只需几次 {@code stat}。</p>
     *
     * <p>白名单扩展名全部未命中时才回退一次目录扫描，保证历史遗留的非常规扩展名仍能被找到。</p>
     */
    static Optional<Path> findIdNamedFile(Path dir, int musicId, List<String> extPriority)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String idPrefix = musicId + ".";
        for (String ext : extPriority) {
            Path candidate = dir.resolve(idPrefix + ext);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return pickOne(listIdNamedFiles(dir, musicId), extPriority);
    }

    private static List<Path> listIdNamedFiles(Path dir, int musicId) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isSingleSegmentExtension(p.getFileName().toString(), prefix))
                    .collect(Collectors.toList());
        }
    }

    private static Optional<Path> pickOne(List<Path> candidates, List<String> extPriority) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Path> byExt = new HashMap<>();
        for (Path p : candidates) {
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            byExt.putIfAbsent(ext, p);
        }
        for (String pref : extPriority) {
            if (byExt.containsKey(pref)) {
                return Optional.of(byExt.get(pref));
            }
        }
        return candidates.stream().min(Comparator.comparing(p -> p.getFileName().toString()));
    }

    private static void deleteIdNamedFiles(Path dir, int musicId, String kind) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isSingleSegmentExtension(p.getFileName().toString(), prefix))
                    .forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除{}文件失败 musicId={} path={}: {}", kind, musicId, p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("列出{}目录失败 musicId={}: {}", kind, musicId, e.toString());
        }
    }

    private static void deleteIdNamedFilesExcept(Path dir, int musicId, Path keep, String kind) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path keepNormalized = keep == null ? null : keep.toAbsolutePath().normalize();
        String prefix = musicId + ".";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isSingleSegmentExtension(p.getFileName().toString(), prefix))
                    .filter(p -> keepNormalized == null
                            || !p.toAbsolutePath().normalize().equals(keepNormalized))
                    .forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除{}文件失败 musicId={} path={}: {}", kind, musicId, p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("列出{}目录失败 musicId={}: {}", kind, musicId, e.toString());
        }
    }
}
