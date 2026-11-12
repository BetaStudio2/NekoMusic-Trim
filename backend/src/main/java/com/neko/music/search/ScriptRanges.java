/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/shared/text/ScriptRanges.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：由 C# 逐函数翻译为 Java，行为保持一致。
 */

package com.neko.music.search;

/**
 * 字符脚本分类（与索引器共用同一套判定）。
 *
 * <p>「哪些脚本要子串索引」与「什么才算拉丁（罗马音）」以这里为唯一事实来源，
 * 避免索引侧、查询侧与歌词分层各写一套导致漂移。
 */
public final class ScriptRanges {

    private ScriptRanges() {
    }

    /** 汉字：CJK 统一表意主区 + 扩展 A + 兼容表意区。 */
    public static boolean isCjk(char c) {
        return (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }

    /**
     * 需要子串索引的脚本：假名（平 / 片 / 半角 / 片假名音标扩展）+ 谚文（音节 / 字母 / 兼容字母）。
     *
     * <p>这些脚本与汉字同样以 bigram + unigram 入索引，但**不进拼音 / 首字母列**。
     */
    public static boolean isSubstringScript(char c) {
        return isKana(c) || isHangul(c);
    }

    /** 假名：平假名 / 片假名 / 片假名音标扩展 / 半角片假名。 */
    public static boolean isKana(char c) {
        return (c >= '\u3040' && c <= '\u309F')
                || (c >= '\u30A0' && c <= '\u30FF')
                || (c >= '\u31F0' && c <= '\u31FF')
                || (c >= '\uFF66' && c <= '\uFF9D');
    }

    /** 谚文：音节 + 字母（Jamo）+ 兼容字母。 */
    public static boolean isHangul(char c) {
        return (c >= '\uAC00' && c <= '\uD7AF')
                || (c >= '\u1100' && c <= '\u11FF')
                || (c >= '\u3130' && c <= '\u318F');
    }

    /** 拉丁字母（含 Latin-1 补充与 Latin 扩展 A/B）——用于「这一行是不是罗马音」的判定。 */
    public static boolean isLatinLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '\u00C0' && c <= '\u024F');
    }

    /**
     * 是否为纯拉丁行（含拉丁字母、且不含汉字 / 假名 / 谚文）——译文层与音译层的保守判定。
     */
    public static boolean isLatinLine(String text) {
        if (text == null) {
            return false;
        }
        boolean hasLatin = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch) || isSubstringScript(ch)) {
                return false;
            }
            if (isLatinLetter(ch)) {
                hasLatin = true;
            }
        }
        return hasLatin;
    }
}
