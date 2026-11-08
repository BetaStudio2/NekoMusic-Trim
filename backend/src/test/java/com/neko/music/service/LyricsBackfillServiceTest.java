package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsBackfillServiceTest {

    private static final LyricsBackfillService.MusicLyricsRow ROW =
            new LyricsBackfillService.MusicLyricsRow(
                    100, "晴天", "周杰伦", "叶惠美", 269_000,
                    "lrc", "ingest", false);

    @Test
    void picksMatchingNeteaseCandidateByTitleArtistAndDuration() {
        List<NeteaseCloudMusicClient.NeteaseSongCandidate> candidates = List.of(
                new NeteaseCloudMusicClient.NeteaseSongCandidate(
                        1, "晴天", "张杰", "专辑", 269_000),
                new NeteaseCloudMusicClient.NeteaseSongCandidate(
                        2, "晴天", "周杰伦", "叶惠美", 420_000),
                new NeteaseCloudMusicClient.NeteaseSongCandidate(
                        3, "晴天", "周杰伦", "叶惠美", 270_000)
        );

        assertEquals(3, LyricsBackfillService.pickNetease(candidates, ROW).id());
    }

    @Test
    void rejectsWeakTitleOrMissingArtistMatches() {
        List<NeteaseCloudMusicClient.NeteaseSongCandidate> candidates = List.of(
                new NeteaseCloudMusicClient.NeteaseSongCandidate(
                        1, "我的晴天", "周杰伦", "叶惠美", 269_000),
                new NeteaseCloudMusicClient.NeteaseSongCandidate(
                        2, "晴天", "其他歌手", "叶惠美", 269_000)
        );

        assertNull(LyricsBackfillService.pickNetease(candidates, ROW));
    }

    @Test
    void picksMatchingQqCandidate() {
        List<QQMusicClient.QqSongCandidate> candidates = List.of(
                new QQMusicClient.QqSongCandidate(
                        10, "wrong-mid", "", "晴天", "其他歌手", "叶惠美", "", 269_000),
                new QQMusicClient.QqSongCandidate(
                        20, "right-mid", "", "晴天", "周杰伦", "叶惠美", "", 269_000)
        );

        assertEquals("right-mid", LyricsBackfillService.pickQq(candidates, ROW).mid());
    }
}
