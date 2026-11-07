package com.neko.music.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NicknameResolverTest {

    @Test
    void resolveByAuthIdsSplitsRequestsAtTheBatchLimit() {
        RecordingAuthClient auth = new RecordingAuthClient();
        NicknameResolver resolver = new NicknameResolver(null, auth);
        List<Long> ids = LongStream.rangeClosed(1, 401).boxed().toList();

        Map<Long, String> nicknames = resolver.resolveByAuthIds(ids);

        assertEquals(List.of(200, 200, 1), auth.batchSizes);
        assertEquals(401, nicknames.size());
        assertEquals("昵称401", nicknames.get(401L));
    }

    @Test
    void resolveByAuthIdFallsBackToEmailPrefixWhenAuthIsUnavailable() {
        NicknameResolver resolver = new NicknameResolver(null, null);

        assertEquals("cat", resolver.resolveByAuthId(9L, "cat@example.com"));
        assertEquals("听众", resolver.resolveByAuthId(null, null));
    }

    @Test
    void resolveByAuthIdsDegradesToEmptyMapWhenAuthFails() {
        NicknameResolver resolver = new NicknameResolver(null, new FailingAuthClient());

        assertTrue(resolver.resolveByAuthIds(List.of(1L, 2L)).isEmpty());
    }

    private static class RecordingAuthClient extends NekoAuthAccountService {
        private final List<Integer> batchSizes = new ArrayList<>();

        RecordingAuthClient() {
            super(null, null);
        }

        @Override
        public Map<Long, String> lookupNicknames(Collection<Long> authUserIds) {
            List<Long> ids = List.copyOf(authUserIds);
            batchSizes.add(ids.size());
            Map<Long, String> result = new LinkedHashMap<>();
            for (Long id : ids) {
                result.put(id, "昵称" + id);
            }
            return result;
        }
    }

    private static final class FailingAuthClient extends NekoAuthAccountService {
        FailingAuthClient() {
            super(null, null);
        }

        @Override
        public Map<Long, String> lookupNicknames(Collection<Long> authUserIds) {
            throw new NekoAuthServiceException(503, "NEKO_AUTH_UNAVAILABLE", "NekoAuth 暂不可用", 0);
        }
    }
}
