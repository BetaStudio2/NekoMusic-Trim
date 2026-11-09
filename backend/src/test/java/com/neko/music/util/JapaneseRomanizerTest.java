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
        String unknownKatakana = JapaneseRomanizer.romanize("ニャンーニャンパスー");
        assertTrue(unknownKatakana.contains("nyan"));
        assertFalse(unknownKatakana.matches(".*[\\u3041-\\u30ff\\u3400-\\u9fff].*"));
        assertEquals("dogura magura", JapaneseRomanizer.romanize("ドグラ・マグラ"));
        assertEquals("kono saki ga dou naru ka", JapaneseRomanizer.romanize("この先がどうなるか"));
        assertEquals("watashi wa kaze anata o tsutsunde iru yo",
                JapaneseRomanizer.romanize("私は風 あなたを包んでいるよ"));
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
    void rewritesDirtyRomanizationButKeepsGoodSpacedValue() {
        NekoLyrics.Document document = NekoLyrics.document(List.of(
                new NekoLyrics.Line(1_000, 2_000, "かっこいいから好きなんじゃない。",
                        null, "kakkoiikara好kinanjanai。", false, List.of()),
                new NekoLyrics.Line(2_000, 3_000, "さくら さくら 会いたいよ",
                        null, "sa ku ra sa ku ra a i ta i yo", false, List.of()),
                new NekoLyrics.Line(3_000, 4_000, "会いたいよ",
                        null, "aitaiyo", false, List.of())
        ));

        NekoLyrics.Document enriched = JapaneseRomanizer.enforceRomanization(document);

        assertEquals("kakkoii kara sukinanjanai.", enriched.lines().get(0).romanization());
        assertEquals("sa ku ra sa ku ra a i ta i yo", enriched.lines().get(1).romanization());
        assertEquals("aitai yo", enriched.lines().get(2).romanization());
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
