package com.neko.music.service;

import com.neko.music.service.PlaylistRecommendationRanker.PlaylistFeature;
import com.neko.music.service.PlaylistRecommendationRanker.Recommendation;
import com.neko.music.service.PlaylistRecommendationRanker.SearchSignal;
import com.neko.music.service.PlaylistRecommendationRanker.UserTaste;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistRecommendationRankerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Test
    void returnsFiveAndExcludesOwnedOrFavoritedPlaylists() {
        List<PlaylistFeature> candidates = candidates();
        UserTaste taste = new UserTaste(
                Map.of("artist-0", 1.0),
                Map.of("tag-0", 1.0),
                Map.of("zh", 1.0),
                List.of(),
                Set.of(101),
                Set.of(1),
                Set.of(2),
                Map.of(10, 2.0),
                8);

        List<Recommendation> result = PlaylistRecommendationRanker.rank(
                candidates, taste, Set.of(1, 2), false, TODAY, new Random(1));

        assertEquals(5, result.size());
        assertTrue(result.stream().noneMatch(r -> r.playlistId() == 1 || r.playlistId() == 2));
        assertEquals("personalized", result.get(0).source());
    }

    @Test
    void refreshReplacesExplorationSlots() {
        List<PlaylistFeature> candidates = candidates();
        UserTaste taste = new UserTaste(
                Map.of("artist-0", 1.0),
                Map.of("tag-0", 1.0),
                Map.of("zh", 1.0),
                List.of(),
                Set.of(101),
                Set.of(),
                Set.of(),
                Map.of(),
                8);

        List<Recommendation> stable = PlaylistRecommendationRanker.rank(
                candidates, taste, Set.of(), false, TODAY, new Random(1));
        List<Recommendation> refreshed = PlaylistRecommendationRanker.rank(
                candidates, taste, Set.of(), true, TODAY, new Random(1));

        assertEquals(5, refreshed.size());
        assertTrue(refreshed.get(3).exploration());
        assertTrue(refreshed.get(4).exploration());
        Set<Integer> stableIds = stable.stream().map(Recommendation::playlistId).collect(Collectors.toSet());
        Set<Integer> refreshedIds = refreshed.stream().map(Recommendation::playlistId).collect(Collectors.toSet());
        assertNotEquals(stableIds, refreshedIds);
    }

    @Test
    void guestFallsBackToPopularSource() {
        List<Recommendation> result = PlaylistRecommendationRanker.rank(
                candidates(), UserTaste.empty(), Set.of(), false, TODAY, new Random(1));

        assertEquals(5, result.size());
        assertEquals("popular", result.get(0).source());
        assertTrue(result.stream().noneMatch(Recommendation::exploration));
    }

    @Test
    void recentSearchCanPromoteMatchingPlaylist() {
        PlaylistFeature matching = feature(30, 30, "华语青春", "周杰伦 晴天 经典歌曲", 0);
        PlaylistFeature unrelated = feature(31, 31, "深夜电子", "ambient electronic", 0);
        UserTaste taste = new UserTaste(
                Map.of(), Map.of(), Map.of(),
                List.of(new SearchSignal("周杰伦 晴天", 4, TODAY.toEpochDay())),
                Set.of(), Set.of(), Set.of(), Map.of(), 4);

        List<Recommendation> result = PlaylistRecommendationRanker.rank(
                List.of(unrelated, matching), taste, Set.of(), false, TODAY, new Random(1));

        assertEquals(2, result.size());
        assertEquals(30, result.get(0).playlistId());
        assertEquals("search", result.get(0).reason());
        assertFalse(result.get(0).exploration());
    }

    private static List<PlaylistFeature> candidates() {
        List<PlaylistFeature> candidates = new ArrayList<>();
        for (int id = 1; id <= 24; id++) {
            candidates.add(feature(id, id % 9, "playlist-" + id,
                    "track artist-" + (id % 5) + " tag-" + (id % 4), id % 4));
        }
        return candidates;
    }

    private static PlaylistFeature feature(int id, int creatorId, String name,
                                           String searchableText, int collaborative) {
        return new PlaylistFeature(
                id,
                creatorId,
                name,
                "",
                20,
                Map.of("artist-" + (id % 5), 1.0),
                Map.of("tag-" + (id % 4), 1.0),
                Map.of("zh", 1.0),
                Set.of(100 + id),
                10 + (id % 3),
                1000L + id,
                TODAY.minusDays(id).toEpochDay(),
                TODAY.minusDays(id).toEpochDay(),
                collaborative,
                creatorId == 10 ? 2.0 : 0.0,
                searchableText);
    }
}
