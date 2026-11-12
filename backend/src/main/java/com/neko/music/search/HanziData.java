/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/HanziData.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：由 C# 逐函数翻译为 Java，资源路径改为 classpath。
 *
 * 数据表来源与许可证见 backend/THIRD-PARTY-NOTICES.md（Unicode License）。
 */

package com.neko.music.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内嵌 Unicode 数据表（{@code src/main/resources/search/}）的加载与查询：
 * NFKC 兼容折叠、拉丁去音符号折叠、异体字补齐，以及 Unihan 释义查询扩展（双向）。
 *
 * <p>表格式：
 * <ul>
 *   <li>{@code nfkc.tsv} / {@code accent_fold.tsv} / {@code variant_fold.tsv}：
 *       {@code HEXCODE<TAB>HEXOUT1 HEXOUT2 …}；</li>
 *   <li>{@code def_en2hanzi.tsv}：{@code WORD<TAB>HEX1 HEX2 …}；</li>
 *   <li>{@code def_hanzi2en.tsv}：{@code HEXCODE<TAB>WORD1 WORD2 …}。</li>
 * </ul>
 * 惰性加载、只读缓存、线程安全。
 */
final class HanziData {

    private static final String COMPATIBILITY_RESOURCE = "/search/nfkc.tsv";
    private static final String ACCENT_RESOURCE = "/search/accent_fold.tsv";
    private static final String VARIANT_RESOURCE = "/search/variant_fold.tsv";
    private static final String GLOSS_EN_TO_HANZI_RESOURCE = "/search/def_en2hanzi.tsv";
    private static final String GLOSS_HANZI_TO_EN_RESOURCE = "/search/def_hanzi2en.tsv";

    private HanziData() {
    }

    private static final class CompatHolder {
        static final Map<Integer, String> TABLE = loadCodePoints(COMPATIBILITY_RESOURCE);
    }

    private static final class AccentHolder {
        static final Map<Integer, String> TABLE = loadCodePoints(ACCENT_RESOURCE);
    }

    private static final class VariantHolder {
        static final Map<Integer, String> TABLE = loadCodePoints(VARIANT_RESOURCE);
    }

    private static final class GlossEnToHanziHolder {
        static final Map<String, List<Integer>> TABLE = loadGlossCodes(GLOSS_EN_TO_HANZI_RESOURCE);
    }

    private static final class GlossHanziToEnHolder {
        static final Map<Integer, List<String>> TABLE = loadGlossWords(GLOSS_HANZI_TO_EN_RESOURCE);
    }

    /** NFKC 兼容折叠：命中返回折叠结果（可能多字符 / 非 BMP），未命中返回 {@code null}。 */
    static String compatibilityFolded(int codePoint) {
        return CompatHolder.TABLE.get(codePoint);
    }

    /** 拉丁去音符号折叠：命中返回 ASCII 基字母，未命中返回 {@code null}。 */
    static String accentFolded(int codePoint) {
        return AccentHolder.TABLE.get(codePoint);
    }

    /** 异体字补齐：命中返回简体目标，未命中返回 {@code null}。 */
    static String variantFolded(int codePoint) {
        return VariantHolder.TABLE.get(codePoint);
    }

    /** 组合音标（combining diacritic）：折叠时直接丢弃。 */
    static boolean isCombiningDiacritic(int codePoint) {
        return (codePoint >= 0x0300 && codePoint <= 0x036F)
                || (codePoint >= 0x1AB0 && codePoint <= 0x1AFF)
                || (codePoint >= 0x1DC0 && codePoint <= 0x1DFF)
                || (codePoint >= 0x20D0 && codePoint <= 0x20FF)
                || (codePoint >= 0xFE20 && codePoint <= 0xFE2F);
    }

    /** 英文词 → 候选汉字码点；未命中返回 {@code null}。 */
    static List<Integer> glossHanzi(String word) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        return GlossEnToHanziHolder.TABLE.get(word);
    }

    /** 汉字码点 → 英文释义词；未命中返回 {@code null}。 */
    static List<String> glossWords(int codePoint) {
        return GlossHanziToEnHolder.TABLE.get(codePoint);
    }

    private static Map<Integer, String> loadCodePoints(String resource) {
        Map<Integer, String> map = new HashMap<>();
        for (String[] row : readRows(resource)) {
            int key = Integer.parseInt(row[0], 16);
            String text = decodeCodePoints(row[1]);
            if (!text.isEmpty()) {
                map.put(key, text);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<Integer>> loadGlossCodes(String resource) {
        Map<String, List<Integer>> map = new HashMap<>();
        for (String[] row : readStringRows(resource)) {
            List<Integer> codes = new ArrayList<>();
            for (String part : row[1].split(" ")) {
                if (part.isEmpty()) {
                    continue;
                }
                codes.add(Integer.parseInt(part, 16));
            }
            if (!codes.isEmpty()) {
                map.put(row[0], Collections.unmodifiableList(codes));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<Integer, List<String>> loadGlossWords(String resource) {
        Map<Integer, List<String>> map = new HashMap<>();
        for (String[] row : readRows(resource)) {
            int key = Integer.parseInt(row[0], 16);
            List<String> words = new ArrayList<>();
            for (String part : row[1].split(" ")) {
                if (!part.isEmpty()) {
                    words.add(part);
                }
            }
            if (!words.isEmpty()) {
                map.put(key, Collections.unmodifiableList(words));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** 空格分隔的十六进制码点 → 字符串；跳过代理区 / 越界值。 */
    private static String decodeCodePoints(String value) {
        StringBuilder builder = new StringBuilder(value.length() / 4);
        for (String part : value.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            int cp = Integer.parseInt(part, 16);
            if (cp >= 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) {
                builder.appendCodePoint(cp);
            }
        }
        return builder.toString();
    }

    private static List<String[]> readRows(String resource) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = open(resource)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                rows.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取检索数据表失败: " + resource, e);
        }
        return rows;
    }

    private static List<String[]> readStringRows(String resource) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = open(resource)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    continue;
                }
                rows.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取检索数据表失败: " + resource, e);
        }
        return rows;
    }

    private static BufferedReader open(String resource) {
        InputStream stream = HanziData.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("内嵌数据表缺失: " + resource);
        }
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
