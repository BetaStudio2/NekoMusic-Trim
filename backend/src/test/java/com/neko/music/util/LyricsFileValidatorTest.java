package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsFileValidatorTest {

    @Test
    void acceptsNekoDocumentAndNormalizesIt() {
        String content = """
                {
                  "version": 1,
                  "lines": [
                    {
                      "startMs": 1000,
                      "endMs": 2500,
                      "text": "你好",
                      "translation": "Hello",
                      "romanization": null,
                      "background": false,
                      "words": [
                        { "startMs": 1000, "endMs": 1500, "text": "你" },
                        { "startMs": 1500, "endMs": 2500, "text": "好" }
                      ]
                    }
                  ]
                }
                """;

        LyricsFileValidator.ValidationResult result = validate("lyrics.neko", content);

        assertTrue(result.isValid(), result.getErrorMessage());
        assertEquals(LyricsFileValidator.Format.NEKO, result.getFormat());
        NekoLyrics.Document document = NekoLyrics.deserialize(result.getNormalizedContent()).orElseThrow();
        assertEquals(2, document.lines().getFirst().words().size());
        assertEquals("Hello", document.lines().getFirst().translation());
    }

    @Test
    void acceptsLrcThroughTheSameUploadValidator() {
        LyricsFileValidator.ValidationResult result = validate("lyrics.lrc", """
                [00:01.00]第一句
                [00:03.50]第二句
                """);

        assertTrue(result.isValid(), result.getErrorMessage());
        assertEquals(LyricsFileValidator.Format.LRC, result.getFormat());
        assertTrue(result.getNormalizedContent().contains("[00:01.00]第一句"));
    }

    @Test
    void rejectsInvalidNekoJsonVersionAndEmptyLines() {
        assertFalse(validate("lyrics.neko", """
                {"version":2,"lines":[{"startMs":0,"endMs":1000,"text":"歌词"}]}
                """).isValid());
        assertFalse(validate("lyrics.neko", """
                {"version":1,"lines":[]}
                """).isValid());
    }

    @Test
    void rejectsMalformedNekoTimingAndUnsupportedExtension() {
        assertFalse(validate("lyrics.neko", """
                {"version":1,"lines":[{"startMs":0,"text":"缺少结束时间"}]}
                """).isValid());
        assertFalse(validate("lyrics.txt", "[00:00.00]歌词").isValid());
    }

    @Test
    void acceptsNekoLargerThanTheLegacyLrcLimit() {
        StringBuilder content = new StringBuilder("{\"version\":1,\"lines\":[");
        int cursorMs = 0;
        for (int lineIndex = 0; lineIndex < 100; lineIndex++) {
            if (lineIndex > 0) {
                content.append(',');
            }
            String text = "字".repeat(50);
            int lineStart = cursorMs;
            content.append("{\"startMs\":").append(lineStart)
                    .append(",\"endMs\":").append(lineStart + 5_000)
                    .append(",\"text\":\"").append(text)
                    .append("\",\"translation\":null,\"romanization\":null,\"background\":false,\"words\":[");
            for (int wordIndex = 0; wordIndex < 50; wordIndex++) {
                if (wordIndex > 0) {
                    content.append(',');
                }
                int start = lineStart + wordIndex * 100;
                content.append("{\"startMs\":").append(start)
                        .append(",\"endMs\":").append(start + 100)
                        .append(",\"text\":\"字\"}");
            }
            content.append("]}");
            cursorMs += 6_000;
        }
        content.append("]}");

        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > 200 * 1024);
        LyricsFileValidator.ValidationResult result = LyricsFileValidator.validate(
                "large.neko", new ByteArrayInputStream(bytes), bytes.length);
        assertTrue(result.isValid(), result.getErrorMessage());
    }

    @Test
    void rejectsNekoOverOneMiB() {
        byte[] bytes = new byte[(int) LyricsFileValidator.NEKO_MAX_FILE_SIZE_BYTES + 1];
        LyricsFileValidator.ValidationResult result = LyricsFileValidator.validate(
                "oversized.neko", new ByteArrayInputStream(bytes), bytes.length);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("1MiB"));
    }

    private static LyricsFileValidator.ValidationResult validate(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return LyricsFileValidator.validate(
                fileName, new ByteArrayInputStream(bytes), bytes.length);
    }
}
