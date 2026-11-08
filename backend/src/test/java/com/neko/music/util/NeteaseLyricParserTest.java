package com.neko.music.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseLyricParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void convertsNewYrcAndJsonMetadataToLineLrc() {
        String yrc = """
                {"t":-1000,"c":[{"tx":"作词: "},{"tx":"周仁"}]}
                [1000,2000](1000,500,0)你(1500,500,0)好
                [3000,1000](3000,1000,0)世界
                """;

        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root(yrc, "", "", ""));

        assertEquals("""
                [00:00.000]作词: 周仁
                [00:01.000]你好
                [00:03.000]世界""", parsed.lrc());
        assertEquals(yrc, parsed.rawMain());
        assertEquals(3, parsed.rich().lines().size());
        assertEquals("你", parsed.rich().lines().get(1).words().get(0).text());
        assertEquals(1_000, parsed.rich().lines().get(1).words().get(0).startMs());
    }

    @Test
    void fallsBackToPlainLrcAndNormalizesNeteaseTimestamps() {
        String lrc = """
                [00:01:250]旧版冒号毫秒
                [00:03]缺少毫秒
                [00:05.50]普通 LRC
                """;

        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root("", lrc, "", ""));

        assertEquals("""
                [00:01.250]旧版冒号毫秒
                [00:03.000]缺少毫秒
                [00:05.500]普通 LRC""", parsed.lrc());
        assertEquals(lrc, parsed.rawMain());
    }

    @Test
    void stripsOldYrcWordTags() {
        String yrc = "[00:01.000]<0,500>你<500,500>好\n";

        assertEquals("[00:01.000]你好", NeteaseLyricParser.convertToLrc(yrc));
    }

    @Test
    void mergesPairedYrcTranslationAfterMainLine() {
        String yrc = "[1000,2000](1000,1000,0)Hello\n";
        String ytlrc = "[1000,2000](1000,1000,0)你好\n";

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root(yrc, "", ytlrc, ""));

        assertEquals("""
                [00:01.000]Hello
                {"你好"}""", parsed.lrc());
        assertEquals("你好", parsed.rich().lines().get(0).translation());
    }

    @Test
    void fallsBackToPlainTranslationAndUsesNearestLine() {
        String lrc = """
                [00:01.000]Hello
                [00:05.000]World
                """;
        String tlyric = """
                [00:01.500]你好
                [00:05.000]世界
                """;

        NeteaseLyricParser.ParsedLyrics parsed =
                NeteaseLyricParser.parse(root("", lrc, "", tlyric));

        assertEquals("""
                [00:01.000]Hello
                {"你好"}
                [00:05.000]World
                {"世界"}""", parsed.lrc());
    }

    @Test
    void emptyResponseProducesNoLyrics() {
        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root("", "", "", ""));

        assertTrue(parsed.lrc().isEmpty());
        assertTrue(parsed.rawMain().isEmpty());
    }

    private static ObjectNode root(String yrc, String lrc, String ytlrc, String tlyric) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.putObject("yrc").put("lyric", yrc);
        root.putObject("lrc").put("lyric", lrc);
        root.putObject("ytlrc").put("lyric", ytlrc);
        root.putObject("tlyric").put("lyric", tlyric);
        return root;
    }
}
