package com.neko.music.util;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量日语罗马音补全。
 *
 * <p>使用内嵌 Kuromoji IPADIC 做离线分词和汉字读音，再把读音转换成纯 ASCII
 * 罗马音。生成结果不会包含假名或汉字，词与词之间使用空格分隔。</p>
 */
public final class JapaneseRomanizer {

    private static final Tokenizer TOKENIZER = new Tokenizer();

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
     * 给日语歌词强制补齐罗马音。高质量的既有罗马音优先保留；混入日文字符、
     * 或明显没有分词空格的旧脏数据会用离线词典重新生成。
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
            if (needsRewrite(romanization, line.text())) {
                romanization = romanize(line.text());
            }
            if (romanization == null || romanization.isBlank()) {
                romanization = line.text().isBlank() || containsJapanese(line.text())
                        ? null
                        : line.text().trim();
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
     * 把日语正文转换成纯 ASCII 罗马音，按词插入空格。
     */
    public static String romanize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        List<Token> tokens;
        synchronized (TOKENIZER) {
            tokens = TOKENIZER.tokenize(normalized);
        }
        List<List<Token>> phrases = groupPhrases(tokens);
        StringBuilder out = new StringBuilder(normalized.length() * 3);
        boolean attachNext = false;
        for (List<Token> phrase : phrases) {
            Token first = phrase.getFirst();
            String surface = first.getSurface();
            if (surface == null || surface.isBlank()) {
                continue;
            }
            boolean punctuation = phrase.size() == 1 && isPunctuationToken(first, surface);
            String value = romanizePhrase(phrase);
            if (value.isBlank()) {
                continue;
            }
            if (punctuation) {
                boolean opening = isOpeningPunctuationToken(first);
                appendPunctuation(out, value, opening);
                attachNext = opening;
                continue;
            }
            appendWord(out, value, attachNext);
            attachNext = false;
        }
        return cleanRomanization(out.toString());
    }

    private static List<List<Token>> groupPhrases(List<Token> tokens) {
        List<List<Token>> phrases = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        Token previous = null;
        for (Token token : tokens) {
            String surface = token.getSurface();
            if (surface == null || surface.isBlank()) {
                flushPhrase(phrases, current);
                previous = null;
                continue;
            }
            if (isPunctuationToken(token, surface)) {
                flushPhrase(phrases, current);
                phrases.add(List.of(token));
                previous = null;
                continue;
            }
            if (isLatinOrDigitText(surface)) {
                flushPhrase(phrases, current);
                phrases.add(List.of(token));
                previous = token;
                continue;
            }
            if (tokenReading(token, false).isBlank()) {
                flushPhrase(phrases, current);
                previous = null;
                continue;
            }
            if (previous == null || !shouldAttach(token, previous)) {
                flushPhrase(phrases, current);
                current.add(token);
            } else {
                current.add(token);
            }
            previous = token;
        }
        flushPhrase(phrases, current);
        return phrases;
    }

    private static void flushPhrase(List<List<Token>> phrases, List<Token> current) {
        if (!current.isEmpty()) {
            phrases.add(List.copyOf(current));
            current.clear();
        }
    }

    private static boolean shouldAttach(Token current, Token previous) {
        String surface = current.getSurface();
        String previousSurface = previous.getSurface();
        String pos = current.getPartOfSpeechLevel1();
        if (isUnknownReading(current) && isAllKana(surface) && startsWithSmallKana(surface)) {
            return true;
        }
        if ("助動詞".equals(pos)) {
            return true;
        }
        if ("動詞".equals(pos) && "動詞".equals(previous.getPartOfSpeechLevel1())) {
            return true;
        }
        if ("動詞".equals(pos) && "く".equals(surface)
                && ("て".equals(previousSurface) || "で".equals(previousSurface))) {
            return true;
        }
        if ("助詞".equals(pos)
                && ("て".equals(surface) || "で".equals(surface)
                || "ば".equals(surface) || "ても".equals(surface)
                || "でも".equals(surface) || "たり".equals(surface)
                || "だり".equals(surface))
                && isInflectable(previous)) {
            return true;
        }
        if ("ん".equals(surface) && !"記号".equals(previous.getPartOfSpeechLevel1())) {
            return true;
        }
        return "じゃ".equals(surface) && ("ん".equals(previousSurface)
                || "助動詞".equals(previous.getPartOfSpeechLevel1()));
    }

    private static String romanizePhrase(List<Token> phrase) {
        if (phrase.size() == 1 && isLatinOrDigitText(phrase.getFirst().getSurface())) {
            return phrase.getFirst().getSurface();
        }
        StringBuilder reading = new StringBuilder();
        for (Token token : phrase) {
            reading.append(tokenReading(token, phrase.size() == 1));
        }
        if (reading.isEmpty()) {
            return "";
        }
        String converted = romanizeKana(reading.toString());
        return containsJapanese(converted) ? "" : converted;
    }

    private static String tokenReading(Token token, boolean standalone) {
        String surface = token.getSurface();
        if (standalone && "助詞".equals(token.getPartOfSpeechLevel1())) {
            if ("は".equals(surface)) {
                return "ワ";
            }
            if ("へ".equals(surface)) {
                return "エ";
            }
            if ("を".equals(surface)) {
                return "オ";
            }
        }
        String reading = token.getReading();
        if (reading != null && !reading.isBlank() && !"*".equals(reading)) {
            return reading;
        }
        if (containsKana(surface)) {
            return surface;
        }
        if (containsJapanese(surface)) {
            return "";
        }
        return surface;
    }

    private static boolean isUnknownReading(Token token) {
        String reading = token.getReading();
        return reading == null || reading.isBlank() || "*".equals(reading);
    }

    private static boolean isInflectable(Token token) {
        String pos = token.getPartOfSpeechLevel1();
        return "動詞".equals(pos) || "形容詞".equals(pos) || "助動詞".equals(pos);
    }

    private static boolean startsWithSmallKana(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return "ゃゅょぁぃぅぇぉっゎゕゖャュョァィゥェォッヮヵヶ".indexOf(first) >= 0;
    }

    private static boolean isAllKana(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (toHiragana(cp) < 0) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static String romanizeKana(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() * 2);
        boolean sokuon = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
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
            if (i + consumed < text.length()) {
                int nextCodePoint = text.codePointAt(i + consumed);
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
            i += unitChars == 2 ? consumed + Character.charCount(text.codePointAt(i + consumed)) : consumed;
        }
        if (sokuon) {
            out.append("tsu");
        }
        return out.toString().trim();
    }

    private static boolean needsRewrite(String romanization, String text) {
        if (romanization == null || romanization.isBlank()) {
            return true;
        }
        if (containsJapanese(romanization)) {
            return true;
        }
        return !hasWhitespace(romanization)
                && containsKana(text)
                && hasMultipleMeaningfulTokens(text);
    }

    private static boolean hasMultipleMeaningfulTokens(String text) {
        List<Token> tokens;
        synchronized (TOKENIZER) {
            tokens = TOKENIZER.tokenize(text);
        }
        int meaningful = 0;
        for (Token token : tokens) {
            String surface = token.getSurface();
            if (surface == null || surface.isBlank()
                    || "記号".equals(token.getPartOfSpeechLevel1())) {
                continue;
            }
            meaningful++;
            if (meaningful > 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsJapanese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            if (toHiragana(cp) >= 0 || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsKana(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            if (toHiragana(cp) >= 0) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isLatinOrDigitText(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int type = Character.getType(cp);
            if (!Character.isLetterOrDigit(cp)
                    && type != Character.CONNECTOR_PUNCTUATION
                    && cp != '\'' && cp != '-') {
                return false;
            }
            if (Character.isLetter(cp)
                    && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return !text.isBlank();
    }

    private static boolean isPunctuationToken(Token token, String surface) {
        if ("記号".equals(token.getPartOfSpeechLevel1())) {
            return true;
        }
        for (int i = 0; i < surface.length(); ) {
            int cp = surface.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static void appendWord(StringBuilder out, String value, boolean attachNext) {
        if (!attachNext && out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.append(' ');
        }
        out.append(value);
    }

    private static void appendPunctuation(StringBuilder out, String value, boolean opening) {
        while (out.length() > 0 && Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
        if (opening && out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.append(' ');
        }
        out.append(value);
    }

    private static boolean isOpeningPunctuationToken(Token token) {
        String surface = token.getSurface();
        return "「".equals(surface) || "『".equals(surface)
                || "（".equals(surface) || "(".equals(surface)
                || "［".equals(surface) || "[".equals(surface)
                || "｛".equals(surface) || "{".equals(surface);
    }

    private static String cleanRomanization(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            String mapped = mapPunctuation(cp);
            if (mapped != null) {
                if (mapped.equals(" ")) {
                    pendingSpace = out.length() > 0;
                } else {
                    if (pendingSpace && needsSpaceBeforePunctuation(out)) {
                        out.append(' ');
                    }
                    out.append(mapped);
                    pendingSpace = false;
                }
            } else if (Character.isWhitespace(cp)) {
                pendingSpace = out.length() > 0;
            } else if (isRomanizableChar(cp)) {
                if (pendingSpace && out.length() > 0
                        && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                    out.append(' ');
                }
                out.appendCodePoint(cp);
                pendingSpace = false;
            }
            i += Character.charCount(cp);
        }
        return out.toString().trim();
    }

    private static boolean needsSpaceBeforePunctuation(StringBuilder out) {
        if (out.isEmpty()) {
            return false;
        }
        char last = out.charAt(out.length() - 1);
        return last != '(' && last != '[' && last != '{' && last != '"';
    }

    private static boolean isRomanizableChar(int cp) {
        if (Character.isLetterOrDigit(cp)) {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.LATIN;
        }
        return Character.getType(cp) == Character.CONNECTOR_PUNCTUATION
                || cp == '\'' || cp == '-';
    }

    private static String mapPunctuation(int cp) {
        return switch (cp) {
            case '.' -> ".";
            case ',' -> ",";
            case '。', '．', '｡' -> ".";
            case '、', '，', '､' -> ",";
            case '！', '!' -> "!";
            case '？', '?' -> "?";
            case '「', '」', '『', '』', '“', '”', '"' -> "\"";
            case '（', '(' -> "(";
            case '）', ')' -> ")";
            case '［', '[' -> "[";
            case '］', ']' -> "]";
            case '｛', '{' -> "{";
            case '｝', '}' -> "}";
            case '…' -> "...";
            case '〜', '～', '~' -> "~";
            case '・', '　', ' ' -> " ";
            case '：', ':' -> ":";
            case '；', ';' -> ";";
            case '／', '/' -> "/";
            case '＼', '\\' -> "\\";
            default -> null;
        };
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
