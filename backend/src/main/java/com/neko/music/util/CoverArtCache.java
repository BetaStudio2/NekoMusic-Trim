package com.neko.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 曲目封面读取入口：优先返回 {@code Music/covers/{id}.*} 的遗留文件，没有则从
 * {@code Music/music/{id}.*} 的内嵌封面解析，并做进程内缓存。
 *
 * <p>缓存以音频文件的 {@code mtime + size} 为版本：音频被替换（含内嵌封面重写）时自动失效，
 * 无需显式清理；条目数按容量上限淘汰，内存占用有界。封面字节同时给出 SHA-256，用于 HTTP
 * ETag——这样只改标题/歌词（音频 mtime 变了）不会让前端封面缓存失效。</p>
 */
public final class CoverArtCache {
    private static final Logger logger = LoggerFactory.getLogger(CoverArtCache.class);

    /** 控制大曲库预热/访问时的最大常驻封面内存。 */
    private static final long MAX_CACHED_BYTES = 128L * 1024 * 1024;
    private static long cachedBytes;

    private CoverArtCache() {
    }

    /** 对外暴露的封面快照。 */
    public record Snapshot(byte[] data, String mime, String etag) {
    }

    private record Entry(Snapshot snapshot, String sourceVersion) {
    }

    /** 缓存封面字节，响应时直接写出，避免每次打开音频、解析标签或重复算哈希。 */
    private static final ConcurrentHashMap<Path, Entry> CACHE = new ConcurrentHashMap<>();

    /** 读取封面；曲库无音频或音频内无封面返回 empty（调用方回默认图）。 */
    public static Optional<Snapshot> find(int musicId) {
        if (musicId <= 0) {
            return Optional.empty();
        }
        Optional<Path> audio = MusicAssetLocator.findAudioFile(musicId);
        if (audio.isEmpty()) {
            return Optional.empty();
        }
        return find(audio.get());
    }

    /** 读取指定音频文件的封面。 */
    public static Optional<Snapshot> find(Path audioFile) {
        String version;
        try {
            BasicFileAttributes attrs = Files.readAttributes(audioFile, BasicFileAttributes.class);
            version = attrs.lastModifiedTime().toMillis() + "-" + attrs.size();
        } catch (IOException e) {
            return Optional.empty();
        }
        Path key = audioFile.toAbsolutePath().normalize();
        Entry cached = CACHE.get(key);
        if (cached != null && version.equals(cached.sourceVersion())) {
            return Optional.of(cached.snapshot());
        }
        Optional<EmbeddedCoverReader.Cover> cover = EmbeddedCoverReader.read(audioFile);
        if (cover.isEmpty()) {
            remove(key);
            return Optional.empty();
        }
        Snapshot snapshot = new Snapshot(cover.get().data(), cover.get().mime(), sha256(cover.get().data()));
        cache(key, new Entry(snapshot, version));
        return Optional.of(snapshot);
    }

    /** 曲目更新后（换音频/换封面）主动失效，避免同一 mtime 分辨率内的竞态。 */
    public static void invalidate(Path audioFile) {
        if (audioFile != null) {
            remove(audioFile.toAbsolutePath().normalize());
        }
    }

    /** 曲目删除时清理缓存。 */
    public static void invalidate(int musicId) {
        String prefix = musicId + ".";
        for (Path key : CACHE.keySet()) {
            Path name = key.getFileName();
            if (name != null && name.toString().startsWith(prefix)) {
                remove(key);
            }
        }
    }

    private static synchronized void cache(Path key, Entry replacement) {
        int entryBytes = replacement.snapshot().data().length;
        if (entryBytes > MAX_CACHED_BYTES) {
            return;
        }
        Entry previous = CACHE.remove(key);
        if (previous != null) {
            cachedBytes -= previous.snapshot().data().length;
        }
        if (cachedBytes + entryBytes > MAX_CACHED_BYTES) {
            CACHE.clear();
            cachedBytes = 0;
        }
        CACHE.put(key, replacement);
        cachedBytes += entryBytes;
    }

    private static synchronized void remove(Path key) {
        Entry removed = CACHE.remove(key);
        if (removed != null) {
            cachedBytes -= removed.snapshot().data().length;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            logger.debug("计算封面哈希失败: {}", e.toString());
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }
}
