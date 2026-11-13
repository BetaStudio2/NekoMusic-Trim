/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：
 */

package com.neko.music.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 罗马字规范形：把 Hepburn / Kunrei 折叠成同一串。
 *
 * <ul>
 *   <li>{@code sh→s}、{@code ch→t}、{@code ts→t}、{@code fu→hu}、{@code j→z}；</li>
 *   <li>促音折叠：重复辅音压成一个（{@code nn} 保留）；</li>
 *   <li>长音折叠：{@code ou / oo → o}。</li>
 * </ul>
 */
final class RomajiCanonical {

    private RomajiCanonical() {
    }

    /** 规范形：折叠 Hepburn / Kunrei 差异、促音与长音。 */
    static String apply(String romaji) {
        if (romaji == null || romaji.isEmpty()) {
            return romaji == null ? "" : romaji;
        }
        String folded = foldSpelling(romaji);
        folded = foldGeminates(folded);
        return foldLongVowels(folded);
    }

    /** 查询侧候选：规范形 + 「ん + 元音」放宽变体（{@code n + 元音 → nn + 元音}）。 */
    static List<String> variants(String token) {
        String canonical = apply(token);
        String relaxed = relaxN(canonical);
        List<String> output = new ArrayList<>(2);
        output.add(canonical);
        if (!canonical.equals(relaxed)) {
            output.add(relaxed);
        }
        return output;
    }

    private static String foldSpelling(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (c == 't' && next == 's') {
                sb.append('t');
                i++;
            } else if (c == 's' && next == 'h') {
                sb.append('s');
                i++;
            } else if (c == 'c' && next == 'h') {
                sb.append('t');
                i++;
            } else if (c == 'f' && next == 'u') {
                sb.append("hu");
                i++;
            } else if (c == 'j') {
                sb.append('z');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String foldGeminates(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c && isFoldableConsonant(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isFoldableConsonant(char c) {
        return c >= 'a' && c <= 'z' && "aeioun".indexOf(c) < 0;
    }

    private static String foldLongVowels(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'o' && i + 1 < s.length() && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'o')) {
                sb.append('o');
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String relaxN(String s) {
        if (s.length() < 2) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'n' && i + 1 < s.length() && "aeiou".indexOf(s.charAt(i + 1)) >= 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == 'n') {
                    sb.append(c);
                    continue;
                }
                sb.append("nn");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
