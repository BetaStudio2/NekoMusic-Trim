package com.neko.music.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量日语罗马音补全。
 *
 * <p>只做逐字假名转换，不加载分词/读音词典：平假名、片假名及常见拗音会转成
 * 罗马音；汉字、英文和标点保持原样，避免为了补全歌词引入大词典或额外计算。</p>
 */
public final class JapaneseRomanizer {

    private static final Map<String, String> PAIRS = Map.ofEntries(
            Map.entry("きゃ", "kya"), Map.entry("きゅ", "kyu"), Map.entry("きょ", "kyo"),
            Map.entry("きぇ", "kye"),
            Map.entry("ぎゃ", "gya"), Map.entry("ぎゅ", "gyu"), Map.entry("ぎょ", "gyo"),
            Map.entry("ぎぇ", "gye"),
            Map.entry("しゃ", "sha"), Map.entry("しゅ", "shu"), Map.entry("しょ", "sho"),
            Map.entry("しぇ", "she"),
            Map.entry("じゃ", "ja"), Map.entry("じゅ", "ju"), Map.entry("じょ", "jo"),
            Map.entry("じぇ", "je"),
            Map.entry("ちゃ", "cha"), Map.entry("ちゅ", "chu"), Map.entry("ちょ", "cho"),
            Map.entry("ちぇ", "che"),
            Map.entry("ぢゃ", "ja"), Map.entry("ぢゅ", "ju"), Map.entry("ぢょ", "jo"),
            Map.entry("ぢぇ", "je"),
            Map.entry("にゃ", "nya"), Map.entry("にゅ", "nyu"), Map.entry("にょ", "nyo"),
            Map.entry("にぇ", "nye"),
            Map.entry("ひゃ", "hya"), Map.entry("ひゅ", "hyu"), Map.entry("ひょ", "hyo"),
            Map.entry("ひぇ", "hye"),
            Map.entry("びゃ", "bya"), Map.entry("びゅ", "byu"), Map.entry("びょ", "byo"),
            Map.entry("びぇ", "bye"),
            Map.entry("ぴゃ", "pya"), Map.entry("ぴゅ", "pyu"), Map.entry("ぴょ", "pyo"),
            Map.entry("ぴぇ", "pye"),
            Map.entry("みゃ", "mya"), Map.entry("みゅ", "myu"), Map.entry("みょ", "myo"),
            Map.entry("みぇ", "mye"),
            Map.entry("りゃ", "rya"), Map.entry("りゅ", "ryu"), Map.entry("りょ", "ryo"),
            Map.entry("りぇ", "rye"),
            Map.entry("ふぁ", "fa"), Map.entry("ふぃ", "fi"), Map.entry("ふぇ", "fe"),
            Map.entry("ふぉ", "fo"), Map.entry("ふゅ", "fyu"),
            Map.entry("ゔぁ", "va"), Map.entry("ゔぃ", "vi"), Map.entry("ゔぇ", "ve"),
            Map.entry("ゔぉ", "vo"), Map.entry("ゔゅ", "vyu"),
            Map.entry("うぁ", "wa"), Map.entry("うぃ", "wi"), Map.entry("うぇ", "we"),
            Map.entry("うぉ", "wo"),
            Map.entry("てぃ", "ti"), Map.entry("てゅ", "tyu"),
            Map.entry("でぃ", "di"), Map.entry("でゅ", "dyu"),
            Map.entry("とぅ", "tu"), Map.entry("どぅ", "du"),
            Map.entry("つぁ", "tsa"), Map.entry("つぃ", "tsi"), Map.entry("つぇ", "tse"),
            Map.entry("つぉ", "tso"),
            Map.entry("くぁ", "kwa"), Map.entry("くぃ", "kwi"), Map.entry("くぇ", "kwe"),
            Map.entry("くぉ", "kwo"),
            Map.entry("ぐぁ", "gwa"), Map.entry("ぐぃ", "gwi"), Map.entry("ぐぇ", "gwe"),
            Map.entry("ぐぉ", "gwo")
    );

    private static final Map<Character, String> SINGLE = Map.ofEntries(
            Map.entry('あ', "a"), Map.entry('い', "i"), Map.entry('う', "u"),
            Map.entry('え', "e"), Map.entry('お', "o"),
            Map.entry('か', "ka"), Map.entry('き', "ki"), Map.entry('く', "ku"),
            Map.entry('け', "ke"), Map.entry('こ', "ko"),
            Map.entry('さ', "sa"), Map.entry('し', "shi"), Map.entry('す', "su"),
            Map.entry('せ', "se"), Map.entry('そ', "so"),
            Map.entry('た', "ta"), Map.entry('ち', "chi"), Map.entry('つ', "tsu"),
            Map.entry('て', "te"), Map.entry('と', "to"),
            Map.entry('な', "na"), Map.entry('に', "ni"), Map.entry('ぬ', "nu"),
            Map.entry('ね', "ne"), Map.entry('の', "no"),
            Map.entry('は', "ha"), Map.entry('ひ', "hi"), Map.entry('ふ', "fu"),
            Map.entry('へ', "he"), Map.entry('ほ', "ho"),
            Map.entry('ま', "ma"), Map.entry('み', "mi"), Map.entry('む', "mu"),
            Map.entry('め', "me"), Map.entry('も', "mo"),
            Map.entry('や', "ya"), Map.entry('ゆ', "yu"), Map.entry('よ', "yo"),
            Map.entry('ら', "ra"), Map.entry('り', "ri"), Map.entry('る', "ru"),
            Map.entry('れ', "re"), Map.entry('ろ', "ro"),
            Map.entry('わ', "wa"), Map.entry('ゐ', "i"), Map.entry('ゑ', "e"),
            Map.entry('を', "wo"), Map.entry('ん', "n"),
            Map.entry('が', "ga"), Map.entry('ぎ', "gi"), Map.entry('ぐ', "gu"),
            Map.entry('げ', "ge"), Map.entry('ご', "go"),
            Map.entry('ざ', "za"), Map.entry('じ', "ji"), Map.entry('ず', "zu"),
            Map.entry('ぜ', "ze"), Map.entry('ぞ', "zo"),
            Map.entry('だ', "da"), Map.entry('ぢ', "ji"), Map.entry('づ', "zu"),
            Map.entry('で', "de"), Map.entry('ど', "do"),
            Map.entry('ば', "ba"), Map.entry('び', "bi"), Map.entry('ぶ', "bu"),
            Map.entry('べ', "be"), Map.entry('ぼ', "bo"),
            Map.entry('ぱ', "pa"), Map.entry('ぴ', "pi"), Map.entry('ぷ', "pu"),
            Map.entry('ぺ', "pe"), Map.entry('ぽ', "po"),
            Map.entry('ゔ', "vu"),
            Map.entry('ぁ', "a"), Map.entry('ぃ', "i"), Map.entry('ぅ', "u"),
            Map.entry('ぇ', "e"), Map.entry('ぉ', "o"),
            Map.entry('ゃ', "ya"), Map.entry('ゅ', "yu"), Map.entry('ょ', "yo"),
            Map.entry('ゎ', "wa"), Map.entry('ゕ', "ka"), Map.entry('ゖ', "ke")
    );

    private JapaneseRomanizer() {
    }

    /**
     * 语种值兼容现有中文枚举以及客户端可能传入的 ISO 写法。
     */
    public static boolean isJapaneseLanguage(String language) {
        if (language == null || language.isBlank()) {
            return false;
        }
        String value = language.trim().toLowerCase(Locale.ROOT);
        return value.equals("日语")
                || value.equals("日語")
                || value.equals("日本語")
                || value.equals("japanese")
                || value.equals("ja")
                || value.startsWith("ja-")
                || value.startsWith("ja_");
    }

    /**
     * 给日语歌词强制补齐罗马音。已有罗马音优先保留；缺失时逐行生成，
     * 非假名内容保持原样，保证每条有效歌词行都有可消费的 {@code romanization}。
     */
    public static NekoLyrics.Document enforceRomanization(NekoLyrics.Document document) {
        if (document == null || document.isEmpty()) {
            return document;
        }
        // 占位歌词不是实际歌词，不伪造读音。
        if (LyricsPlainTextExtractor.isPlaceholder(NekoLyrics.plainText(document))) {
            return document;
        }
        List<NekoLyrics.Line> lines = new ArrayList<>(document.lines().size());
        for (NekoLyrics.Line line : document.lines()) {
            String romanization = line.romanization();
            if (romanization == null || romanization.isBlank()) {
                romanization = romanize(line.text());
            }
            if (romanization == null || romanization.isBlank()) {
                romanization = line.text().isBlank() ? null : line.text().trim();
            }
            lines.add(new NekoLyrics.Line(
                    line.startMs(),
                    line.endMs(),
                    line.text(),
                    line.translation(),
                    romanization,
                    line.background(),
                    line.words()
            ));
        }
        return NekoLyrics.document(lines);
    }

    /**
     * 把假名转成 ASCII 罗马音。汉字等无法在不引入词典的情况下准确读音的内容保持原样。
     */
    public static String romanize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalized.length() * 2);
        boolean sokuon = false;
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            int hiragana = toHiragana(codePoint);
            int consumed = Character.charCount(codePoint);
            if (hiragana < 0) {
                if (sokuon) {
                    out.append("tsu");
                    sokuon = false;
                }
                out.appendCodePoint(codePoint);
                i += consumed;
                continue;
            }

            char current = (char) hiragana;
            if (current == 'っ') {
                sokuon = true;
                i += consumed;
                continue;
            }
            if (current == 'ー') {
                appendLongVowel(out);
                i += consumed;
                continue;
            }

            String romaji = null;
            int unitChars = 1;
            if (i + consumed < normalized.length()) {
                int nextCodePoint = normalized.codePointAt(i + consumed);
                int nextHiragana = toHiragana(nextCodePoint);
                if (nextHiragana >= 0) {
                    romaji = PAIRS.get(new String(new char[]{current, (char) nextHiragana}));
                    if (romaji != null) {
                        unitChars = 2;
                    }
                }
            }
            if (romaji == null) {
                romaji = SINGLE.get(current);
            }
            if (romaji == null) {
                out.append(current);
                if (sokuon) {
                    sokuon = false;
                }
                i += consumed;
                continue;
            }
            if (sokuon) {
                romaji = geminate(romaji);
                sokuon = false;
            }
            out.append(romaji);
            i += unitChars == 2 ? consumed + Character.charCount(normalized.codePointAt(i + consumed)) : consumed;
        }
        if (sokuon) {
            out.append("tsu");
        }
        return out.toString().trim();
    }

    private static int toHiragana(int codePoint) {
        if (codePoint >= 0x30A1 && codePoint <= 0x30F6) {
            return codePoint - 0x60;
        }
        if (codePoint == 0x30FD || codePoint == 0x30FE) {
            return codePoint - 0x60;
        }
        if (codePoint == 0x309D || codePoint == 0x309E || codePoint == 0x3099
                || codePoint == 0x309A || codePoint == 0x309B || codePoint == 0x309C
                || codePoint == 0x30FC) {
            return codePoint;
        }
        if (codePoint >= 0x3041 && codePoint <= 0x3096 || codePoint == 0x309D
                || codePoint == 0x309E) {
            return codePoint;
        }
        return -1;
    }

    private static void appendLongVowel(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = Character.toLowerCase(out.charAt(i));
            if (c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o') {
                out.append(c);
                return;
            }
            if (c != ' ') {
                break;
            }
        }
        out.append('ー');
    }

    private static String geminate(String romaji) {
        if (romaji == null || romaji.isEmpty()) {
            return "";
        }
        char first = Character.toLowerCase(romaji.charAt(0));
        if (first == 'a' || first == 'i' || first == 'u' || first == 'e' || first == 'o' || first == 'n') {
            return romaji;
        }
        if (romaji.startsWith("ch") || first == 'c') {
            return "t" + romaji;
        }
        return first + romaji;
    }
}
