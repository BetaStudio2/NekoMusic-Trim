package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsPlaceholderFilterTest {

    @Test
    void filtersPlaceholderLrcLinesWithoutDroppingNormalText() {
        String lrc = """
                [ti:测试]
                [00:01.00]第一行
                [00:02.00]//
                [00:02.50] //
                [00:02.60]null
                [00:02.70] NULL
                [00:02.80] {"null"}
                [00:02.90]{"null"}
                [00:02.95]{"//"}
                [00:03.00][00:04.00]//
                [00:04.50][00:04.60]null
                //
                null
                {"//"}
                {"null"}
                [00:05.00]还有 // 的正常正文
                [00:05.50]nullify 与 null value 都是正常正文
                [00:06.00]http://example.com/lyrics
                """;

        assertEquals("""
                [ti:测试]
                [00:01.00]第一行
                [00:05.00]还有 // 的正常正文
                [00:05.50]nullify 与 null value 都是正常正文
                [00:06.00]http://example.com/lyrics
                """, LyricsPlaceholderFilter.filterLrc(lrc));
    }

    @Test
    void filtersPlaceholderRichLyricsAndOptionalText() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "//", null, null, false, List.of()),
                new NekoLyrics.Line(2_000, 3_000, "null", null, null, false, List.of()),
                new NekoLyrics.Line(3_000, 4_000, "Hello", "NULL", " null ", false,
                        List.of(new NekoLyrics.Word(3_000, 4_000, "Hello")))));

        assertEquals(1, document.lines().size());
        assertEquals("Hello", document.lines().get(0).text());
        assertNull(document.lines().get(0).translation());
        assertNull(document.lines().get(0).romanization());
        assertEquals("hello", NekoLyrics.plainText(document));
        assertEquals("[00:03.000]Hello", NekoLyrics.toLrc(document));
    }

    @Test
    void historicalRichJsonIsFilteredDuringDeserialization() {
        String json = """
                {
                  "version": 1,
                  "lines": [
                    {"startMs": 1000, "endMs": 2000, "text": "//", "words": []},
                    {"startMs": 2000, "endMs": 3000, "text": "null", "words": []},
                    {"startMs": 3000, "endMs": 4000, "text": "保留", "words": []}
                  ]
                }
                """;

        NekoLyrics.Document document = NekoLyrics.deserialize(json).orElseThrow();

        assertEquals(1, document.lines().size());
        assertEquals("保留", document.lines().get(0).text());
        assertEquals("[00:03.000]保留", NekoLyrics.toLrc(document));
    }

    @Test
    void plainTextExtractionIgnoresPlaceholderRows() {
        String lrc = """
                [00:01.00]Hello
                [00:02.00]//
                [00:02.50]null
                [00:02.60]{"null"}
                [00:03.00]World
                """;

        assertEquals("helloworld", LyricsPlainTextExtractor.fromLrc(lrc));
    }

    @Test
    void placeholderMatchingIsExactAndCaseInsensitiveForNull() {
        assertTrue(LyricsPlaceholderFilter.isPlaceholder(" // "));
        assertTrue(LyricsPlaceholderFilter.isPlaceholder(" null "));
        assertTrue(LyricsPlaceholderFilter.isPlaceholder("NULL"));
        assertFalse(LyricsPlaceholderFilter.isPlaceholder("null value"));
        assertFalse(LyricsPlaceholderFilter.isPlaceholder("http://null"));
    }
}
