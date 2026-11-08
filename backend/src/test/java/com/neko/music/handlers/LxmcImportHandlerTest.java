package com.neko.music.handlers;

import com.neko.music.service.LxmcPlaylistParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LxmcImportHandlerTest {

    @Test
    void allSelectsEveryPlaylistInFileOrder() {
        LxmcPlaylistParser.Document document = document();

        List<LxmcPlaylistParser.Playlist> selected =
                LxmcImportHandler.selectPlaylists(document, "all");

        assertEquals(List.of("first", "second"),
                selected.stream().map(LxmcPlaylistParser.Playlist::id).toList());
    }

    @Test
    void playlistIdSelectsOnlyTheMatchingPlaylist() {
        List<LxmcPlaylistParser.Playlist> selected =
                LxmcImportHandler.selectPlaylists(document(), "second");

        assertEquals(1, selected.size());
        assertEquals("second", selected.get(0).id());
    }

    @Test
    void omittedIdOnlyWorksForSinglePlaylistFiles() {
        assertTrue(LxmcImportHandler.selectPlaylists(document(), null).isEmpty());

        LxmcPlaylistParser.Playlist only = playlist("only", "Only");
        LxmcPlaylistParser.Document single = new LxmcPlaylistParser.Document(List.of(only), 1);
        assertEquals(List.of(only), LxmcImportHandler.selectPlaylists(single, null));
    }

    private static LxmcPlaylistParser.Document document() {
        LxmcPlaylistParser.Playlist first = playlist("first", "First");
        LxmcPlaylistParser.Playlist second = playlist("second", "Second");
        return new LxmcPlaylistParser.Document(List.of(first, second), 2);
    }

    private static LxmcPlaylistParser.Playlist playlist(String id, String name) {
        return new LxmcPlaylistParser.Playlist(
                id,
                name,
                List.of(new LxmcPlaylistParser.Track("wy", id + "-song", name + " Song", "Artist")),
                Map.of("wy", 1));
    }
}
