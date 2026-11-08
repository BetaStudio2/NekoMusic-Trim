package com.neko.music.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqLyricParserTest {

    @Test
    void convertsQrcWordsIntoNekoLyrics() {
        String qrc = "[1000,2000]你(1000,500)好(1500,500)\n";
        String trans = "[00:01.00]Hello\n";
        String roma = "[00:01.00]ni hao\n";

        NekoLyrics.Document document = QqLyricParser.parse(qrc, trans, roma);

        assertEquals(1, document.lines().size());
        NekoLyrics.Line line = document.lines().get(0);
        assertEquals("你好", line.text());
        assertEquals("Hello", line.translation());
        assertEquals("ni hao", line.romanization());
        assertEquals(2, line.words().size());
        assertEquals(1_000, line.words().get(0).startMs());
        assertEquals(1_500, line.words().get(1).startMs());
    }

    @Test
    void unwrapsQrcXml() {
        String qrc = "<Lyric_1 LyricContent=\"[1000,1000]字(1000,1000)\"/>";

        NekoLyrics.Document document = QqLyricParser.parse(qrc, "", "");

        assertEquals("字", document.lines().get(0).text());
    }

    @Test
    void nekoFormatRoundTripsAndProducesLrc() {
        NekoLyrics.Document document = NekoLyrics.document(java.util.List.of(
                new NekoLyrics.Line(1_000, 2_000, "Hello", "你好", "ni hao", false,
                        java.util.List.of(new NekoLyrics.Word(1_000, 1_500, "Hel"),
                                new NekoLyrics.Word(1_500, 2_000, "lo")))));

        String json = NekoLyrics.serialize(document);
        NekoLyrics.Document restored = NekoLyrics.deserialize(json).orElseThrow();

        assertEquals("Hello", restored.lines().get(0).text());
        assertEquals(2, restored.lines().get(0).words().size());
        assertEquals("[00:01.000]Hello\n{\"你好\"}", NekoLyrics.toLrc(restored));
        var apiJson = new ObjectMapper().valueToTree(restored);
        assertEquals(1, apiJson.path("version").asInt());
        assertEquals(2, apiJson.path("lines").get(0).path("words").size());
        assertTrue(apiJson.path("lines").get(0).path("hasWords").isMissingNode());
    }

    @Test
    void inflatesQrcZlibPayload() throws Exception {
        String expected = "[1000,1000]字(1000,1000)";
        byte[] compressed = deflate(expected.getBytes(StandardCharsets.UTF_8));

        assertEquals(expected, QqQrcCrypto.decodePayload(compressed));
        assertTrue(QqQrcCrypto.decrypt("").isEmpty());
    }

    @Test
    void decryptsReferenceQrcFixture() throws Exception {
        String encrypted = "c90db2e3f6940a43f7471bcb5fa5516ed668524b1f38b70e603c6220e5f945c9"
                + "7dc0a28ddbb2e4f5e194a1c8dded286c";

        assertEquals("<Lyric_1 LyricContent=\"[1000,1000]字(1000,1000)\"/>",
                QqQrcCrypto.decrypt(encrypted));
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] buffer = new byte[512];
        int length = deflater.deflate(buffer);
        deflater.end();
        return java.util.Arrays.copyOf(buffer, length);
    }
}
