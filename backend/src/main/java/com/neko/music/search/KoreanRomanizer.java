/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/Korean.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：由 C# 逐函数翻译为 Java。
 */

package com.neko.music.search;

/**
 * 韩文读音：谚文音节 → 国语罗马字（RR），写入读音列（字母 bigram）。
 * 只做字面转写，音变后置迭代。
 */
final class KoreanRomanizer {

    private KoreanRomanizer() {
    }

    private static final String[] INITIALS = {
            "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s",
            "ss", "", "j", "jj", "ch", "k", "t", "p", "h",
    };

    private static final String[] VOWELS = {
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae",
            "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    };

    private static final String[] FINALS = {
            "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m",
            "l", "l", "l", "p", "l", "m", "p", "p", "t", "t",
            "ng", "t", "t", "k", "t", "p", "t",
    };

    private static final String[] COMPAT = {
            "g", "kk", "gs", "n", "nj", "nh", "d", "tt", "r", "lg", "lm", "lb", "ls",
            "lt", "lp", "lh", "m", "b", "pp", "bs", "s", "ss", "ng", "j", "jj", "ch",
            "k", "t", "p", "h",
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe",
            "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    };

    static boolean containsHangul(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isHangul(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isHangul(char c) {
        return (c >= '\uAC00' && c <= '\uD7A3') || (c >= '\u3131' && c <= '\u3163');
    }

    /** 谚文串 → RR 罗马字（非谚文字符丢弃；调用方只传连续谚文段）。 */
    static String romanize(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\uAC00' && ch <= '\uD7A3') {
                int index = ch - '\uAC00';
                sb.append(INITIALS[index / (21 * 28)]);
                sb.append(VOWELS[index % (21 * 28) / 28]);
                sb.append(FINALS[index % 28]);
            } else if (ch >= '\u3131' && ch <= '\u3163') {
                sb.append(COMPAT[ch - '\u3131']);
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }
}
