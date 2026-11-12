/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/Japanese.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：由 C# 逐函数翻译为 Java，资源改为 classpath。
 */

package com.neko.music.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日文读音映射（索引期）：假名 → 罗马字 + 汉字 → 音读 / 训读罗马字，产出字母 bigram。
 *
 * <p>只在「看起来是日文」的文本上产出（含假名，或调用方标记整首为日文）；
 * 纯中文曲库零成本。数据表：{@code kana_romaji.tsv}（AGPL）、{@code ja_readings.tsv}
 * （Unihan，Unicode License）。
 */
final class JapaneseReadings {

    private JapaneseReadings() {
    }

    private static final int MAX_VARIANTS_PER_RUN = 64;

    private static final class KanaHolder {
        static final Map<String, String[]> TABLE = loadKana();
    }

    private static final class KanjiHolder {
        static final Map<Integer, String[]> READINGS = loadKanji();
    }

    /** 文本是否含假名（平 / 片 / 半角）。 */
    static boolean containsKana(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ScriptRanges.isKana(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ScriptRanges.isCjk(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成读音列的 bigram 词元串（空格分隔的 2 字符字母 bigram）。
     *
     * @param text                原文
     * @param includeKanjiReadings 调用方判定整首曲目是日文时，连纯汉字段也查读音表
     */
    static String readingBigrams(String text, boolean includeKanjiReadings) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!containsKana(text) && !KoreanRomanizer.containsHangul(text)
                && !(includeKanjiReadings && containsCjk(text))) {
            return "";
        }

        List<String> bigrams = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);

            if (ScriptRanges.isKana(ch)) {
                int start = i;
                while (i < text.length() && ScriptRanges.isKana(text.charAt(i))) {
                    i++;
                }
                addKanaRunBigrams(text.substring(start, i), bigrams);
                continue;
            }

            if (KoreanRomanizer.isHangul(ch)) {
                int start = i;
                while (i < text.length() && KoreanRomanizer.isHangul(text.charAt(i))) {
                    i++;
                }
                String romaji = KoreanRomanizer.romanize(text.substring(start, i));
                if (!romaji.isEmpty()) {
                    addBigrams(romaji, bigrams);
                }
                continue;
            }

            if (includeKanjiReadings && ScriptRanges.isCjk(ch)) {
                int cp = text.codePointAt(i);
                String[] readings = KanjiHolder.READINGS.get(cp);
                if (readings != null) {
                    for (String reading : readings) {
                        String romaji = toRomaji(reading);
                        if (!romaji.isEmpty()) {
                            addBigrams(romaji, bigrams);
                        }
                    }
                }
                i += Character.charCount(cp);
                continue;
            }

            i++;
        }
        return String.join(" ", bigrams);
    }

    private static void addKanaRunBigrams(String run, List<String> bigrams) {
        List<String> units = splitKanaUnits(run);
        Set<String> seen = new HashSet<>();

        String primary = romanizeUnits(units, 0, units.size() - 1, -1, 0);
        if (!primary.isEmpty() && seen.add(primary)) {
            addBigrams(primary, bigrams);
        }

        int variantCount = 0;
        for (int i = 0; i < units.size() && variantCount < MAX_VARIANTS_PER_RUN; i++) {
            String[] variants = variants(units.get(i));
            if (variants.length < 2) {
                continue;
            }
            int from = Math.max(0, i - 1);
            int to = Math.min(units.size() - 1, i + 1);
            for (int altIndex = 1; altIndex < variants.length; altIndex++) {
                if (variantCount++ >= MAX_VARIANTS_PER_RUN) {
                    break;
                }
                String romaji = romanizeUnits(units, from, to, i, altIndex);
                if (!romaji.isEmpty() && seen.add(romaji)) {
                    addBigrams(romaji, bigrams);
                }
            }
        }
    }

    private static List<String> splitKanaUnits(String text) {
        List<String> units = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
            if (two.length() == 2 && KanaHolder.TABLE.containsKey(two)) {
                units.add(two);
                i += 2;
            } else {
                units.add(String.valueOf(text.charAt(i)));
                i++;
            }
        }
        return units;
    }

    private static String[] variants(String unit) {
        String[] value = KanaHolder.TABLE.get(unit);
        return value != null ? value : new String[]{unit};
    }

    private static String toRomaji(String kana) {
        List<String> units = splitKanaUnits(kana);
        return romanizeUnits(units, 0, units.size() - 1, -1, 0);
    }

    private static String romanizeUnits(List<String> units, int from, int to, int altUnit, int altIndex) {
        StringBuilder sb = new StringBuilder(units.size() * 2);
        boolean pendingGeminate = false;
        for (int i = from; i <= to; i++) {
            String unit = units.get(i);
            if ("っ".equals(unit)) {
                pendingGeminate = true;
                continue;
            }

            if ("ー".equals(unit)) {
                char last = lastVowel(sb);
                if (last != 0) {
                    sb.append(last);
                }
                continue;
            }

            if ("ん".equals(unit)) {
                String next = i + 1 <= to ? variants(units.get(i + 1))[0] : "";
                sb.append(!next.isEmpty() && "aeiou".indexOf(next.charAt(0)) >= 0 ? "nn" : "n");
                continue;
            }

            String[] spellings = variants(unit);
            String spelling = i == altUnit && altIndex < spellings.length ? spellings[altIndex] : spellings[0];
            if (pendingGeminate) {
                pendingGeminate = false;
                if (!spelling.isEmpty()) {
                    sb.append(spelling.length() > 1 && spelling.charAt(0) == 'c' && spelling.charAt(1) == 'h'
                            ? 't'
                            : spelling.charAt(0));
                }
            }
            sb.append(spelling);
        }
        return sb.toString();
    }

    private static char lastVowel(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if ("aeiou".indexOf(c) >= 0) {
                return c;
            }
            if (c == 'n') {
                return '\0';
            }
        }
        return '\0';
    }

    /** 字母 bigram（含单字符本体）；只保留 ASCII 字母与数字，生成前折叠为规范形。 */
    private static void addBigrams(String romaji, List<String> bigrams) {
        StringBuilder normalized = new StringBuilder(romaji.length());
        for (int i = 0; i < romaji.length(); i++) {
            char lower = Character.toLowerCase(romaji.charAt(i));
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                normalized.append(lower);
            }
        }
        String s = RomajiCanonical.apply(normalized.toString());
        if (s.isEmpty()) {
            return;
        }
        if (s.length() == 1) {
            bigrams.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            bigrams.add(s.substring(i, i + 2));
        }
    }

    private static Map<String, String[]> loadKana() {
        Map<String, String[]> table = new HashMap<>(512);
        for (String line : readLines("/search/kana_romaji.tsv")) {
            String[] cols = line.split("\t");
            if (cols.length < 2) {
                continue;
            }
            String[] variants = splitNonEmpty(cols[1]);
            if (variants.length > 0) {
                table.put(cols[0], variants);
            }
        }
        return table;
    }

    private static Map<Integer, String[]> loadKanji() {
        Map<Integer, String[]> table = new HashMap<>(16_384);
        for (String line : readLines("/search/ja_readings.tsv")) {
            String[] cols = line.split("\t");
            if (cols.length < 2) {
                continue;
            }
            int cp;
            try {
                cp = Integer.parseInt(cols[0], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String[] kana = splitNonEmpty(cols[1]);
            if (kana.length > 0) {
                table.put(cp, kana);
            }
        }
        return table;
    }

    private static String[] splitNonEmpty(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(" ")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(new String[0]);
    }

    private static List<String> readLines(String resource) {
        List<String> lines = new ArrayList<>();
        InputStream stream = JapaneseReadings.class.getResourceAsStream(resource);
        if (stream == null) {
            // 资源缺失：降级为空表（调用方得到空读音，不崩）。
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                lines.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取日文读音表失败: " + resource, e);
        }
        return lines;
    }
}
