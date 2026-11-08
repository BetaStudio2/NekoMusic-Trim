package com.neko.music.service;

import com.neko.music.util.ChineseConverter;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 外站自动补全的非音乐内容黑名单。
 *
 * <p>只用于爬取/补全入库前的判定，不影响管理员或用户主动上传。规则刻意保留
 * 「广播剧」等可能出现在正常音乐标题中的词，避免把主题曲、片尾曲一并拦掉。
 * 同时拦截歌名仅由标点或符号组成的曲目，避免用无检索价值的标题请求外站。</p>
 */
final class NonMusicContentFilter {

    private static final List<String> CONTENT_TERMS = List.of(
            "有声小说",
            "有声书",
            "有声剧",
            "长篇小说",
            "小说连播",
            "听书",
            "评书",
            "相声",
            "播客",
            "多人剧",
            "故事会",
            "睡前故事",
            "儿童故事",
            "童话故事",
            "课文朗读",
            "古诗朗读",
            "诗词朗读",
            "朗诵",
            "听电影学英语",
            "看动画学古诗",
            "语言学习",
            "英语听力",
            "听力训练",
            "背单词",
            "唐诗三百首",
            "宋词三百首",
            "百家讲坛",
            "ai专辑",
            "ai有声"
    );

    private static final List<String> CHANNEL_TERMS = List.of(
            "喜马拉雅",
            "懒人听书",
            "番茄畅听",
            "酷我畅听",
            "蜻蜓fm",
            "荔枝fm",
            "小宇宙",
            "微信读书",
            "qq阅读",
            "樊登读书",
            "凯叔讲故事",
            "圆柱文化"
    );

    private static final Pattern EPISODE_MARKER = Pattern.compile(
            "第\\s*[0-9一二三四五六七八九十百千万零〇两]+\\s*[集章回期话]"
    );

    private static final Pattern CHAPTER_MARKER = Pattern.compile(
            "^chapter\\s+[0-9]+\\s*[-–—:：].*"
    );

    private NonMusicContentFilter() {
    }

    /**
     * @return 命中原因；空表示按黑名单规则判断为音乐或信息不足
     */
    static Optional<String> match(String title, String artist, String album) {
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        String normalizedAlbum = normalize(album);

        if (isPunctuationOnly(normalizedTitle)) {
            return Optional.of("歌名仅含标点或符号");
        }

        for (String term : CONTENT_TERMS) {
            if (normalizedTitle.contains(term)
                    || normalizedArtist.contains(term)
                    || normalizedAlbum.contains(term)) {
                return Optional.of("非音乐内容关键词: " + term);
            }
        }

        if (EPISODE_MARKER.matcher(normalizedTitle).find()
                || EPISODE_MARKER.matcher(normalizedAlbum).find()) {
            return Optional.of("连载/有声集数标记");
        }
        if (CHAPTER_MARKER.matcher(normalizedTitle).matches()) {
            return Optional.of("有声书章节标题");
        }

        for (String term : CHANNEL_TERMS) {
            if (normalizedArtist.contains(term) || normalizedAlbum.contains(term)) {
                return Optional.of("有声内容渠道: " + term);
            }
        }
        return Optional.empty();
    }

    /**
     * 歌名是否只有标点、符号或不可见控制字符。字母、数字和组合附加符号视为有效文字，
     * 因此 {@code ... (Live)}、数字标题等仍可正常补全。
     */
    private static boolean isPunctuationOnly(String normalizedTitle) {
        if (normalizedTitle.isEmpty()) {
            return false;
        }
        boolean sawNonWhitespace = false;
        for (int offset = 0; offset < normalizedTitle.length(); ) {
            int codePoint = normalizedTitle.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.getType(codePoint) == Character.SPACE_SEPARATOR) {
                continue;
            }
            sawNonWhitespace = true;
            int type = Character.getType(codePoint);
            if (Character.isLetterOrDigit(codePoint)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                return false;
            }
        }
        return sawNonWhitespace;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = ChineseConverter.toSimplified(normalized);
        return normalized.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
