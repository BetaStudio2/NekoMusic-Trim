package com.neko.music.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 歌单推荐排序器。
 *
 * <p>先由收藏、收藏歌单、自建歌单与最近搜索构建用户画像，再把候选歌单表示成
 * 艺人 / 标签 / 语种向量。排序不是简单的 if-else 加权：它用用户的显式歌单行为与
 * 收藏重合样本做在线逻辑回归，对每类特征进行个性化校准；样本不足时回退到音乐平台
 * 常用的稳定先验权重。协同过滤、社区收藏、热度和新鲜度作为独立特征参与同一模型。</p>
 *
 * <p>排序器不持久化任何模型或结果。每次接口请求都从当前数据重新构建画像并推理，
 * 以满足推荐歌单“刷新即更换、不缓存”的产品约束。</p>
 */
public final class PlaylistRecommendationRanker {
    public static final int RESULT_LIMIT = 5;

    private static final double[] PRIOR_WEIGHTS = {
            0.0,  // bias
            1.55, // artist cosine
            1.25, // tag cosine
            0.50, // language cosine
            1.10, // recent search affinity
            1.35, // collaborative filtering
            0.55, // community popularity
            0.35, // freshness
            0.45, // playlist completeness
            0.40, // creator affinity
            0.35  // discovery / novelty
    };
    private static final double[] GUEST_WEIGHTS = {
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.35, 0.55, 0.55, 0.0, 0.0
    };

    private PlaylistRecommendationRanker() {
    }

    public static List<Recommendation> rank(List<PlaylistFeature> candidates,
                                            UserTaste taste,
                                            Set<Integer> excludedPlaylistIds,
                                            boolean refresh,
                                            LocalDate today,
                                            Random random) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        UserTaste profile = taste == null ? UserTaste.empty() : taste;
        Set<Integer> excluded = excludedPlaylistIds == null ? Set.of() : excludedPlaylistIds;

        List<ScoredPlaylist> scored = scoreCandidates(candidates, profile, today);
        scored.sort(Comparator.comparingDouble(ScoredPlaylist::score).reversed()
                .thenComparingInt(s -> s.feature().id()));

        List<ScoredPlaylist> available = scored.stream()
                .filter(s -> !excluded.contains(s.feature().id()))
                .toList();
        if (available.isEmpty()) {
            return List.of();
        }

        String source = profile.hasSignals() ? "personalized" : "popular";
        List<ScoredPlaylist> selected;
        if (refresh && available.size() > RESULT_LIMIT) {
            selected = new ArrayList<>(selectDiverse(available, 3));
            Set<Integer> selectedIds = selected.stream()
                    .map(s -> s.feature().id())
                    .collect(HashSet::new, Set::add, Set::addAll);
            List<ScoredPlaylist> explorationPool = available.stream()
                    .filter(s -> !selectedIds.contains(s.feature().id()))
                    .limit(Math.min(18, available.size()))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            Collections.shuffle(explorationPool, random);
            selected.addAll(selectDiverse(explorationPool, RESULT_LIMIT - selected.size()));
        } else {
            selected = selectDiverse(available, RESULT_LIMIT);
        }

        List<Recommendation> result = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            ScoredPlaylist one = selected.get(i);
            boolean exploration = refresh && i >= 3;
            result.add(new Recommendation(
                    one.feature().id(),
                    one.score(),
                    chooseReason(one, profile.hasSignals()),
                    source,
                    exploration));
        }
        return result;
    }

    private static List<ScoredPlaylist> scoreCandidates(List<PlaylistFeature> candidates,
                                                        UserTaste taste,
                                                        LocalDate today) {
        Map<Integer, Map<String, Double>> artistVectors = new HashMap<>();
        Map<Integer, Map<String, Double>> tagVectors = new HashMap<>();
        Map<Integer, Map<String, Double>> languageVectors = new HashMap<>();
        Map<Integer, Double> searchRaw = new HashMap<>();
        List<Double> popularityRaw = new ArrayList<>(candidates.size());
        double maxPopularityLog = 0;
        double maxCollaborativeLog = 0;
        double maxCreatorAffinityLog = 0;
        double maxSearch = 0;

        for (PlaylistFeature feature : candidates) {
            artistVectors.put(feature.id(), normalizeVector(feature.artistWeights()));
            tagVectors.put(feature.id(), normalizeVector(feature.tagWeights()));
            languageVectors.put(feature.id(), normalizeVector(feature.languageWeights()));
            double rawPopularity = Math.log1p(Math.max(0, feature.favoriteCount()) * 5.0
                    + Math.max(0, feature.playCount()));
            popularityRaw.add(rawPopularity);
            maxPopularityLog = Math.max(maxPopularityLog, rawPopularity);
            maxCollaborativeLog = Math.max(maxCollaborativeLog,
                    Math.log1p(Math.max(0, feature.collaborativeScore())));
            maxCreatorAffinityLog = Math.max(maxCreatorAffinityLog,
                    Math.log1p(Math.max(0, feature.creatorAffinity())));
            double search = searchAffinity(taste.searches(), feature.searchableText(), today);
            searchRaw.put(feature.id(), search);
            maxSearch = Math.max(maxSearch, search);
        }

        List<Example> examples = buildTrainingExamples(candidates, taste, artistVectors, tagVectors,
                languageVectors, searchRaw, maxSearch, maxPopularityLog, maxCollaborativeLog,
                maxCreatorAffinityLog, today);
        double[] weights = trainLogistic(examples, taste, candidates);

        List<ScoredPlaylist> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            PlaylistFeature feature = candidates.get(i);
            double[] x = featureVector(feature, taste, artistVectors, tagVectors, languageVectors,
                    searchRaw, maxSearch, popularityRaw.get(i), maxPopularityLog,
                    maxCollaborativeLog, maxCreatorAffinityLog, today);
            double score = dot(weights, x);
            double[] contribution = new double[x.length];
            for (int j = 0; j < x.length; j++) {
                contribution[j] = weights[j] * x[j];
            }
            scored.add(new ScoredPlaylist(feature, score, contribution));
        }
        return scored;
    }

    private static List<Example> buildTrainingExamples(List<PlaylistFeature> candidates,
                                                       UserTaste taste,
                                                       Map<Integer, Map<String, Double>> artistVectors,
                                                       Map<Integer, Map<String, Double>> tagVectors,
                                                       Map<Integer, Map<String, Double>> languageVectors,
                                                       Map<Integer, Double> searchRaw,
                                                       double maxSearch,
                                                       double maxPopularityLog,
                                                       double maxCollaborativeLog,
                                                       double maxCreatorAffinityLog,
                                                       LocalDate today) {
        List<Example> examples = new ArrayList<>();
        boolean hasExplicitPlaylistLabels = !taste.ownPlaylistIds().isEmpty()
                || !taste.favoritePlaylistIds().isEmpty();
        for (PlaylistFeature feature : candidates) {
            double[] x = featureVector(feature, taste, artistVectors, tagVectors, languageVectors,
                    searchRaw, maxSearch, Math.log1p(Math.max(0, feature.favoriteCount()) * 5.0
                            + Math.max(0, feature.playCount())), maxPopularityLog,
                    maxCollaborativeLog, maxCreatorAffinityLog, today);
            boolean positive;
            if (hasExplicitPlaylistLabels) {
                positive = taste.ownPlaylistIds().contains(feature.id())
                        || taste.favoritePlaylistIds().contains(feature.id());
            } else {
                double overlap = trackOverlap(feature, taste.favoriteTrackIds());
                positive = overlap >= 0.20 || x[1] >= 0.72 || x[2] >= 0.72;
            }
            examples.add(new Example(x, positive ? 1.0 : 0.0));
        }
        return examples;
    }

    private static double[] trainLogistic(List<Example> examples, UserTaste taste,
                                          List<PlaylistFeature> candidates) {
        if (taste.interactionCount() < 3 || examples.isEmpty()) {
            return taste.hasSignals() ? PRIOR_WEIGHTS.clone() : GUEST_WEIGHTS.clone();
        }
        long positives = examples.stream().filter(e -> e.label() > 0.5).count();
        long negatives = examples.size() - positives;
        if (positives < 2 || negatives < 2) {
            return taste.hasSignals() ? PRIOR_WEIGHTS.clone() : GUEST_WEIGHTS.clone();
        }

        double[] weights = PRIOR_WEIGHTS.clone();
        double learningRate = 0.055;
        double l2 = 0.025;
        int epochs = candidates.size() > 300 ? 55 : 80;
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (Example example : examples) {
                double predicted = sigmoid(dot(weights, example.x()));
                double error = example.label() - predicted;
                for (int j = 1; j < weights.length; j++) {
                    weights[j] += learningRate * (error * example.x()[j] - l2 * weights[j]);
                }
            }
        }
        return weights;
    }

    private static double[] featureVector(PlaylistFeature feature,
                                          UserTaste taste,
                                          Map<Integer, Map<String, Double>> artistVectors,
                                          Map<Integer, Map<String, Double>> tagVectors,
                                          Map<Integer, Map<String, Double>> languageVectors,
                                          Map<Integer, Double> searchRaw,
                                          double maxSearch,
                                          double popularityRaw,
                                          double maxPopularityLog,
                                          double maxCollaborativeLog,
                                          double maxCreatorAffinityLog,
                                          LocalDate today) {
        double artist = cosine(taste.artistWeights(), artistVectors.get(feature.id()));
        double tags = cosine(taste.tagWeights(), tagVectors.get(feature.id()));
        double language = cosine(taste.languageWeights(), languageVectors.get(feature.id()));
        double search = maxSearch <= 0 ? 0 : searchRaw.getOrDefault(feature.id(), 0.0) / maxSearch;
        double collaborative = maxCollaborativeLog <= 0 ? 0
                : Math.log1p(Math.max(0, feature.collaborativeScore())) / maxCollaborativeLog;
        double popularity = maxPopularityLog <= 0 ? 0 : popularityRaw / maxPopularityLog;
        double freshness = freshness(feature.updatedAtEpochDay(), today);
        double completeness = Math.min(1.0, Math.log1p(Math.max(0, feature.musicCount()))
                / Math.log1p(30.0));
        double creator = maxCreatorAffinityLog <= 0 ? 0
                : Math.log1p(Math.max(0, feature.creatorAffinity())) / maxCreatorAffinityLog;
        double novelty = 1.0 - trackOverlap(feature, taste.favoriteTrackIds());
        return new double[] {
                1.0, artist, tags, language, search, collaborative,
                popularity, freshness, completeness, creator, novelty
        };
    }

    private static List<ScoredPlaylist> selectDiverse(List<ScoredPlaylist> sorted, int limit) {
        if (limit <= 0 || sorted.isEmpty()) {
            return List.of();
        }
        List<ScoredPlaylist> selected = new ArrayList<>(limit);
        Set<Integer> usedCreators = new HashSet<>();
        for (ScoredPlaylist one : sorted) {
            if (selected.size() >= limit) {
                break;
            }
            if (usedCreators.add(one.feature().creatorId())) {
                selected.add(one);
            }
        }
        if (selected.size() < limit) {
            Set<Integer> selectedIds = selected.stream()
                    .map(s -> s.feature().id())
                    .collect(HashSet::new, Set::add, Set::addAll);
            for (ScoredPlaylist one : sorted) {
                if (selected.size() >= limit) {
                    break;
                }
                if (selectedIds.add(one.feature().id())) {
                    selected.add(one);
                }
            }
        }
        return selected;
    }

    private static String chooseReason(ScoredPlaylist scored, boolean personalized) {
        if (!personalized) {
            return scored.contribution()[7] >= 0.22 ? "fresh" : "popular";
        }
        double taste = Math.max(scored.contribution()[1],
                Math.max(scored.contribution()[2], scored.contribution()[3]));
        if (scored.contribution()[4] >= 0.24 && scored.contribution()[4] >= taste) {
            return "search";
        }
        if (scored.contribution()[5] >= 0.28 && scored.contribution()[5] >= taste) {
            return "similar_users";
        }
        if (taste >= 0.20) {
            return "taste";
        }
        if (scored.contribution()[7] >= 0.22) {
            return "fresh";
        }
        return "popular";
    }

    private static double searchAffinity(List<SearchSignal> searches, String searchableText,
                                         LocalDate today) {
        if (searches.isEmpty() || searchableText == null || searchableText.isBlank()) {
            return 0;
        }
        String haystack = normalizeText(searchableText);
        double score = 0;
        for (SearchSignal signal : searches) {
            String query = normalizeText(signal.query());
            if (query.length() < 2) {
                continue;
            }
            long days = Math.max(0, today.toEpochDay() - signal.lastSearchedEpochDay());
            double recency = Math.exp(-days / 120.0);
            double frequency = Math.log1p(Math.max(1, signal.count()));
            double match = 0;
            if (haystack.contains(query)) {
                match = 1.0;
            } else {
                List<String> tokens = searchTokens(query);
                if (!tokens.isEmpty()) {
                    int hits = 0;
                    for (String token : tokens) {
                        if (haystack.contains(token)) {
                            hits++;
                        }
                    }
                    match = hits / (double) tokens.size();
                    if (match < 0.60) {
                        match = 0;
                    }
                }
            }
            score += match * recency * frequency;
        }
        return score;
    }

    private static List<String> searchTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : query.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    private static String normalizeText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double trackOverlap(PlaylistFeature feature, Set<Integer> favoriteTrackIds) {
        if (favoriteTrackIds == null || favoriteTrackIds.isEmpty() || feature.trackIds().isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (Integer id : feature.trackIds()) {
            if (favoriteTrackIds.contains(id)) {
                overlap++;
            }
        }
        return Math.min(1.0, overlap / (double) Math.min(10, feature.trackIds().size()));
    }

    private static double freshness(long updatedEpochDay, LocalDate today) {
        long days = Math.max(0, today.toEpochDay() - updatedEpochDay);
        return Math.exp(-days / 240.0);
    }

    private static Map<String, Double> normalizeVector(Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        double norm = 0;
        for (double value : raw.values()) {
            norm += value * value;
        }
        if (norm <= 0) {
            return Map.of();
        }
        norm = Math.sqrt(norm);
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / norm);
        }
        return normalized;
    }

    static double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = smaller == left ? right : left;
        double score = 0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            Double other = larger.get(entry.getKey());
            if (other != null) {
                score += entry.getValue() * other;
            }
        }
        return Math.max(0, Math.min(1, score));
    }

    private static double dot(double[] left, double[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) {
            value += left[i] * right[i];
        }
        return value;
    }

    private static double sigmoid(double value) {
        if (value >= 0) {
            double z = Math.exp(-value);
            return 1 / (1 + z);
        }
        double z = Math.exp(value);
        return z / (1 + z);
    }

    public record PlaylistFeature(
            int id,
            int creatorId,
            String name,
            String description,
            int musicCount,
            Map<String, Double> artistWeights,
            Map<String, Double> tagWeights,
            Map<String, Double> languageWeights,
            Set<Integer> trackIds,
            int favoriteCount,
            long playCount,
            long createdAtEpochDay,
            long updatedAtEpochDay,
            double collaborativeScore,
            double creatorAffinity,
            String searchableText) {
    }

    public record SearchSignal(String query, int count, long lastSearchedEpochDay) {
    }

    public record UserTaste(
            Map<String, Double> artistWeights,
            Map<String, Double> tagWeights,
            Map<String, Double> languageWeights,
            List<SearchSignal> searches,
            Set<Integer> favoriteTrackIds,
            Set<Integer> ownPlaylistIds,
            Set<Integer> favoritePlaylistIds,
            Map<Integer, Double> creatorAffinity,
            int interactionCount) {

        public static UserTaste empty() {
            return new UserTaste(Map.of(), Map.of(), Map.of(), List.of(), Set.of(), Set.of(), Set.of(),
                    Map.of(), 0);
        }

        public boolean hasSignals() {
            return interactionCount > 0 || !searches.isEmpty();
        }
    }

    public record Recommendation(
            int playlistId,
            double score,
            String reason,
            String source,
            boolean exploration) {
    }

    private record Example(double[] x, double label) {
    }

    private record ScoredPlaylist(PlaylistFeature feature, double score, double[] contribution) {
    }
}
