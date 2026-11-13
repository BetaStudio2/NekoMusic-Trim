/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：音节清单改为读取离线导出的 pinyin_syllables.txt。
 */

package com.neko.music.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询侧「连写全拼」切分器：把纯拉丁词按有效拼音音节切分（{@code woaini → wo ai ni}）。
 *
 * <p>要求**完整覆盖**，否则保留原整词；并对最长优先结果施加英文过度切分门控
 * （词长 2..12、音节数 ≥2、每个音节长度 ≥2）。
 */
final class PinyinSegmenter {

    private PinyinSegmenter() {
    }

    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 12;
    private static final int MIN_SYLLABLE_COUNT = 2;
    private static final int MIN_SYLLABLE_LENGTH = 2;

    private static final class Table {
        final Set<String> items;
        final int maxLength;

        Table(Set<String> items, int maxLength) {
            this.items = items;
            this.maxLength = maxLength;
        }
    }

    private static final class TableHolder {
        static final Table TABLE = load();
    }

    /** 是否为有效拼音音节（纯 a–z 且在音节表内）。 */
    static boolean isSyllable(String word) {
        return word != null && !word.isEmpty() && TableHolder.TABLE.items.contains(word);
    }

    /**
     * 尝试把一个纯 ASCII 拉丁词按有效拼音音节完整切分。
     * 成功时把音节写入 {@code syllables}（先清空）并返回 {@code true}；否则返回 {@code false}。
     */
    static boolean trySegment(String word, List<String> syllables) {
        syllables.clear();
        if (word == null) {
            return false;
        }
        int n = word.length();
        if (n < MIN_WORD_LENGTH || n > MAX_WORD_LENGTH) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }

        Table table = TableHolder.TABLE;
        boolean[] reachable = new boolean[n + 1];
        int[] choice = new int[n + 1];
        reachable[n] = true;
        for (int i = n - 1; i >= 0; i--) {
            int limit = Math.min(table.maxLength, n - i);
            for (int length = limit; length >= 1; length--) {
                if (!reachable[i + length]) {
                    continue;
                }
                if (!table.items.contains(word.substring(i, i + length))) {
                    continue;
                }
                reachable[i] = true;
                choice[i] = length;
                break;
            }
        }
        if (!reachable[0]) {
            return false;
        }

        int count = 0;
        for (int i = 0; i < n; ) {
            int length = choice[i];
            if (length < MIN_SYLLABLE_LENGTH) {
                syllables.clear();
                return false;
            }
            syllables.add(word.substring(i, i + length));
            count++;
            i += length;
        }
        if (count < MIN_SYLLABLE_COUNT) {
            syllables.clear();
            return false;
        }
        return true;
    }

    private static Table load() {
        Set<String> items = new HashSet<>(1024);
        int maxLength = 0;
        InputStream stream = PinyinSegmenter.class.getResourceAsStream("/search/pinyin_syllables.txt");
        if (stream == null) {
            throw new IllegalStateException("内嵌数据表缺失: /search/pinyin_syllables.txt");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String syllable = line.trim();
                if (syllable.isEmpty()) {
                    continue;
                }
                items.add(syllable);
                if (syllable.length() > maxLength) {
                    maxLength = syllable.length();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取拼音音节表失败", e);
        }
        return new Table(items, maxLength);
    }

    /** 诊断用：音节表大小。 */
    static int syllableCount() {
        return TableHolder.TABLE.items.size();
    }

    /** 诊断用：把集合转成列表（避免外部直接持有内部集合）。 */
    static List<String> syllableList() {
        return new ArrayList<>(TableHolder.TABLE.items);
    }
}
