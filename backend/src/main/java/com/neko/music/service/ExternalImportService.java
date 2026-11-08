package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.model.Playlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 外部歌单导入：把网易云 / QQ / 酷狗 / 汽水 / 落雪歌单的曲目入库并加入用户指定的歌单，
 * SSE 进度由上层推送。
 *
 * <ul>
 *   <li>网易云：按歌曲 ID 直接下载原曲入库（不经过站内搜索匹配）。</li>
 *   <li>QQ / 酷狗 / 汽水 / 落雪：歌单只提供元数据，因此先在站内曲库匹配，匹配不到再按
 *       「歌名 + 歌手」从网易云补全下载。</li>
 * </ul>
 */
public class ExternalImportService {
    private static final Logger logger = LoggerFactory.getLogger(ExternalImportService.class);

    public static final String SOURCE_NETEASE = "netease";
    public static final String SOURCE_QQ = "qq";
    public static final String SOURCE_KUGOU = "kugou";
    public static final String SOURCE_QISHUI = "qishui";
    public static final String SOURCE_LXMC = "lxmc";

    public static final String STATUS_IMPORTED = "imported";
    public static final String STATUS_EXISTED = "existed";
    public static final String STATUS_FAILED = "failed";

    /** 站内消息类型：外部歌单导入结束（成功或失败）。 */
    private static final String NOTIFY_TYPE_PLAYLIST_IMPORT = "playlist_import";

    /** 曲目级并发数：单曲耗时主要在下载，并行处理可显著缩短整个歌单的导入时间。 */
    private static final int TRACK_CONCURRENCY = 4;

    /** 每个站内歌单一把锁，串行化「加入歌单」的 position 位移，避免并发插入冲突。 */
    private static final ConcurrentHashMap<Integer, ReentrantLock> PLAYLIST_LOCKS = new ConcurrentHashMap<>();

    private final NeteaseCloudMusicClient neteaseClient;
    private final NeteaseSearchFillService fillService;
    private final AdminMusicIngestService ingestService;
    private final PlaylistService playlistService;
    private final QQMusicClient qqMusicClient;
    private final KugouMusicClient kugouMusicClient;
    private final QishuiMusicClient qishuiMusicClient;
    private final UserNotificationService userNotificationService;

    private final ExecutorService executor;
    private final ExecutorService trackExecutor;

    public record Track(String sourceId, String title, String artist) {
    }

    public record TrackResult(int index, int total, String source, String sourceId, String title, String artist,
                              String status, Integer musicId, boolean playlistAdded, String message) {
    }

    public record Summary(int total, int imported, int existed, int failed) {
    }

    /** 导入目标与发起人：导入结束后据此给发起人写一条站内消息。 */
    public record ImportTarget(int userId, int playlistId, String playlistName, boolean created) {
    }

    /** 导入过程中的事件回调；并发处理时由 {@link SyncListener} 串行触发，实现无需关心线程安全。 */
    public interface Listener {
        void onStart(String source, int total, int targetPlaylistId, boolean targetPlaylistCreated);

        void onTrackStarted(TrackResult result);

        void onTrackProgress(int index, int total, String sourceId, long bytesRead, long totalBytes);

        void onTrackFinished(TrackResult result);

        void onComplete(Summary summary);

        void onError(String message);
    }

    @FunctionalInterface
    private interface ImportTask {
        void run() throws IOException;
    }

    public ExternalImportService(NeteaseCloudMusicClient neteaseClient,
                                 NeteaseSearchFillService fillService,
                                 AdminMusicIngestService ingestService,
                                 PlaylistService playlistService,
                                 QQMusicClient qqMusicClient,
                                 KugouMusicClient kugouMusicClient,
                                 QishuiMusicClient qishuiMusicClient,
                                 UserNotificationService userNotificationService) {
        this.neteaseClient = neteaseClient;
        this.fillService = fillService;
        this.ingestService = ingestService;
        this.playlistService = playlistService;
        this.qqMusicClient = qqMusicClient;
        this.kugouMusicClient = kugouMusicClient;
        this.qishuiMusicClient = qishuiMusicClient;
        this.userNotificationService = userNotificationService;
        AtomicInteger threadNo = new AtomicInteger();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "external-import-" + threadNo.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger trackNo = new AtomicInteger();
        this.trackExecutor = Executors.newFixedThreadPool(TRACK_CONCURRENCY, r -> {
            Thread thread = new Thread(r, "external-import-track-" + trackNo.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public QishuiMusicClient getQishuiMusicClient() {
        return qishuiMusicClient;
    }

    /** 网易云歌单 / 指定歌曲导入。 */
    public void startNeteaseImport(Long neteasePlaylistId, List<Long> songIds, ImportTarget target,
                                   Listener listener) {
        submit(target, () -> runNeteaseImport(neteasePlaylistId, songIds, target, listener), listener);
    }

    /** QQ 歌单导入。 */
    public void startQqImport(String disstid, ImportTarget target, Listener listener) {
        submit(target, () -> runQqImport(disstid, target, listener), listener);
    }

    /** 酷狗歌单导入。 */
    public void startKugouImport(String listId, ImportTarget target, Listener listener) {
        submit(target, () -> runKugouImport(listId, target, listener), listener);
    }

    /** 汽水音乐歌单导入。 */
    public void startQishuiImport(String playlistInput, ImportTarget target, Listener listener) {
        submit(target, () -> runQishuiImport(playlistInput, target, listener), listener);
    }

    /** 落雪音乐 .lxmc 文件中已解析出的单个歌单导入。 */
    public void startLxmcImport(List<Track> tracks, ImportTarget target, Listener listener) {
        submit(target, () -> runMatchedImport(SOURCE_LXMC, "落雪歌单为空或不可访问", tracks, target, listener),
                listener);
    }

    public void shutdown() {
        trackExecutor.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void submit(ImportTarget target, ImportTask task, Listener listener) {
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("外部歌单导入失败", e);
                    String message = "导入失败: " + rootMessage(e);
                    listener.onError(message);
                    notifyImportFailed(target, message);
                }
            });
        } catch (RejectedExecutionException e) {
            listener.onError("导入服务不可用");
            notifyImportFailed(target, "导入服务不可用");
        }
    }

    private void runNeteaseImport(Long neteasePlaylistId, List<Long> songIds, ImportTarget target,
                                  Listener listener) throws IOException {
        List<Track> tracks = resolveNeteaseTracks(neteasePlaylistId, songIds);
        if (tracks.isEmpty()) {
            String message = neteasePlaylistId != null ? "网易云歌单为空或不可访问" : "没有可导入的歌曲 ID";
            listener.onError(message);
            notifyImportFailed(target, message);
            return;
        }

        final int total = tracks.size();
        listener.onStart(SOURCE_NETEASE, total, target.playlistId(), target.created());
        executeTracks(SOURCE_NETEASE, target, tracks, listener, (index, track, trackListener) -> {
            trackListener.onTrackStarted(result(SOURCE_NETEASE, index, total, track,
                    "downloading", null, false, null));

            long songId = Long.parseLong(track.sourceId());
            NeteaseSearchFillService.ExactIngest ingest = fillService.ingestExactFromNetease(
                    songId,
                    (bytesRead, totalBytes) -> trackListener.onTrackProgress(
                            index, total, track.sourceId(), bytesRead, totalBytes));

            String title = firstNonBlank(ingest.title(), track.title());
            String artist = firstNonBlank(ingest.artist(), track.artist());
            Track resolved = new Track(track.sourceId(), title, artist);
            if (ingest.success()) {
                boolean existedAlready = ingest.alreadyExisted();
                return result(SOURCE_NETEASE, index, total, resolved,
                        existedAlready ? STATUS_EXISTED : STATUS_IMPORTED,
                        ingest.music().get().id(), false, null);
            }
            return result(SOURCE_NETEASE, index, total, resolved,
                    STATUS_FAILED, null, false, reasonMessage(ingest.reason()));
        });
    }

    private void runQqImport(String disstid, ImportTarget target, Listener listener) throws IOException {
        QQMusicClient.QqPlaylist playlist = qqMusicClient.fetchPlaylist(disstid);
        List<QQMusicClient.QqTrack> qqTracks = playlist.tracks();
        List<Track> tracks = new ArrayList<>(qqTracks.size());
        for (QQMusicClient.QqTrack qqTrack : qqTracks) {
            tracks.add(new Track(qqTrack.mid(), qqTrack.title(), qqTrack.artist()));
        }
        runMatchedImport(SOURCE_QQ, "QQ 歌单为空或不可访问", tracks, target, listener);
    }

    private void runKugouImport(String listId, ImportTarget target, Listener listener) throws IOException {
        KugouMusicClient.KugouPlaylist playlist = kugouMusicClient.fetchPlaylist(listId);
        List<KugouMusicClient.KugouTrack> kugouTracks = playlist.tracks();
        List<Track> tracks = new ArrayList<>(kugouTracks.size());
        for (KugouMusicClient.KugouTrack kugouTrack : kugouTracks) {
            tracks.add(new Track(kugouTrack.hash(), kugouTrack.title(), kugouTrack.artist()));
        }
        runMatchedImport(SOURCE_KUGOU, "酷狗歌单为空或不可访问", tracks, target, listener);
    }

    private void runQishuiImport(String playlistInput, ImportTarget target, Listener listener)
            throws IOException {
        QishuiMusicClient.QishuiPlaylist playlist = qishuiMusicClient.fetchPlaylist(playlistInput);
        List<Track> tracks = new ArrayList<>(playlist.tracks().size());
        for (QishuiMusicClient.QishuiTrack qishuiTrack : playlist.tracks()) {
            tracks.add(new Track(qishuiTrack.id(), qishuiTrack.title(), qishuiTrack.artist()));
        }
        runMatchedImport(SOURCE_QISHUI, "汽水歌单为空或不可访问", tracks, target, listener);
    }

    /** QQ / 酷狗等只提供元数据的歌单：先在站内曲库匹配，未命中再尝试网易云补全。 */
    private void runMatchedImport(String source, String emptyMessage, List<Track> tracks,
                                  ImportTarget target, Listener listener) {
        if (tracks.isEmpty()) {
            listener.onError(emptyMessage);
            notifyImportFailed(target, emptyMessage);
            return;
        }

        final int total = tracks.size();
        listener.onStart(source, total, target.playlistId(), target.created());
        executeTracks(source, target, tracks, listener, (index, track, trackListener) -> {
            trackListener.onTrackStarted(result(source, index, total, track,
                    "matching", null, false, null));

            Optional<AdminMusicIngestService.IngestedMusic> matched = matchLocal(track.title(), track.artist());
            if (matched.isPresent()) {
                return result(source, index, total, track,
                        STATUS_EXISTED, matched.get().id(), false, null);
            }

            NeteaseSearchFillService.FillAttempt attempt = fillService.tryFillFromNetease(track.title(), track.artist());
            if (attempt.music().isPresent()) {
                return result(source, index, total, track,
                        STATUS_IMPORTED, attempt.music().get().id(), false, null);
            }
            return result(source, index, total, track,
                    STATUS_FAILED, null, false, fillReasonMessage(attempt.reason()));
        });
    }

    @FunctionalInterface
    private interface TrackProcessor {
        /** 处理单首曲目并返回终态结果（status 为 imported / existed / failed）。 */
        TrackResult process(int index, Track track, Listener listener) throws Exception;
    }

    /**
     * 并发处理曲目；事件经 SyncListener 串行回调，全部结束后汇总并通知完成。
     *
     * <p>曲目并发处理导致完成顺序不定，加入歌单统一交给 {@link OrderedPlaylistAppender}
     * 按来源下标落库，保证导入后歌单顺序与来源歌单一致。</p>
     */
    private void executeTracks(String source, ImportTarget target, List<Track> tracks, Listener listener,
                               TrackProcessor processor) {
        final int total = tracks.size();
        Listener syncListener = new SyncListener(listener);
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger existed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // 歌单侧口径：真正加入目标歌单的、目标歌单里本来就有而跳过的、本次导入内重复而跳过的
        AtomicInteger playlistAdded = new AtomicInteger();
        AtomicInteger playlistSkipped = new AtomicInteger();
        AtomicInteger playlistDuplicated = new AtomicInteger();
        OrderedPlaylistAppender appender = new OrderedPlaylistAppender(target.playlistId(), total, syncListener,
                playlistAdded, playlistSkipped, playlistDuplicated);
        CountDownLatch latch = new CountDownLatch(total);

        for (int index = 0; index < total; index++) {
            final int currentIndex = index;
            final Track track = tracks.get(index);
            try {
                trackExecutor.execute(() -> {
                    try {
                        TrackResult result = processor.process(currentIndex, track, syncListener);
                        if (result != null) {
                            switch (result.status()) {
                                case STATUS_IMPORTED -> imported.incrementAndGet();
                                case STATUS_EXISTED -> existed.incrementAndGet();
                                default -> failed.incrementAndGet();
                            }
                            appender.submit(result);
                        }
                    } catch (Exception e) {
                        logger.warn("曲目导入失败 title={} artist={}: {}",
                                track.title(), track.artist(), rootMessage(e));
                        failed.incrementAndGet();
                        appender.submit(result(source, currentIndex, total, track,
                                STATUS_FAILED, null, false, rootMessage(e)));
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                failed.incrementAndGet();
                latch.countDown();
                appender.submit(result(source, currentIndex, total, track,
                        STATUS_FAILED, null, false, "导入服务已停止"));
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("{} 导入被中断，剩余曲目可能未处理", source);
        }
        appender.flushPending();
        Summary summary = new Summary(total, imported.get(), existed.get(), failed.get());
        syncListener.onComplete(summary);
        notifyImportFinished(source, target, playlistAdded.get(), playlistSkipped.get(),
                playlistDuplicated.get(), summary.failed());
    }

    /**
     * 按来源顺序把曲目追加到目标歌单末尾：先缓存各曲目的处理结果，只有「下一个待追加下标」
     * 就绪时才落库并回调，从而在并发处理下仍保持导入顺序与来源歌单一致。
     */
    private final class OrderedPlaylistAppender {
        private final int playlistId;
        private final int total;
        private final Listener listener;
        private final AtomicInteger addedCount;
        private final AtomicInteger skippedCount;
        private final AtomicInteger duplicatedCount;
        /** 本次导入已经加进目标歌单的 music id：用于区分「来源歌单内重复」与「歌单里本来就有」。 */
        private final Set<Integer> addedInThisRun = new HashSet<>();
        private final Map<Integer, TrackResult> pending = new HashMap<>();
        private int nextIndex;

        OrderedPlaylistAppender(int playlistId, int total, Listener listener,
                                AtomicInteger addedCount, AtomicInteger skippedCount,
                                AtomicInteger duplicatedCount) {
            this.playlistId = playlistId;
            this.total = total;
            this.listener = listener;
            this.addedCount = addedCount;
            this.skippedCount = skippedCount;
            this.duplicatedCount = duplicatedCount;
        }

        /** 登记一首曲目的结果；已轮到位则按顺序落库并回调，否则等待前序曲目。 */
        synchronized void submit(TrackResult trackResult) {
            pending.put(trackResult.index(), trackResult);
            while (pending.containsKey(nextIndex)) {
                TrackResult ready = pending.remove(nextIndex);
                listener.onTrackFinished(appendToPlaylist(ready));
                nextIndex++;
            }
        }

        /** 收尾：把仍未轮到的结果按顺序补录，避免中断场景下遗漏。 */
        synchronized void flushPending() {
            for (int index = nextIndex; index < total; index++) {
                TrackResult ready = pending.remove(index);
                if (ready != null) {
                    listener.onTrackFinished(appendToPlaylist(ready));
                }
            }
            nextIndex = total;
            pending.clear();
        }

        private TrackResult appendToPlaylist(TrackResult trackResult) {
            if (trackResult.musicId() == null) {
                // 失败的曲目与歌单无关，不计入歌单侧口径
                return trackResult;
            }
            boolean added = ExternalImportService.this.appendToPlaylist(playlistId, trackResult.musicId());
            if (added) {
                addedCount.incrementAndGet();
                addedInThisRun.add(trackResult.musicId());
            } else if (addedInThisRun.contains(trackResult.musicId())) {
                // 同一首歌在本次导入里又来了一次（源歌单重复，或两条源记录去重成同一条曲库记录）
                duplicatedCount.incrementAndGet();
                logger.info("曲目在本次导入中重复，跳过: playlistId={}, musicId={}, title={}, artist={}, source={}",
                        playlistId, trackResult.musicId(), trackResult.title(), trackResult.artist(),
                        trackResult.source());
            } else {
                skippedCount.incrementAndGet();
                logger.info("曲目已在目标歌单中，跳过: playlistId={}, musicId={}, title={}, artist={}, source={}",
                        playlistId, trackResult.musicId(), trackResult.title(), trackResult.artist(),
                        trackResult.source());
            }
            return new TrackResult(trackResult.index(), trackResult.total(), trackResult.source(),
                    trackResult.sourceId(), trackResult.title(), trackResult.artist(), trackResult.status(),
                    trackResult.musicId(), added, trackResult.message());
        }
    }

    /** 把回调串行化，保证 SSE 事件按顺序、无交错地写出。 */
    private static final class SyncListener implements Listener {
        private final Listener delegate;

        SyncListener(Listener delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void onStart(String source, int total, int targetPlaylistId,
                                         boolean targetPlaylistCreated) {
            delegate.onStart(source, total, targetPlaylistId, targetPlaylistCreated);
        }

        @Override
        public synchronized void onTrackStarted(TrackResult result) {
            delegate.onTrackStarted(result);
        }

        @Override
        public synchronized void onTrackProgress(int index, int total, String sourceId,
                                                 long bytesRead, long totalBytes) {
            delegate.onTrackProgress(index, total, sourceId, bytesRead, totalBytes);
        }

        @Override
        public synchronized void onTrackFinished(TrackResult result) {
            delegate.onTrackFinished(result);
        }

        @Override
        public synchronized void onComplete(Summary summary) {
            delegate.onComplete(summary);
        }

        @Override
        public synchronized void onError(String message) {
            delegate.onError(message);
        }
    }

    /**
     * 站内曲库匹配（QQ / 酷狗 / 汽水这类只给元数据的歌单）。
     *
     * <p>先按「规范化后完整歌名一致」精确匹配：同一歌单里的同名不同版本（`Live` / `Remix` / `Ver.`）
     * 必须各自对应曲库里不同的曲目，宽松合并会让它们落到同一条记录上、导进歌单就少曲目。</p>
     */
    private Optional<AdminMusicIngestService.IngestedMusic> matchLocal(String title, String artist) {
        try {
            Optional<AdminMusicIngestService.IngestedMusic> exact =
                    ingestService.findSameTitleDuplicate(title, artist, "");
            if (exact.isPresent()) {
                return exact;
            }
            return ingestService.findBestLocalMatchForBatchItem(title, artist);
        } catch (SQLException e) {
            logger.warn("站内曲库匹配失败 title={} artist={}: {}", title, artist, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 追加曲目到歌单末尾。这里必须用末尾追加：{@link PlaylistService#addMusicToPlaylist(int, int)}
     * 是插到首位，逐个调用会把顺序整体倒过来。
     */
    private boolean appendToPlaylist(int targetPlaylistId, int musicId) {
        ReentrantLock lock = PLAYLIST_LOCKS.computeIfAbsent(targetPlaylistId, k -> new ReentrantLock());
        lock.lock();
        try {
            return playlistService.appendMusicToPlaylist(targetPlaylistId, musicId);
        } catch (Exception e) {
            logger.warn("追加曲目到歌单失败 playlistId={} musicId={}: {}", targetPlaylistId, musicId, e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 解析待导入曲目：歌单模式走网易云歌单数据，IDs 模式直接用给定 ID。 */
    private List<Track> resolveNeteaseTracks(Long neteasePlaylistId, List<Long> songIds) throws IOException {
        List<Track> tracks = new ArrayList<>();
        if (neteasePlaylistId != null) {
            JsonNode response = neteaseClient.fetchPlaylistTrackAll(neteasePlaylistId, 0, 0);
            if (response.path("code").asInt(0) != 200) {
                throw new IOException("网易云歌单不存在或不可访问");
            }
            for (JsonNode song : response.path("songs")) {
                long id = song.path("id").asLong(0);
                if (id <= 0) {
                    continue;
                }
                tracks.add(new Track(Long.toString(id),
                        song.path("name").asText(""), joinArtists(song.path("ar"))));
            }
        } else if (songIds != null) {
            for (Long id : songIds) {
                if (id == null || id <= 0) {
                    continue;
                }
                tracks.add(new Track(Long.toString(id), "", ""));
            }
        }
        return tracks;
    }

    private static TrackResult result(String source, int index, int total, Track track,
                                      String status, Integer musicId, boolean playlistAdded, String message) {
        return new TrackResult(index, total, source, track.sourceId(), track.title(), track.artist(),
                status, musicId, playlistAdded, message);
    }

    private static String joinArtists(JsonNode artists) {
        if (!artists.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode artist : artists) {
            String name = artist.path("name").asText("").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    private static String reasonMessage(NeteaseSearchFillService.FillReason reason) {
        return switch (reason) {
            case LOW_DISK_SPACE -> "服务器磁盘空间不足";
            case LOGIN_EXPIRED -> "网易云 Cookie 失效或该音质不可用";
            case NON_MUSIC -> "内容不属于音乐，已跳过";
            case NOT_FOUND -> "网易云无可用音频（可能无版权或需会员）";
            default -> "下载或入库失败";
        };
    }

    private static String fillReasonMessage(NeteaseSearchFillService.FillReason reason) {
        if (reason == NeteaseSearchFillService.FillReason.NONE) {
            return "站内与网易云均未匹配到可下载曲目";
        }
        return reasonMessage(reason);
    }

    /**
     * 导入结束后给发起人写一条站内消息：在线时由 {@code /api/user/notifications/stream}
     * 实时推送，离线时下次打开消息中心按游标补拉即可看到。
     *
     * <p>正文只报歌单侧口径（加入成功 / 目标歌单里本来就有 / 本次导入内重复 / 失败）。曲目处理状态
     * 里的「站内曲库已有」是另一回事：那些曲目本次不重新下载，但照样会加进歌单，不能当成
     * 「你的歌单里已经有了」报给用户。</p>
     */
    private void notifyImportFinished(String source, ImportTarget target, int playlistAdded,
                                      int playlistSkipped, int playlistDuplicated, int failed) {
        StringBuilder body = new StringBuilder(sourceDisplayName(source)).append("歌单：成功加入 ")
                .append(playlistAdded).append(" 首");
        if (playlistSkipped > 0) {
            body.append("，已在你的歌单中 ").append(playlistSkipped).append(" 首");
        }
        if (playlistDuplicated > 0) {
            body.append("，重复 ").append(playlistDuplicated).append(" 首（同一首歌站内歌单只保留一条）");
        }
        if (failed > 0) {
            body.append("，失败 ").append(failed).append(" 首");
        }
        String playlistName = resolvePlaylistName(target);
        String title = playlistName == null || playlistName.isBlank()
                ? "歌单导入完成"
                : "《" + playlistName + "》导入完成";
        sendImportNotification(target, title, body.toString());
    }

    private void notifyImportFailed(ImportTarget target, String message) {
        sendImportNotification(target, "歌单导入失败",
                message == null || message.isBlank() ? "导入未完成" : message);
    }

    /** 站内消息只是导入的附带提醒：写失败只记日志，不能影响导入本身的结果。 */
    private void sendImportNotification(ImportTarget target, String title, String body) {
        if (userNotificationService == null || target == null) {
            return;
        }
        try {
            int created = userNotificationService.notify(target.userId(), NOTIFY_TYPE_PLAYLIST_IMPORT,
                    title, body, "/playlist/" + target.playlistId(), null);
            if (created <= 0) {
                logger.warn("导入站内消息写入失败: userId={}, playlistId={}",
                        target.userId(), target.playlistId());
            }
        } catch (Exception e) {
            logger.error("导入站内消息异常: userId={}, playlistId={}",
                    target.userId(), target.playlistId(), e);
        }
    }

    /** 目标歌单名：新建导入时已知；导入到已有歌单时按 id 现查一次，查不到就退回通用标题。 */
    private String resolvePlaylistName(ImportTarget target) {
        if (target.playlistName() != null && !target.playlistName().isBlank()) {
            return target.playlistName().trim();
        }
        try {
            return playlistService.getPlaylistById(target.playlistId()).map(Playlist::name).orElse(null);
        } catch (Exception e) {
            logger.warn("查询导入目标歌单名失败: playlistId={}, {}", target.playlistId(), e.getMessage());
            return null;
        }
    }

    private static String sourceDisplayName(String source) {
        return switch (source) {
            case SOURCE_NETEASE -> "网易云";
            case SOURCE_QQ -> "QQ 音乐";
            case SOURCE_KUGOU -> "酷狗音乐";
            case SOURCE_QISHUI -> "汽水音乐";
            case SOURCE_LXMC -> "落雪";
            default -> "外部";
        };
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
