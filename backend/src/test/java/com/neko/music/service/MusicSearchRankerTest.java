package com.neko.music.service;

import com.neko.music.util.PinyinUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索排序规则：先匹配歌名、再匹配歌手，并保证「歌名命中」恒优于「仅歌手命中」。
 */
class MusicSearchRankerTest {

    @Test
    void splitsArtistAndTitleInEitherOrder() {
        assertEquals(List.of("周杰伦", "晴天"), MusicSearchRanker.splitTerms("周杰伦，晴天"));
        assertEquals(List.of("晴天", "周杰伦"), MusicSearchRanker.splitTerms("晴天 周杰伦"));
        assertEquals(List.of("周杰伦", "晴天"), MusicSearchRanker.splitTerms("周杰伦-晴天"));
        assertEquals(List.of("晴天", "周杰伦"), MusicSearchRanker.splitTerms("晴天/周杰伦"));
        assertEquals(List.of("晴天", "周杰伦"), MusicSearchRanker.splitTerms("  晴天 ｜ 周杰伦  "));
    }

    @Test
    void keepsSingleKeywordQueriesIntact() {
        assertEquals(List.of("晴天"), MusicSearchRanker.splitTerms("晴天"));
        assertEquals(List.of("晴天"), MusicSearchRanker.splitTerms("  晴天  "));
        assertEquals(List.of("晴天"), MusicSearchRanker.splitTerms("晴天，晴天"));
        assertEquals(List.of(), MusicSearchRanker.splitTerms("   "));
        assertEquals(List.of(), MusicSearchRanker.splitTerms(null));
    }

    @Test
    void keepsLongEnglishPhraseAsSingleTerm() {
        assertEquals(List.of("hello from the other side"),
                MusicSearchRanker.splitTerms("hello from the other side"));
    }

    @Test
    void capsTermCount() {
        List<String> terms = MusicSearchRanker.splitTerms("晴天 雨天 阴天 雪天 风天 雾天 云天");
        assertEquals(6, terms.size());
        assertEquals("晴天", terms.get(0));
    }

    @Test
    void dropsCoverQualifierFromSearchTerms() {
        assertEquals(List.of("粛聖", "ロリ神レクイエム☆"),
                MusicSearchRanker.splitTerms("粛聖!! ロリ神レクイエム☆ (Cover)"));
        assertEquals(List.of("晴天"), MusicSearchRanker.splitTerms("晴天 Cover"));
        assertEquals(List.of("Cover"), MusicSearchRanker.splitTerms("Cover"));
    }

    @Test
    void rewardsAdditionalMatchedTitleTerms() {
        List<String> terms = MusicSearchRanker.splitTerms("粛聖!! ロリ神レクイエム☆ (Cover)");
        int exact = MusicSearchRanker.score(
                fields("粛聖!! ロリ神レクイエム☆", "しぐれうい", "まだ雨はやまない"), terms);
        int missingStar = MusicSearchRanker.score(
                fields("粛聖!! ロリ神レクイエム", "しぐれうい", "未知专辑"), terms);
        int unrelatedCover = MusicSearchRanker.score(
                fields("真夏の果実 (Cover)", "Sotte Bosse", "Essence of life"), terms);

        assertTrue(exact > missingStar, "命中更多歌名词条的完整标题应排在前面");
        assertTrue(missingStar > 0);
        assertEquals(0, unrelatedCover, "只命中被剔除的 Cover 修饰词不能形成结果");
    }

    @Test
    void titleMatchAlwaysOutranksArtistOnlyMatch() {
        int titleHit = MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), List.of("晴天"));
        int artistHit = MusicSearchRanker.score(fields("稻香", "晴天", "魔杰座"), List.of("晴天"));

        assertTrue(artistHit > 0, "歌手命中应有分");
        assertTrue(titleHit > artistHit * 100, "歌名命中必须压倒性地排在仅歌手命中之前");
    }

    @Test
    void combinedQueryIsOrderIndependentAndBeatsTitleOnlyMatch() {
        List<String> comma = MusicSearchRanker.splitTerms("周杰伦，晴天");
        List<String> slash = MusicSearchRanker.splitTerms("晴天/周杰伦");
        List<String> dash = MusicSearchRanker.splitTerms("周杰伦-晴天");

        int both = MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), comma);
        assertEquals(both, MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), slash));
        assertEquals(both, MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), dash));

        int sameTitleOtherArtist = MusicSearchRanker.score(fields("晴天", "张杰", "叶惠美"), comma);
        assertTrue(sameTitleOtherArtist > 0);
        assertTrue(both > sameTitleOtherArtist, "歌名与歌手同时命中应排在只有歌名命中之前");
    }

    @Test
    void exactBeatsPrefixBeatsContains() {
        List<String> term = List.of("晴天");
        int exact = MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), term);
        int prefix = MusicSearchRanker.score(fields("晴天娃娃", "周杰伦", "叶惠美"), term);
        int contains = MusicSearchRanker.score(fields("我的晴天", "周杰伦", "叶惠美"), term);

        assertTrue(exact > prefix, "精确匹配应排在前缀匹配之前");
        assertTrue(prefix > contains, "前缀匹配应排在子串匹配之前");
    }

    @Test
    void pinyinQueryStillFindsChineseTitles() {
        int hit = MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), List.of("qingtian"));
        assertTrue(hit > 0, "拼音词条应能命中中文歌名");
    }

    @Test
    void wholeQueryStrengthOnlyCountsUnsplittedQuery() {
        assertTrue(MusicSearchRanker.wholeQueryStrength(fields("晴天", "周杰伦", "叶惠美"), "晴天") >= 80);
        assertEquals(0, MusicSearchRanker.wholeQueryStrength(fields("晴天", "周杰伦", "叶惠美"), "晴天 周杰伦"));
    }

    @Test
    void splitsConcatenatedTitleAndArtist() {
        assertEquals(List.of(List.of("晴天", "周杰伦"), List.of("晴天周", "杰伦")),
                MusicSearchRanker.splitConcatenations("晴天周杰伦"));
        assertTrue(MusicSearchRanker.splitConcatenations("周杰伦晴天").contains(List.of("周杰伦", "晴天")));
    }

    @Test
    void skipsConcatenationForQueriesThatAreNotConcatenations() {
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations("晴天"));
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations("晴天周"));
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations("晴天 周杰伦"));
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations("qingtianzhoujielun"));
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations("晴天周杰伦晴天周杰伦晴天"));
        assertEquals(List.of(), MusicSearchRanker.splitConcatenations(null));
    }

    @Test
    void concatenatedQueryScoresLikeSeparatedQuery() {
        List<List<String>> hypotheses = MusicSearchRanker.splitConcatenations("晴天周杰伦");
        int concatenated = MusicSearchRanker.concatenatedScore(fields("晴天", "周杰伦", "叶惠美"), hypotheses);
        int separated = MusicSearchRanker.score(fields("晴天", "周杰伦", "叶惠美"), List.of("晴天", "周杰伦"));

        assertTrue(concatenated > 0, "连写查询应能命中「歌名 + 歌手」");
        assertEquals(separated, concatenated);
    }

    @Test
    void concatenatedQueryAlsoWorksWithArtistFirst() {
        assertTrue(MusicSearchRanker.concatenatedScore(fields("晴天", "周杰伦", "叶惠美"),
                MusicSearchRanker.splitConcatenations("周杰伦晴天")) > 0);
    }

    @Test
    void concatenatedSplitRequiresBothRolesToMatch() {
        // 歌名片段（想见你只想见你）不能因为前半段刚好是歌名就被当成「歌名 + 歌手」
        assertEquals(0, MusicSearchRanker.concatenatedScore(fields("想见你", "王菲", "专辑"),
                MusicSearchRanker.splitConcatenations("想见你只想见你")));
        // 只有歌名命中、歌手不是库里任何一条的歌手时，同样不算命中
        assertEquals(0, MusicSearchRanker.concatenatedScore(fields("晴天娃娃", "张杰", "专辑"),
                MusicSearchRanker.splitConcatenations("晴天娃娃")));
    }

    private static MusicSearchRanker.MusicFields fields(String title, String artist, String album) {
        return new MusicSearchRanker.MusicFields(
                title, artist, album,
                PinyinUtil.getPinyin(title), PinyinUtil.getPinyinInitials(title), PinyinUtil.getPinyinInitials(title),
                PinyinUtil.getPinyin(artist), PinyinUtil.getPinyinInitials(artist), PinyinUtil.getPinyinInitials(artist),
                PinyinUtil.getPinyin(album));
    }
}
