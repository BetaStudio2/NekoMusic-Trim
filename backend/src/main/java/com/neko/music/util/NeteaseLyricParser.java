package com.neko.music.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云歌词解析：把 lyric_new 返回的 YRC / LRC / 翻译统一转换成项目当前使用的逐行 LRC。
 *
 * <p>本阶段只保留行级时间轴，不把字级片段写入数据库。新版 YRC 的
 * {@code [start,dur](start,dur,flag)text} 与旧版 YRC 的
 * {@code [mm:ss.xxx]<offset,dur>text} 都会先还原成纯文本行。</p>
 */
public final class NeteaseLyricParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern NEW_YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern NEW_YRC_FRAGMENT =
            Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(\\r\\n]*)");
    private static final Pattern OLD_YRC_FRAGMENT = Pattern.compile("<\\d+,\\d+>");
    private static final Pattern TIMED_LINE =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,5}))?\\](.*)$");
    private static final int TRANSLATION_TOLERANCE_MS = 4_000;

    private NeteaseLyricParser() {
    }

    public record ParsedLyrics(String lrc, String rawMain) {
        public static ParsedLyrics empty() {
            return new ParsedLyrics("", "");
        }
    }

    private record TimedText(long timeMs, String text) {
    }

    /**
     * 解析 lyric_new 完整响应。主歌词优先使用 YRC，无法形成时间轴时回退 LRC；
     * 翻译优先使用与 YRC 配套的 ytlrc，再回退 tlyric。
     */
    public static ParsedLyrics parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return ParsedLyrics.empty();
        }

        String rawYrc = lyricText(root.path("yrc"));
        String rawLrc = lyricText(root.path("lrc"));
        String yrcLrc = convertToLrc(rawYrc);
        String plainLrc = convertToLrc(rawLrc);

        String main = hasTimedContent(yrcLrc) ? yrcLrc : plainLrc;
        String rawMain = hasTimedContent(yrcLrc) ? rawYrc : rawLrc;
        if (!hasTimedContent(main)) {
            return ParsedLyrics.empty();
        }

        String pairedTranslation = convertToLrc(lyricText(root.path("ytlrc")));
        String plainTranslation = convertToLrc(lyricText(root.path("tlyric")));
        String translation = hasTimedContent(pairedTranslation)
                ? pairedTranslation
                : plainTranslation;
        return new ParsedLyrics(mergeTranslations(main, translation), rawMain);
    }

    /**
     * 把网易云的任一种歌词文本转换成标准逐行 LRC。
     *
     * <p>普通 LRC 原样保留时间标签；新版 YRC 与旧版字级标签会被还原为纯文本，
     * 随后复用 {@link LrcValidator#normalizeNeteaseTimestamps(String)} 修正网易云
     * 偶发的冒号毫秒、缺失毫秒和多时间戳行。</p>
     */
    static String convertToLrc(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                String metadata = convertJsonMetadata(line);
                if (!metadata.isEmpty()) {
                    lines.add(metadata);
                }
                continue;
            }

            Matcher newYrc = NEW_YRC_LINE.matcher(line);
            if (newYrc.matches()) {
                String text = stripNewYrcFragments(newYrc.group(3));
                if (!text.isEmpty()) {
                    lines.add(formatTimestamp(parseLong(newYrc.group(1))) + text);
                }
                continue;
            }

            // 旧版 YRC 与普通 LRC 共用 [mm:ss.xxx] 行时间，只去掉行内字级标签。
            lines.add(OLD_YRC_FRAGMENT.matcher(line).replaceAll(""));
        }
        return LrcValidator.normalizeNeteaseTimestamps(String.join("\n", lines)).trim();
    }

    /**
     * 新版 YRC 的元数据行：{@code {"t":-1000,"c":[{"tx":"作词: "},{"tx":"周仁"}]}}。
     */
    private static String convertJsonMetadata(String line) {
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(line);
            long timeMs = metadata.path("t").asLong(0);
            JsonNode contents = metadata.path("c");
            if (!contents.isArray()) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : contents) {
                text.append(item.path("tx").asText(""));
            }
            String value = text.toString().trim();
            return value.isEmpty() ? "" : formatTimestamp(Math.max(0, timeMs)) + value;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 新版 YRC 的字级标记是 {@code (start,dur,flag)text}，这里只拼接文本，
     * 保留片段之间的普通文本，暂不生成字级时间轴。
     */
    private static String stripNewYrcFragments(String body) {
        StringBuilder text = new StringBuilder();
        int cursor = 0;
        Matcher fragment = NEW_YRC_FRAGMENT.matcher(body);
        while (fragment.find()) {
            text.append(body, cursor, fragment.start());
            text.append(fragment.group(3));
            cursor = fragment.end();
        }
        text.append(body.substring(cursor));
        return text.toString().trim();
    }

    /**
     * 把翻译行按最近时间挂到主歌词后面，沿用现有 {@code {"翻译"}} LRC 约定。
     */
    private static String mergeTranslations(String mainLrc, String translationLrc) {
        if (!hasTimedContent(translationLrc)) {
            return mainLrc;
        }

        List<TimedText> translations = new ArrayList<>();
        for (String line : translationLrc.split("\\R", -1)) {
            TimedText timed = parseTimedText(line);
            if (timed != null && !timed.text().isEmpty()) {
                translations.add(timed);
            }
        }
        if (translations.isEmpty()) {
            return mainLrc;
        }
        translations.sort(Comparator.comparingLong(TimedText::timeMs));

        StringBuilder merged = new StringBuilder(mainLrc.length() + translationLrc.length());
        for (String line : mainLrc.split("\\R", -1)) {
            merged.append(line).append('\n');
            TimedText timed = parseTimedText(line);
            if (timed == null) {
                continue;
            }
            TimedText nearest = nearestTranslation(translations, timed.timeMs());
            if (nearest == null
                    || Math.abs(nearest.timeMs() - timed.timeMs()) > TRANSLATION_TOLERANCE_MS
                    || nearest.text().equals(timed.text())) {
                continue;
            }
            merged.append("{\"").append(escapeTranslation(nearest.text())).append("\"}\n");
        }
        return merged.toString().trim();
    }

    private static TimedText nearestTranslation(List<TimedText> translations, long timeMs) {
        int low = 0;
        int high = translations.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (translations.get(mid).timeMs() < timeMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        TimedText best = translations.get(low);
        if (low > 0) {
            TimedText previous = translations.get(low - 1);
            if (Math.abs(previous.timeMs() - timeMs) < Math.abs(best.timeMs() - timeMs)) {
                best = previous;
            }
        }
        return best;
    }

    private static TimedText parseTimedText(String line) {
        Matcher matcher = TIMED_LINE.matcher(line.trim());
        if (!matcher.matches() || matcher.group(4).isBlank()) {
            return null;
        }
        long minutes = parseLong(matcher.group(1));
        long seconds = parseLong(matcher.group(2));
        String fraction = matcher.group(3) == null ? "" : matcher.group(3);
        long millis = fraction.isEmpty()
                ? 0
                : parseLong(fraction.length() >= 3 ? fraction.substring(0, 3) : fraction + "0".repeat(3 - fraction.length()));
        return new TimedText(minutes * 60_000 + seconds * 1_000 + millis, matcher.group(4).trim());
    }

    private static boolean hasTimedContent(String lrc) {
        if (lrc == null || lrc.isBlank()) {
            return false;
        }
        for (String line : lrc.split("\\R", -1)) {
            String trimmed = line.trim();
            if (TIMED_LINE.matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String lyricText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.path("lyric").asText("");
    }

    private static String formatTimestamp(long timeMs) {
        long safe = Math.max(0, timeMs);
        long minutes = safe / 60_000;
        long seconds = (safe % 60_000) / 1_000;
        long millis = safe % 1_000;
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String escapeTranslation(String translation) {
        return translation.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
