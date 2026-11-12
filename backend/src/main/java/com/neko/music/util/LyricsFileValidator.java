package com.neko.music.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.i18n.ApiMessages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 上传歌词文件校验：沿用 {@code .lrc} 行级歌词，并支持 {@code .neko} 富歌词 JSON。
 */
public final class LyricsFileValidator {
    public static final String LRC_EXTENSION = ".lrc";
    public static final String NEKO_EXTENSION = ".neko";
    public static final long NEKO_MAX_FILE_SIZE_BYTES = 1024 * 1024;
    public static final int MAX_LINES = 5000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum Format {
        LRC("lrc"),
        NEKO(NekoLyrics.FORMAT);

        private final String databaseFormat;

        Format(String databaseFormat) {
            this.databaseFormat = databaseFormat;
        }

        public String databaseFormat() {
            return databaseFormat;
        }
    }

    public record ValidationResult(
            boolean valid,
            Format format,
            String normalizedContent,
            String errorMessage
    ) {
        public boolean isValid() {
            return valid;
        }

        public Format getFormat() {
            return format;
        }

        public String getNormalizedContent() {
            return normalizedContent;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        static ValidationResult success(Format format, String normalizedContent) {
            return new ValidationResult(true, format, normalizedContent, null);
        }

        static ValidationResult fail(String errorMessage) {
            return new ValidationResult(false, null, null, errorMessage);
        }
    }

    private LyricsFileValidator() {
    }

    public static boolean isSupportedFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(LRC_EXTENSION) || lower.endsWith(NEKO_EXTENSION);
    }

    public static boolean isNekoFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(NEKO_EXTENSION);
    }

    public static ValidationResult validate(String fileName, InputStream input, long fileSize) {
        if (isNekoFile(fileName)) {
            return validateNeko(input, fileSize);
        }
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(LRC_EXTENSION)) {
            return validateLrc(input, fileSize);
        }
        return ValidationResult.fail(ApiMessages.translate("upload.lyrics_only_lrc"));
    }

    private static ValidationResult validateLrc(InputStream input, long fileSize) {
        LrcValidator.ValidationResult result = LrcValidator.validate(input, fileSize);
        return result.isValid()
                ? ValidationResult.success(Format.LRC, result.getNormalizedContent())
                : ValidationResult.fail(result.getErrorMessage());
    }

    private static ValidationResult validateNeko(InputStream input, long fileSize) {
        if (fileSize > NEKO_MAX_FILE_SIZE_BYTES) {
            return ValidationResult.fail("NEKO 歌词文件大小不能超过1MiB");
        }

        String content;
        try {
            content = decodeUtf8(input.readAllBytes());
        } catch (IOException e) {
            return ValidationResult.fail("歌词文件读取失败：" + e.getMessage());
        }
        if (content.isBlank()) {
            return ValidationResult.fail("歌词文件内容为空");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(content);
        } catch (Exception e) {
            return ValidationResult.fail(".neko 必须是合法的 neko-v1 JSON");
        }
        if (root == null || !root.isObject()) {
            return ValidationResult.fail(".neko 顶层必须是 neko-v1 JSON 对象");
        }
        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() != NekoLyrics.VERSION) {
            return ValidationResult.fail(".neko 的 version 必须为 " + NekoLyrics.VERSION);
        }
        JsonNode lines = root.get("lines");
        if (lines == null || !lines.isArray()) {
            return ValidationResult.fail(".neko 的 lines 必须是数组");
        }
        if (lines.size() > MAX_LINES) {
            return ValidationResult.fail("歌词行数不能超过 " + MAX_LINES);
        }

        boolean hasLyricText = false;
        for (int i = 0; i < lines.size(); i++) {
            JsonNode line = lines.get(i);
            String prefix = "第" + (i + 1) + "行";
            if (line == null || !line.isObject()) {
                return ValidationResult.fail(prefix + "必须是对象");
            }
            String text = requiredText(line, "text");
            if (text == null) {
                return ValidationResult.fail(prefix + "缺少字符串字段 text");
            }
            hasLyricText |= !text.isBlank();
            if (!validOptionalText(line, "translation")
                    || !validOptionalText(line, "romanization")) {
                return ValidationResult.fail(prefix + "的 translation / romanization 必须是字符串或 null");
            }
            if (line.has("background") && !line.get("background").isBoolean()) {
                return ValidationResult.fail(prefix + "的 background 必须是布尔值");
            }

            String timingError = validateTiming(line, "startMs", "endMs", prefix);
            if (timingError != null) {
                return ValidationResult.fail(timingError);
            }

            JsonNode words = line.get("words");
            if (words != null && !words.isNull()) {
                if (!words.isArray()) {
                    return ValidationResult.fail(prefix + "的 words 必须是数组");
                }
                for (int j = 0; j < words.size(); j++) {
                    JsonNode word = words.get(j);
                    String wordPrefix = prefix + "第" + (j + 1) + "个逐字片段";
                    if (word == null || !word.isObject()) {
                        return ValidationResult.fail(wordPrefix + "必须是对象");
                    }
                    if (requiredText(word, "text") == null) {
                        return ValidationResult.fail(wordPrefix + "缺少字符串字段 text");
                    }
                    String wordTimingError = validateTiming(word, "startMs", "endMs", wordPrefix);
                    if (wordTimingError != null) {
                        return ValidationResult.fail(wordTimingError);
                    }
                }
            }
        }
        if (!hasLyricText) {
            return ValidationResult.fail("歌词文件没有有效歌词正文");
        }

        NekoLyrics.Document document = NekoLyrics.deserialize(content).orElse(null);
        if (document == null || document.isEmpty()) {
            return ValidationResult.fail("歌词文件没有有效歌词行");
        }
        return ValidationResult.success(Format.NEKO, NekoLyrics.serialize(document));
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static boolean validOptionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isTextual();
    }

    private static String validateTiming(JsonNode node, String startField, String endField, String prefix) {
        JsonNode start = node.get(startField);
        JsonNode end = node.get(endField);
        if (start == null || !start.isIntegralNumber()
                || end == null || !end.isIntegralNumber()) {
            return prefix + "的 " + startField + " / " + endField + " 必须是整数毫秒";
        }
        long startMs = start.asLong();
        long endMs = end.asLong();
        if (startMs < 0 || endMs < startMs) {
            return prefix + "的时间范围无效";
        }
        return null;
    }
}
