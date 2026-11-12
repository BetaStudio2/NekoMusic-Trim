package com.neko.music.util;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.wav.WavOptions;
import org.jaudiotagger.audio.wav.WavSaveOptions;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EmbeddedCoverReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsMp3AndFlacArtworkWithoutReadingWholeAudio() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试音频");
        Path image = createPng();
        for (String format : new String[]{"mp3", "flac"}) {
            Path audio = createAudio(format);
            embedArtwork(audio, image);
            EmbeddedCoverReader.Cover cover = EmbeddedCoverReader.read(audio).orElseThrow();
            assertArrayEquals(Files.readAllBytes(image), cover.data());
            assertEquals("image/png", cover.mime());
        }
    }

    @Test
    void readsArtworkFromWaveId3Chunk() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 WAV");
        Path audio = createAudio("wav");
        Path image = createPng();
        TagOptionSingleton options = TagOptionSingleton.getInstance();
        WavOptions oldRead = options.getWavOptions();
        WavSaveOptions oldSave = options.getWavSaveOptions();
        try {
            // WAV 的 INFO tag 不支持 APIC；曲库封面存放于标准 ID3 chunk。
            options.setWavOptions(WavOptions.READ_ID3_ONLY);
            options.setWavSaveOptions(WavSaveOptions.SAVE_EXISTING_AND_ACTIVE);
            embedArtwork(audio, image);
        } finally {
            options.setWavOptions(oldRead);
            options.setWavSaveOptions(oldSave);
        }

        EmbeddedCoverReader.Cover cover = EmbeddedCoverReader.read(audio).orElseThrow();
        assertArrayEquals(Files.readAllBytes(image), cover.data());
        assertEquals("image/png", cover.mime());
    }

    @Test
    void metadataSyncEmbedsWaveCoverAndRestoresGlobalOptions() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试 WAV");
        Path audio = createAudio("wav");
        Path image = createPng();
        TagOptionSingleton options = TagOptionSingleton.getInstance();
        WavOptions previousRead = options.getWavOptions();
        WavSaveOptions previousSave = options.getWavSaveOptions();

        MusicAdMetadataPatcher.syncLibraryMetadata(audio,
                new MusicAdMetadataPatcher.LibraryMetadata(
                        "标题", "歌手", "专辑", "[00:01.00]歌词", image));

        assertEquals(previousRead, options.getWavOptions());
        assertEquals(previousSave, options.getWavSaveOptions());
        EmbeddedCoverReader.Cover cover = EmbeddedCoverReader.read(audio).orElseThrow();
        assertArrayEquals(Files.readAllBytes(image), cover.data());
        assertEquals("image/png", cover.mime());
    }

    @Test
    void metadataSyncEmbedsWebpCoverIntoFlac() throws Exception {
        assumeTrue(hasFfmpeg(), "需要 ffmpeg 生成测试音频与 WebP");
        Path audio = createAudio("flac");
        Path image = tempDir.resolve("cover.webp");
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1",
                "-frames:v", "1", "-c:v", "libwebp", image.toString())
                .redirectErrorStream(true)
                .start();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0,
                "ffmpeg build does not include the WebP encoder");

        MusicAdMetadataPatcher.syncLibraryMetadata(audio,
                new MusicAdMetadataPatcher.LibraryMetadata("标题", "歌手", "专辑", "", image));

        EmbeddedCoverReader.Cover cover = EmbeddedCoverReader.read(audio).orElseThrow();
        assertArrayEquals(Files.readAllBytes(image), cover.data());
        assertEquals("image/webp", cover.mime());
    }

    @Test
    void recoversArtworkSwallowedByMalformedPreviousFrameSize() throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", image);

        ByteArrayOutputStream apicBody = new ByteArrayOutputStream();
        apicBody.write(0); // ISO-8859-1
        apicBody.write("image/png".getBytes(StandardCharsets.ISO_8859_1));
        apicBody.write(0);
        apicBody.write(3); // front cover
        apicBody.write(0); // empty description
        apicBody.write(image.toByteArray());
        byte[] apicFrame = frame("APIC", apicBody.toByteArray());

        ByteArrayOutputStream malformedCommentBody = new ByteArrayOutputStream();
        malformedCommentBody.write(new byte[]{0, 'x'});
        malformedCommentBody.write(apicFrame);
        byte[] commentBody = malformedCommentBody.toByteArray();

        ByteArrayOutputStream tagBody = new ByteArrayOutputStream();
        tagBody.write(frameHeader("COMM", commentBody.length));
        tagBody.write(commentBody);

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        audio.write(new byte[]{'I', 'D', '3', 3, 0, 0});
        audio.write(syncSafe(tagBody.size()));
        audio.write(tagBody.toByteArray());
        Path file = tempDir.resolve("malformed.mp3");
        Files.write(file, audio.toByteArray());

        EmbeddedCoverReader.Cover cover = EmbeddedCoverReader.read(file).orElseThrow();
        assertArrayEquals(image.toByteArray(), cover.data());
        assertEquals("image/png", cover.mime());
    }

    private Path createAudio(String format) throws Exception {
        Path audio = tempDir.resolve("sample." + format);
        String codec = switch (format) {
            case "mp3" -> "libmp3lame";
            case "flac" -> "flac";
            default -> "pcm_s16le";
        };
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3",
                "-acodec", codec, audio.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return audio;
    }

    private Path createPng() throws Exception {
        Path image = tempDir.resolve("cover.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        return image;
    }

    private static void embedArtwork(Path audioPath, Path imagePath) throws Exception {
        AudioFile audio = AudioFileIO.read(audioPath.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(ArtworkFactory.createArtworkFromFile(imagePath.toFile()));
        AudioFileIO.write(audio);
    }

    private static byte[] frame(String id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(frameHeader(id, body.length));
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] frameHeader(String id, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(bigEndian(size));
        out.writeBytes(new byte[]{0, 0});
        return out.toByteArray();
    }

    private static byte[] syncSafe(int value) {
        return new byte[]{
                (byte) ((value >> 21) & 0x7f),
                (byte) ((value >> 14) & 0x7f),
                (byte) ((value >> 7) & 0x7f),
                (byte) (value & 0x7f)
        };
    }

    private static byte[] bigEndian(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static boolean hasFfmpeg() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
