/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：
 */

package com.neko.music.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模糊层的词典工具：在前缀有序词表里定位区间、判定编辑距离。
 *
 * <p>词典来源是索引自身的 FTS 词表（{@code fts5vocab}），查询侧按序缓存，
 * 变体只保留「索引里确实存在」的候选。
 */
final class FuzzyVocabulary {

    private FuzzyVocabulary() {
    }

    /** 半开区间 [start, end) 与 [end, …) 的便捷载体。 */
    record Range(int start, int end) {
        boolean isEmpty() {
            return start >= end;
        }
    }

    /** 在字典序有序词表中取「所有以 {@code prefix} 开头」的半开区间。 */
    static Range prefixRange(List<String> sorted, String prefix) {
        int start = lowerBound(sorted, prefix);
        if (start >= sorted.size()) {
            return new Range(start, start);
        }
        int low = start;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).startsWith(prefix)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return new Range(start, low);
    }

    /** Damerau（OSA）距离 ≤ 1：Myers 位并行 Levenshtein + 单次相邻换位。 */
    static boolean withinOne(String left, String right) {
        if (Math.abs(left.length() - right.length()) > 1) {
            return false;
        }
        if (myersWithinOne(left, right)) {
            return true;
        }
        return isSingleAdjacentSwap(left, right);
    }

    private static int lowerBound(List<String> sorted, String key) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted.get(mid).compareTo(key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Myers 位并行编辑距离，判定是否 ≤ 1。模式长度 ≤ 64 走位运算；
     * 更长的查询退回有界 DP，保证正确性。
     */
    private static boolean myersWithinOne(String pattern, String text) {
        if (pattern.isEmpty()) {
            return text.length() <= 1;
        }
        if (pattern.length() > 64) {
            return boundedLevenshteinWithinOne(pattern, text);
        }

        Map<Character, Long> masks = new HashMap<>(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            long bit = 1L << i;
            char ch = pattern.charAt(i);
            Long existing = masks.get(ch);
            masks.put(ch, existing == null ? bit : existing | bit);
        }

        long pv = ~0L;
        long mv = 0L;
        int score = pattern.length();
        long top = 1L << (pattern.length() - 1);

        for (int t = 0; t < text.length(); t++) {
            Long mask = masks.get(text.charAt(t));
            long eq = mask == null ? 0L : mask;
            long xv = eq | mv;
            long xh = (((eq & pv) + pv) ^ pv) | eq;
            long ph = mv | ~(xh | pv);
            long mh = pv & xh;
            if ((ph & top) != 0) {
                score++;
            }
            if ((mh & top) != 0) {
                score--;
            }
            ph = (ph << 1) | 1L;
            mh <<= 1;
            pv = mh | ~(xv | ph);
            mv = ph & xv;
        }

        return score <= 1;
    }

    private static boolean boundedLevenshteinWithinOne(String pattern, String text) {
        int[] previous = new int[text.length() + 1];
        int[] current = new int[text.length() + 1];
        for (int j = 0; j <= text.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= pattern.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= text.length(); j++) {
                int cost = pattern.charAt(i - 1) == text.charAt(j - 1) ? 0 : 1;
                int value = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + cost);
                current[j] = value;
                if (value < rowMin) {
                    rowMin = value;
                }
            }
            if (rowMin > 1) {
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[text.length()] <= 1;
    }

    private static boolean isSingleAdjacentSwap(String left, String right) {
        if (left.length() != right.length() || left.length() < 2) {
            return false;
        }

        int i = 0;
        while (i < left.length() && left.charAt(i) == right.charAt(i)) {
            i++;
        }
        if (i + 1 >= left.length()) {
            return false;
        }
        if (left.charAt(i) != right.charAt(i + 1) || left.charAt(i + 1) != right.charAt(i)) {
            return false;
        }

        for (int j = i + 2; j < left.length(); j++) {
            if (left.charAt(j) != right.charAt(j)) {
                return false;
            }
        }
        return true;
    }
}
