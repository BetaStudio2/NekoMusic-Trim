package com.neko.music.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSearchHistoryServiceTest {
    @Test
    void normalizesWhitespaceAndControlCharacters() {
        assertEquals("周杰伦 晴天", UserSearchHistoryService.normalizeQuery("  周杰伦\t\u0001  晴天  "));
    }

    @Test
    void truncatesOversizedQueries() {
        String normalized = UserSearchHistoryService.normalizeQuery("a".repeat(400));
        assertEquals(255, normalized.length());
        assertTrue(normalized.chars().allMatch(ch -> ch == 'a'));
    }
}
