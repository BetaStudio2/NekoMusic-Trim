package com.neko.music.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云歌词解析：把 lyric_new 返回的 YRC / LRC / 翻译统一转换成 Neko 字级格式。
 *
 * <p>新版 YRC 的
 * {@code [start,dur](start,dur,flag)text} 与旧版 YRC 的
 * {@code [mm:ss.xxx]<offset,dur>text} 都会保留字级时间，并同时提供普通 LRC 兼容文本。</p>
 */
public final class NeteaseLyricParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern NEW_YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern NEW_YRC_FRAGMENT =
            Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(\\r\\n]*)");
    private static final Pattern OLD_YRC_FRAGMENT =
            Pattern.compile("<(\\d+),(\\d+)>([^<\\r\\n]*)");
    private static final Pattern TIMED_LINE =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,5}))?\\](.*)$");
    private static final int TRANSLATION_TOLERANCE_MS = 4_000;

    private NeteaseLyricParser() {
    }

    public record ParsedLyrics(String lrc, String rawMain, NekoLyrics.Document rich) {
        public static ParsedLyrics empty() {
            return new ParsedLyrics("", "", new NekoLyrics.Document(
                    NekoLyrics.VERSION, List.of()));
        }
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
        boolean useYrc = hasActualTimedLyric(rawYrc);
        boolean useLrc = hasActualTimedLyric(rawLrc);
        if (!useYrc && !useLrc) {
            return ParsedLyrics.empty();
        }
        NekoLyrics.Document yrcDocument = parseDocument(rawYrc);
        NekoLyrics.Document lrcDocument = parseDocument(rawLrc);

        NekoLyrics.Document main = useYrc ? yrcDocument : lrcDocument;
        String rawMain = useYrc ? rawYrc : rawLrc;
        if (main.isEmpty()) {
            return ParsedLyrics.empty();
        }

        NekoLyrics.Document pairedTranslation = parseDocument(lyricText(root.path("ytlrc")));
        NekoLyrics.Document plainTranslation = parseDocument(lyricText(root.path("tlyric")));
        NekoLyrics.Document translation = !pairedTranslation.isEmpty()
                ? pairedTranslation
                : plainTranslation;
        NekoLyrics.Document pairedRomaji = parseDocument(lyricText(root.path("yromalrc")));
        NekoLyrics.Document plainRomaji = parseDocument(lyricText(root.path("romalrc")));
        NekoLyrics.Document romaji = !pairedRomaji.isEmpty() ? pairedRomaji : plainRomaji;

        NekoLyrics.Document rich = attachSubLyrics(main, translation, romaji);
        return new ParsedLyrics(NekoLyrics.toLrc(rich), rawMain, rich);
    }

    /**
     * 把网易云的任一种歌词文本转换成标准逐行 LRC。
     *
     * <p>普通 LRC 原样保留时间标签；新版 YRC 与旧版字级标签会被还原为纯文本，
     * 随后复用 {@link LrcValidator#normalizeNeteaseTimestamps(String)} 修正网易云
     * 偶发的冒号毫秒、缺失毫秒和多时间戳行。</p>
     */
    static String convertToLrc(String content) {
        return NekoLyrics.toLrc(parseDocument(content));
    }

    /**
     * 解析任一网易云歌词文本为 Neko 字级文档。
     */
    private static NekoLyrics.Document parseDocument(String content) {
        if (content == null || content.isBlank()) {
            return new NekoLyrics.Document(NekoLyrics.VERSION, List.of());
        }
        List<NekoLyrics.Line> lines = new ArrayList<>();
        for (String raw : content.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                NekoLyrics.Line metadata = parseJsonMetadata(line);
                if (metadata != null) {
                    lines.add(metadata);
                }
                continue;
            }
            Matcher newYrc = NEW_YRC_LINE.matcher(line);
            if (newYrc.matches()) {
                NekoLyrics.Line parsed = parseNewYrcLine(newYrc);
                if (parsed != null) {
                    lines.add(parsed);
                }
                continue;
            }
            String normalized = LrcValidator.normalizeNeteaseTimestamps(line);
            for (String timed : normalized.split("\\R", -1)) {
                NekoLyrics.Line parsed = parseTimedLine(timed);
                if (parsed != null) {
                    lines.add(parsed);
                }
            }
        }
        return NekoLyrics.document(lines);
    }

    /**
     * 判断歌词源里是否存在真实的带时间轴正文。JSON 制作信息和没有时间轴的残片
     * （例如网易云偶发返回的裸文本 {@code d的}）都不算歌词。
     */
    private static boolean hasActualTimedLyric(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String raw : content.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("{")) {
                continue;
            }
            Matcher newYrc = NEW_YRC_LINE.matcher(line);
            if (newYrc.matches() && !newYrc.group(3).isBlank()) {
                return true;
            }
            String normalized = LrcValidator.normalizeNeteaseTimestamps(line);
            for (String timed : normalized.split("\\R", -1)) {
                Matcher matcher = TIMED_LINE.matcher(timed.trim());
                if (matcher.matches() && !matcher.group(4).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static NekoLyrics.Line parseJsonMetadata(String line) {
        try {
            JsonNode metadata = OBJECT_MAPPER.readTree(line);
            long timeMs = Math.max(0, metadata.path("t").asLong(0));
            JsonNode contents = metadata.path("c");
            if (!contents.isArray()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : contents) {
                text.append(item.path("tx").asText(""));
            }
            String value = text.toString().trim();
            return value.isEmpty()
                    ? null
                    : new NekoLyrics.Line(timeMs, 0, value, null, null, false, List.of());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static NekoLyrics.Line parseNewYrcLine(Matcher lineMatcher) {
        long lineStart = parseLong(lineMatcher.group(1));
        long lineDuration = parseLong(lineMatcher.group(2));
        String body = lineMatcher.group(3);
        StringBuilder text = new StringBuilder();
        List<NekoLyrics.Word> words = new ArrayList<>();
        int cursor = 0;
        long previousEnd = lineStart;
        Matcher fragment = NEW_YRC_FRAGMENT.matcher(body);
        while (fragment.find()) {
            String plain = body.substring(cursor, fragment.start());
            if (!plain.isEmpty()) {
                text.append(plain);
                if (!plain.isBlank()) {
                    words.add(new NekoLyrics.Word(previousEnd, parseLong(fragment.group(1)), plain));
                }
            }
            long wordStart = parseLong(fragment.group(1));
            long wordEnd = wordStart + parseLong(fragment.group(2));
            String wordText = fragment.group(3);
            text.append(wordText);
            if (!wordText.isEmpty()) {
                words.add(new NekoLyrics.Word(wordStart, wordEnd, wordText));
            }
            previousEnd = wordEnd;
            cursor = fragment.end();
        }
        String tail = body.substring(cursor);
        if (!tail.isEmpty()) {
            text.append(tail);
            if (!tail.isBlank()) {
                words.add(new NekoLyrics.Word(previousEnd, lineStart + lineDuration, tail));
            }
        }
        String value = text.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        return new NekoLyrics.Line(
                lineStart,
                lineStart + lineDuration,
                value,
                null,
                null,
                false,
                words
        );
    }

    private static NekoLyrics.Line parseTimedLine(String rawLine) {
        Matcher matcher = TIMED_LINE.matcher(rawLine.trim());
        if (!matcher.matches()) {
            return null;
        }
        long lineStart = timestampToMs(matcher);
        String body = matcher.group(4);
        List<NekoLyrics.Word> words = new ArrayList<>();
        Matcher fragment = OLD_YRC_FRAGMENT.matcher(body);
        while (fragment.find()) {
            String wordText = fragment.group(3);
            if (wordText.isEmpty()) {
                continue;
            }
            long wordStart = lineStart + parseLong(fragment.group(1));
            words.add(new NekoLyrics.Word(
                    wordStart,
                    wordStart + parseLong(fragment.group(2)),
                    wordText
            ));
        }
        String text = words.isEmpty()
                ? body.trim()
                : words.stream().map(NekoLyrics.Word::text).reduce("", String::concat).trim();
        if (text.isEmpty()) {
            return null;
        }
        return new NekoLyrics.Line(lineStart, 0, text, null, null, false, words);
    }

    private static NekoLyrics.Document attachSubLyrics(
            NekoLyrics.Document main,
            NekoLyrics.Document translation,
            NekoLyrics.Document romaji
    ) {
        if (main.isEmpty()) {
            return main;
        }
        List<NekoLyrics.Line> translated = new ArrayList<>(main.lines().size());
        for (NekoLyrics.Line line : main.lines()) {
            NekoLyrics.Line trans = nearestLine(translation, line.startMs());
            NekoLyrics.Line roma = nearestLine(romaji, line.startMs());
            translated.add(new NekoLyrics.Line(
                    line.startMs(),
                    line.endMs(),
                    line.text(),
                    trans != null && !trans.text().equals(line.text()) ? trans.text() : null,
                    roma != null && !roma.text().equals(line.text()) ? roma.text() : null,
                    line.background(),
                    line.words()
            ));
        }
        return NekoLyrics.document(translated);
    }

    private static NekoLyrics.Line nearestLine(NekoLyrics.Document document, long timeMs) {
        if (document == null || document.lines().isEmpty()) {
            return null;
        }
        List<NekoLyrics.Line> lines = document.lines();
        int low = 0;
        int high = lines.size() - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).startMs() < timeMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        NekoLyrics.Line best = lines.get(low);
        if (low > 0) {
            NekoLyrics.Line previous = lines.get(low - 1);
            if (Math.abs(previous.startMs() - timeMs) < Math.abs(best.startMs() - timeMs)) {
                best = previous;
            }
        }
        return Math.abs(best.startMs() - timeMs) <= TRANSLATION_TOLERANCE_MS ? best : null;
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

    private static String lyricText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        return node.path("lyric").asText("");
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

}
