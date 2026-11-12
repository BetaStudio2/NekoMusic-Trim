package com.neko.music.util;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM;
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MusicAdMetadataPatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void mergeLyricsWithBanner_prependsToExisting() {
        String merged = MusicAdMetadataPatcher.mergeLyricsWithBanner("[00:12.00]原歌词");
        assertTrue(merged.startsWith(MusicAdMetadataPatcher.LYRICS_BANNER_FIRST));
        assertTrue(merged.contains("[00:12.00]原歌词"));
        assertFalse(merged.contains(MusicAdMetadataPatcher.LYRICS_BANNER + MusicAdMetadataPatcher.LYRICS_BANNER));
    }

    @Test
    void mergeLyricsWithBanner_emptyGetsBannerOnly() {
        assertEquals(MusicAdMetadataPatcher.LYRICS_BANNER.trim(),
                MusicAdMetadataPatcher.mergeLyricsWithBanner(""));
    }

    @Test
    void hasBannerFirstLine_detectsDuplicate() {
        String already = MusicAdMetadataPatcher.mergeLyricsWithBanner("[00:01.00]x");
        assertTrue(MusicAdMetadataPatcher.hasBannerFirstLine(already));
    }

    @Test
    void stripBannerLines_removesExistingBanner() {
        String body = MusicAdMetadataPatcher.LYRICS_BANNER + "\n[00:12.00]正文";
        assertEquals("[00:12.00]正文", MusicAdMetadataPatcher.stripBannerLines(body));
        assertEquals("", MusicAdMetadataPatcher.stripBannerLines(null));
    }

    @Test
    void syncLibraryMetadata_rewritesTagsAndKeepsAds(@TempDir Path dir) throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 MP3");

        Path mp3 = dir.resolve("sync.mp3");
        runFfmpeg(mp3, "mp3");
        seedMp3Tags(mp3, "旧评论应被替换",
                MusicAdMetadataPatcher.LYRICS_BANNER + "\n[00:15.00]旧歌词行");
        Path cover = writePng(dir.resolve("cover.png"));

        MusicAdMetadataPatcher.syncLibraryMetadata(mp3, new MusicAdMetadataPatcher.LibraryMetadata(
                "新标题", "新艺术家", "新专辑", "[00:20.00]曲库歌词", cover));

        AbstractID3v2Tag id3 = (AbstractID3v2Tag) AudioFileIO.read(mp3.toFile()).getTag();
        assertEquals(MusicAdMetadataPatcher.COMMENT, id3.getFirst(ID3v24Frames.FRAME_ID_COMMENT));
        assertEquals("新标题", id3.getFirst(FieldKey.TITLE));
        assertEquals("新艺术家", id3.getFirst(FieldKey.ARTIST));
        assertEquals("新专辑", id3.getFirst(FieldKey.ALBUM));

        String lyrics = id3.getFirst(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        assertTrue(MusicAdMetadataPatcher.hasBannerFirstLine(lyrics));
        assertTrue(lyrics.contains("[00:20.00]曲库歌词"));
        assertFalse(lyrics.contains("[00:15.00]旧歌词行"));
        assertEquals(1, countOccurrences(lyrics, MusicAdMetadataPatcher.LYRICS_BANNER_FIRST));
        assertEquals(1, countOccurrences(lyrics, MusicAdMetadataPatcher.LYRICS_BANNER_SECOND));
        assertNotNull(id3.getFirstArtwork());

        // 再次写入广告元数据不应改动刚同步的歌词
        MusicAdMetadataPatcher.patch(mp3);
        String again = ((AbstractID3v2Tag) AudioFileIO.read(mp3.toFile()).getTag())
                .getFirst(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        assertEquals(lyrics, again);
    }

    @Test
    void syncLibraryMetadata_flacKeepsAdMetadataAndLyrics(@TempDir Path dir) throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 FLAC");

        Path flac = dir.resolve("sync.flac");
        runFfmpeg(flac, "flac");

        MusicAdMetadataPatcher.syncLibraryMetadata(flac, new MusicAdMetadataPatcher.LibraryMetadata(
                "标题F", "艺术家F", "专辑F", "[00:30.00]FLAC歌词", writePng(dir.resolve("cover-flac.png"))));

        Tag tag = AudioFileIO.read(flac.toFile()).getTag();
        assertTrue(tag.getFirst(FieldKey.COMMENT).contains("Neko歌姬计划"));
        assertEquals("标题F", tag.getFirst(FieldKey.TITLE));
        String lyrics = tag.getFirst(FieldKey.LYRICS);
        assertTrue(MusicAdMetadataPatcher.hasBannerFirstLine(lyrics));
        assertTrue(lyrics.contains("[00:30.00]FLAC歌词"));
    }

    @Test
    void syncLibraryMetadata_id3v23WritesReadableArtwork(@TempDir Path dir) throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 MP3");

        Path mp3 = dir.resolve("sync-v23.mp3");
        runFfmpeg(mp3, "mp3", true);
        Path cover = writePng(dir.resolve("cover-v23.png"));

        MusicAdMetadataPatcher.syncLibraryMetadata(mp3, new MusicAdMetadataPatcher.LibraryMetadata(
                "标题", "歌手", "专辑", "[00:20.00]曲库歌词", cover));

        EmbeddedCoverReader.Cover embedded = EmbeddedCoverReader.read(mp3).orElseThrow();
        assertArrayEquals(Files.readAllBytes(cover), embedded.data());
        assertEquals("image/png", embedded.mime());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Path writePng(Path out) throws Exception {
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out.toFile());
        return out;
    }

    @Test
    void patchMp3_replacesCommentAndPrependsLyrics(@TempDir Path dir) throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 MP3");

        Path mp3 = dir.resolve("test.mp3");
        runFfmpeg(mp3, "mp3");

        seedMp3Tags(mp3, "旧评论应被替换", "[00:15.00]原有歌词行");

        MusicAdMetadataPatcher.patch(mp3);

        AudioFile after = AudioFileIO.read(mp3.toFile());
        AbstractID3v2Tag id3 = (AbstractID3v2Tag) after.getTag();
        String comment = id3.getFirst(ID3v24Frames.FRAME_ID_COMMENT);
        assertEquals(MusicAdMetadataPatcher.COMMENT, comment);
        assertFalse(comment.contains("旧评论"));

        String lyrics = id3.getFirst(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        assertTrue(MusicAdMetadataPatcher.hasBannerFirstLine(lyrics));
        assertTrue(lyrics.contains("[00:15.00]原有歌词行"));

        // 二次打标不重复插入横幅
        MusicAdMetadataPatcher.patch(mp3);
        AudioFile again = AudioFileIO.read(mp3.toFile());
        String lyrics2 = ((AbstractID3v2Tag) again.getTag()).getFirst(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        assertEquals(lyrics, lyrics2);
    }

    private static void seedMp3Tags(Path mp3, String oldComment, String oldLyrics) throws Exception {
        AudioFile audio = AudioFileIO.read(mp3.toFile());
        AbstractID3v2Tag id3 = (AbstractID3v2Tag) audio.getTagOrCreateAndSetDefault();

        ID3v24Frame comm = new ID3v24Frame(ID3v24Frames.FRAME_ID_COMMENT);
        comm.setBody(new FrameBodyCOMM((byte) 3, "zho", "", oldComment));
        id3.setFrame(comm);

        ID3v24Frame uslt = new ID3v24Frame(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS);
        uslt.setBody(new FrameBodyUSLT((byte) 3, "zho", "", oldLyrics));
        id3.setFrame(uslt);

        AudioFileIO.write(audio);
    }

    private static void runFfmpeg(Path out, String codec) throws Exception {
        runFfmpeg(out, codec, false);
    }

    private static void runFfmpeg(Path out, String codec, boolean id3v23) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.2",
                "-acodec", codec.equals("mp3") ? "libmp3lame" : codec,
                out.toString()
        );
        if (id3v23) {
            builder.command().add(builder.command().size() - 1, "-write_id3v2");
            builder.command().add(builder.command().size() - 1, "1");
            builder.command().add(builder.command().size() - 1, "-id3v2_version");
            builder.command().add(builder.command().size() - 1, "3");
        }
        Process p = builder.start();
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0);
        assumeTrue(Files.size(out) > 0);
    }

    private static boolean hasFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
