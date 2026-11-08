package com.neko.music.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 过滤歌词源中仅用于占位的 {@code //} 行。
 *
 * <p>只匹配正文去除首尾空白后恰好为 {@code //} 的整行，避免误删 URL 或正常歌词中
 * 包含的双斜杠。</p>
 */
public final class LyricsPlaceholderFilter {

    private static final Pattern LEADING_TIMESTAMPS = Pattern.compile(
            "^(?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,5})?\\])+");
    private static final Pattern SLASH_TRANSLATION = Pattern.compile(
            "^\\{[\"']\\s*//\\s*[\"']\\}$");

    private LyricsPlaceholderFilter() {
    }

    public static boolean isSlashPlaceholder(String text) {
        return text != null && "//".equals(text.trim());
    }

    /**
     * 丢弃正文为 {@code //} 的 LRC 行，包括同时间戳的翻译占位行。
     * 其它行保持原始内容不变。
     */
    public static String filterLrc(String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return lrc;
        }
        String[] lines = lrc.split("\\R", -1);
        StringBuilder filtered = new StringBuilder(lrc.length());
        boolean first = true;
        for (String rawLine : lines) {
            if (isDiscardableLrcLine(rawLine)) {
                continue;
            }
            if (!first) {
                filtered.append('\n');
            }
            filtered.append(rawLine);
            first = false;
        }
        return filtered.toString();
    }

    private static boolean isDiscardableLrcLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty()) {
            return false;
        }
        if (isSlashPlaceholder(line)) {
            return true;
        }
        if (SLASH_TRANSLATION.matcher(line).matches()) {
            return true;
        }
        Matcher timestamps = LEADING_TIMESTAMPS.matcher(line);
        if (!timestamps.find()) {
            return false;
        }
        return isSlashPlaceholder(line.substring(timestamps.end()));
    }
}
