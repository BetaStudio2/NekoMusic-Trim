package com.neko.music.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 过滤歌词源中仅用于占位的 {@code //} / {@code null} 行。
 *
 * <p>只匹配正文去除首尾空白后恰好为占位符的整行，避免误删 URL、正常英文歌词等
 * 包含相同片段的正文。</p>
 */
public final class LyricsPlaceholderFilter {

    private static final Pattern LEADING_TIMESTAMPS = Pattern.compile(
            "^(?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,5})?\\])+");
    private static final Pattern PLACEHOLDER_TRANSLATION = Pattern.compile(
            "^\\{[\"']\\s*(?://|null)\\s*[\"']\\}$", Pattern.CASE_INSENSITIVE);

    private LyricsPlaceholderFilter() {
    }

    public static boolean isPlaceholder(String text) {
        if (text == null) {
            return false;
        }
        String value = text.trim();
        return "//".equals(value) || "null".equalsIgnoreCase(value);
    }

    /** 兼容旧调用方；{@code //} 与 {@code null} 都按占位符处理。 */
    public static boolean isSlashPlaceholder(String text) {
        return isPlaceholder(text);
    }

    /** 判断整行是否为 LRC 翻译占位格式，如 {@code {"null"}}。 */
    public static boolean isPlaceholderTranslation(String text) {
        return text != null && PLACEHOLDER_TRANSLATION.matcher(text.trim()).matches();
    }

    /**
     * 丢弃正文为 {@code //} / {@code null} 的 LRC 行，包括同时间戳的翻译占位行。
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
        if (isPlaceholder(line)) {
            return true;
        }
        if (isPlaceholderTranslation(line)) {
            return true;
        }
        Matcher timestamps = LEADING_TIMESTAMPS.matcher(line);
        if (!timestamps.find()) {
            return false;
        }
        String body = line.substring(timestamps.end()).trim();
        return isPlaceholder(body) || isPlaceholderTranslation(body);
    }
}
