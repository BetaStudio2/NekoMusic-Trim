package com.neko.music.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JapaneseRomanizerTest {

    @Test
    void romanizesKanaWithCommonCombinations() {
        assertEquals("konnichiha", JapaneseRomanizer.romanize("こんにちは"));
        assertEquals("shashin", JapaneseRomanizer.romanize("しゃしん"));
        assertEquals("kyasshu", JapaneseRomanizer.romanize("きゃっしゅ"));
        assertEquals("koohii", JapaneseRomanizer.romanize("コーヒー"));
    }

    @Test
    void recognizesJapaneseLanguageAliasesOnly() {
        assertTrue(JapaneseRomanizer.isJapaneseLanguage("日语"));
        assertTrue(JapaneseRomanizer.isJapaneseLanguage("日語"));
        assertTrue(JapaneseRomanizer.isJapaneseLanguage("ja-JP"));
        assertTrue(JapaneseRomanizer.isJapaneseLanguage("Japanese"));
        assertFalse(JapaneseRomanizer.isJapaneseLanguage("英文"));
    }

    @Test
    void fillsMissingRomanizationWithoutOverwritingExistingValues() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "こんにちは", null, null, false, List.of()),
                new NekoLyrics.Line(2_000, 3_000, "さよなら", null, "keep", false, List.of()),
                new NekoLyrics.Line(3_000, 4_000, "Hello", null, null, false, List.of())
        ));

        NekoLyrics.Document enriched = JapaneseRomanizer.enforceRomanization(document);

        assertEquals("konnichiha", enriched.lines().get(0).romanization());
        assertEquals("keep", enriched.lines().get(1).romanization());
        assertEquals("Hello", enriched.lines().get(2).romanization());
    }

    @Test
    void parsesLrcIntoNekoLyricsBeforeEnforcing() {
        String lrc = """
                [offset:500]
                [00:01.00]こんにちは
                {"你好"}
                [00:02.00]
                {"不应绑定到上一行"}
                [00:03.00]さよなら
                """;

        NekoLyrics.Document document = NekoLyrics.fromLrc(lrc).orElseThrow();

        assertEquals(2, document.lines().size());
        assertEquals("こんにちは", document.lines().get(0).text());
        assertEquals("你好", document.lines().get(0).translation());
        assertEquals(1_500, document.lines().get(0).startMs());
        assertEquals(3_500, document.lines().get(1).startMs());
        assertNull(document.lines().get(1).translation());
    }
}
