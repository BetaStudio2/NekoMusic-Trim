package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.util.BatchMusicMatchUtil;
import com.neko.music.util.ChineseConverter;
import com.neko.music.util.LrcValidator;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.RuntimeDiskGuard;
import com.neko.music.util.SongLanguageInferer;
import com.neko.music.util.StripedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地单曲搜索无结果时：优先从网易云官方接口拉取一首匹配曲并走管理员入库流程；
 * 网易云无结果（或候选均未能入库）时，若已启用则改用 QQ 音乐接口再补全一次。
 */
public class NeteaseSearchFillService {
    private static final Logger logger = LoggerFactory.getLogger(NeteaseSearchFillService.class);

    private static final int REDIS_LOCK_SECONDS = 180;
    private static final int PEER_WAIT_MAX_MS = 90_000;
    private static final int PEER_WAIT_POLL_MS = 500;

    /**
     * 按 key 串行化的固定条带锁。补全并发上限为 {@code netease_search_fill.max_parallel_fills}（≤10），
     * 远小于条带数，因此不同 key 落到同一条带的碰撞概率很低；同时内存占用有界——原先按
     * 歌名 / songId / songmid 建表会随查询词与曲目数量无限增长。
     */
    private static final int LOCK_STRIPES = 256;
    /** 同一 JVM 内按规范化歌名+主歌手串行化补全，避免不同查询词并发双份入库 */
    private static final StripedLock QUERY_LOCKS = new StripedLock(LOCK_STRIPES);
    /** 同一网易云 songId 串行化下载入库，避免多查询命中同一曲并发写入 */
    private static final StripedLock NETEASE_SONG_LOCKS = new StripedLock(LOCK_STRIPES);
    /** 同一 QQ 音乐 songmid 串行化下载入库，避免多查询命中同一曲并发写入 */
    private static final StripedLock QQ_SONG_LOCKS = new StripedLock(LOCK_STRIPES);

    private final ConfigManager config;
    private final NeteaseCloudMusicClient neteaseClient;
    private final QQMusicClient qqClient;
    private final AdminMusicIngestService ingestService;
    private final RedisService redisService;
    private final ExecutorService batchFillExecutor;

    public NeteaseSearchFillService(
            ConfigManager config,
            NeteaseCloudMusicClient neteaseClient,
            QQMusicClient qqClient,
            AdminMusicIngestService ingestService,
            RedisService redisService
    ) {
        this.config = config;
        this.neteaseClient = neteaseClient;
        this.qqClient = qqClient;
        this.ingestService = ingestService;
        this.redisService = redisService;
        int maxParallelFills = Math.max(1, Math.min(10, config.getNeteaseMaxParallelFills()));
        AtomicInteger threadNo = new AtomicInteger();
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "netease-fill-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.batchFillExecutor = Executors.newFixedThreadPool(maxParallelFills, threadFactory);
        logger.info("网易云批量补全线程池初始化: maxParallelFills={}", maxParallelFills);
    }

    public enum FillReason {
        /** 补全成功或未触发补全 */
        NONE,
        /** 命中非音乐黑名单，本次补全直接跳过 */
        NON_MUSIC,
        /** 网易云无匹配或无法取链/入库 */
        NOT_FOUND,
        /** API 未登录，高音质不可用（常因 Cookie 过期） */
        LOGIN_EXPIRED,
        /** 其它异常 */
        ERROR,
        /** 运行目录磁盘可用空间不足 */
        LOW_DISK_SPACE
    }

    public record FillAttempt(Optional<AdminMusicIngestService.IngestedMusic> music, FillReason reason) {
        public static FillAttempt skipped() {
            return new FillAttempt(Optional.empty(), FillReason.NONE);
        }

        public static FillAttempt nonMusic() {
            return new FillAttempt(Optional.empty(), FillReason.NON_MUSIC);
        }
    }

    public record FillRequest(String title, String artist) {}

    public FillAttempt tryFillFromNetease(String query) {
        if (query == null || query.isBlank()) {
            return FillAttempt.skipped();
        }
        return tryFillFromNetease(query.trim(), null);
    }

    /**
     * 按歌名与可选歌手从网易云补全一首（用于批量精确搜索等场景）。
     */
    public FillAttempt tryFillFromNetease(String title, String artist) {
        boolean neteaseEnabled = config.isNeteaseSearchFillEnabled();
        // 网易云与 QQ 都关掉时整条外站补全链才跳过；只关网易云时仍可走 QQ 兜底（见下）。
        if (!neteaseEnabled && !config.isQqSearchFillEnabled()) {
            return FillAttempt.skipped();
        }
        if (title == null || title.isBlank()) {
            return FillAttempt.skipped();
        }
        String trimmedTitle = title.trim();
        String trimmedArtist = artist == null ? "" : artist.trim();
        Optional<String> nonMusic = NonMusicContentFilter.match(trimmedTitle, trimmedArtist, "");
        if (nonMusic.isPresent()) {
            logger.info("跳过非音乐补全: title={} artist={} reason={}",
                    trimmedTitle, trimmedArtist, nonMusic.get());
            return FillAttempt.nonMusic();
        }
        if (!RuntimeDiskGuard.hasSufficientSpaceForMusicWrites()) {
            return new FillAttempt(Optional.empty(), FillReason.LOW_DISK_SPACE);
        }
        String lockKey = canonicalIngestLockKey(trimmedTitle, trimmedArtist);
        ReentrantLock localLock = QUERY_LOCKS.forKey(lockKey);

        localLock.lock();
        try {
            Optional<AdminMusicIngestService.IngestedMusic> existing =
                    findLocalBeforeNetease(trimmedTitle, trimmedArtist);
            if (existing.isPresent()) {
                return new FillAttempt(existing, FillReason.NONE);
            }

            boolean redisHeld = tryAcquireRedisLock(lockKey);
            if (!redisHeld) {
                logger.info("外站补全已有其它实例/请求在处理: title={} artist={}",
                        trimmedTitle, trimmedArtist);
                return waitForPeerIngest(trimmedTitle, trimmedArtist);
            }

            try {
                existing = findLocalBeforeNetease(trimmedTitle, trimmedArtist);
                if (existing.isPresent()) {
                    return new FillAttempt(existing, FillReason.NONE);
                }
                if (!neteaseEnabled) {
                    // 管理端关闭了网易云补全：不请求网易云，直接给 QQ 音乐一次兜底机会。
                    logger.info("网易云补全已关闭，直接尝试 QQ 音乐兜底: title={} artist={}",
                            trimmedTitle, trimmedArtist);
                    return fallbackToQq(trimmedTitle, trimmedArtist,
                            new FillAttempt(Optional.empty(), FillReason.NOT_FOUND));
                }
                return doFillFromNetease(trimmedTitle, trimmedArtist);
            } finally {
                releaseRedisLock(lockKey);
            }
        } catch (SQLException e) {
            logger.error("查询本地曲库失败 title={} artist={}", trimmedTitle, trimmedArtist, e);
            return new FillAttempt(Optional.empty(), FillReason.ERROR);
        } finally {
            localLock.unlock();
        }
    }

    public List<FillAttempt> tryFillBatchFromNetease(List<FillRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<Future<FillAttempt>> futures = new ArrayList<>(requests.size());
        for (FillRequest request : requests) {
            futures.add(batchFillExecutor.submit(() -> {
                if (request == null) {
                    return FillAttempt.skipped();
                }
                return tryFillFromNetease(request.title(), request.artist());
            }));
        }

        List<FillAttempt> attempts = new ArrayList<>(requests.size());
        for (int i = 0; i < futures.size(); i++) {
            Future<FillAttempt> future = futures.get(i);
            try {
                attempts.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                while (attempts.size() < requests.size()) {
                    attempts.add(FillAttempt.skipped());
                }
                break;
            } catch (Exception e) {
                logger.error("网易云批量补全任务失败", e);
                attempts.add(new FillAttempt(Optional.empty(), FillReason.ERROR));
            }
        }
        return attempts;
    }

    /** 直接按网易云歌曲 ID 下载入库的结果（不经过站内搜索匹配）。 */
    public record ExactIngest(
            long neteaseSongId,
            String title,
            String artist,
            String album,
            Optional<AdminMusicIngestService.IngestedMusic> music,
            boolean alreadyExisted,
            FillReason reason
    ) {
        public boolean success() {
            return music.isPresent();
        }
    }

    /**
     * 按网易云歌曲 ID 直接下载并入库：元数据与音频直链全部取自网易云，
     * 不对站内曲库做搜索匹配（歌单导入使用）。同一 songId 并发调用会串行化。
     *
     * <p>入库归属固定为配置的补全专用账号，不会记到触发导入的用户名下。
     *
     * @param progressListener 下载进度回调，可为 null
     */
    public ExactIngest ingestExactFromNetease(
            long songId,
            NeteaseCloudMusicClient.DownloadProgressListener progressListener
    ) {
        NeteaseCloudMusicClient.SongDetail detail;
        try {
            detail = neteaseClient.fetchSongDetail(songId).orElse(null);
        } catch (IOException e) {
            logger.warn("获取网易云歌曲详情失败 songId={}: {}", songId, e.getMessage());
            return new ExactIngest(songId, "", "", "", Optional.empty(), false, FillReason.ERROR);
        }
        if (detail == null) {
            logger.info("网易云歌曲不存在 songId={}", songId);
            return new ExactIngest(songId, "", "", "", Optional.empty(), false, FillReason.NOT_FOUND);
        }
        String title = pickNonEmpty(detail.title(), "");
        String artist = pickNonEmpty(detail.artist(), "");
        String album = pickNonEmpty(detail.album(), "未知专辑");

        Optional<String> nonMusic = NonMusicContentFilter.match(title, artist, album);
        if (nonMusic.isPresent()) {
            logger.info("跳过非音乐网易云曲目 songId={} title={} artist={} album={} reason={}",
                    songId, title, artist, album, nonMusic.get());
            return new ExactIngest(songId, title, artist, album, Optional.empty(), false, FillReason.NON_MUSIC);
        }

        if (!config.isNeteaseSearchFillEnabled()) {
            logger.warn("网易云下载入库未启用（netease_search_fill.enabled=false）songId={}", songId);
            return new ExactIngest(songId, title, artist, album, Optional.empty(), false, FillReason.NOT_FOUND);
        }
        if (!RuntimeDiskGuard.hasSufficientSpaceForMusicWrites()) {
            return new ExactIngest(songId, title, artist, album, Optional.empty(), false, FillReason.LOW_DISK_SPACE);
        }

        Path workDir = null;
        ReentrantLock songLock = NETEASE_SONG_LOCKS.forKey(songId);
        songLock.lock();
        try {
            // 导入按完整歌名判重：同名不同版本（Live / Remix / Ver.）不能被并掉，否则会少曲目
            Optional<AdminMusicIngestService.IngestedMusic> existing =
                    ingestService.findSameTitleDuplicate(title, artist, album);
            if (existing.isPresent()) {
                return new ExactIngest(songId, title, artist, album, existing, true, FillReason.NONE);
            }

            RuntimeDiskGuard.logStorageForOperation(
                    "网易云歌单导入", "songId=" + songId + ", title=" + title);
            workDir = Files.createTempDirectory("neko-netease-import-");
            NeteaseCloudMusicClient.NeteaseSongCandidate candidate =
                    new NeteaseCloudMusicClient.NeteaseSongCandidate(
                            songId, title, artist, album, detail.durationMs());
            Optional<AdminMusicIngestService.IngestedMusic> ingested =
                    downloadAndIngestUnderLock(candidate, workDir, songId, progressListener, true);
            if (ingested.isPresent()) {
                return new ExactIngest(songId, title, artist, album, ingested, false, FillReason.NONE);
            }
            boolean loggedIn = probeLoginStateAndReport();
            return new ExactIngest(songId, title, artist, album, Optional.empty(), false, failReason(loggedIn).reason());
        } catch (Exception e) {
            logger.error("网易云歌曲下载入库失败 songId={} title={}", songId, title, e);
            return new ExactIngest(songId, title, artist, album, Optional.empty(), false, FillReason.ERROR);
        } finally {
            songLock.unlock();
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    public void shutdown() {
        batchFillExecutor.shutdown();
        try {
            if (!batchFillExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                batchFillExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            batchFillExecutor.shutdownNow();
        }
    }

    private FillAttempt doFillFromNetease(String reqTitle, String reqArtist) {
        boolean loggedIn = probeLoginStateAndReport();
        if (!loggedIn) {
            logger.warn("网易云 API 未登录或 Cookie 已失效（/login/status profile 为空），"
                    + "Hi-Res/无损可能不可用，将尝试降档或跳过");
        }

        Path workDir = null;
        try {
            List<NeteaseCloudMusicClient.NeteaseSongCandidate> candidates =
                    searchNeteaseCandidates(reqTitle, reqArtist);
            if (candidates.isEmpty()) {
                logger.info("网易云搜索无结果: title={} artist={}", reqTitle, reqArtist);
                return fallbackToQq(reqTitle, reqArtist, failReason(loggedIn));
            }

            candidates.removeIf(candidate -> {
                Optional<String> nonMusic = NonMusicContentFilter.match(
                        candidate.title(), candidate.artist(), candidate.album());
                if (nonMusic.isPresent()) {
                    logger.info("忽略非音乐网易云候选 id={} title={} artist={} album={} reason={}",
                            candidate.id(), candidate.title(), candidate.artist(), candidate.album(), nonMusic.get());
                    return true;
                }
                return false;
            });
            if (candidates.isEmpty()) {
                logger.info("网易云候选均命中非音乐黑名单: title={} artist={}", reqTitle, reqArtist);
                return FillAttempt.nonMusic();
            }

            candidates.sort(Comparator.comparingInt(c -> scoreCandidate(c, reqTitle, reqArtist)));

            RuntimeDiskGuard.logStorageForOperation(
                    "网易云自动补全",
                    "title=" + reqTitle + (reqArtist.isBlank() ? "" : ", artist=" + reqArtist));

            workDir = Files.createTempDirectory("neko-netease-fill-");
            for (NeteaseCloudMusicClient.NeteaseSongCandidate candidate : candidates) {
                String album = candidate.album() == null || candidate.album().isBlank()
                        ? "未知专辑"
                        : candidate.album();
                if (ingestService.isDuplicateMusic(candidate.title(), candidate.artist(), album)) {
                    Optional<AdminMusicIngestService.IngestedMusic> dup =
                            ingestService.findExistingDuplicate(candidate.title(), candidate.artist(), album);
                    if (dup.isPresent()) {
                        return new FillAttempt(dup, FillReason.NONE);
                    }
                    continue;
                }
                Optional<AdminMusicIngestService.IngestedMusic> ingested =
                        downloadAndIngest(candidate, workDir);
                if (ingested.isPresent()) {
                    return new FillAttempt(ingested, FillReason.NONE);
                }
            }
            logger.info("网易云候选均未能入库: title={} artist={}", reqTitle, reqArtist);
            return fallbackToQq(reqTitle, reqArtist, failReason(loggedIn));
        } catch (Exception e) {
            logger.error("网易云搜索补全失败 title={} artist={}", reqTitle, reqArtist, e);
            // 网易云整段异常（含登录失效 / 网络错误）也再给 QQ 一次机会，避免只差一步就放弃补全。
            return fallbackToQq(reqTitle, reqArtist, new FillAttempt(Optional.empty(), FillReason.ERROR));
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private FillAttempt waitForPeerIngest(String title, String artist) {
        long deadline = System.currentTimeMillis() + PEER_WAIT_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Optional<AdminMusicIngestService.IngestedMusic> existing = findLocalBeforeNetease(title, artist);
                if (existing.isPresent()) {
                    logger.info("等待其它补全完成后命中曲库: title={} artist={} id={}",
                            title, artist, existing.get().id());
                    return new FillAttempt(existing, FillReason.NONE);
                }
                Thread.sleep(PEER_WAIT_POLL_MS);
            } catch (SQLException e) {
                logger.error("等待补全时查询曲库失败 title={} artist={}", title, artist, e);
                return new FillAttempt(Optional.empty(), FillReason.ERROR);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FillAttempt.skipped();
            }
        }
        logger.warn("等待其它补全超时: title={} artist={}", title, artist);
        return FillAttempt.skipped();
    }

    private Optional<AdminMusicIngestService.IngestedMusic> findLocalBeforeNetease(String title, String artist)
            throws SQLException {
        Optional<AdminMusicIngestService.IngestedMusic> dup =
                ingestService.findExistingDuplicate(title, artist, "");
        if (dup.isPresent()) {
            return dup;
        }
        return ingestService.findBestLocalMatchForBatchItem(title, artist);
    }

    private List<NeteaseCloudMusicClient.NeteaseSongCandidate> searchNeteaseCandidates(
            String reqTitle,
            String reqArtist
    ) throws IOException {
        Map<Long, NeteaseCloudMusicClient.NeteaseSongCandidate> merged = new LinkedHashMap<>();
        for (String query : buildNeteaseSearchQueries(reqTitle, reqArtist)) {
            for (NeteaseCloudMusicClient.NeteaseSongCandidate c : neteaseClient.searchSongs(query, 12)) {
                merged.putIfAbsent(c.id(), c);
            }
            if (!merged.isEmpty() && merged.size() >= 5) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 网易云搜索词：优先「核心歌名 + 主歌手」，其次仅核心歌名，避免 feat/remix 长串导致无结果。
     */
    private static List<String> buildNeteaseSearchQueries(String title, String artist) {
        List<String> queries = new ArrayList<>();
        String fullTitle = title == null ? "" : title.trim();
        String coreTitle = BatchMusicMatchUtil.coreTitle(fullTitle);
        String fullArtist = artist == null ? "" : artist.trim();
        List<String> artistTokens = BatchMusicMatchUtil.artistTokens(fullArtist);
        String primaryArtist = artistTokens.isEmpty() ? "" : artistTokens.get(0);

        if (!coreTitle.isBlank() && !primaryArtist.isBlank()) {
            queries.add(coreTitle + " " + primaryArtist);
        }
        if (!coreTitle.isBlank()) {
            queries.add(coreTitle);
        }
        if (!fullTitle.isBlank() && !fullTitle.equals(coreTitle) && !primaryArtist.isBlank()) {
            queries.add(fullTitle + " " + primaryArtist);
        }
        if (!fullTitle.isBlank() && primaryArtist.isBlank()) {
            queries.add(fullTitle);
        }
        return queries.stream().distinct().toList();
    }

    /** 锁键按核心歌名 + 主歌手，使不同搜索词命中同一曲时互斥 */
    private static String canonicalIngestLockKey(String title, String artist) {
        String core = BatchMusicMatchUtil.coreTitle(title == null ? "" : title.trim());
        String normTitle = ChineseConverter.toSimplified(core).toLowerCase(Locale.ROOT);
        List<String> tokens = BatchMusicMatchUtil.artistTokens(artist == null ? "" : artist.trim());
        String primary = tokens.isEmpty() ? "" : tokens.get(0);
        return lockKeyForQuery(normTitle + "\0" + primary);
    }

    private boolean tryAcquireRedisLock(String lockKey) {
        if (redisService == null) {
            return true;
        }
        return redisService.setIfAbsentWithExpiry(redisLockKey(lockKey), "1", REDIS_LOCK_SECONDS);
    }

    private void releaseRedisLock(String lockKey) {
        if (redisService == null) {
            return;
        }
        redisService.del(redisLockKey(lockKey));
    }

    private static String redisLockKey(String lockKey) {
        return "neko:netease_fill:lock:" + lockKey;
    }

    private static String lockKeyForQuery(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return normalized;
        }
    }

    private Optional<AdminMusicIngestService.IngestedMusic> downloadAndIngest(
            NeteaseCloudMusicClient.NeteaseSongCandidate candidate,
            Path workDir
    ) throws IOException, SQLException {
        long songId = candidate.id();
        ReentrantLock songLock = NETEASE_SONG_LOCKS.forKey(songId);
        songLock.lock();
        try {
            return downloadAndIngestUnderLock(candidate, workDir, songId, null, false);
        } finally {
            songLock.unlock();
        }
    }

    /**
     * @param exactTitleOnly 是否只按「规范化后完整歌名一致」判重。歌单导入（按歌曲 ID 精确下载）传
     *                       {@code true}：同名不同版本必须各自入库，否则导入曲目数会少于来源歌单；
     *                       站内搜索补全传 {@code false}，保留原有的宽松合并，避免堆同曲重复记录。
     */
    private Optional<AdminMusicIngestService.IngestedMusic> downloadAndIngestUnderLock(
            NeteaseCloudMusicClient.NeteaseSongCandidate candidate,
            Path workDir,
            long songId,
            NeteaseCloudMusicClient.DownloadProgressListener progressListener,
            boolean exactTitleOnly
    ) throws IOException, SQLException {
        NeteaseCloudMusicClient.SongDetail detail = neteaseClient.fetchSongDetail(songId)
                .orElse(new NeteaseCloudMusicClient.SongDetail(
                        candidate.title(),
                        candidate.artist(),
                        candidate.album(),
                        "",
                        0
                ));

        String title = pickNonEmpty(detail.title(), candidate.title());
        String artist = pickNonEmpty(detail.artist(), candidate.artist());
        String album = pickNonEmpty(detail.album(), candidate.album());
        if (album.isBlank()) {
            album = "未知专辑";
        }

        Optional<String> nonMusic = NonMusicContentFilter.match(title, artist, album);
        if (nonMusic.isPresent()) {
            logger.info("跳过非音乐网易云曲目 songId={} title={} artist={} album={} reason={}",
                    songId, title, artist, album, nonMusic.get());
            return Optional.empty();
        }

        Optional<AdminMusicIngestService.IngestedMusic> existing = findDuplicateByMode(
                title, artist, album, exactTitleOnly);
        if (existing.isPresent()) {
            return existing;
        }

        NeteaseCloudMusicClient.SongPlayUrl playUrl = neteaseClient
                .resolvePlayUrl(songId, config.getNeteaseQuality())
                .orElse(null);
        if (playUrl == null || playUrl.url().isBlank()) {
            logger.warn("无法获取播放链接 songId={} title={}", songId, title);
            return Optional.empty();
        }

        String audioExt = normalizeAudioExt(playUrl.type());
        Path audioTemp = workDir.resolve("audio." + audioExt);
        neteaseClient.downloadToFile(playUrl.url(), audioTemp, progressListener);

        LyricsPrepareResult lyricsPrep = prepareLyricsFile(workDir, songId, title, artist);

        Path coverTemp = null;
        if (detail.coverUrl() != null && !detail.coverUrl().isBlank()) {
            coverTemp = workDir.resolve("cover.jpg");
            try {
                neteaseClient.downloadToFile(detail.coverUrl(), coverTemp);
            } catch (IOException e) {
                logger.warn("封面下载失败 songId={}: {}", songId, e.getMessage());
                Files.deleteIfExists(coverTemp);
                coverTemp = null;
            }
        }

        int durationSec = detail.durationMs() > 0
                ? detail.durationMs() / 1000
                : (playUrl.durationMs() > 0 ? playUrl.durationMs() / 1000 : 0);

        String language = resolveLanguage(title, artist, album, lyricsPrep.lyricsPath());
        logger.info("网易云补全语种: title={} language={}", title, language);

        Optional<AdminMusicIngestService.IngestedMusic> ingested = ingestService.ingestFromTempFiles(
                audioTemp,
                coverTemp,
                lyricsPrep.lyricsPath(),
                title,
                artist,
                album,
                language,
                "",
                durationSec,
                config.getNeteaseFillUploadUserId(),
                exactTitleOnly
        );
        if (ingested.isPresent()) {
            int ingestedMusicId = ingested.get().id();
            saveRichLyricsForNewMusic(ingestedMusicId, lyricsPrep.rich());
            lyricsPrep.invalidLyricsAlert().ifPresent(alert ->
                    {
                        Main.getEmailService().scheduleNeteaseInvalidLyricsAlertToAdmins(
                                alert.neteaseSongId(),
                                ingestedMusicId,
                                alert.title(),
                                alert.artist(),
                                alert.fullOriginalLyrics(),
                                alert.reason());
                        Main.getNotificationService().scheduleNeteaseInvalidLyricsNotification(
                                alert.neteaseSongId(),
                                ingestedMusicId,
                                alert.title(),
                                alert.artist(),
                                alert.fullOriginalLyrics(),
                                alert.reason());
                    });
            return ingested;
        }
        // 旧曲只返回已有记录，不回写歌词；旧歌歌词升级功能已移除。
        return findDuplicateByMode(title, artist, album, exactTitleOnly);
    }

    /** 按调用方要求的判重口径查曲库重复（导入用严格完整歌名，补全用宽松合并）。 */
    private Optional<AdminMusicIngestService.IngestedMusic> findDuplicateByMode(
            String title, String artist, String album, boolean exactTitleOnly) throws SQLException {
        return exactTitleOnly
                ? ingestService.findSameTitleDuplicate(title, artist, album)
                : ingestService.findExistingDuplicate(title, artist, album);
    }

    // ───────────────────── QQ 音乐兜底补全（网易云无结果时的第二次拉取） ─────────────────────

    /**
     * 网易云无结果或候选均未能入库时的兜底：优先网易云，故这里只在网易云失败后调用一次。
     * QQ 补全成功则返回其结果；仍失败则保留网易云的失败原因（更贴近首站语义）。
     */
    private FillAttempt fallbackToQq(String reqTitle, String reqArtist, FillAttempt neteaseAttempt) {
        if (!config.isQqSearchFillEnabled()) {
            return neteaseAttempt;
        }
        logger.info("网易云无可用结果，尝试 QQ 音乐兜底补全: title={} artist={}", reqTitle, reqArtist);
        FillAttempt qqAttempt = doFillFromQq(reqTitle, reqArtist);
        return qqAttempt.music().isPresent() ? qqAttempt : neteaseAttempt;
    }

    /** 从 QQ 音乐搜索一首匹配曲并走管理员入库流程。 */
    private FillAttempt doFillFromQq(String reqTitle, String reqArtist) {
        if (!RuntimeDiskGuard.hasSufficientSpaceForMusicWrites()) {
            return new FillAttempt(Optional.empty(), FillReason.LOW_DISK_SPACE);
        }
        Path workDir = null;
        try {
            List<QQMusicClient.QqSongCandidate> candidates = searchQqCandidates(reqTitle, reqArtist);
            if (candidates.isEmpty()) {
                logger.info("QQ 音乐搜索无结果: title={} artist={}", reqTitle, reqArtist);
                return new FillAttempt(Optional.empty(), FillReason.NOT_FOUND);
            }

            candidates.removeIf(candidate -> {
                Optional<String> nonMusic = NonMusicContentFilter.match(
                        candidate.title(), candidate.artist(), candidate.album());
                if (nonMusic.isPresent()) {
                    logger.info("忽略非音乐 QQ 候选 mid={} title={} artist={} album={} reason={}",
                            candidate.mid(), candidate.title(), candidate.artist(), candidate.album(), nonMusic.get());
                    return true;
                }
                return false;
            });
            if (candidates.isEmpty()) {
                logger.info("QQ 候选均命中非音乐黑名单: title={} artist={}", reqTitle, reqArtist);
                return FillAttempt.nonMusic();
            }
            candidates.sort(Comparator.comparingInt(c -> scoreQqCandidate(c, reqTitle, reqArtist)));

            RuntimeDiskGuard.logStorageForOperation(
                    "QQ 音乐自动补全",
                    "title=" + reqTitle + (reqArtist.isBlank() ? "" : ", artist=" + reqArtist));

            workDir = Files.createTempDirectory("neko-qq-fill-");
            for (QQMusicClient.QqSongCandidate candidate : candidates) {
                String album = candidate.album() == null || candidate.album().isBlank()
                        ? "未知专辑"
                        : candidate.album();
                if (ingestService.isDuplicateMusic(candidate.title(), candidate.artist(), album)) {
                    Optional<AdminMusicIngestService.IngestedMusic> dup =
                            ingestService.findExistingDuplicate(candidate.title(), candidate.artist(), album);
                    if (dup.isPresent()) {
                        return new FillAttempt(dup, FillReason.NONE);
                    }
                    continue;
                }
                Optional<AdminMusicIngestService.IngestedMusic> ingested;
                try {
                    ingested = downloadQqAndIngest(candidate, workDir);
                } catch (IOException e) {
                    // 单个候选下载失败不该拖垮整次补全：记一条并继续试下一个候选。
                    logger.warn("QQ 候选下载失败，跳过该候选 mid={} title={}: {}",
                            candidate.mid(), candidate.title(), e.getMessage());
                    continue;
                }
                if (ingested.isPresent()) {
                    return new FillAttempt(ingested, FillReason.NONE);
                }
            }
            logger.info("QQ 音乐候选均未能入库: title={} artist={}", reqTitle, reqArtist);
            // 有候选却全部下载 / 入库失败，可能是 Cookie 被风控导致付费曲退化为游客态：探测一次并上报。
            probeQqLoginStateAndReport();
            return new FillAttempt(Optional.empty(), FillReason.NOT_FOUND);
        } catch (Exception e) {
            logger.error("QQ 音乐搜索补全失败 title={} artist={}", reqTitle, reqArtist, e);
            return new FillAttempt(Optional.empty(), FillReason.ERROR);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** 复用网易云的搜索词构造（核心歌名 + 主歌手优先），对 QQ 音乐逐词搜索并去重。 */
    private List<QQMusicClient.QqSongCandidate> searchQqCandidates(String reqTitle, String reqArtist)
            throws IOException {
        Map<String, QQMusicClient.QqSongCandidate> merged = new LinkedHashMap<>();
        for (String query : buildNeteaseSearchQueries(reqTitle, reqArtist)) {
            for (QQMusicClient.QqSongCandidate c : qqClient.searchSongs(query, 12)) {
                merged.putIfAbsent(c.mid(), c);
            }
            if (!merged.isEmpty() && merged.size() >= 5) {
                break;
            }
        }
        return new ArrayList<>(merged.values());
    }

    private Optional<AdminMusicIngestService.IngestedMusic> downloadQqAndIngest(
            QQMusicClient.QqSongCandidate candidate,
            Path workDir
    ) throws IOException, SQLException {
        String mid = candidate.mid();
        ReentrantLock songLock = QQ_SONG_LOCKS.forKey(mid);
        songLock.lock();
        try {
            return downloadQqAndIngestUnderLock(candidate, workDir);
        } finally {
            songLock.unlock();
        }
    }

    /** QQ 曲目下载入库：取链 → 下载音频 → 占位歌词 + 封面 → 入库。 */
    private Optional<AdminMusicIngestService.IngestedMusic> downloadQqAndIngestUnderLock(
            QQMusicClient.QqSongCandidate candidate,
            Path workDir
    ) throws IOException, SQLException {
        String title = candidate.title();
        String artist = candidate.artist();
        String album = candidate.album() == null || candidate.album().isBlank()
                ? "未知专辑"
                : candidate.album();

        Optional<String> nonMusic = NonMusicContentFilter.match(title, artist, album);
        if (nonMusic.isPresent()) {
            logger.info("跳过非音乐 QQ 曲目 mid={} title={} artist={} album={} reason={}",
                    candidate.mid(), title, artist, album, nonMusic.get());
            return Optional.empty();
        }

        Optional<AdminMusicIngestService.IngestedMusic> existing =
                ingestService.findExistingDuplicate(title, artist, album);
        if (existing.isPresent()) {
            return existing;
        }

        List<QQMusicClient.QqPlayUrl> playUrls =
                qqClient.resolvePlayUrls(candidate.mid(), candidate.mediaMid(), config.getNeteaseQuality());
        if (playUrls.isEmpty()) {
            logger.warn("无法获取 QQ 播放链接 mid={} title={}", candidate.mid(), title);
            return Optional.empty();
        }
        // 按音质顺位逐个尝试：上游可能对无权档位下发「假直链」（CDN 404），逐档降级重试。
        Path audioTemp = null;
        IOException lastDownloadError = null;
        for (QQMusicClient.QqPlayUrl playUrl : playUrls) {
            String audioExt = "flac".equalsIgnoreCase(playUrl.type()) ? "flac" : "mp3";
            Path attempt = workDir.resolve("audio." + audioExt);
            try {
                qqClient.downloadToFile(playUrl.url(), attempt, null);
                audioTemp = attempt;
                break;
            } catch (IOException e) {
                lastDownloadError = e;
                logger.info("QQ 音质档位不可下载，降级重试 mid={} level={}: {}",
                        candidate.mid(), playUrl.level(), e.getMessage());
                Files.deleteIfExists(attempt);
            }
        }
        if (audioTemp == null) {
            throw lastDownloadError != null
                    ? lastDownloadError
                    : new IOException("QQ 音乐无可下载的音质档位");
        }

        Path lyricsTemp = workDir.resolve("lyrics.lrc");
        NekoLyrics.Document richLyrics = prepareQqLyricsFile(lyricsTemp, candidate);
        if (richLyrics == null) {
            // 拿不到歌词或未通过校验时回落占位歌词，保证入库流程完整。
            writePlaceholderLyrics(lyricsTemp);
        }

        Path coverTemp = null;
        if (candidate.coverUrl() != null && !candidate.coverUrl().isBlank()) {
            coverTemp = workDir.resolve("cover.jpg");
            try {
                qqClient.downloadToFile(candidate.coverUrl(), coverTemp, null);
            } catch (IOException e) {
                logger.warn("QQ 封面下载失败 mid={}: {}", candidate.mid(), e.getMessage());
                Files.deleteIfExists(coverTemp);
                coverTemp = null;
            }
        }

        int durationSec = candidate.durationMs() > 0 ? candidate.durationMs() / 1000 : 0;
        String language = resolveLanguage(title, artist, album, lyricsTemp);
        logger.info("QQ 音乐补全语种: title={} language={}", title, language);

        Optional<AdminMusicIngestService.IngestedMusic> ingested = ingestService.ingestFromTempFiles(
                audioTemp,
                coverTemp,
                lyricsTemp,
                title,
                artist,
                album,
                language,
                "",
                durationSec,
                config.getNeteaseFillUploadUserId(),
                false
        );
        if (ingested.isPresent()) {
            saveRichLyricsForNewMusic(ingested.get().id(), richLyrics);
            return ingested;
        }
        // 旧曲只返回已有记录，不回写歌词；旧歌歌词升级功能已移除。
        return ingestService.findExistingDuplicate(title, artist, album);
    }

    private static int scoreQqCandidate(
            QQMusicClient.QqSongCandidate c,
            String reqTitle,
            String reqArtist
    ) {
        int score = BatchMusicMatchUtil.scoreTitleContribution(reqTitle, c.title())
                + BatchMusicMatchUtil.scoreArtistContribution(reqArtist, c.artist());
        return score <= 0 ? 999 : -score;
    }

    private record InvalidLyricsAlert(
            long neteaseSongId, String title, String artist, String fullOriginalLyrics, String reason) {
    }

    private record LyricsPrepareResult(
            Path lyricsPath,
            Optional<InvalidLyricsAlert> invalidLyricsAlert,
            NekoLyrics.Document rich
    ) {
    }

    private String resolveLanguage(String title, String artist, String album, Path lyricsTemp) {
        String configured = config.getNeteaseFillLanguage();
        if (configured != null && !configured.isBlank()) {
            String c = configured.trim();
            if (!"auto".equalsIgnoreCase(c)) {
                return c;
            }
        }
        String lrcSample = "";
        try {
            if (Files.isRegularFile(lyricsTemp)) {
                lrcSample = Files.readString(lyricsTemp);
                if (lrcSample.length() > 4000) {
                    lrcSample = lrcSample.substring(0, 4000);
                }
            }
        } catch (IOException e) {
            logger.warn("读取歌词样本用于语种推断失败: {}", e.getMessage());
        }
        return SongLanguageInferer.infer(title, artist, album, lrcSample);
    }

    private LyricsPrepareResult prepareLyricsFile(Path workDir, long songId, String title, String artist) throws IOException {
        Path lyricsTemp = workDir.resolve("lyrics.lrc");
        Optional<InvalidLyricsAlert> invalidAlert = Optional.empty();
        String lrc = "";
        String fullOriginalLyrics = "";
        NekoLyrics.Document rich = null;
        try {
            NeteaseCloudMusicClient.LyricApiPayload payload = neteaseClient.fetchLyricPayload(songId);
            lrc = payload.primaryLrc();
            fullOriginalLyrics = payload.rawMain();
            rich = payload.rich();
        } catch (IOException e) {
            logger.warn("获取歌词失败 songId={}: {}", songId, e.getMessage());
        }

        if (!lrc.isBlank()) {
            lrc = LrcValidator.normalizeNeteaseTimestamps(lrc);
            Files.writeString(lyricsTemp, lrc);
            try (InputStream in = Files.newInputStream(lyricsTemp)) {
                long size = Files.size(lyricsTemp);
                LrcValidator.ValidationResult check = LrcValidator.validate(in, size);
                if (check.isValid()) {
                    return new LyricsPrepareResult(lyricsTemp, Optional.empty(), rich);
                }
                String reason = check.getErrorMessage();
                String lyricsForEmail = fullOriginalLyrics;
                logger.warn("网易云歌词未通过 LRC 校验 songId={} title={}: {}，将使用占位歌词",
                        songId, title, reason);
                logger.warn("网易云完整原始歌词 songId={}:\n{}", songId, lyricsForEmail);
                invalidAlert = Optional.of(new InvalidLyricsAlert(songId, title, artist, lyricsForEmail, reason));
            }
            Files.deleteIfExists(lyricsTemp);
        }

        writePlaceholderLyrics(lyricsTemp);
        return new LyricsPrepareResult(lyricsTemp, invalidAlert, null);
    }

    private void writePlaceholderLyrics(Path lyricsTemp) throws IOException {
        try (InputStream fallback = getClass().getClassLoader().getResourceAsStream("no_lrc.lrc")) {
            if (fallback == null) {
                Files.writeString(lyricsTemp, "[00:00.00]Neko歌姬计划 暂无歌词\n");
            } else {
                Files.copy(fallback, lyricsTemp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * 取 QQ 明文歌词写入 {@code lyricsTemp} 并通过 LRC 校验时返回 true；
     * 取不到、为空或校验不通过时返回 false（由调用方写占位歌词），不抛异常中断入库。
     */
    private NekoLyrics.Document prepareQqLyricsFile(
            Path lyricsTemp,
            QQMusicClient.QqSongCandidate candidate
    )
            throws IOException {
        NekoLyrics.Document rich;
        try {
            rich = qqClient.fetchNekoLyrics(candidate);
        } catch (IOException e) {
            logger.warn("获取 QQ 歌词失败 mid={} title={}: {}",
                    candidate.mid(), candidate.title(), e.getMessage());
            return null;
        }
        String lrc = NekoLyrics.toLrc(rich);
        if (lrc == null || lrc.isBlank()) {
            logger.info("QQ 歌词为空 mid={} title={}，使用占位歌词",
                    candidate.mid(), candidate.title());
            return null;
        }
        // 时间轴归一化（多时间戳展开 / 补毫秒）对 QQ 与网易云通用，直接复用。
        Files.writeString(lyricsTemp, LrcValidator.normalizeNeteaseTimestamps(lrc));
        try (InputStream in = Files.newInputStream(lyricsTemp)) {
            LrcValidator.ValidationResult check =
                    LrcValidator.validate(in, Files.size(lyricsTemp));
            if (check.isValid()) {
                return rich;
            }
            logger.warn("QQ 歌词未通过 LRC 校验 mid={} title={}: {}，使用占位歌词",
                    candidate.mid(), candidate.title(), check.getErrorMessage());
        }
        Files.deleteIfExists(lyricsTemp);
        return null;
    }

    private void saveRichLyricsForNewMusic(int musicId, NekoLyrics.Document rich) {
        if (musicId <= 0 || rich == null || rich.isEmpty()) {
            return;
        }
        try {
            String content = NekoLyrics.serialize(rich);
            if (!Main.getLyricsDatabaseManager().upsert(
                    musicId, content, NekoLyrics.FORMAT)) {
                logger.warn("保存 Neko 字级歌词失败 musicId={}", musicId);
                return;
            }
            SearchIndexService.notifyMusicChanged(musicId);
        } catch (Exception e) {
            logger.warn("保存 Neko 字级歌词异常 musicId={}: {}", musicId, e.getMessage());
        }
    }

    private FillAttempt failReason(boolean loggedIn) {
        if (!loggedIn && prefersHighQuality(config.getNeteaseQuality())) {
            return new FillAttempt(Optional.empty(), FillReason.LOGIN_EXPIRED);
        }
        return new FillAttempt(Optional.empty(), FillReason.NOT_FOUND);
    }

    /**
     * 探测一次登录态并把结果上报给 {@link NeteaseLoginMonitor}（事件驱动掉线告警，替代定时巡检）。
     *
     * <p>复用补全失败时本就要做的登录态探测，不额外增加请求；查询失败（网络异常）不判为掉线。</p>
     */
    private boolean probeLoginStateAndReport() {
        Optional<Boolean> probed = neteaseClient.probeLoginState();
        probed.ifPresent(state -> {
            NeteaseLoginMonitor monitor = Main.getNeteaseLoginMonitor();
            if (monitor != null) {
                monitor.reportLoginState(state);
            }
        });
        return probed.orElse(false);
    }

    /**
     * 探测一次 QQ 登录态并上报给 {@link QQLoginMonitor}（事件驱动掉线告警，与网易云同构）。
     *
     * <p>仅在 QQ 补全「有候选但全部未能入库」时调用：Cookie 失效会让付费曲退化为游客态、
     * 静默 NOT_FOUND，这个时机最贴近真实掉线。查询失败（网络异常）不判为掉线。</p>
     */
    private void probeQqLoginStateAndReport() {
        Optional<Boolean> probed = qqClient.probeLoginState();
        probed.ifPresent(state -> {
            QQLoginMonitor monitor = Main.getQqLoginMonitor();
            if (monitor != null) {
                monitor.reportLoginState(state);
            }
        });
    }

    private static boolean prefersHighQuality(String quality) {
        if (quality == null || quality.isBlank()) {
            return true;
        }
        String q = quality.trim().toLowerCase(Locale.ROOT);
        return switch (q) {
            case "hires", "lossless", "jymaster", "jyeffect", "sky" -> true;
            default -> false;
        };
    }

    private static int scoreCandidate(
            NeteaseCloudMusicClient.NeteaseSongCandidate c,
            String reqTitle,
            String reqArtist
    ) {
        int score = BatchMusicMatchUtil.scoreTitleContribution(reqTitle, c.title())
                + BatchMusicMatchUtil.scoreArtistContribution(reqArtist, c.artist());
        return score <= 0 ? 999 : -score;
    }

    private static String pickNonEmpty(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String normalizeAudioExt(String type) {
        if (type == null) {
            return "mp3";
        }
        String t = type.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "flac" -> "flac";
            case "wav" -> "wav";
            default -> "mp3";
        };
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            logger.warn("清理临时目录失败: {}", root, e);
        }
    }
}
