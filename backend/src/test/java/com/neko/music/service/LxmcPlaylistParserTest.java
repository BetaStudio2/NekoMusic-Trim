package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LxmcPlaylistParserTest {
    private final LxmcPlaylistParser parser = new LxmcPlaylistParser();

    @Test
    void parsesGzippedLxmcPlaylistsAndSources() throws Exception {
        String json = """
                {
                  "type": "playList_v2",
                  "data": [
                    {
                      "id": "default",
                      "name": "试听列表",
                      "list": [
                        {
                          "id": "wy_123",
                          "name": "第一首",
                          "singer": "歌手甲",
                          "source": "wy",
                          "meta": { "songId": 123 }
                        },
                        {
                          "id": "kg_ABC",
                          "name": "第二首",
                          "singer": "歌手乙 / 歌手丙",
                          "source": "kg",
                          "meta": { "hash": "ABC" }
                        }
                      ]
                    },
                    {
                      "id": "favorite",
                      "name": "我的收藏",
                      "list": [
                        {
                          "id": "tx_001",
                          "title": "第三首",
                          "artists": [{ "name": "歌手丁" }, { "name": "歌手戊" }],
                          "source": "tx",
                          "meta": { "songId": "001" }
                        }
                      ]
                    }
                  ]
                }
                """;

        LxmcPlaylistParser.Document document = parser.parse(
                new ByteArrayInputStream(gzip(json)));

        assertEquals(2, document.playlists().size());
        assertEquals(3, document.totalTrackCount());
        assertEquals("试听列表", document.playlists().get(0).name());
        assertEquals(2, document.playlists().get(0).tracks().size());
        assertEquals(1, document.playlists().get(0).sourceCounts().get("wy"));
        assertEquals(1, document.playlists().get(0).sourceCounts().get("kg"));

        LxmcPlaylistParser.Track first = document.playlists().get(0).tracks().get(0);
        assertEquals("wy", first.source());
        assertEquals("123", first.sourceId());
        assertEquals("第一首", first.title());
        assertEquals("歌手甲", first.artist());

        LxmcPlaylistParser.Track third = document.playlists().get(1).tracks().get(0);
        assertEquals("tx", third.source());
        assertEquals("001", third.sourceId());
        assertEquals("歌手丁 / 歌手戊", third.artist());
    }

    @Test
    void acceptsPlainJsonAsWellAsGzip() throws Exception {
        String json = """
                {"type":"playList_v2","data":[{"id":"one","name":"单曲单","list":[]}]}
                """;

        LxmcPlaylistParser.Document document = parser.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, document.playlists().size());
        assertEquals(0, document.totalTrackCount());
    }

    @Test
    void rejectsMalformedGzipAndMissingDataArray() {
        assertThrows(LxmcPlaylistParser.LxmcFormatException.class,
                () -> parser.parse(new ByteArrayInputStream(new byte[]{0x1f, (byte) 0x8b, 0, 1, 2})));
        assertThrows(LxmcPlaylistParser.LxmcFormatException.class,
                () -> parser.parse(new ByteArrayInputStream("{\"type\":\"playList_v2\"}"
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsDecompressedPayloadOverLimit() throws Exception {
        byte[] oversized = gzip(" ".repeat(LxmcPlaylistParser.MAX_DECOMPRESSED_BYTES + 1));
        assertThrows(LxmcPlaylistParser.LxmcTooLargeException.class,
                () -> parser.parse(new ByteArrayInputStream(oversized)));
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
}
