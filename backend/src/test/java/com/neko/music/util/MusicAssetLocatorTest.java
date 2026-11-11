package com.neko.music.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资产定位：优先直接探测 {@code {id}.<ext>}，白名单扩展名都不命中时才回退一次目录扫描。
 *
 * <p>这里锁住的是「直接探测与原来的全目录扫描结果一致」——{@code Music/music} 有数万个条目，
 * 定位方式退化回全目录扫描会把每次音频请求的固定开销从微秒级拉回几十毫秒。</p>
 */
class MusicAssetLocatorTest {

    private static final List<String> AUDIO_EXT = List.of("flac", "wav", "mp3", "m4a", "aac", "ogg");

    @TempDir
    Path dir;

    private Path touch(String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void picksHighestPriorityExtension() throws IOException {
        touch("7.mp3");
        Path flac = touch("7.flac");
        assertEquals(flac, MusicAssetLocator.findIdNamedFile(dir, 7, AUDIO_EXT).orElseThrow());
    }

    @Test
    void picksLowerPriorityWhenHigherMissing() throws IOException {
        Path mp3 = touch("7.mp3");
        assertEquals(mp3, MusicAssetLocator.findIdNamedFile(dir, 7, AUDIO_EXT).orElseThrow());
    }

    @Test
    void doesNotConfuseIdPrefixes() throws IOException {
        touch("70.flac");
        touch("700.flac");
        assertTrue(MusicAssetLocator.findIdNamedFile(dir, 7, AUDIO_EXT).isEmpty());
    }

    /** 多段扩展名（{@code 12.a.mp3}）不算，与旧的 {@code isSingleSegmentExtension} 口径一致。 */
    @Test
    void rejectsMultiSegmentExtensions() throws IOException {
        touch("12.a.mp3");
        assertTrue(MusicAssetLocator.findIdNamedFile(dir, 12, AUDIO_EXT).isEmpty());
    }

    /** 非常规扩展名仍要能找到：走的是回退的那一次目录扫描。 */
    @Test
    void fallsBackToDirectoryScanForUnknownExtension() throws IOException {
        Path opus = touch("9.opus");
        assertEquals(opus, MusicAssetLocator.findIdNamedFile(dir, 9, AUDIO_EXT).orElseThrow());
    }

    @Test
    void returnsEmptyForMissingDirAndMissingId() throws IOException {
        assertTrue(MusicAssetLocator.findIdNamedFile(dir.resolve("nope"), 7, AUDIO_EXT).isEmpty());
        touch("7.mp3");
        assertTrue(MusicAssetLocator.findIdNamedFile(dir, 8, AUDIO_EXT).isEmpty());
    }
}
