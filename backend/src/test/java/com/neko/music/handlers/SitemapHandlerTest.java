package com.neko.music.handlers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SitemapHandlerTest {

    @Test
    void indexReferencesAllChildSitemaps() {
        String xml = SitemapHandler.buildIndexXml("https://music.example.com/");

        assertTrue(xml.contains("<sitemapindex"));
        assertTrue(xml.contains("<loc>https://music.example.com/sitemap-static.xml</loc>"));
        assertTrue(xml.contains("<loc>https://music.example.com/sitemap-music.xml</loc>"));
        assertTrue(xml.contains("<loc>https://music.example.com/sitemap-playlists.xml</loc>"));
        assertTrue(xml.contains("</sitemapindex>"));
    }

    @Test
    void staticSitemapDoesNotFakeDailyLastmod() {
        String xml = SitemapHandler.buildStaticXml("https://music.example.com/");

        assertTrue(xml.contains("<loc>https://music.example.com/</loc>"));
        assertTrue(xml.contains("<loc>https://music.example.com/lxmusic</loc>"));
        assertTrue(xml.contains("<loc>https://music.example.com/ranking</loc>"));
        assertFalse(xml.contains("<lastmod>"), "固定页没有可靠更新时间时不应写入 lastmod");
    }

    @Test
    void musicSitemapContainsImageExtensionAndEscapesMetadata() {
        SitemapHandler.MusicEntry entry = new SitemapHandler.MusicEntry();
        entry.id = 42;
        entry.title = "夜曲 <Live>";
        entry.artist = "周杰伦 & Friends";
        entry.lastmod = "2026-10-27";

        String xml = SitemapHandler.buildMusicXml("https://music.example.com/", List.of(entry));

        assertTrue(xml.contains("xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\""));
        assertTrue(xml.contains("<loc>https://music.example.com/detail/42</loc>"));
        assertTrue(xml.contains("<lastmod>2026-10-27</lastmod>"));
        assertTrue(xml.contains("<image:loc>https://music.example.com/media/cover/42</image:loc>"));
        assertTrue(xml.contains("<image:title>夜曲 &lt;Live&gt; - 周杰伦 &amp; Friends</image:title>"));
    }

    @Test
    void playlistSitemapUsesPlaylistLastmodOnlyWhenAvailable() {
        SitemapHandler.MusicEntry entry = new SitemapHandler.MusicEntry();
        entry.id = 7;
        entry.title = "深夜歌单";

        String xml = SitemapHandler.buildPlaylistXml("https://music.example.com/", List.of(entry));

        assertTrue(xml.contains("<loc>https://music.example.com/playlist/7</loc>"));
        assertFalse(xml.contains("<lastmod>"));
    }
}
