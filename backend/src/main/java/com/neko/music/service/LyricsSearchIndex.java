package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.util.LyricsPlainTextExtractor;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.VideoRenderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 站内歌词搜索索引：仅索引已入库曲目（{@code music} 表存在且 {@code music_lyrics} 有有效歌词）。
 * 纯文本缓存目录：{@code /tmp/.neko/lyrics-index/plain}（tmpfs）。
 *
 * <p>索引与查询用同一套归一（繁简转换 + 去空白 + 小写），所以繁体歌词也能被简体查询命中；
 * 命中判定按 2-gram 覆盖率容忍少量漏字（用户按记忆搜歌词常多打/漏打一两个字）。
 */
public class LyricsSearchIndex {
    private static final Logger logger = LoggerFactory.getLogger(LyricsSearchIndex.class);

    public static final Path INDEX_ROOT = VideoRenderPaths.NEKO_TMP_ROOT.resolve("lyrics-index");
    public static final Path PLAIN_DIR = INDEX_ROOT.resolve("plain");

    private static final int MIN_QUERY_LEN = 7;
    private static final int NGRAM_SIZE = 2;
    private static final int MAX_RESULTS = 200;
    /** 单次搜索最多扫描多少个候选（取最小倒排桶，仅防御性上限）。 */
    private static final int MAX_CANDIDATE_SCAN = 20_000;

    private final Map<Integer, String> plainById = new ConcurrentHashMap<>();
    private final Map<Integer, Set<String>> ngramsById = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> postings = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean building = new AtomicBoolean(false);
    private final AtomicBoolean notReadyWarned = new AtomicBoolean(false);

    public boolean isReady() {
        return ready.get();
    }

    public void buildIndexAsync() {
        if (!building.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::buildIndex, "lyrics-index-build");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void buildIndex() {
        building.set(true);
        ready.set(false);
        try {
            Files.createDirectories(PLAIN_DIR);
            plainById.clear();
            ngramsById.clear();
            postings.clear();

            int indexed = 0;
            for (LyricsDatabaseManager.StoredLyrics lyrics : Main.getLyricsDatabaseManager().findAllForIndex()) {
                if (indexOne(lyrics.musicId(), lyrics.content(), lyrics.plainText(), lyrics.format())) {
                    indexed++;
                }
            }
            ready.set(true);
            logger.info("站内歌词索引就绪: {} 首, tmpfs={}", indexed, PLAIN_DIR);
            if (indexed == 0) {
                logger.warn("站内歌词索引为空：music_lyrics 里没有可用歌词（缺行/占位符/正文过短），歌词搜索会全部落空");
            }
        } catch (Exception e) {
            logger.error("站内歌词索引构建失败", e);
        } finally {
            building.set(false);
        }
    }

    /** 该曲目是否有已入库且可展示的有效歌词（非占位）。 */
    public boolean hasIndexedLyrics(int musicId) {
        return musicId > 0 && ready.get() && plainById.containsKey(musicId);
    }

    public record SearchOutcome(List<Integer> ids, int candidateCount, boolean saturated) {
    }

    /** 仅返回已入库且歌词正文包含 query 的 musicId。 */
    public List<Integer> search(String query, int limit) {
        return searchWithStats(query, limit).ids();
    }

    /** 返回歌词搜索命中，并附带候选规模，供调用方过滤过宽泛的查询。 */
    public SearchOutcome searchWithStats(String query, int limit) {
        if (!ready.get() || query == null) {
            if (!ready.get() && notReadyWarned.compareAndSet(false, true)) {
                logger.warn("歌词索引尚未就绪，本次歌词搜索被跳过（索引为空或仍在构建）");
            }
            return new SearchOutcome(List.of(), 0, false);
        }
        String normalized = LyricsPlainTextExtractor.normalize(query);
        if (normalized.length() < MIN_QUERY_LEN) {
            return new SearchOutcome(List.of(), 0, false);
        }

        List<String> grams = extractNgrams(normalized);
        if (grams.isEmpty()) {
            return new SearchOutcome(List.of(), 0, false);
        }

        // 候选集取所有倒排桶的并集（规模不会超过索引曲目数）：旧实现取桶的交集，
        // 「来自中原一群伙伴」漏掉一个「的」就一律判死，连近似命中都进不了候选。
        Set<Integer> candidates = new HashSet<>();
        for (String gram : grams) {
            Set<Integer> bucket = postings.get(gram);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        if (candidates.isEmpty()) {
            return new SearchOutcome(List.of(), 0, false);
        }
        if (candidates.size() > MAX_CANDIDATE_SCAN) {
            logger.info("歌词候选过多，跳过本次歌词搜索: query='{}', candidates={}", query, candidates.size());
            return new SearchOutcome(List.of(), candidates.size(), true);
        }

        int required = grams.size() - allowedMisses(grams.size());
        List<LyricHit> exactHits = new ArrayList<>();
        List<LyricHit> nearHits = new ArrayList<>();
        for (Integer id : candidates) {
            Set<String> songGrams = ngramsById.get(id);
            if (songGrams == null) {
                continue;
            }
            int matched = 0;
            for (String gram : grams) {
                if (songGrams.contains(gram)) {
                    matched++;
                }
            }
            if (matched < required) {
                continue;
            }
            String plain = plainById.get(id);
            LyricHit hit = new LyricHit(id, matched, plain != null && plain.contains(normalized));
            if (hit.exactPhrase()) {
                exactHits.add(hit);
            } else {
                nearHits.add(hit);
            }
        }

        // 完整短语命中就只返回完整短语（与旧行为一致）；一个完整短语都没有时，才退回近似命中。
        // 近似命中按命中 gram 数排序，同分按 id，保证结果顺序稳定。
        List<LyricHit> hits = exactHits.isEmpty() ? nearHits : exactHits;
        hits.sort(Comparator.comparingInt(LyricHit::matched).reversed()
                .thenComparingInt(LyricHit::musicId));

        int cap = Math.min(Math.max(1, limit), MAX_RESULTS);
        boolean saturated = hits.size() > cap;
        List<Integer> ids = new ArrayList<>(Math.min(hits.size(), cap));
        for (LyricHit hit : hits.subList(0, Math.min(hits.size(), cap))) {
            ids.add(hit.musicId());
        }
        return new SearchOutcome(List.copyOf(ids), exactHits.size(), saturated);
    }

    /**
     * 允许漏掉几个 2-gram：短查询要求全中保精度，长查询容忍一两个字。
     * 注意多打/漏打一个字会打断两个相邻 gram（「来自中原一群伙伴」插入「的」后，
     * 「原的」「的一」两个 gram 都不命中），所以长查询至少要容忍 2 个。
     */
    private static int allowedMisses(int gramCount) {
        if (gramCount <= 4) {
            return 0;
        }
        if (gramCount <= 6) {
            return 1;
        }
        return gramCount <= 10 ? 2 : 3;
    }

    /** 一条歌词命中：{@code exactPhrase} 表示正文含完整短语，{@code matched} 是命中的 gram 数。 */
    private record LyricHit(int musicId, int matched, boolean exactPhrase) {
    }

    /** 新入库或歌词更新后增量重建（仅当曲目已在库且 lrc 存在）。 */
    public synchronized void rebuildOne(int musicId) {
        if (musicId <= 0 || !musicExistsInDb(musicId)) {
            removeOne(musicId);
            return;
        }
        removeOne(musicId);
        try {
            Files.createDirectories(PLAIN_DIR);
            Main.getLyricsDatabaseManager()
                    .findByMusicId(musicId)
                    .ifPresent(lyrics -> indexOne(
                            lyrics.musicId(), lyrics.content(), lyrics.plainText(), lyrics.format()));
        } catch (Exception e) {
            logger.warn("重建歌词索引失败 musicId={}: {}", musicId, e.toString());
        }
    }

    private boolean indexOne(int musicId, String lrcContent, String precomputedPlain) {
        return indexOne(musicId, lrcContent, precomputedPlain, "lrc");
    }

    private boolean indexOne(int musicId, String content, String precomputedPlain, String format) {
        String indexed = precomputedPlain == null || precomputedPlain.isBlank()
                ? (NekoLyrics.isNekoFormat(format)
                    ? NekoLyrics.deserialize(content).map(NekoLyrics::plainText).orElse("")
                    : LyricsPlainTextExtractor.fromLrc(content))
                : precomputedPlain;
        // plain_text 可能是历史遗留或外部写入的未归一原文（如繁体、带空白），必须在这里再归一一次：
        // 查询侧是归一后的文本，索引侧不归一就会出现「繁体歌词永远搜不到简体关键词」。
        String plain = LyricsPlainTextExtractor.normalize(indexed);
        if (plain.length() < MIN_QUERY_LEN || LyricsPlainTextExtractor.isPlaceholder(plain)) {
            return false;
        }
        plainById.put(musicId, plain);
        Set<String> grams = Set.copyOf(extractNgrams(plain));
        ngramsById.put(musicId, grams);
        for (String gram : grams) {
            postings.computeIfAbsent(gram, k -> ConcurrentHashMap.newKeySet()).add(musicId);
        }
        try {
            Files.writeString(PLAIN_DIR.resolve(musicId + ".txt"), plain);
        } catch (IOException e) {
            logger.debug("写入歌词纯文本缓存失败 musicId={}: {}", musicId, e.toString());
        }
        return true;
    }

    private void removeOne(int musicId) {
        plainById.remove(musicId);
        Set<String> old = ngramsById.remove(musicId);
        if (old != null) {
            for (String gram : old) {
                Set<Integer> bucket = postings.get(gram);
                if (bucket != null) {
                    bucket.remove(musicId);
                    if (bucket.isEmpty()) {
                        postings.remove(gram);
                    }
                }
            }
        }
        try {
            Files.deleteIfExists(PLAIN_DIR.resolve(musicId + ".txt"));
        } catch (IOException ignored) {
        }
    }

    private static boolean musicExistsInDb(int musicId) {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM music WHERE id = ? LIMIT 1")) {
            ps.setInt(1, musicId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            logger.debug("检查 music 是否存在失败 id={}", musicId);
            return false;
        }
    }

    private static List<String> extractNgrams(String normalized) {
        if (normalized.length() < NGRAM_SIZE) {
            return List.of();
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i <= normalized.length() - NGRAM_SIZE; i++) {
            grams.add(normalized.substring(i, i + NGRAM_SIZE));
        }
        return new ArrayList<>(grams);
    }
}
