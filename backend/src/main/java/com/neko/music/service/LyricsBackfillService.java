package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.database.MusicExternalRefDatabaseManager;
import com.neko.music.util.BatchMusicMatchUtil;
import com.neko.music.util.NekoLyrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 历史歌词后台迁移：把可匹配到的网易云 YRC / QQ QRC 尽量升级为 {@code neko-v1}。
 *
 * <p>只处理已有歌词行的外站来源或占位歌词，跳过用户上传与后台手工编辑。
 * 单线程、限速执行，避免把外站接口打出风控。</p>
 */
public class LyricsBackfillService {
    private static final Logger logger = LoggerFactory.getLogger(LyricsBackfillService.class);
    private static final long REQUEST_INTERVAL_MS = 250;
    private static final int SEARCH_LIMIT = 10;
    private static final int DURATION_TOLERANCE_MS = 15_000;
    private static final Set<String> PROTECTED_SOURCES = Set.of(
            "admin", "manual", "user_upload");

    private final ConfigManager config;
    private final NeteaseCloudMusicClient neteaseClient;
    private final QQMusicClient qqClient;
    private final LyricsDatabaseManager lyricsDatabaseManager;
    private final MusicExternalRefDatabaseManager externalRefDatabaseManager;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Status status = Status.idle();

    public LyricsBackfillService(
            ConfigManager config,
            NeteaseCloudMusicClient neteaseClient,
            QQMusicClient qqClient,
            LyricsDatabaseManager lyricsDatabaseManager,
            MusicExternalRefDatabaseManager externalRefDatabaseManager
    ) {
        this.config = config;
        this.neteaseClient = neteaseClient;
        this.qqClient = qqClient;
        this.lyricsDatabaseManager = lyricsDatabaseManager;
        this.externalRefDatabaseManager = externalRefDatabaseManager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lyrics-backfill");
            thread.setDaemon(true);
            return thread;
        });
    }

    public record Status(
            boolean running,
            boolean cancelled,
            String state,
            int total,
            int processed,
            int migrated,
            int skipped,
            int failed,
            int currentMusicId,
            String currentTitle,
            String startedAt,
            String finishedAt,
            String message
    ) {
        static Status idle() {
            return new Status(false, false, "idle", 0, 0, 0, 0, 0,
                    0, "", null, null, "");
        }
    }

    record MusicLyricsRow(
            int musicId,
            String title,
            String artist,
            String album,
            int durationMs,
            String format,
            String source,
            boolean placeholder
    ) {
    }

    public synchronized boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelled.set(false);
        status = new Status(true, false, "running", 0, 0, 0, 0, 0,
                0, "", Instant.now().toString(), null, "");
        executor.submit(this::runBackfill);
        return true;
    }

    public boolean cancel() {
        if (!running.get()) {
            return false;
        }
        cancelled.set(true);
        status = copyStatus(status, true, "cancelling", "正在停止迁移");
        return true;
    }

    public Status status() {
        return status;
    }

    public void shutdown() {
        cancelled.set(true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runBackfill() {
        int total = 0;
        int processed = 0;
        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<MusicLyricsRow> rows = loadCandidates();
            total = rows.size();
            status = new Status(true, false, "running", total, 0, 0, 0, 0,
                    0, "", status.startedAt(), null, "");

            for (MusicLyricsRow row : rows) {
                if (cancelled.get()) {
                    status = new Status(false, true, "cancelled", total, processed,
                            migrated, skipped, failed, 0, "", status.startedAt(),
                            Instant.now().toString(), "迁移已取消");
                    return;
                }
                status = new Status(true, false, "running", total, processed,
                        migrated, skipped, failed, row.musicId(), row.title(),
                        status.startedAt(), null, "");

                Outcome outcome = migrateOne(row);
                processed++;
                switch (outcome) {
                    case MIGRATED -> migrated++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }
                status = new Status(true, false, "running", total, processed,
                        migrated, skipped, failed, row.musicId(), row.title(),
                        status.startedAt(), null, "");
                if (outcome != Outcome.SKIPPED) {
                    sleepQuietly();
                }
            }
            status = new Status(false, false, "completed", total, processed,
                    migrated, skipped, failed, 0, "", status.startedAt(),
                    Instant.now().toString(), "迁移完成");
        } catch (Exception e) {
            logger.error("历史歌词逐字迁移失败", e);
            status = new Status(false, false, "failed", total, processed,
                    migrated, skipped, failed, 0, "", status.startedAt(),
                    Instant.now().toString(), e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private enum Outcome {
        MIGRATED,
        SKIPPED,
        FAILED
    }

    private Outcome migrateOne(MusicLyricsRow row) {
        if (row == null || row.musicId() <= 0 || NekoLyrics.FORMAT.equalsIgnoreCase(row.format())) {
            return Outcome.SKIPPED;
        }
        if (isProtectedSource(row.source())) {
            return Outcome.SKIPPED;
        }

        Map<String, MusicExternalRefDatabaseManager.ExternalRef> refs =
                externalRefDatabaseManager.findByMusicId(row.musicId());
        try {
            Optional<NekoLyrics.Document> netease = migrateFromNetease(row, refs.get("netease"));
            if (netease.isPresent()) {
                save(row, netease.get(), "netease_backfill");
                return Outcome.MIGRATED;
            }
            Optional<NekoLyrics.Document> qq = migrateFromQq(row, refs.get("qq"));
            if (qq.isPresent()) {
                save(row, qq.get(), "qq_backfill");
                return Outcome.MIGRATED;
            }
            return Outcome.FAILED;
        } catch (Exception e) {
            logger.debug("逐字回填单曲失败 musicId={} title={}: {}",
                    row.musicId(), row.title(), e.getMessage());
            return Outcome.FAILED;
        }
    }

    private Optional<NekoLyrics.Document> migrateFromNetease(
            MusicLyricsRow row,
            MusicExternalRefDatabaseManager.ExternalRef ref
    ) throws Exception {
        if (!config.isNeteaseSearchFillEnabled()) {
            return Optional.empty();
        }
        long songId = ref == null ? 0 : parseLong(ref.externalId());
        if (songId <= 0) {
            String keyword = searchKeyword(row);
            List<NeteaseCloudMusicClient.NeteaseSongCandidate> candidates =
                    neteaseClient.searchSongs(keyword, SEARCH_LIMIT);
            NeteaseCloudMusicClient.NeteaseSongCandidate best = pickNetease(candidates, row);
            if (best == null) {
                return Optional.empty();
            }
            songId = best.id();
        }
        NeteaseCloudMusicClient.LyricApiPayload payload = neteaseClient.fetchLyricPayload(songId);
        NekoLyrics.Document document = payload.rich();
        if (document == null || document.isEmpty()) {
            return Optional.empty();
        }
        externalRefDatabaseManager.upsert(
                row.musicId(), "netease", Long.toString(songId), null,
                row.title(), row.artist(), row.album(), row.durationMs());
        return Optional.of(document);
    }

    private Optional<NekoLyrics.Document> migrateFromQq(
            MusicLyricsRow row,
            MusicExternalRefDatabaseManager.ExternalRef ref
    ) throws Exception {
        if (!config.isQqSearchFillEnabled()) {
            return Optional.empty();
        }
        QQMusicClient.QqSongCandidate candidate = null;
        if (ref != null && ref.externalId() != null && !ref.externalId().isBlank()) {
            long songId = parseLong(ref.secondaryId());
            candidate = new QQMusicClient.QqSongCandidate(
                    songId,
                    ref.externalId(),
                    "",
                    firstNonBlank(ref.title(), row.title()),
                    firstNonBlank(ref.artist(), row.artist()),
                    firstNonBlank(ref.album(), row.album()),
                    "",
                    ref.durationMs() > 0 ? ref.durationMs() : row.durationMs());
        }
        if (candidate == null || candidate.songId() <= 0) {
            List<QQMusicClient.QqSongCandidate> candidates =
                    qqClient.searchSongs(searchKeyword(row), SEARCH_LIMIT);
            candidate = pickQq(candidates, row);
        }
        if (candidate == null) {
            return Optional.empty();
        }
        NekoLyrics.Document document = qqClient.fetchNekoLyrics(candidate);
        if (document == null || document.isEmpty()) {
            return Optional.empty();
        }
        externalRefDatabaseManager.upsert(
                row.musicId(), "qq", candidate.mid(),
                candidate.songId() > 0 ? Long.toString(candidate.songId()) : null,
                candidate.title(), candidate.artist(), candidate.album(), candidate.durationMs());
        return Optional.of(document);
    }

    private void save(MusicLyricsRow row, NekoLyrics.Document document, String source) {
        String content = NekoLyrics.serialize(document);
        if (!lyricsDatabaseManager.upsert(row.musicId(), content, NekoLyrics.FORMAT, source)) {
            throw new IllegalStateException("写入 Neko 歌词失败");
        }
        if (Main.getLyricsSearchIndex() != null) {
            Main.getLyricsSearchIndex().rebuildOne(row.musicId());
        }
        logger.info("历史歌词已升级为逐字歌词 musicId={} title={} source={}",
                row.musicId(), row.title(), source);
    }

    private List<MusicLyricsRow> loadCandidates() throws SQLException {
        String sql = """
                SELECT m.id, m.title, m.artist, m.album, m.duration,
                       ml.format, ml.source, ml.is_placeholder
                FROM music m
                JOIN music_lyrics ml ON ml.music_id = m.id
                WHERE COALESCE(ml.format, 'lrc') <> ?
                  AND COALESCE(ml.source, '') NOT IN ('admin', 'manual', 'user_upload')
                ORDER BY m.id
                """;
        List<MusicLyricsRow> rows = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, NekoLyrics.FORMAT);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MusicLyricsRow(
                            rs.getInt("id"),
                            firstNonBlank(rs.getString("title"), ""),
                            firstNonBlank(rs.getString("artist"), ""),
                            firstNonBlank(rs.getString("album"), ""),
                            Math.max(0, rs.getInt("duration")) * 1000,
                            firstNonBlank(rs.getString("format"), "lrc"),
                            firstNonBlank(rs.getString("source"), ""),
                            rs.getBoolean("is_placeholder")
                    ));
                }
            }
        }
        return rows;
    }

    static NeteaseCloudMusicClient.NeteaseSongCandidate pickNetease(
            List<NeteaseCloudMusicClient.NeteaseSongCandidate> candidates,
            MusicLyricsRow row
    ) {
        NeteaseCloudMusicClient.NeteaseSongCandidate best = null;
        int bestScore = 0;
        for (var candidate : candidates) {
            int score = scoreCandidate(row.title(), row.artist(), row.album(), row.durationMs(),
                    candidate.title(), candidate.artist(), candidate.album(), candidate.durationMs());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    static QQMusicClient.QqSongCandidate pickQq(
            List<QQMusicClient.QqSongCandidate> candidates,
            MusicLyricsRow row
    ) {
        QQMusicClient.QqSongCandidate best = null;
        int bestScore = 0;
        for (var candidate : candidates) {
            int score = scoreCandidate(row.title(), row.artist(), row.album(), row.durationMs(),
                    candidate.title(), candidate.artist(), candidate.album(), candidate.durationMs());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int scoreCandidate(
            String reqTitle, String reqArtist, String reqAlbum, int reqDurationMs,
            String candidateTitle, String candidateArtist, String candidateAlbum, int candidateDurationMs
    ) {
        int title = BatchMusicMatchUtil.scoreTitleContribution(reqTitle, candidateTitle);
        if (title < 100) {
            return 0;
        }
        if (reqArtist != null && !reqArtist.isBlank()
                && !BatchMusicMatchUtil.artistsRelate(reqArtist, candidateArtist)) {
            return 0;
        }
        if (reqDurationMs > 0 && candidateDurationMs > 0
                && Math.abs(reqDurationMs - candidateDurationMs) > DURATION_TOLERANCE_MS) {
            return 0;
        }
        int score = title * 10;
        if (reqArtist != null && !reqArtist.isBlank()) {
            score += Math.max(0, BatchMusicMatchUtil.scoreArtistContribution(reqArtist, candidateArtist));
        }
        if (reqAlbum != null && !reqAlbum.isBlank() && candidateAlbum != null
                && BatchMusicMatchUtil.normalize(reqAlbum).equals(BatchMusicMatchUtil.normalize(candidateAlbum))) {
            score += 20;
        }
        if (reqDurationMs > 0 && candidateDurationMs > 0) {
            int diff = Math.abs(reqDurationMs - candidateDurationMs);
            score += Math.max(0, 15 - diff / 1_000);
        }
        return score;
    }

    private static boolean isProtectedSource(String source) {
        return source != null && PROTECTED_SOURCES.contains(source.toLowerCase(Locale.ROOT));
    }

    private static String searchKeyword(MusicLyricsRow row) {
        return (row.title() + " " + row.artist()).trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(REQUEST_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Status copyStatus(Status source, boolean cancelled, String state, String message) {
        return new Status(source.running(), cancelled, state, source.total(), source.processed(),
                source.migrated(), source.skipped(), source.failed(), source.currentMusicId(),
                source.currentTitle(), source.startedAt(), source.finishedAt(), message);
    }
}
