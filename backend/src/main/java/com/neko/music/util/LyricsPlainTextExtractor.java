package com.neko.music.util;

import java.util.regex.Pattern;

/** 从 LRC 提取可搜索纯文本。 */
public final class LyricsPlainTextExtractor {

    private static final Pattern TIME_TAG = Pattern.compile("\\[\\d{2}:\\d{2}[:.]\\d{1,5}\\]");
    private static final Pattern META_TAG = Pattern.compile("\\[[a-zA-Z]+:[^\\]]*\\]");
    private static final Pattern TRANSLATION_LINE = Pattern.compile("^\\{[\"'].+[\"']\\}$");

    private LyricsPlainTextExtractor() {
    }

    public static boolean isPlaceholder(String plain) {
        return plain == null || plain.isBlank() || plain.contains("暂无歌词");
    }

    public static String fromLrc(String lrc) {
        if (lrc == null || lrc.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : lrc.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (META_TAG.matcher(line).matches() || TRANSLATION_LINE.matcher(line).matches()) {
                continue;
            }
            line = TIME_TAG.matcher(line).replaceAll("").trim();
            if (line.isEmpty() || LyricsPlaceholderFilter.isSlashPlaceholder(line)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(line);
        }
        return normalize(sb.toString());
    }

    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return ChineseConverter.toSimplified(text)
                .replaceAll("[\\s\\u200B\\u200C\\u200D\\u2060\\uFEFF]+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
