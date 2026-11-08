package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import com.neko.music.service.PlaylistRecommendationRanker.PlaylistFeature;
import com.neko.music.service.PlaylistRecommendationRanker.Recommendation;
import com.neko.music.service.PlaylistRecommendationRanker.SearchSignal;
import com.neko.music.service.PlaylistRecommendationRanker.UserTaste;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 推荐歌单服务。
 *
 * <p>该服务按请求实时读取候选歌单、用户画像和协同信号，不写 Redis、不维护进程内结果缓存，
 * 也不生成持久化推荐表。这样刷新按钮每次都能重新探索候选，而不会命中旧结果。</p>
 */
public class PlaylistRecommendationService {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistRecommendationService.class);
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CANDIDATE_PLAYLISTS_AUTHENTICATED = 600;
    private static final int CANDIDATE_PLAYLISTS_GUEST = 240;
    private static final int MAX_TRACKS_PER_PLAYLIST_AUTHENTICATED = 80;
    private static final int MAX_TRACKS_PER_PLAYLIST_GUEST = 40;
    private static final int PROFILE_INTERACTIONS_PER_SOURCE = 600;
    private static final int SEARCH_SIGNALS = 40;

    private final DatabaseManager databaseManager;

    public PlaylistRecommendationService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public List<RecommendedPlaylist> recommend(Integer userId, boolean refresh) {
        LocalDate today = LocalDate.now(CN_ZONE);
        try {
            boolean authenticated = userId != null;
            List<PlaylistSeed> seeds = loadPlaylistSeeds(authenticated
                    ? CANDIDATE_PLAYLISTS_AUTHENTICATED : CANDIDATE_PLAYLISTS_GUEST);
            if (seeds.isEmpty()) {
                return List.of();
            }
            UserTaste taste = authenticated ? loadUserTaste(userId) : UserTaste.empty();
            Map<Integer, List<TrackFeature>> tracksByPlaylist = loadPlaylistTracks(seeds.stream()
                    .map(PlaylistSeed::id)
                    .toList(), authenticated
                    ? MAX_TRACKS_PER_PLAYLIST_AUTHENTICATED
                    : MAX_TRACKS_PER_PLAYLIST_GUEST);
            List<PlaylistFeature> candidates = buildFeatures(seeds, tracksByPlaylist,
                    authenticated ? loadCollaborativeScores(userId) : Map.of(),
                    taste.creatorAffinity());
            Set<Integer> excluded = new LinkedHashSet<>();
            excluded.addAll(taste.ownPlaylistIds());
            excluded.addAll(taste.favoritePlaylistIds());

            Random random = refresh ? ThreadLocalRandom.current() : new Random(0);
            List<Recommendation> ranked = PlaylistRecommendationRanker.rank(
                    candidates, taste, excluded, refresh, today, random);

            Map<Integer, PlaylistFeature> featureById = new HashMap<>();
            for (PlaylistFeature feature : candidates) {
                featureById.put(feature.id(), feature);
            }
            List<RecommendedPlaylist> result = new ArrayList<>(ranked.size());
            int rank = 1;
            for (Recommendation one : ranked) {
                PlaylistFeature feature = featureById.get(one.playlistId());
                if (feature == null) {
                    continue;
                }
                List<String> coverUrls = feature.trackIds().stream()
                        .limit(4)
                        .map(id -> "/api/music/cover/" + id)
                        .toList();
                if (coverUrls.isEmpty()) {
                    coverUrls = List.of("/api/music/cover/0");
                }
                result.add(new RecommendedPlaylist(
                        rank++,
                        feature.id(),
                        feature.creatorId(),
                        feature.name(),
                        feature.description(),
                        feature.musicCount(),
                        coverUrls,
                        one.reason(),
                        one.source(),
                        one.exploration()));
            }
            return result;
        } catch (Exception e) {
            logger.error("生成推荐歌单失败 userId={}", userId, e);
            return List.of();
        }
    }

    private List<PlaylistSeed> loadPlaylistSeeds(int limit) throws Exception {
        String sql = """
                SELECT p.id, p.user_id, p.name, p.description, p.music_count,
                       p.created_at, p.updated_at,
                       COALESCE(f.favorite_count, 0) AS favorite_count
                FROM playlists p
                LEFT JOIN (
                    SELECT playlist_id, COUNT(*) AS favorite_count
                    FROM user_favorite_playlists
                    GROUP BY playlist_id
                ) f ON f.playlist_id = p.id
                WHERE p.music_count > 0
                ORDER BY favorite_count DESC, p.updated_at DESC, p.id DESC
                LIMIT ?
                """;
        List<PlaylistSeed> seeds = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seeds.add(new PlaylistSeed(
                            rs.getInt("id"),
                            rs.getInt("user_id"),
                            nullToEmpty(rs.getString("name")),
                            nullToEmpty(rs.getString("description")),
                            rs.getInt("music_count"),
                            epochDay(rs.getTimestamp("created_at")),
                            epochDay(rs.getTimestamp("updated_at")),
                            rs.getInt("favorite_count")));
                }
            }
        }
        return seeds;
    }

    private Map<Integer, List<TrackFeature>> loadPlaylistTracks(List<Integer> playlistIds,
                                                               int maxTracksPerPlaylist) throws Exception {
        if (playlistIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<TrackFeature>> byPlaylist = new LinkedHashMap<>();
        for (int start = 0; start < playlistIds.size(); start += 300) {
            List<Integer> chunk = playlistIds.subList(start, Math.min(playlistIds.size(), start + 300));
            String sql = """
                    SELECT pm.playlist_id, m.id, m.title, m.artist, m.language, m.tags, m.play_count
                    FROM playlist_music pm
                    INNER JOIN music m ON m.id = pm.music_id
                    WHERE pm.playlist_id IN (%s)
                      AND pm.position <= ?
                    ORDER BY pm.playlist_id ASC, pm.position ASC
                    """.formatted(placeholders(chunk.size()));
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                for (Integer playlistId : chunk) {
                    ps.setInt(index++, playlistId);
                }
                ps.setInt(index, maxTracksPerPlaylist);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int playlistId = rs.getInt("playlist_id");
                        byPlaylist.computeIfAbsent(playlistId, ignored -> new ArrayList<>())
                                .add(new TrackFeature(
                                        rs.getInt("id"),
                                        nullToEmpty(rs.getString("title")),
                                        nullToEmpty(rs.getString("artist")),
                                        nullToEmpty(rs.getString("language")),
                                        nullToEmpty(rs.getString("tags")),
                                        rs.getInt("play_count")));
                    }
                }
            }
        }
        return byPlaylist;
    }

    private List<PlaylistFeature> buildFeatures(List<PlaylistSeed> seeds,
                                                Map<Integer, List<TrackFeature>> tracksByPlaylist,
                                                Map<Integer, Double> collaborativeScores,
                                                Map<Integer, Double> creatorAffinity) {
        List<PlaylistFeature> features = new ArrayList<>();
        for (PlaylistSeed seed : seeds) {
            List<TrackFeature> tracks = tracksByPlaylist.getOrDefault(seed.id(), List.of());
            if (tracks.isEmpty()) {
                continue;
            }
            Map<String, Double> artists = new HashMap<>();
            Map<String, Double> tags = new HashMap<>();
            Map<String, Double> languages = new HashMap<>();
            Set<Integer> trackIds = new LinkedHashSet<>();
            long playCount = 0;
            StringBuilder searchable = new StringBuilder(256);
            searchable.append(seed.name()).append(' ').append(seed.description()).append(' ');
            for (TrackFeature track : tracks) {
                trackIds.add(track.id());
                playCount += Math.max(0, track.playCount());
                artists.merge(normalizeTerm(track.artist()), 1.0, Double::sum);
                languages.merge(normalizeTerm(track.language()), 1.0, Double::sum);
                Set<String> trackTags = splitTags(track.tags());
                double tagWeight = trackTags.isEmpty() ? 0 : 1.0 / Math.sqrt(trackTags.size());
                for (String tag : trackTags) {
                    tags.merge(tag, tagWeight, Double::sum);
                }
                if (searchable.length() < 2400) {
                    searchable.append(track.title()).append(' ')
                            .append(track.artist()).append(' ')
                            .append(track.tags()).append(' ');
                }
            }
            artists.remove("");
            tags.remove("");
            languages.remove("");
            features.add(new PlaylistFeature(
                    seed.id(),
                    seed.userId(),
                    seed.name(),
                    seed.description(),
                    Math.max(seed.musicCount(), tracks.size()),
                    artists,
                    tags,
                    languages,
                    trackIds,
                    seed.favoriteCount(),
                    playCount,
                    seed.createdAtEpochDay(),
                    seed.updatedAtEpochDay(),
                    collaborativeScores.getOrDefault(seed.id(), 0.0),
                    creatorAffinity.getOrDefault(seed.userId(), 0.0),
                    searchable.toString()));
        }
        return features;
    }

    private UserTaste loadUserTaste(int userId) throws Exception {
        Map<String, Double> artists = new HashMap<>();
        Map<String, Double> tags = new HashMap<>();
        Map<String, Double> languages = new HashMap<>();
        Set<Integer> favoriteTrackIds = new LinkedHashSet<>();
        Set<Integer> ownPlaylistIds = new LinkedHashSet<>();
        Set<Integer> favoritePlaylistIds = new LinkedHashSet<>();
        Map<Integer, Double> creatorAffinity = new HashMap<>();
        int interactions = 0;

        interactions += accrueInteractionSource(
                """
                SELECT m.id, m.artist, m.language, m.tags, uf.created_at AS acted_at
                FROM user_favorites uf
                INNER JOIN music m ON m.id = uf.music_id
                WHERE uf.user_id = ?
                ORDER BY uf.created_at DESC
                LIMIT ?
                """,
                userId, 1.0, artists, tags, languages, favoriteTrackIds);

        interactions += accrueInteractionSource(
                """
                SELECT m.id, m.artist, m.language, m.tags, pm.added_at AS acted_at
                FROM user_favorite_playlists ufp
                INNER JOIN playlist_music pm ON pm.playlist_id = ufp.playlist_id
                INNER JOIN music m ON m.id = pm.music_id
                WHERE ufp.user_id = ?
                ORDER BY ufp.created_at DESC, pm.added_at DESC
                LIMIT ?
                """,
                userId, 0.72, artists, tags, languages, null);

        interactions += accrueInteractionSource(
                """
                SELECT m.id, m.artist, m.language, m.tags, pm.added_at AS acted_at
                FROM playlists p
                INNER JOIN playlist_music pm ON pm.playlist_id = p.id
                INNER JOIN music m ON m.id = pm.music_id
                WHERE p.user_id = ?
                ORDER BY p.updated_at DESC, pm.added_at DESC
                LIMIT ?
                """,
                userId, 0.38, artists, tags, languages, null);

        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM playlists WHERE user_id = ?")) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ownPlaylistIds.add(rs.getInt(1));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT ufp.playlist_id, p.user_id
                    FROM user_favorite_playlists ufp
                    INNER JOIN playlists p ON p.id = ufp.playlist_id
                    WHERE ufp.user_id = ?
                    """)) {
                ps.setInt(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        favoritePlaylistIds.add(rs.getInt("playlist_id"));
                        creatorAffinity.merge(rs.getInt("user_id"), 1.0, Double::sum);
                    }
                }
            }
        }

        List<SearchSignal> searches;
        try {
            searches = loadSearchSignals(userId);
        } catch (Exception e) {
            // 搜索画像是增强信号；表暂不可用时，收藏与歌单画像仍应继续工作。
            logger.warn("加载用户搜索画像失败 userId={}", userId, e);
            searches = List.of();
        }
        return new UserTaste(
                artists,
                tags,
                languages,
                searches,
                favoriteTrackIds,
                ownPlaylistIds,
                favoritePlaylistIds,
                creatorAffinity,
                interactions + searches.size());
    }

    private int accrueInteractionSource(String sql,
                                        int userId,
                                        double baseWeight,
                                        Map<String, Double> artists,
                                        Map<String, Double> tags,
                                        Map<String, Double> languages,
                                        Set<Integer> favoriteTrackIds) throws Exception {
        int rows = 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, PROFILE_INTERACTIONS_PER_SOURCE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    if (favoriteTrackIds != null) {
                        favoriteTrackIds.add(rs.getInt("id"));
                    }
                    double weight = baseWeight * recencyWeight(rs.getTimestamp("acted_at"));
                    String artist = normalizeTerm(rs.getString("artist"));
                    if (!artist.isEmpty()) {
                        artists.merge(artist, weight, Double::sum);
                    }
                    String language = normalizeTerm(rs.getString("language"));
                    if (!language.isEmpty()) {
                        languages.merge(language, weight, Double::sum);
                    }
                    Set<String> trackTags = splitTags(rs.getString("tags"));
                    double tagWeight = trackTags.isEmpty() ? 0 : weight / Math.sqrt(trackTags.size());
                    for (String tag : trackTags) {
                        tags.merge(tag, tagWeight, Double::sum);
                    }
                }
            }
        }
        return rows;
    }

    private List<SearchSignal> loadSearchSignals(int userId) throws Exception {
        List<SearchSignal> signals = new ArrayList<>();
        String sql = """
                SELECT query_text, search_count, last_searched_at
                FROM user_search_history
                WHERE user_id = ?
                ORDER BY last_searched_at DESC
                LIMIT ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, SEARCH_SIGNALS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp last = rs.getTimestamp("last_searched_at");
                    signals.add(new SearchSignal(
                            rs.getString("query_text"),
                            rs.getInt("search_count"),
                            last == null ? LocalDate.now(CN_ZONE).toEpochDay()
                                    : last.toInstant().atZone(CN_ZONE).toLocalDate().toEpochDay()));
                }
            }
        }
        return signals;
    }

    /**
     * 同好协同：先找收藏过相同曲目的用户，再看他们收藏的歌单；同时合并“收藏歌单共现”。
     * 两个信号都按候选歌单聚合，作为独立特征交给排序模型。
     */
    private Map<Integer, Double> loadCollaborativeScores(int userId) {
        Map<Integer, Double> scores = new HashMap<>();
        try (Connection conn = databaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT ufp.playlist_id, COUNT(*) AS score
                    FROM (
                        SELECT music_id
                        FROM user_favorites
                        WHERE user_id = ?
                        ORDER BY created_at DESC
                        LIMIT 50
                    ) seed
                    INNER JOIN user_favorites peer
                        ON peer.music_id = seed.music_id AND peer.user_id <> seed.user_id
                    INNER JOIN user_favorite_playlists ufp ON ufp.user_id = peer.user_id
                    WHERE ufp.user_id <> ?
                    GROUP BY ufp.playlist_id
                    """)) {
                ps.setInt(1, userId);
                ps.setInt(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scores.merge(rs.getInt("playlist_id"), rs.getDouble("score"), Double::sum);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT peer.playlist_id, COUNT(*) AS score
                    FROM (
                        SELECT playlist_id
                        FROM user_favorite_playlists
                        WHERE user_id = ?
                        ORDER BY created_at DESC
                        LIMIT 30
                    ) seed
                    INNER JOIN user_favorite_playlists peer
                        ON peer.user_id = seed.user_id
                       AND peer.playlist_id <> seed.playlist_id
                    WHERE peer.user_id <> ?
                    GROUP BY peer.playlist_id
                    """)) {
                ps.setInt(1, userId);
                ps.setInt(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        scores.merge(rs.getInt("playlist_id"), rs.getDouble("score"), Double::sum);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("加载歌单协同过滤信号失败 userId={}", userId, e);
        }
        return scores;
    }

    private static double recencyWeight(Timestamp actedAt) {
        if (actedAt == null) {
            return 1.0;
        }
        long days = Math.max(0, ChronoUnit.DAYS.between(
                actedAt.toInstant().atZone(CN_ZONE).toLocalDate(), LocalDate.now(CN_ZONE)));
        return Math.max(0.20, Math.exp(-days / 240.0));
    }

    private static long epochDay(Timestamp timestamp) {
        return timestamp == null ? 0 : timestamp.toInstant().atZone(CN_ZONE).toLocalDate().toEpochDay();
    }

    private static String normalizeTerm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags.split("[,|/;，、\\s]+")) {
            String normalized = normalizeTerm(tag);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record RecommendedPlaylist(
            int rank,
            int id,
            int userId,
            String name,
            String description,
            int musicCount,
            List<String> coverUrls,
            String reason,
            String source,
            boolean exploration) {
    }

    private record PlaylistSeed(
            int id,
            int userId,
            String name,
            String description,
            int musicCount,
            long createdAtEpochDay,
            long updatedAtEpochDay,
            int favoriteCount) {
    }

    private record TrackFeature(
            int id,
            String title,
            String artist,
            String language,
            String tags,
            int playCount) {
    }
}
