package com.neko.music.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐 QRC 解析：把 QRC + 翻译 + 罗马音转换成 {@link NekoLyrics}。
 *
 * <p>QRC 行头为 {@code [起始毫秒,时长毫秒]}，字级标记为
 * {@code 文字(起始毫秒,时长毫秒)}（文字在前、时间为绝对值）。</p>
 */
public final class QqLyricParser {

    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern QRC_TIME = Pattern.compile("\\((\\d+),(\\d+)\\)");
    private static final Pattern LRC_LINE =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,5}))?\\](.*)$");
    private static final int SUB_TOLERANCE_MS = 4_000;

    private QqLyricParser() {
    }

    public static NekoLyrics.Document parse(String qrc, String translation, String romaji) {
        NekoLyrics.Document main = parseDocument(qrc, true);
        if (main.isEmpty()) {
            return main;
        }
        NekoLyrics.Document trans = parseDocument(translation, true);
        NekoLyrics.Document roma = parseDocument(romaji, true);
        return attachSubLyrics(main, trans, roma);
    }

    static NekoLyrics.Document parseDocument(String content, boolean allowQrc) {
        if (content == null || content.isBlank()) {
            return new NekoLyrics.Document(NekoLyrics.VERSION, List.of());
        }
        String unwrapped = unwrapQrcXml(content);
        List<NekoLyrics.Line> lines = new ArrayList<>();
        for (String raw : unwrapped.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (allowQrc) {
                Matcher qrcLine = QRC_LINE.matcher(line);
                if (qrcLine.matches()) {
                    NekoLyrics.Line parsed = parseQrcLine(qrcLine);
                    if (parsed != null) {
                        lines.add(parsed);
                    }
                    continue;
                }
            }
            NekoLyrics.Line parsed = parseLrcLine(line);
            if (parsed != null) {
                lines.add(parsed);
            }
        }
        return NekoLyrics.document(lines);
    }

    private static NekoLyrics.Line parseQrcLine(Matcher lineMatcher) {
        long lineStart = parseLong(lineMatcher.group(1));
        long lineDuration = parseLong(lineMatcher.group(2));
        List<NekoLyrics.Word> words = parseQrcWords(lineMatcher.group(3));
        if (words.isEmpty()) {
            return null;
        }
        String text = words.stream().map(NekoLyrics.Word::text)
                .reduce("", String::concat).trim();
        if (text.isEmpty()) {
            return null;
        }
        return new NekoLyrics.Line(
                lineStart,
                lineStart + lineDuration,
                text,
                null,
                null,
                false,
                words
        );
    }

    private static List<NekoLyrics.Word> parseQrcWords(String body) {
        List<NekoLyrics.Word> words = new ArrayList<>();
        int cursor = 0;
        long lastStart = -1;
        long lastEnd = -1;
        while (cursor < body.length()) {
            int marker = body.indexOf('(', cursor);
            while (marker >= 0 && !startsWithDigit(body, marker + 1)) {
                marker = body.indexOf('(', marker + 1);
            }
            if (marker < 0) {
                break;
            }
            Matcher time = QRC_TIME.matcher(body);
            if (!time.find(marker) || time.start() != marker) {
                break;
            }
            long start = parseLong(time.group(1));
            long end = start + parseLong(time.group(2));
            String text = body.substring(cursor, marker);
            if (!text.isEmpty()) {
                words.add(new NekoLyrics.Word(start, end, text));
                lastStart = start;
                lastEnd = end;
            }
            cursor = time.end();
            if (cursor < body.length() && body.charAt(cursor) == ')') {
                words.add(new NekoLyrics.Word(start, end, ")"));
                lastStart = start;
                lastEnd = end;
                cursor++;
            }
        }
        if (cursor < body.length()) {
            String tail = body.substring(cursor);
            if (!tail.isBlank()) {
                long start = lastStart >= 0 ? lastStart : 0;
                long end = lastEnd > start ? lastEnd : start + 1;
                words.add(new NekoLyrics.Word(start, end, tail));
            }
        }
        return words;
    }

    private static NekoLyrics.Line parseLrcLine(String line) {
        Matcher matcher = LRC_LINE.matcher(line);
        if (!matcher.matches() || matcher.group(4).isBlank()) {
            return null;
        }
        long start = timestampToMs(matcher);
        return new NekoLyrics.Line(
                start,
                0,
                matcher.group(4).trim(),
                null,
                null,
                false,
                List.of()
        );
    }

    private static NekoLyrics.Document attachSubLyrics(
            NekoLyrics.Document main,
            NekoLyrics.Document translation,
            NekoLyrics.Document romaji
    ) {
        List<NekoLyrics.Line> out = new ArrayList<>(main.lines().size());
        for (NekoLyrics.Line line : main.lines()) {
            NekoLyrics.Line trans = nearestLine(translation, line.startMs());
            NekoLyrics.Line roma = nearestLine(romaji, line.startMs());
            out.add(new NekoLyrics.Line(
                    line.startMs(),
                    line.endMs(),
                    line.text(),
                    trans != null && !trans.text().equals(line.text()) ? trans.text() : null,
                    roma != null && !roma.text().equals(line.text()) ? roma.text() : null,
                    line.background(),
                    line.words()
            ));
        }
        return NekoLyrics.document(out);
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
        return Math.abs(best.startMs() - timeMs) <= SUB_TOLERANCE_MS ? best : null;
    }

    /**
     * 云端 QRC 可能带 {@code <Lyric_1 LyricContent="..."/>} XML 外壳。
     */
    private static String unwrapQrcXml(String raw) {
        String trimmed = raw.trim();
        String marker = "LyricContent=\"";
        if (!trimmed.startsWith("<") || !trimmed.contains(marker)) {
            return raw;
        }
        int start = trimmed.indexOf(marker) + marker.length();
        int end = trimmed.lastIndexOf('"');
        if (end <= start) {
            return raw;
        }
        return trimmed.substring(start, end)
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&amp;", "&");
    }

    private static boolean startsWithDigit(String value, int index) {
        if (index < 0 || index >= value.length()) {
            return false;
        }
        char c = value.charAt(index);
        return c >= '0' && c <= '9';
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
}
