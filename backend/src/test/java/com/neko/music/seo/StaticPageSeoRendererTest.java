package com.neko.music.seo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticPageSeoRendererTest {

    @Test
    void lxmusicLandingPageTargetsLxMusicAndLxmcKeywords() {
        assertTrue(StaticPageSeoRenderer.isStaticPage("/lxmusic"));

        String html = StaticPageSeoRenderer.create()
                .render("/lxmusic", "https://music.example.com/");

        assertTrue(html.contains("<title>落雪音乐 LX Music 歌单导入 - LXMC 文件迁移"));
        assertTrue(html.contains("name=\"keywords\" content=\"落雪音乐"));
        assertTrue(html.contains("LXMC / .lxmc"));
        assertTrue(html.contains("https://music.example.com/lxmusic"));
        assertTrue(html.contains("\"@type\":\"HowTo\""));
        assertTrue(html.contains("\"@type\":\"FAQPage\""));
        assertTrue(html.contains("/playlists?import=lxmc"));
    }
}
