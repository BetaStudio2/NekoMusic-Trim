/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/SearchSession.cs
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：去掉 FFI JSON 解析，改为强类型请求；增加索引重建后的
 * 会话 / 查询缓存失效入口。
 */

package com.neko.music.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只读查询会话：按 {@code search.db} 路径缓存一个只读连接，执行
 * 「主趟 → 拼音宽松 → 日文读音 → 跨语言 → 模糊 → 中文容错」六段渐进查询。
 *
 * <p>结果按 trip（趟）合并去重，首次出现顺序即更高优先级趟。查询结果 LRU 128 条；
 * 单次结果超过 500 条不缓存。会话与缓存都在静态锁下串行。
 */
public final class SearchSession implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SearchSession.class);

    private static final Object SYNC = new Object();
    private static SearchSession cached;

    private static long cacheHits;
    private static long cacheMisses;

    /** 查询结果 LRU 容量。 */
    public static final int CACHE_CAPACITY = 128;

    /** 单条结果超过该条数时不入缓存。 */
    public static final int MAX_CACHED_ITEMS = 500;

    private final FtsStore store;
    private final String path;
    private final int analyzerVersion;
    /** 最近一次观察到的 data_version（其它连接写入后会变化，用于自动失效查询缓存）。 */
    private long dataVersion = -1;

    private final LinkedHashMap<String, SearchResult> cache = new LinkedHashMap<>(CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SearchResult> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    private SearchSession(String path) throws Exception {
        this.path = Paths.get(path).toAbsolutePath().toString();
        this.store = new FtsStore();
        this.store.openReadOnly(this.path);
        FtsStore.StoreMeta meta = this.store.readMeta();
        if (meta.schemaVersion != FtsStore.SCHEMA_VERSION) {
            this.store.close();
            throw new IllegalStateException("索引 schema 版本不匹配（库 " + meta.schemaVersion
                    + "，期望 " + FtsStore.SCHEMA_VERSION + "）");
        }
        this.analyzerVersion = meta.analyzerVersion;
    }

    /** 单次查询请求。 */
    public record SearchRequest(
            String query,
            boolean metadata,
            int limit,
            int offset,
            boolean fuzzy,
            int minExact,
            int candidateCap) {

        public static SearchRequest of(String query, int limit) {
            return new SearchRequest(query, false, limit, 0, false, 1, FtsStore.DEFAULT_CANDIDATE_CAP);
        }
    }

    /** 单条命中（bm25 分数越小越相关）。 */
    public record SearchHit(String trackId, double score) {
    }

    /** 本次查询实际执行的兜底趟（用于诊断与回归计数）。 */
    public static final class PassCounts {
        public int pinyinLoose;
        public int reading;
        public int crossLingual;
        public int fuzzy;
        public int cjkFuzzy;
    }

    /** 查询结果（按趟次合并去重后的 top-k）。 */
    public static final class SearchResult {
        private final List<SearchHit> items = new ArrayList<>();
        private final PassCounts passes = new PassCounts();

        public List<SearchHit> items() {
            return items;
        }

        public PassCounts passes() {
            return passes;
        }
    }

    /** 执行一次查询；路径变化时自动切换会话。 */
    public static SearchResult run(String path, SearchRequest request) {
        synchronized (SYNC) {
            String full = Paths.get(path).toAbsolutePath().toString();
            if (cached == null || !cached.path.equals(full)) {
                closeCachedLocked();
                try {
                    cached = new SearchSession(full);
                } catch (Exception e) {
                    throw new IllegalStateException("打开搜索索引失败: " + e.getMessage(), e);
                }
            }
            return cached.execute(request);
        }
    }

    /** 释放缓存会话（全量重建 / 删除索引 / 退出前调用）。 */
    public static void closeCached() {
        synchronized (SYNC) {
            closeCachedLocked();
        }
    }

    /** 索引内容变化后清空查询结果缓存（保留只读连接）。 */
    public static void invalidateQueryCache() {
        synchronized (SYNC) {
            if (cached != null) {
                cached.cache.clear();
                // 模糊词表同样要失效，否则增量新增的单词永远进不来。
                cached.store.invalidateFuzzyVocabulary();
            }
        }
    }

    private static void closeCachedLocked() {
        if (cached != null) {
            cached.close();
            cached = null;
        }
    }

    /** 测试接缝：累计缓存命中次数。 */
    public static long debugCacheHits() {
        synchronized (SYNC) {
            return cacheHits;
        }
    }

    /** 测试接缝：累计缓存未命中次数。 */
    public static long debugCacheMisses() {
        synchronized (SYNC) {
            return cacheMisses;
        }
    }

    /** 测试接缝：当前缓存条目数。 */
    public static int debugCacheCount() {
        synchronized (SYNC) {
            return cached == null ? 0 : cached.cache.size();
        }
    }

    /** 测试接缝：清零命中 / 未命中计数。 */
    public static void debugResetCacheStats() {
        synchronized (SYNC) {
            cacheHits = 0;
            cacheMisses = 0;
        }
    }

    private SearchResult execute(SearchRequest request) {
        // 其它连接（增量写入 / 删除）提交后 data_version 会变化：自动清空结果缓存与模糊词表，
        // 调用方即使忘记手动 invalidate，也不会搜到已删除的曲目。
        long version = store.dataVersion();
        if (version >= 0 && version != dataVersion) {
            dataVersion = version;
            cache.clear();
            store.invalidateFuzzyVocabulary();
        }

        String key = buildCacheKey(request);
        SearchResult hit = cache.get(key);
        if (hit != null) {
            cacheHits++;
            return hit;
        }

        cacheMisses++;
        SearchResult result = executeUncached(request);
        if (result.items().size() > MAX_CACHED_ITEMS) {
            return result;
        }
        cache.put(key, result);
        return result;
    }

    private String buildCacheKey(SearchRequest request) {
        String query = request.query() == null ? "" : request.query().trim().toLowerCase(Locale.ROOT);
        return String.join("\u0001",
                query,
                request.metadata() ? "meta" : "all",
                Integer.toString(request.limit()),
                Integer.toString(request.offset()),
                request.fuzzy() ? "fuzzy" : "exact",
                Integer.toString(request.minExact()),
                Integer.toString(request.candidateCap()),
                Integer.toString(analyzerVersion));
    }

    private SearchResult executeUncached(SearchRequest request) {
        SearchResult result = new SearchResult();
        Analyzer.QueryTokens tokens = Analyzer.analyzeQuery(request.query());
        Set<String> seen = new HashSet<>();

        // 语言画像每次查询重读：增量刷新可能新增日语 / 韩语曲目，快照会漏掉读音趟。
        FtsStore.StoreMeta meta;
        try {
            meta = store.readMeta();
        } catch (Exception e) {
            logger.debug("读取索引语言画像失败，使用保守兜底画像: {}", e.getMessage());
            meta = new FtsStore.StoreMeta();
        }
        final FtsStore.StoreMeta profile = meta;

        // 主趟：精确表达式（词法 / 拼音 / 首字母）。
        merge(result, seen, SearchQuery.compileMatch(tokens, request.metadata(), false), null, request);

        if (result.items().size() < request.minExact()) {
            String expression = SearchQuery.compilePinyinLoose(tokens, request.metadata());
            if (expression != null) {
                result.passes().pinyinLoose = 1;
                merge(result, seen, expression, null, request);
            }
        }
        if (result.items().size() < request.minExact() && (profile.hasJapanese || profile.hasKorean)) {
            String expression = SearchQuery.compileReading(tokens, request.metadata());
            if (expression != null) {
                result.passes().reading = 1;
                merge(result, seen, expression, SearchQuery.readingSequences(tokens), request);
            }
        }
        if (result.items().size() < request.minExact()) {
            List<String> candidates = filterCrossLingual(tokens.crossLingual(), profile);
            if (!candidates.isEmpty()) {
                tokens.crossLingual().clear();
                tokens.crossLingual().addAll(candidates);
                String expression = SearchQuery.compileCrossLingual(tokens, request.metadata());
                if (expression != null) {
                    result.passes().crossLingual = 1;
                    merge(result, seen, expression, null, request);
                }
            }
        }

        if (!request.fuzzy() || result.items().size() >= request.minExact()) {
            return result;
        }

        if (SearchQuery.hasFuzzyEligible(tokens)) {
            result.passes().fuzzy = 1;
            merge(result, seen,
                    SearchQuery.compileFuzzy(tokens, request.metadata(),
                            store.loadFuzzyVocabulary(), SearchQuery.DEFAULT_MAX_FUZZY_VARIANTS),
                    null, request);
        }
        if (result.items().size() >= request.minExact()) {
            return result;
        }

        List<String> variants = SearchQuery.cjkVariants(request.query(), SearchQuery.DEFAULT_MAX_CJK_VARIANTS);
        if (variants.isEmpty()) {
            return result;
        }
        result.passes().cjkFuzzy = 1;

        List<String> parts = new ArrayList<>(variants.size());
        for (String variant : variants) {
            String expression = SearchQuery.compileMatch(Analyzer.analyzeQuery(variant), false, false);
            if (expression != null) {
                parts.add(expression);
            }
        }
        if (!parts.isEmpty()) {
            String joined = parts.size() == 1 ? parts.get(0) : "(" + String.join(" OR ", parts) + ")";
            merge(result, seen,
                    request.metadata() ? "{" + SearchQuery.METADATA_COLUMNS + "} : (" + joined + ")" : joined,
                    null, request);
        }
        return result;
    }

    /**
     * 按曲库语言画像裁剪跨语言候选：汉字候选只可能在库内含汉字时命中，
     * 拉丁释义候选只可能在库内含拉丁字母时命中。
     */
    private static List<String> filterCrossLingual(List<String> candidates, FtsStore.StoreMeta meta) {
        List<String> output = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            boolean han = false;
            for (int i = 0; i < candidate.length(); i++) {
                if (ScriptRanges.isCjk(candidate.charAt(i))) {
                    han = true;
                    break;
                }
            }
            if (han ? !meta.hasHan : !meta.hasLatin) {
                continue;
            }
            output.add(candidate);
        }
        return output;
    }

    private void merge(SearchResult result, Set<String> seen, String expression,
                       List<String[]> readingSequences, SearchRequest request) {
        if (expression == null) {
            return;
        }
        for (SearchHit hit : executeMatch(expression, request, readingSequences)) {
            if (seen.add(hit.trackId())) {
                result.items().add(hit);
            }
        }
    }

    private List<SearchHit> executeMatch(String expression, SearchRequest request,
                                         List<String[]> readingSequences) {
        List<FtsStore.ScoredDoc> rows;
        try {
            rows = store.search(expression, request.limit(), request.offset(), request.candidateCap());
        } catch (Exception e) {
            logger.warn("索引查询失败: query={}, error={}", request.query(), e.toString());
            return new ArrayList<>();
        }
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> docIds = new ArrayList<>(rows.size());
        for (FtsStore.ScoredDoc row : rows) {
            docIds.add(row.docId());
        }
        Map<Long, String> trackIds;
        try {
            trackIds = store.resolveTrackIds(docIds);
        } catch (Exception e) {
            logger.warn("命中曲目映射失败: {}", e.toString());
            return new ArrayList<>();
        }

        if (readingSequences == null || readingSequences.isEmpty()) {
            List<SearchHit> plain = new ArrayList<>(rows.size());
            for (FtsStore.ScoredDoc row : rows) {
                String trackId = trackIds.get(row.docId());
                if (trackId != null) {
                    plain.add(new SearchHit(trackId, row.score()));
                }
            }
            return plain;
        }

        Map<Long, String> readings;
        try {
            readings = store.resolveReadingTexts(docIds);
        } catch (Exception e) {
            readings = Map.of();
        }

        record Ranked(SearchHit hit, int tier) {
        }
        List<Ranked> ranked = new ArrayList<>(rows.size());
        for (FtsStore.ScoredDoc row : rows) {
            String trackId = trackIds.get(row.docId());
            if (trackId == null) {
                continue;
            }
            String text = readings.get(row.docId());
            int tier = text != null ? ReadingRanker.score(text, readingSequences) : ReadingRanker.OTHER_TIER;
            ranked.add(new Ranked(new SearchHit(trackId, row.score()), tier));
        }
        ranked.sort((a, b) -> {
            if (a.tier() != b.tier()) {
                return Integer.compare(b.tier(), a.tier());
            }
            return Double.compare(a.hit().score(), b.hit().score());
        });
        List<SearchHit> ordered = new ArrayList<>(ranked.size());
        for (Ranked entry : ranked) {
            ordered.add(entry.hit());
        }
        return ordered;
    }

    @Override
    public void close() {
        store.close();
    }
}
