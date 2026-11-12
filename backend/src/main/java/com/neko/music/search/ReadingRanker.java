/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/ReadingRanker.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：由 C# 逐函数翻译为 Java。
 */

package com.neko.music.search;

import java.util.List;

/**
 * 读音兜底趟的命中分档。用候选的读音 bigram 原文副本（{@code search_docs.reading_tok}）
 * 复查，把结果重排为「整词前缀 &gt; 整词包含 &gt; 其它」。
 */
final class ReadingRanker {

    private ReadingRanker() {
    }

    /** 整词前缀：读音串以查询 bigram 序列开头。 */
    static final int PREFIX_TIER = 3;

    /** 整词包含：读音串中间出现查询 bigram 序列。 */
    static final int CONTAINS_TIER = 2;

    /** 非连续 / 无读音文本的兜底档。 */
    static final int OTHER_TIER = 0;

    /** 给单个候选的读音串打分（取所有查询序列变体中的最高档）。 */
    static int score(String readingText, List<String[]> sequences) {
        if (readingText == null || readingText.isEmpty() || sequences.isEmpty()) {
            return OTHER_TIER;
        }

        String[] tokens = readingText.split("\\s+");
        int best = OTHER_TIER;
        for (String[] sequence : sequences) {
            if (sequence.length == 0) {
                continue;
            }
            if (isPrefix(tokens, sequence)) {
                return PREFIX_TIER;
            }
            if (contains(tokens, sequence)) {
                best = CONTAINS_TIER;
            }
        }
        return best;
    }

    private static boolean isPrefix(String[] tokens, String[] sequence) {
        if (tokens.length < sequence.length) {
            return false;
        }
        for (int i = 0; i < sequence.length; i++) {
            if (!tokens[i].equals(sequence[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String[] tokens, String[] sequence) {
        if (tokens.length < sequence.length) {
            return false;
        }
        int last = tokens.length - sequence.length;
        for (int start = 0; start <= last; start++) {
            boolean matched = true;
            for (int i = 0; i < sequence.length; i++) {
                if (tokens[start + i].equals(sequence[i])) {
                    continue;
                }
                matched = false;
                break;
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }
}
