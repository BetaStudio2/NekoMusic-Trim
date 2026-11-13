/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：ToolGood.Words 的词库离线导出为
 * resources/search/pinyin_words.tsv、pinyin_chars.tsv，Java 侧做最长匹配 + 单字回退。
 */

package com.neko.music.search;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 汉字 → 拼音门面。
 *
 * <p>词库与单字读音均从 ToolGood.Words（Apache-2.0）离线导出：
 * <ul>
 *   <li>{@code pinyin_words.tsv}：{@code 词<TAB>音节1 音节2 …}，提供**词级多音字消歧**
 *       （重庆→chong qing、银行→yin hang）；</li>
 *   <li>{@code pinyin_chars.tsv}：{@code 字<TAB>默认音节}，覆盖 U+3400–U+9FD5；</li>
 *   <li>表外字符退回 pinyin4j 首读音。</li>
 * </ul>
 * 音节统一归一为纯 ascii 小写（ü / u: → v）。
 */
final class PinyinFacade {

    private PinyinFacade() {
    }

    private static final HanyuPinyinOutputFormat FALLBACK_FORMAT;

    static {
        FALLBACK_FORMAT = new HanyuPinyinOutputFormat();
        FALLBACK_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FALLBACK_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    private static final class DictHolder {
        static final Map<String, String[]> WORDS = loadWords();
        static final Map<Character, String> CHARS = loadChars();
        static final int MAX_WORD_LENGTH = computeMaxWordLength();
    }

    /**
     * 整段 CJK 的全拼音节序列（纯 ascii 小写，按字符顺序；无读音者跳过）。
     * 段内按词库最长匹配做多音字消歧。
     */
    static List<String> fromCjkRun(String run) {
        if (run == null || run.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> syllables = new ArrayList<>(run.length());
        int i = 0;
        while (i < run.length()) {
            int matched = tryWord(run, i, syllables);
            if (matched > 0) {
                i += matched;
                continue;
            }
            String syllable = syllableOf(run.charAt(i));
            if (syllable != null && !syllable.isEmpty()) {
                syllables.add(syllable);
            }
            i++;
        }
        return syllables;
    }

    /** 整段 CJK 的首字母串（纯 ascii 小写，逐字一位；无读音者跳过）。 */
    static String initials(String run) {
        if (run == null || run.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(run.length());
        int i = 0;
        while (i < run.length()) {
            int start = i;
            int matched = tryWord(run, i, null);
            if (matched > 0) {
                String[] wordSyllables = DictHolder.WORDS.get(run.substring(start, start + matched));
                if (wordSyllables != null && wordSyllables.length == matched) {
                    for (String syllable : wordSyllables) {
                        if (!syllable.isEmpty()) {
                            builder.append(syllable.charAt(0));
                        }
                    }
                }
                i += matched;
                continue;
            }
            String syllable = syllableOf(run.charAt(i));
            if (syllable != null && !syllable.isEmpty()) {
                builder.append(syllable.charAt(0));
            }
            i++;
        }
        return builder.toString();
    }

    /** 归一为纯 ascii 小写：ü / u: → v，只保留字母并转小写。 */
    static String asciiLower(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String normalized = value.replace("u:", "v").replace("ü", "v").replace("Ü", "V");
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                builder.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                builder.append((char) (ch + 32));
            }
        }
        return builder.toString();
    }

    /** 在 {@code run[i..]} 上做最长词匹配；命中时把音节追加到 {@code out}（可为 null）并返回词长。 */
    private static int tryWord(String run, int i, List<String> out) {
        int limit = Math.min(DictHolder.MAX_WORD_LENGTH, run.length() - i);
        for (int length = limit; length >= 2; length--) {
            String candidate = run.substring(i, i + length);
            String[] syllables = DictHolder.WORDS.get(candidate);
            if (syllables == null) {
                continue;
            }
            if (out != null) {
                for (String syllable : syllables) {
                    if (!syllable.isEmpty()) {
                        out.add(syllable);
                    }
                }
            }
            return length;
        }
        return 0;
    }

    private static String syllableOf(char c) {
        String syllable = DictHolder.CHARS.get(c);
        if (syllable != null) {
            return syllable;
        }
        try {
            String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, FALLBACK_FORMAT);
            if (readings != null && readings.length > 0) {
                return asciiLower(readings[0]);
            }
        } catch (Exception ignored) {
            // pinyin4j 对非汉字或无读音字符可能抛异常；按「无读音」处理。
        }
        return null;
    }

    private static Map<String, String[]> loadWords() {
        Map<String, String[]> map = new HashMap<>(98_000);
        for (String line : readLines("/search/pinyin_words.tsv")) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                continue;
            }
            String word = line.substring(0, tab);
            String[] syllables = line.substring(tab + 1).split(" ");
            map.put(word, syllables);
        }
        return map;
    }

    private static Map<Character, String> loadChars() {
        Map<Character, String> map = new HashMap<>(36_000);
        for (String line : readLines("/search/pinyin_chars.tsv")) {
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                continue;
            }
            String key = line.substring(0, tab);
            if (key.length() != 1) {
                continue;
            }
            map.put(key.charAt(0), line.substring(tab + 1));
        }
        return map;
    }

    private static int computeMaxWordLength() {
        int max = 2;
        for (String word : DictHolder.WORDS.keySet()) {
            if (word.length() > max) {
                max = word.length();
            }
        }
        return Math.min(max, 16);
    }

    private static List<String> readLines(String resource) {
        List<String> lines = new ArrayList<>();
        InputStream stream = PinyinFacade.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("内嵌数据表缺失: " + resource);
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
            throw new IllegalStateException("读取数据表失败: " + resource, e);
        }
        return lines;
    }

    /** 供诊断 / 测试使用：是否已加载词库。 */
    static int wordCount() {
        return DictHolder.WORDS.size();
    }

    /** 规范化小写（供其它类复用）。 */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
