/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Java port of ArchoeraMusic/app/core/indexer/IndexerEngine.cs（数据源改为 MySQL）
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：去掉 library.db / mtime / 世代水位线，改用
 * 「元数据 + 歌词 content_hash」指纹做增量判定；歌词按批读取，全量不常驻内存。
 */

package com.neko.music.search;

import com.neko.music.util.LyricsPlainTextExtractor;
import com.neko.music.util.NekoLyrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以 MySQL 为数据源的曲库索引器。
 *
 * <p>与 C# 原实现的差异：
 * <ul>
 *   <li>脏集判定改用 64 位指纹（title/artist/album/language/歌词 content_hash/大小/占位标记），
 *       不再依赖文件 mtime；</li>
 *   <li>歌词分块（{@value #DETAIL_BATCH} 首）读取并及时释放，**不把全库歌词留在堆里**；</li>
 *   <li>语言画像在增量时只做「或」累积（保守，宁可多跑一趟），全量时精确重算；</li>
 *   <li>译文 / 音译歌词层首期留空（Neko 的 {@code music_lyrics} 尚无独立分层）。</li>
 * </ul>
 */
public final class LibraryIndexer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LibraryIndexer.class);

    /** 累计改写曲数达到该值时做一次 FTS5 merge。 */
    static final int MERGE_EVERY_UPDATES = 30;

    /** 单次读取歌词的批大小（控制瞬时内存）。 */
    static final int DETAIL_BATCH = 200;

    /** 索引数据源（一次调用借一条连接）。 */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection getConnection() throws SQLException;
    }

    private final Path indexPath;
    private final ConnectionSource connections;

    private FtsStore store;
    private long indexVersion;
    private volatile boolean closed;

    public LibraryIndexer(Path indexPath, ConnectionSource connections) {
        this.indexPath = indexPath.toAbsolutePath();
        this.connections = connections;
    }

    public Path indexPath() {
        return indexPath;
    }

    /** 全量重建完成的代次（查询会话据此判断是否需要重新打开只读连接）。 */
    public synchronized long indexVersion() {
        return indexVersion;
    }

    /** 确保索引可用：版本不符 / 文件缺失时自动全量重建，否则增量刷新。 */
    public synchronized void ensureReady() {
        if (closed) {
            throw new IllegalStateException("索引器已关闭");
        }
        if (store == null) {
            refresh(false);
        }
    }

    /** 增量刷新：新增 / 改写 / 删除的曲目落盘。 */
    public synchronized void refresh() {
        refresh(false);
    }

    /** 全量重建（世代化写临时库后原子替换）。 */
    public synchronized void rebuildAll() {
        refresh(true);
    }

    /**
     * 刷新入口。
     *
     * @param forceFull 强制全量；否则版本不符 / 库缺失时自动全量
     */
    private void refresh(boolean forceFull) {
        if (closed) {
            throw new IllegalStateException("索引器已关闭");
        }

        boolean full = forceFull || !Files.exists(indexPath) || !FtsStore.versionsMatch(indexPath.toString());
        long startedAt = Instant.now().getEpochSecond();

        try {
            Map<String, FtsStore.DocState> state = Map.of();

            if (store != null) {
                store.close();
                store = null;
            }

            store = new FtsStore();
            store.open(indexPath.toString(), full);
            if (!full) {
                state = store.loadState();
            }

            FtsStore.StoreMeta meta = new FtsStore.StoreMeta();
            if (!full) {
                meta = store.readMeta();
            } else {
                meta.hasJapanese = false;
                meta.hasKorean = false;
                meta.hasHan = false;
                meta.hasLatin = false;
            }
            meta.indexLrc = true;
            meta.pinyin = true;
            meta.romajiLyrics = false;
            meta.appliedGeneration = startedAt;

            final Map<String, FtsStore.DocState> knownState = state;
            Set<String> seen = new HashSet<>();
            List<Integer> changed = new ArrayList<>();
            scanTracks(ref -> {
                seen.add(ref.trackId());
                FtsStore.DocState existing = knownState.get(ref.trackId());
                if (existing == null || existing.mtime() != ref.fingerprint()) {
                    changed.add(ref.musicId());
                }
            });

            int removed = 0;
            if (!full) {
                for (Map.Entry<String, FtsStore.DocState> entry : knownState.entrySet()) {
                    if (!seen.contains(entry.getKey())) {
                        store.delete(entry.getValue().docId());
                        removed++;
                    }
                }
            }

            for (int offset = 0; offset < changed.size(); offset += DETAIL_BATCH) {
                if (closed) {
                    // 停机时不再继续下一批；已提交的增量批次可保留，剩余交由下次刷新补齐。
                    throw new IllegalStateException("索引器已关闭，中止未完成的索引批次");
                }
                List<Integer> batch = changed.subList(offset, Math.min(offset + DETAIL_BATCH, changed.size()));
                Map<Integer, TrackDetail> details = loadDetails(batch);
                for (Integer musicId : batch) {
                    TrackDetail detail = details.get(musicId);
                    if (detail == null) {
                        continue;
                    }
                    FtsStore.DocState existing = state.get(String.valueOf(musicId));
                    long docId = existing != null ? existing.docId() : store.allocateDocId();
                    store.put(docId, buildDoc(detail, meta));
                }
            }

            int changedCount = changed.size() + removed;
            meta.updatesSinceMerge += changedCount;
            if (meta.updatesSinceMerge >= MERGE_EVERY_UPDATES) {
                store.optimize();
                meta.updatesSinceMerge = 0;
            }

            meta.builtAt = startedAt;
            meta.appliedGeneration = startedAt;
            if (full) {
                meta.fullBuildAt = startedAt;
            }
            store.writeMeta(meta);
            if (full) {
                // 必须在原子替换之前丢弃旧只读会话：否则旧会话持有旧 WAL，
                // 替换后新库会回放旧 WAL（删掉的歌复现 / SQLITE_CORRUPT）。
                SearchSession.closeCached();
            }
            store.seal();

            // seal 之后本实例的连接已失效（全量会关闭连接），置空让单曲路径重新打开；
            // 否则 refreshOne / removeOne 会持有已关闭的连接抛 IllegalStateException。
            store.close();
            store = null;

            if (full) {
                indexVersion++;
            }
            logger.info("曲库索引{}完成: 变更 {} 首（新增/改写 {}，删除 {}），耗时 {} ms，文件 {}",
                    full ? "全量重建" : "增量刷新", changedCount, changed.size(), removed,
                    (Instant.now().getEpochSecond() - startedAt) * 1000, indexPath);
        } catch (Exception e) {
            if (store != null) {
                store.close();
                store = null;
            }
            if (closed) {
                // 停机导致的中止属正常路径，不抛错、不打 error 日志。
                logger.info("索引器已关闭，本轮索引提前结束: {}", e.getMessage());
                return;
            }
            logger.error("曲库索引构建失败", e);
            throw new IllegalStateException("曲库索引构建失败", e);
        }
    }

    /** 单曲增量刷新（歌词 / 元数据变更后调用）；索引不存在时先做一次全量。 */
    public synchronized void refreshOne(int musicId) {
        if (closed || musicId <= 0) {
            return;
        }
        if (!Files.exists(indexPath) || !FtsStore.versionsMatch(indexPath.toString())) {
            refresh(false);
            return;
        }
        FtsStore current;
        try {
            current = ensureOpen();
        } catch (SQLException e) {
            logger.warn("打开索引失败，改为全量重建: {}", e.getMessage());
            store = null;
            refresh(false);
            return;
        }

        try {
            Map<Integer, TrackDetail> details = loadDetails(List.of(musicId));
            TrackDetail detail = details.get(musicId);
            FtsStore.StoreMeta meta = current.readMeta();
            FtsStore.DocState existing = current.loadDocState(String.valueOf(musicId));

            if (detail == null) {
                if (existing != null) {
                    current.delete(existing.docId());
                }
            } else if (existing == null || existing.mtime() != detail.fingerprint()) {
                long docId = existing != null ? existing.docId() : current.allocateDocId();
                current.put(docId, buildDoc(detail, meta));
            } else {
                return;
            }

            meta.builtAt = Instant.now().getEpochSecond();
            meta.updatesSinceMerge += 1;
            if (meta.updatesSinceMerge >= MERGE_EVERY_UPDATES) {
                current.optimize();
                meta.updatesSinceMerge = 0;
            }
            current.writeMeta(meta);
            current.commit();
            // 索引内容已变化：清掉查询结果缓存，避免删除 / 改写后仍命中旧结果。
            SearchSession.invalidateQueryCache();
        } catch (Exception e) {
            logger.warn("单曲索引刷新失败 musicId={}: {}", musicId, e.getMessage());
            // 回滚残留事务：否则 autoCommit=false 会一直挂着，后续写入持续累积而不落盘。
            try {
                current.rollback();
            } catch (Exception rollbackError) {
                logger.warn("单曲索引事务回滚失败，关闭连接待下次重建: {}", rollbackError.getMessage());
                store.close();
                store = null;
            }
        }
    }

    /** 曲目删除：从索引移除。 */
    public synchronized void removeOne(int musicId) {
        if (closed || musicId <= 0) {
            return;
        }
        if (!Files.exists(indexPath) || !FtsStore.versionsMatch(indexPath.toString())) {
            return;
        }
        try {
            FtsStore current = ensureOpen();
            FtsStore.DocState existing = current.loadDocState(String.valueOf(musicId));
            if (existing != null) {
                current.delete(existing.docId());
                current.commit();
                // 删除后必须让查询缓存失效，否则已删除曲目仍会被搜到。
                SearchSession.invalidateQueryCache();
            }
        } catch (Exception e) {
            logger.warn("删除曲目索引失败 musicId={}: {}", musicId, e.getMessage());
            try {
                if (store != null) {
                    store.rollback();
                }
            } catch (Exception rollbackError) {
                logger.warn("删除曲目索引事务回滚失败，关闭连接待下次重建: {}", rollbackError.getMessage());
                if (store != null) {
                    store.close();
                    store = null;
                }
            }
        }
    }

    /** 取可用的写连接；seal / 异常之后 store 已被置空，这里按需重新打开。 */
    private FtsStore ensureOpen() throws SQLException {
        if (store != null && !store.isOpen()) {
            store.close();
            store = null;
        }
        if (store == null) {
            FtsStore opened = new FtsStore();
            opened.open(indexPath.toString(), false);
            store = opened;
        }
        return store;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (store != null) {
            store.close();
            store = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 数据读取                                                            */
    /* ------------------------------------------------------------------ */

    private record TrackRef(int musicId, String trackId, long fingerprint) {
    }

    private record TrackDetail(
            int musicId,
            String title,
            String artist,
            String album,
            String language,
            String lyricsContent,
            String lyricsPlain,
            String format,
            boolean placeholder,
            long lyricsSize,
            long lyricsUpdatedAt,
            long fingerprint) {
    }

    @FunctionalInterface
    private interface TrackRefConsumer {
        void accept(TrackRef ref);
    }

    /** 逐行扫描元数据（不读歌词），把每首曲目的指纹回调给调用方。 */
    private void scanTracks(TrackRefConsumer consumer) throws SQLException {
        String sql = """
                SELECT m.id, m.title, m.artist, m.album, m.language, m.updated_at AS music_updated_at,
                       l.updated_at AS lyrics_updated_at, l.size_bytes AS lyrics_size,
                       l.content_hash AS lyrics_hash, l.is_placeholder AS lyrics_placeholder
                FROM music m
                LEFT JOIN music_lyrics l ON l.music_id = m.id
                ORDER BY m.id
                """;
        try (Connection conn = connections.getConnection();
             PreparedStatement cmd = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            cmd.setFetchSize(Integer.MIN_VALUE); // MySQL 流式读取，避免整表落内存
            try (ResultSet rs = cmd.executeQuery()) {
                while (rs.next()) {
                    if (closed) {
                        // MySQL 流式扫描不响应中断，只能在行间主动退出。
                        throw new IllegalStateException("索引器已关闭，中止曲目扫描");
                    }
                    int musicId = rs.getInt("id");
                    String title = nz(rs.getString("title"));
                    String artist = nz(rs.getString("artist"));
                    String album = nz(rs.getString("album"));
                    String language = nz(rs.getString("language"));
                    String lyricsHash = nz(rs.getString("lyrics_hash"));
                    boolean placeholder = rs.getBoolean("lyrics_placeholder");
                    long lyricsSize = rs.getLong("lyrics_size");
                    long musicUpdatedAt = epochSeconds(rs.getTimestamp("music_updated_at"));
                    long lyricsUpdatedAt = epochSeconds(rs.getTimestamp("lyrics_updated_at"));
                    long fingerprint = fingerprint(title, artist, album, language,
                            lyricsHash, placeholder, lyricsSize, musicUpdatedAt, lyricsUpdatedAt);
                    consumer.accept(new TrackRef(musicId, String.valueOf(musicId), fingerprint));
                }
            }
        }
    }

    /** 按 id 批量读取元数据 + 歌词正文（每批最多 {@link #DETAIL_BATCH} 首）。 */
    private Map<Integer, TrackDetail> loadDetails(List<Integer> musicIds) throws SQLException {
        Map<Integer, TrackDetail> map = new LinkedHashMap<>();
        if (musicIds.isEmpty()) {
            return map;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(musicIds.size(), "?"));
        String sql = """
                SELECT m.id, m.title, m.artist, m.album, m.language, m.updated_at AS music_updated_at,
                       l.content, l.plain_text, l.format, l.is_placeholder, l.size_bytes,
                       l.updated_at AS lyrics_updated_at, l.content_hash
                FROM music m
                LEFT JOIN music_lyrics l ON l.music_id = m.id
                WHERE m.id IN (%s)
                """.formatted(placeholders);

        try (Connection conn = connections.getConnection();
             PreparedStatement cmd = conn.prepareStatement(sql)) {
            int index = 1;
            for (int musicId : musicIds) {
                cmd.setInt(index++, musicId);
            }
            try (ResultSet rs = cmd.executeQuery()) {
                while (rs.next()) {
                    int musicId = rs.getInt("id");
                    String title = nz(rs.getString("title"));
                    String artist = nz(rs.getString("artist"));
                    String album = nz(rs.getString("album"));
                    String language = nz(rs.getString("language"));
                    String content = rs.getString("content");
                    String plain = rs.getString("plain_text");
                    String format = nz(rs.getString("format"));
                    boolean placeholder = rs.getBoolean("is_placeholder");
                    long size = rs.getLong("size_bytes");
                    long musicUpdatedAt = epochSeconds(rs.getTimestamp("music_updated_at"));
                    long lyricsUpdatedAt = epochSeconds(rs.getTimestamp("lyrics_updated_at"));
                    String hash = nz(rs.getString("content_hash"));
                    long fingerprint = fingerprint(title, artist, album, language,
                            hash, placeholder, size, musicUpdatedAt, lyricsUpdatedAt);
                    map.put(musicId, new TrackDetail(musicId, title, artist, album, language,
                            content, plain, format, placeholder, size, lyricsUpdatedAt, fingerprint));
                }
            }
        }
        return map;
    }

    /** 把一行曲目转成索引词元。歌词正文只在此刻存在，写完即可被回收。 */
    private FtsStore.DocTokens buildDoc(TrackDetail detail, FtsStore.StoreMeta meta) {
        String plain = resolvePlainText(detail);
        boolean japanese = "日语".equals(detail.language())
                || JapaneseReadings.containsKana(detail.title())
                || JapaneseReadings.containsKana(detail.artist())
                || JapaneseReadings.containsKana(detail.album())
                || JapaneseReadings.containsKana(plain);

        Analyzer.AnalysisResult title = Analyzer.analyze(detail.title(), japanese);
        Analyzer.AnalysisResult artist = Analyzer.analyze(detail.artist(), japanese);
        Analyzer.AnalysisResult album = Analyzer.analyze(detail.album(), japanese);
        Analyzer.AnalysisResult lyrics = Analyzer.analyze(plain, false);

        FtsStore.DocTokens doc = new FtsStore.DocTokens();
        doc.trackId = String.valueOf(detail.musicId());
        doc.titleTok = title.tokens();
        doc.artistTok = artist.tokens();
        doc.albumTok = album.tokens();
        doc.lyricsTok = lyrics.tokens();
        doc.lyricsTrTok = "";
        doc.lyricsRomTok = "";
        doc.pinyinMetaTok = join(title.pinyin(), artist.pinyin(), album.pinyin());
        doc.pinyinLyricsTok = lyrics.pinyin();
        doc.initialsMetaTok = join(title.pinyinInitials(), artist.pinyinInitials(), album.pinyinInitials());
        doc.initialsLyricsTok = lyrics.pinyinInitials();
        doc.readingMetaTok = join(title.readings(), artist.readings(), album.readings());
        doc.readingText = doc.readingMetaTok;
        doc.filenameTok = "";
        doc.indexedMtime = detail.fingerprint();
        doc.indexedLrcSize = detail.lyricsSize();
        doc.indexedLrcMtime = detail.lyricsUpdatedAt();
        doc.indexedGeneration = meta.appliedGeneration;

        observeProfile(meta, detail.title(), detail.artist(), detail.album(), plain);
        return doc;
    }

    private String resolvePlainText(TrackDetail detail) {
        if (detail.placeholder()) {
            return "";
        }
        String plain = detail.lyricsPlain();
        if (plain == null || plain.isBlank()) {
            if (detail.lyricsContent() == null || detail.lyricsContent().isBlank()) {
                return "";
            }
            plain = NekoLyrics.isNekoFormat(detail.format())
                    ? NekoLyrics.deserialize(detail.lyricsContent()).map(NekoLyrics::plainText).orElse("")
                    : LyricsPlainTextExtractor.fromLrc(detail.lyricsContent());
        }
        return plain == null ? "" : plain;
    }

    /** 更新语言画像（只置位，不清零；全量时从 false 起算即为精确值）。 */
    private void observeProfile(FtsStore.StoreMeta meta, String... texts) {
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ScriptRanges.isCjk(ch)) {
                    meta.hasHan = true;
                }
                if (ScriptRanges.isKana(ch)) {
                    meta.hasJapanese = true;
                }
                if (KoreanRomanizer.isHangul(ch)) {
                    meta.hasKorean = true;
                }
                if (ScriptRanges.isLatinLetter(ch)) {
                    meta.hasLatin = true;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 辅助                                                                */
    /* ------------------------------------------------------------------ */

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static long epochSeconds(Timestamp timestamp) {
        return timestamp == null ? 0 : timestamp.toInstant().getEpochSecond();
    }

    /** 64 位变更指纹：对曲目可见内容做 SHA-256 后取前 8 字节（0 保留为「未索引」哨兵）。 */
    private static long fingerprint(Object... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object field : fields) {
                byte[] bytes = (field == null ? "" : String.valueOf(field)).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            return value == 0 ? 1 : value;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
