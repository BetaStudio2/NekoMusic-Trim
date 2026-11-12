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

    @Test
    void toolsLandingPageProvidesCrawlableAppHowToAndFaqContent() {
        assertTrue(StaticPageSeoRenderer.isStaticPage("/tools-lyr"));

        String html = StaticPageSeoRenderer.create()
                .render("/tools-lyr", "https://music.example.com/");

        assertTrue(html.contains("<title>逐字歌词制作工具 - LRC/NEKO 在线打轴与下载"));
        assertTrue(html.contains("name=\"keywords\" content=\"逐字歌词"));
        assertTrue(html.contains("音频与歌词不上传服务器"));
        assertTrue(html.contains("https://music.example.com/tools-lyr"));
        assertTrue(html.contains("\"@type\":\"WebApplication\""));
        assertTrue(html.contains("\"@type\":\"HowTo\""));
        assertTrue(html.contains("\"@type\":\"FAQPage\""));
        assertTrue(html.contains("逐字歌词工具 Lyrics Tool"));
    }
}
