package com.neko.music.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonMusicContentFilterTest {

    @Test
    void blocksKnownAudiobookAndSpokenWordMetadata() {
        assertTrue(NonMusicContentFilter.match("《燕辞归》第 290 集", "", "").isPresent());
        assertTrue(NonMusicContentFilter.match("听电影学英语", "帕丁顿熊", "").isPresent());
        assertTrue(NonMusicContentFilter.match("Chapter 19 - a New World, a New Day", "", "").isPresent());
        assertTrue(NonMusicContentFilter.match("第3集《画》", "", "看动画学古诗").isPresent());
        assertTrue(NonMusicContentFilter.match("有聲小說：山海經", "", "").isPresent());
        assertTrue(NonMusicContentFilter.match("睡前故事", "主播", "喜马拉雅").isPresent());
    }

    @Test
    void blocksEpisodesAndChannelsEvenWhenTitleLooksLikeMusic() {
        assertTrue(NonMusicContentFilter.match("第三回", "蒋月泉", "评弹").isPresent());
        assertTrue(NonMusicContentFilter.match("第 12 期", "主播", "").isPresent());
        assertTrue(NonMusicContentFilter.match("晴天", "主播", "懒人听书").isPresent());
        assertTrue(NonMusicContentFilter.match("英语听力训练", "", "").isPresent());
    }

    @Test
    void keepsNormalMusicAndThemeSongs() {
        assertFalse(NonMusicContentFilter.match("晴天", "周杰伦", "叶惠美").isPresent());
        assertFalse(NonMusicContentFilter.match("小说", "许嵩", "不如吃茶去").isPresent());
        assertFalse(NonMusicContentFilter.match("广播剧《某某》主题曲", "歌手", "广播剧原声带").isPresent());
        assertFalse(NonMusicContentFilter.match("电台情歌", "莫文蔚", "做自己").isPresent());
        assertFalse(NonMusicContentFilter.match("Chapter One", "歌手", "专辑").isPresent());
        assertFalse(NonMusicContentFilter.match("故事", "许巍", "时光·漫步").isPresent());
    }

    @Test
    void blocksTitlesMadeOnlyOfPunctuationOrSymbols() {
        assertTrue(NonMusicContentFilter.match("...", "周杰伦", "").isPresent());
        assertTrue(NonMusicContentFilter.match("——？！", "", "").isPresent());
        assertTrue(NonMusicContentFilter.match("【 　】", "歌手", "").isPresent());
        assertTrue(NonMusicContentFilter.match("♪ ♡", "", "").isPresent());
        assertTrue(NonMusicContentFilter.match("\u200B\uFEFF", "", "").isPresent());
    }

    @Test
    void keepsTitlesContainingLettersOrDigits() {
        assertFalse(NonMusicContentFilter.match("... (Live)", "", "").isPresent());
        assertFalse(NonMusicContentFilter.match("!!! 7 !!!", "", "").isPresent());
        assertFalse(NonMusicContentFilter.match("∞ (Live)", "", "").isPresent());
    }

    @Test
    void punctuationOnlyTitleWinsOverMeaningfulArtist() {
        assertTrue(NonMusicContentFilter.match("？？", "A-Lin", "").isPresent());
        assertTrue(NonMusicContentFilter.match("∞", "歌手", "").isPresent());
    }
}
