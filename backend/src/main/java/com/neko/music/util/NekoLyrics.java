package com.neko.music.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neko 统一歌词格式 {@code neko-v1}。
 *
 * <p>网易云 YRC、QQ QRC 等平台格式先转换成该结构再入库：
 * 行级数据统一使用毫秒时间，{@code words} 保存行内逐字/逐词时间。
 * 下游接口同时提供转换为普通 LRC 的兼容文本。</p>
 */
public final class NekoLyrics {

    public static final String FORMAT = "neko-v1";
    public static final int VERSION = 1;

    private static final Pattern LRC_LINE =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{1,3})(?:[.:](\\d{1,5}))?\\](.*)$");
    private static final Pattern LRC_TRANSLATION = Pattern.compile("^\\{[\"'](.*)[\"']\\}$");
    private static final Pattern LRC_OFFSET =
            Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\]$", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private NekoLyrics() {
    }

    public record Word(long startMs, long endMs, String text) {
        public Word {
            text = text == null ? "" : text;
            endMs = Math.max(startMs, endMs);
        }
    }

    public record Line(
            long startMs,
            long endMs,
            String text,
            String translation,
            String romanization,
            boolean background,
            List<Word> words
    ) {
        public Line {
            text = text == null ? "" : text;
            endMs = Math.max(startMs, endMs);
            words = words == null ? List.of() : List.copyOf(words);
        }

        @JsonIgnore
        public boolean hasWords() {
            return !words.isEmpty();
        }
    }

    public record Document(int version, List<Line> lines) {
        public Document {
            version = version <= 0 ? VERSION : version;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    public static Document document(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return new Document(VERSION, List.of());
        }
        List<Line> filtered = new ArrayList<>(lines.size());
        for (Line line : lines) {
            if (line != null && !LyricsPlaceholderFilter.isPlaceholder(line.text())) {
                filtered.add(line);
            }
        }
        if (filtered.isEmpty()) {
            return new Document(VERSION, List.of());
        }
        List<Line> sorted = new ArrayList<>(filtered);
        sorted.sort(Comparator.comparingLong(Line::startMs)
                .thenComparingLong(Line::endMs));
        List<Line> normalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Line line = sorted.get(i);
            long nextStart = i + 1 < sorted.size() ? sorted.get(i + 1).startMs() : 0;
            long end = line.endMs();
            if (end <= line.startMs()) {
                end = nextStart > line.startMs() ? nextStart : line.startMs() + 4_000;
            }
            normalized.add(new Line(
                    line.startMs(),
                    end,
                    line.text(),
                    optionalText(line.translation()),
                    optionalText(line.romanization()),
                    line.background(),
                    normalizeWords(line.words(), end)
            ));
        }
        return new Document(VERSION, normalized);
    }

    public static String serialize(Document document) {
        try {
            return OBJECT_MAPPER.writeValueAsString(document == null
                    ? new Document(VERSION, List.of())
                    : document);
        } catch (Exception e) {
            throw new IllegalStateException("Neko 歌词序列化失败", e);
        }
    }

    public static Optional<Document> deserialize(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            Document document = OBJECT_MAPPER.readValue(content, Document.class);
            return document == null ? Optional.empty() : Optional.of(document(document.lines()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static boolean isNekoFormat(String format) {
        return format != null && FORMAT.equalsIgnoreCase(format.trim());
    }

    public static boolean looksLikeNekoJson(String content) {
        String trimmed = content == null ? "" : content.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"lines\"");
    }

    /**
     * 把受校验的普通 LRC 转成 Neko 结构，供需要给日语歌词补罗马音时复用。
     */
    public static Optional<Document> fromLrc(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String normalized = LrcValidator.normalizeNeteaseTimestamps(
                LyricsPlaceholderFilter.filterLrc(content));
        List<Line> lines = new ArrayList<>();
        long offsetMs = 0;
        boolean canAttachTranslation = false;
        for (String raw : normalized.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher offset = LRC_OFFSET.matcher(line);
            if (offset.matches()) {
                offsetMs = parseLong(offset.group(1));
                canAttachTranslation = false;
                continue;
            }
            Matcher timed = LRC_LINE.matcher(line);
            if (timed.matches()) {
                String text = timed.group(4).trim();
                if (!text.isEmpty()) {
                    lines.add(new Line(
                            Math.max(0, timestampToMs(timed) + offsetMs),
                            0,
                            text,
                            null,
                            null,
                            false,
                            List.of()
                    ));
                }
                canAttachTranslation = !text.isEmpty();
                continue;
            }
            Matcher translation = LRC_TRANSLATION.matcher(line);
            if (translation.matches() && canAttachTranslation && !lines.isEmpty()) {
                Line previous = lines.removeLast();
                lines.add(new Line(
                        previous.startMs(),
                        previous.endMs(),
                        previous.text(),
                        unescapeTranslation(translation.group(1)),
                        previous.romanization(),
                        previous.background(),
                        previous.words()
                ));
                canAttachTranslation = false;
                continue;
            }
            canAttachTranslation = false;
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(document(lines));
    }

    public static String toLrc(String content, String format) {
        if (!isNekoFormat(format) && !looksLikeNekoJson(content)) {
            String filtered = LyricsPlaceholderFilter.filterLrc(content);
            return filtered == null ? "" : filtered;
        }
        return deserialize(content).map(NekoLyrics::toLrc).orElse("");
    }

    public static String toLrc(Document document) {
        if (document == null || document.lines().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Line line : document.lines()) {
            if (line.text().isBlank()
                    || LyricsPlaceholderFilter.isPlaceholder(line.text())) {
                continue;
            }
            out.append(formatTimestamp(line.startMs())).append(line.text()).append('\n');
            if (line.translation() != null
                    && !line.translation().isBlank()
                    && !LyricsPlaceholderFilter.isPlaceholder(line.translation())) {
                out.append("{\"").append(escapeTranslation(line.translation())).append("\"}\n");
            }
        }
        return out.toString().trim();
    }

    public static String plainText(Document document) {
        if (document == null || document.lines().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Line line : document.lines()) {
            if (line.text().isBlank()
                    || LyricsPlaceholderFilter.isPlaceholder(line.text())) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(line.text().trim());
        }
        return LyricsPlainTextExtractor.normalize(out.toString());
    }

    private static List<Word> normalizeWords(List<Word> words, long lineEndMs) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<Word> normalized = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            long end = word.endMs();
            if (end <= word.startMs()) {
                end = i + 1 < words.size() ? words.get(i + 1).startMs() : lineEndMs;
            }
            if (end <= word.startMs()) {
                end = word.startMs() + 1;
            }
            normalized.add(new Word(word.startMs(), end, word.text()));
        }
        return List.copyOf(normalized);
    }

    private static String formatTimestamp(long timeMs) {
        long safe = Math.max(0, timeMs);
        long minutes = safe / 60_000;
        long seconds = (safe % 60_000) / 1_000;
        long millis = safe % 1_000;
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis);
    }

    private static String escapeTranslation(String translation) {
        return translation.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeTranslation(String translation) {
        return translation.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long timestampToMs(Matcher matcher) {
        long minutes = parseLong(matcher.group(1));
        long seconds = parseLong(matcher.group(2));
        String fraction = matcher.group(3) == null ? "" : matcher.group(3);
        long millis = fraction.isEmpty()
                ? 0
                : parseLong(fraction.length() >= 3
                        ? fraction.substring(0, 3)
                        : fraction + "0".repeat(3 - fraction.length()));
        return minutes * 60_000 + seconds * 1_000 + millis;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String optionalText(String value) {
        String cleaned = blankToNull(value);
        return LyricsPlaceholderFilter.isPlaceholder(cleaned) ? null : cleaned;
    }
}
