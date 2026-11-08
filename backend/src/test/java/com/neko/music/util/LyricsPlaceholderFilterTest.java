package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsPlaceholderFilterTest {

    @Test
    void filtersSlashOnlyLrcLinesWithoutDroppingNormalText() {
        String lrc = """
                [ti:测试]
                [00:01.00]第一行
                [00:02.00]//
                [00:02.50] //
                [00:03.00][00:04.00]//
                //
                {"//"}
                [00:05.00]还有 // 的正常正文
                [00:06.00]http://example.com/lyrics
                """;

        assertEquals("""
                [ti:测试]
                [00:01.00]第一行
                [00:05.00]还有 // 的正常正文
                [00:06.00]http://example.com/lyrics
                """, LyricsPlaceholderFilter.filterLrc(lrc));
    }

    @Test
    void filtersSlashOnlyRichLyricsAndOptionalText() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "//", null, null, false, List.of()),
                new NekoLyrics.Line(2_000, 3_000, "Hello", "//", " // ", false,
                        List.of(new NekoLyrics.Word(2_000, 3_000, "Hello")))));

        assertEquals(1, document.lines().size());
        assertEquals("Hello", document.lines().get(0).text());
        assertNull(document.lines().get(0).translation());
        assertNull(document.lines().get(0).romanization());
        assertEquals("hello", NekoLyrics.plainText(document));
        assertEquals("[00:02.000]Hello", NekoLyrics.toLrc(document));
    }

    @Test
    void historicalRichJsonIsFilteredDuringDeserialization() {
        String json = """
                {
                  "version": 1,
                  "lines": [
                    {"startMs": 1000, "endMs": 2000, "text": "//", "words": []},
                    {"startMs": 2000, "endMs": 3000, "text": "保留", "words": []}
                  ]
                }
                """;

        NekoLyrics.Document document = NekoLyrics.deserialize(json).orElseThrow();

        assertEquals(1, document.lines().size());
        assertEquals("保留", document.lines().get(0).text());
        assertEquals("[00:02.000]保留", NekoLyrics.toLrc(document));
    }

    @Test
    void plainTextExtractionIgnoresSlashOnlyRows() {
        String lrc = """
                [00:01.00]Hello
                [00:02.00]//
                [00:03.00]World
                """;

        assertEquals("helloworld", LyricsPlainTextExtractor.fromLrc(lrc));
    }
}
