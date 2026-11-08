package com.neko.music.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteaseSearchFillServiceTest {

    @Test
    void punctuationOnlyTitleDoesNotSearchExternalSources() {
        FillEnabledConfig config = new FillEnabledConfig();
        CountingNeteaseClient netease = new CountingNeteaseClient(config);
        CountingQqClient qq = new CountingQqClient(config);
        NeteaseSearchFillService service = new NeteaseSearchFillService(
                config, netease, qq, new AdminMusicIngestService(), null);
        try {
            NeteaseSearchFillService.FillAttempt attempt =
                    service.tryFillFromNetease("——？！", "周杰伦");

            assertTrue(attempt.music().isEmpty());
            assertEquals(NeteaseSearchFillService.FillReason.NON_MUSIC, attempt.reason());
            assertEquals(0, netease.searchCalls.get());
            assertEquals(0, qq.searchCalls.get());
        } finally {
            service.shutdown();
        }
    }

    private static final class FillEnabledConfig extends ConfigManager {
        @Override
        public boolean isNeteaseSearchFillEnabled() {
            return true;
        }

        @Override
        public boolean isQqSearchFillEnabled() {
            return true;
        }

        @Override
        public int getNeteaseMaxParallelFills() {
            return 1;
        }
    }

    private static final class CountingNeteaseClient extends NeteaseCloudMusicClient {
        private final AtomicInteger searchCalls = new AtomicInteger();

        private CountingNeteaseClient(ConfigManager config) {
            super(config, new ObjectMapper());
        }

        @Override
        public List<NeteaseSongCandidate> searchSongs(String keywords, int limit) throws IOException {
            searchCalls.incrementAndGet();
            return List.of();
        }
    }

    private static final class CountingQqClient extends QQMusicClient {
        private final AtomicInteger searchCalls = new AtomicInteger();

        private CountingQqClient(ConfigManager config) {
            super(new ObjectMapper(), config);
        }

        @Override
        public List<QqSongCandidate> searchSongs(String keywords, int limit) throws IOException {
            searchCalls.incrementAndGet();
            return List.of();
        }
    }
}
