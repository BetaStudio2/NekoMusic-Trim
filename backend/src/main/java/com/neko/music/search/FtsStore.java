/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Portions derived from ArchoeraMusic, Copyright (C) 2026 Archoera && BetaStudio2,
 * AGPL-3.0-or-later. 修改说明：Microsoft.Data.Sqlite → sqlite-jdbc；接口收敛为单个 Java 类，
 * 所有公开方法串行化（单实例索引，读写共用一条连接）。
 */

package com.neko.music.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * SQLite FTS5 索引存储（schema v7 / analyzer v12）。
 *
 * <p>要点：
 * <ul>
 *   <li>三张表结构冻结：{@code search_docs} / {@code search_fts} / {@code search_meta}；
 *       FTS 列序即 bm25 权重序，不得改动；</li>
 *   <li>contentless FTS（{@code content=''} + {@code contentless_delete=1}），
 *       倒排只存 postings / 位置；</li>
 *   <li>全量构建写 {@code <path>.tmp}，fsync 后原子替换；增量打开既有库走 WAL；</li>
 *   <li>bm25 必须在含 {@code MATCH} 的同一查询块内求值，否则静默返回 0。</li>
 * </ul>
 *
 * <p>线程模型：单实例、读写共用一个连接。所有公开方法 {@code synchronized}，
 * 避免 JDBC 连接的并发使用；查询与写入不会同时进行（首期可接受，后续如需并发再拆只读连接）。
 */
public final class FtsStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FtsStore.class);

    /** schema 版本；schema 变更必须递增（触发全量重建）。 */
    public static final int SCHEMA_VERSION = 7;

    /** FTS 列（顺序 = bm25 权重顺序，冻结）。 */
    static final String FTS_COLUMNS =
            "title_tok, artist_tok, album_tok, lyrics_tok, lyrics_tr_tok, lyrics_rom_tok, "
                    + "pinyin_meta_tok, pinyin_lyrics_tok, reading_meta_tok, "
                    + "filename_tok, initials_meta_tok, initials_lyrics_tok";

    /** bm25 权重，顺序与 {@link #FTS_COLUMNS} 严格一致。 */
    private static final double[] BM25_WEIGHTS = {
            4.0, 3.0, 2.0, 1.5, 1.0, 0.5,
            2.0, 1.5, 2.0,
            1.0, 2.0, 1.5,
    };

    /** 候选集上限（历史参数；0 = 不限）。排序一律走含 MATCH 的全命中 bm25。 */
    public static final int DEFAULT_CANDIDATE_CAP = 5000;

    private static final String WEIGHTS_SQL;

    static {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < BM25_WEIGHTS.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.ROOT, "%.1f", BM25_WEIGHTS[i]));
        }
        WEIGHTS_SQL = builder.toString();
    }

    /** 写入 FTS 倒排的一篇文档分析后词元；与 FTS 列一一对应。 */
    public static final class DocTokens {
        public String trackId = "";
        public String titleTok = "";
        public String artistTok = "";
        public String albumTok = "";
        public String lyricsTok = "";
        public String lyricsTrTok = "";
        public String lyricsRomTok = "";
        public String pinyinMetaTok = "";
        public String pinyinLyricsTok = "";
        public String initialsMetaTok = "";
        public String initialsLyricsTok = "";
        public String readingMetaTok = "";
        /** 读音 bigram 原文副本（写入 search_docs.reading_tok）。 */
        public String readingText = "";
        public String filenameTok = "";
        public long indexedMtime;
        public long indexedLrcMtime;
        public long indexedLrcSize;
        public long indexedGeneration;
    }

    /** 单文档既有状态快照（增量判定用）。 */
    public record DocState(long docId, long mtime, long lrcMtime, long lrcSize, long generation) {
    }

    /** search_meta 的强类型视图。 */
    public static final class StoreMeta {
        public int schemaVersion;
        public int analyzerVersion;
        public boolean indexLrc;
        public boolean pinyin;
        public boolean romajiLyrics;
        public long appliedGeneration;
        public long builtAt;
        public long fullBuildAt;
        public boolean hasJapanese = true;
        public boolean hasKorean = true;
        public boolean hasHan = true;
        public boolean hasLatin = true;
        public int updatesSinceMerge;
        /** 仅阶段预检使用：true 表示「重试后仍读失败」，不代表版本不符。 */
        public boolean readFailed;
    }

    private Connection connection;
    private boolean autoCommit = true;
    private Path path;
    private Path tmpPath;
    private boolean build;
    private int pending;
    private boolean sealed;
    private boolean disposed;

    /** 模糊层词典缓存（写入纪元 + 索引文件 mtime 双条件失效）。 */
    private List<String> fuzzyVocabulary;
    private long fuzzyVocabularyEpoch = -1;
    /** 缓存时索引文件的最后修改时间（只读会话没有写入纪元，靠文件变化失效）。 */
    private long fuzzyVocabularyStamp = -1;
    private long writeEpoch;

    /** 批量事务的文档条数上限。 */
    public int batchSize = 500;

    /** 下一个可用 docId。 */
    private long nextDocId = 1;

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public long nextDocId() {
        return nextDocId;
    }

    /** 分配一个未使用的 docId（全量从 1 起，增量从既有最大值 + 1 起）。 */
    public synchronized long allocateDocId() {
        return nextDocId++;
    }

    /** 打开存储：{@code build=true} 世代化全量（写 .tmp 后原子替换），否则增量打开既有库。 */
    public synchronized void open(String pathValue, boolean build) throws SQLException {
        if (connection != null) {
            throw new IllegalStateException("存储已打开");
        }
        this.path = Paths.get(pathValue).toAbsolutePath();
        this.build = build;

        try {
            if (build) {
                tmpPath = Paths.get(this.path + ".tmp");
                deleteFileAndSidecars(tmpPath);
                connection = DriverManager.getConnection("jdbc:sqlite:" + tmpPath);
                // busy_timeout 必须在其它 PRAGMA 之前生效，否则首个 PRAGMA 遇锁会直接失败。
                configureBusyTimeout(connection);
                exec("PRAGMA journal_mode = WAL");
                exec("PRAGMA synchronous = FULL");
            } else {
                tmpPath = this.path;
                connection = DriverManager.getConnection("jdbc:sqlite:" + this.path);
                configureBusyTimeout(connection);
                exec("PRAGMA journal_mode = WAL");
                exec("PRAGMA synchronous = NORMAL");
                exec("PRAGMA wal_autocheckpoint = 10000");
            }
            connection.setAutoCommit(true);
            ensureSchema();
            nextDocId = build ? 1 : scalarLong("SELECT COALESCE(MAX(doc_id), 0) + 1 FROM search_docs");
        } catch (SQLException e) {
            closeQuietly();
            throw e;
        }
    }

    /** 只读打开既有索引（查询侧）：不建表、不改 journal_mode / synchronous。 */
    public synchronized void openReadOnly(String pathValue) throws SQLException {
        if (connection != null) {
            throw new IllegalStateException("存储已打开");
        }
        this.path = Paths.get(pathValue).toAbsolutePath();
        this.tmpPath = this.path;
        this.build = false;
        this.connection = openReadOnlyConnection(this.path);
        exec("PRAGMA query_only = ON");
        this.nextDocId = scalarLong("SELECT COALESCE(MAX(doc_id), 0) + 1 FROM search_docs");
    }

    /** 连接是否仍可用（seal / close 之后为 false）。 */
    public synchronized boolean isOpen() {
        return connection != null && !disposed;
    }

    /** 当前连接的 data_version（其它连接写入后变化）；读取失败返回 -1。 */
    public synchronized long dataVersion() {
        try {
            return scalarLong("PRAGMA data_version");
        } catch (Exception e) {
            return -1;
        }
    }

    /** 写入 / 覆盖一篇文档。 */
    public synchronized void put(long docId, DocTokens fields) throws SQLException {
        ensureOpen();
        ensureTx();

        try (PreparedStatement up = connection.prepareStatement("""
                INSERT OR REPLACE INTO search_docs
                    (doc_id, track_id, indexed_mtime, indexed_lrc_mtime, indexed_lrc_size,
                     indexed_generation, schema_version, reading_tok)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            up.setLong(1, docId);
            up.setString(2, nullToEmpty(fields.trackId));
            up.setLong(3, fields.indexedMtime);
            up.setLong(4, fields.indexedLrcMtime);
            up.setLong(5, fields.indexedLrcSize);
            up.setLong(6, fields.indexedGeneration);
            up.setLong(7, SCHEMA_VERSION);
            up.setString(8, nullToEmpty(fields.readingText));
            up.executeUpdate();
        }

        try (PreparedStatement del = connection.prepareStatement("DELETE FROM search_fts WHERE rowid = ?")) {
            del.setLong(1, docId);
            del.executeUpdate();
        }

        try (PreparedStatement ins = connection.prepareStatement(
                "INSERT INTO search_fts (rowid, " + FTS_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ins.setLong(1, docId);
            ins.setString(2, nullToEmpty(fields.titleTok));
            ins.setString(3, nullToEmpty(fields.artistTok));
            ins.setString(4, nullToEmpty(fields.albumTok));
            ins.setString(5, nullToEmpty(fields.lyricsTok));
            ins.setString(6, nullToEmpty(fields.lyricsTrTok));
            ins.setString(7, nullToEmpty(fields.lyricsRomTok));
            ins.setString(8, nullToEmpty(fields.pinyinMetaTok));
            ins.setString(9, nullToEmpty(fields.pinyinLyricsTok));
            ins.setString(10, nullToEmpty(fields.readingMetaTok));
            ins.setString(11, nullToEmpty(fields.filenameTok));
            ins.setString(12, nullToEmpty(fields.initialsMetaTok));
            ins.setString(13, nullToEmpty(fields.initialsLyricsTok));
            ins.executeUpdate();
        }

        afterWrite();
    }

    /** 按 docId 删除一篇文档（含 FTS 倒排）。 */
    public synchronized void delete(long docId) throws SQLException {
        ensureOpen();
        ensureTx();
        try (PreparedStatement del = connection.prepareStatement("DELETE FROM search_fts WHERE rowid = ?")) {
            del.setLong(1, docId);
            del.executeUpdate();
        }
        try (PreparedStatement rm = connection.prepareStatement("DELETE FROM search_docs WHERE doc_id = ?")) {
            rm.setLong(1, docId);
            rm.executeUpdate();
        }
        afterWrite();
    }

    /** 回滚挂起事务（失败路径用），避免 autoCommit=false 的事务长期悬挂。 */
    public synchronized void rollback() throws SQLException {
        ensureOpen();
        if (autoCommit || connection == null) {
            return;
        }
        connection.rollback();
        connection.setAutoCommit(true);
        autoCommit = true;
        pending = 0;
    }

    /** 提交挂起事务并做一次被动 checkpoint（不阻塞读会话）。 */
    public synchronized void commit() throws SQLException {
        ensureOpen();
        commitTx();
        checkpointPassive();
    }

    /** 离线优化（PRAGMA optimize + FTS optimize）。 */
    public synchronized void optimize() throws SQLException {
        ensureOpen();
        commitTx();
        exec("PRAGMA optimize");
        exec("INSERT INTO search_fts(search_fts) VALUES('optimize')");
    }

    /**
     * 执行 MATCH 查询，返回 bm25 升序命中（score 越小越相关）。
     *
     * @param limit        ≤ 0 表示不限
     * @param candidateCap 历史参数，当前实现不裁剪候选
     */
    public synchronized List<ScoredDoc> search(String matchExpr, int limit, int offset, int candidateCap)
            throws SQLException {
        ensureOpen();
        commitTx();

        String sql = """
                SELECT rowid, bm25(search_fts, %s) AS score
                FROM search_fts
                WHERE search_fts MATCH ?
                ORDER BY score
                LIMIT ? OFFSET ?
                """.formatted(WEIGHTS_SQL);
        List<ScoredDoc> results = new ArrayList<>();
        try (PreparedStatement cmd = connection.prepareStatement(sql)) {
            cmd.setString(1, matchExpr);
            cmd.setInt(2, limit <= 0 ? -1 : limit);
            cmd.setInt(3, Math.max(offset, 0));
            try (ResultSet reader = cmd.executeQuery()) {
                while (reader.next()) {
                    results.add(new ScoredDoc(reader.getLong(1), reader.getDouble(2)));
                }
            }
        }
        return results;
    }

    public record ScoredDoc(long docId, double score) {
    }

    /** 批量把 doc_id 映射回 track_id。 */
    public synchronized Map<Long, String> resolveTrackIds(Collection<Long> docIds) throws SQLException {
        Map<Long, String> map = new HashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return map;
        }
        ensureOpen();
        String placeholders = String.join(", ", java.util.Collections.nCopies(docIds.size(), "?"));
        String sql = "SELECT doc_id, track_id FROM search_docs WHERE doc_id IN (" + placeholders + ")";
        try (PreparedStatement cmd = connection.prepareStatement(sql)) {
            int index = 1;
            for (long docId : docIds) {
                cmd.setLong(index++, docId);
            }
            try (ResultSet reader = cmd.executeQuery()) {
                while (reader.next()) {
                    map.put(reader.getLong(1), reader.getString(2));
                }
            }
        }
        return map;
    }

    /** 批量取候选文档的读音 bigram 原文副本（兜底趟分档用）。 */
    public synchronized Map<Long, String> resolveReadingTexts(Collection<Long> docIds) throws SQLException {
        Map<Long, String> map = new HashMap<>();
        if (docIds == null || docIds.isEmpty()) {
            return map;
        }
        ensureOpen();
        String placeholders = String.join(", ", java.util.Collections.nCopies(docIds.size(), "?"));
        String sql = "SELECT doc_id, reading_tok FROM search_docs WHERE doc_id IN (" + placeholders + ")";
        try (PreparedStatement cmd = connection.prepareStatement(sql)) {
            int index = 1;
            for (long docId : docIds) {
                cmd.setLong(index++, docId);
            }
            try (ResultSet reader = cmd.executeQuery()) {
                while (reader.next()) {
                    String text = reader.getString(2);
                    if (text != null && !text.isEmpty()) {
                        map.put(reader.getLong(1), text);
                    }
                }
            }
        }
        return map;
    }

    /**
     * 加载模糊层词典：FTS 词表（fts5vocab）里可检索的 ASCII 拉丁 / 数字 term，按字典序。
     * 按写入纪元缓存；失败返回空表（调用方退回无词典过滤）。
     */
    public synchronized List<String> loadFuzzyVocabulary() {
        long stamp = indexPathStamp();
        if (fuzzyVocabulary != null && fuzzyVocabularyEpoch == writeEpoch && fuzzyVocabularyStamp == stamp) {
            return fuzzyVocabulary;
        }
        if (path == null || !Files.exists(path)) {
            return fuzzyVocabulary != null ? fuzzyVocabulary : List.of();
        }

        List<String> terms = new ArrayList<>();
        try (Connection conn = openReadOnlyConnection(path);
             Statement create = conn.createStatement();
             Statement query = conn.createStatement()) {
            create.execute("CREATE VIRTUAL TABLE temp.fuzzy_vocab USING fts5vocab(main, search_fts, 'col')");
            try (ResultSet reader = query.executeQuery("""
                    SELECT DISTINCT term FROM temp.fuzzy_vocab
                    WHERE col IN ('title_tok', 'artist_tok', 'album_tok', 'filename_tok',
                                  'lyrics_tok', 'lyrics_tr_tok')
                    ORDER BY term
                    """)) {
                while (reader.next()) {
                    String term = reader.getString(1);
                    if (isAsciiWord(term)) {
                        terms.add(term);
                    }
                }
            }
        } catch (Exception e) {
            // 失败不写缓存：否则一次瞬时故障会把空词典永久钉住。
            logger.info("模糊词典加载失败，退回无词典过滤: {}", e.getMessage());
            return fuzzyVocabulary != null ? fuzzyVocabulary : List.of();
        }

        fuzzyVocabulary = List.copyOf(terms);
        fuzzyVocabularyEpoch = writeEpoch;
        fuzzyVocabularyStamp = stamp;
        return fuzzyVocabulary;
    }

    /** 丢弃模糊词典缓存（索引内容变化后由查询会话调用）。 */
    public synchronized void invalidateFuzzyVocabulary() {
        fuzzyVocabulary = null;
        fuzzyVocabularyEpoch = -1;
        fuzzyVocabularyStamp = -1;
    }

    /** 索引文件 mtime（毫秒）；不可读时返回 -1（此时每次都会重载）。 */
    private long indexPathStamp() {
        if (path == null) {
            return -1;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean isAsciiWord(String term) {
        if (term == null || term.isEmpty()) {
            return false;
        }
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 读取 search_meta（缺失键取默认值）。 */
    public synchronized StoreMeta readMeta() throws SQLException {
        StoreMeta meta = new StoreMeta();
        ensureOpen();
        if (!tableExists(connection, "search_meta")) {
            return meta;
        }
        try (Statement cmd = connection.createStatement();
             ResultSet reader = cmd.executeQuery("SELECT key, value FROM search_meta")) {
            while (reader.next()) {
                applyMeta(meta, reader.getString(1), reader.getString(2) == null ? "" : reader.getString(2));
            }
        }
        return meta;
    }

    /** 写入 / 更新 search_meta 的版本、选项与世代键。 */
    public synchronized void writeMeta(StoreMeta meta) throws SQLException {
        ensureOpen();
        ensureTx();
        try (PreparedStatement cmd = connection.prepareStatement("""
                INSERT INTO search_meta (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            String[][] entries = {
                    {"schema_version", Integer.toString(SCHEMA_VERSION)},
                    {"analyzer_version", Integer.toString(Analyzer.ANALYZER_VERSION)},
                    {"index_lrc", meta.indexLrc ? "1" : "0"},
                    {"pinyin", meta.pinyin ? "1" : "0"},
                    {"romaji_lyrics", meta.romajiLyrics ? "1" : "0"},
                    {"applied_generation", Long.toString(meta.appliedGeneration)},
                    {"built_at", Long.toString(meta.builtAt)},
                    {"full_build_at", Long.toString(meta.fullBuildAt)},
                    {"has_japanese", meta.hasJapanese ? "1" : "0"},
                    {"has_korean", meta.hasKorean ? "1" : "0"},
                    {"has_han", meta.hasHan ? "1" : "0"},
                    {"has_latin", meta.hasLatin ? "1" : "0"},
                    {"updates_since_merge", Integer.toString(meta.updatesSinceMerge)},
            };
            for (String[] entry : entries) {
                cmd.setString(1, entry[0]);
                cmd.setString(2, entry[1]);
                cmd.executeUpdate();
            }
        }
    }

    /** 读取全部既有文档状态，键为 track_id。 */
    public synchronized Map<String, DocState> loadState() throws SQLException {
        Map<String, DocState> state = new HashMap<>();
        ensureOpen();
        if (!tableExists(connection, "search_docs")) {
            return state;
        }
        try (Statement cmd = connection.createStatement();
             ResultSet reader = cmd.executeQuery("""
                     SELECT track_id, doc_id, indexed_mtime, indexed_lrc_mtime,
                            indexed_lrc_size, indexed_generation
                     FROM search_docs
                     """)) {
            while (reader.next()) {
                state.put(reader.getString(1), new DocState(
                        reader.getLong(2),
                        reader.getLong(3),
                        reader.getLong(4),
                        reader.getLong(5),
                        reader.getLong(6)));
            }
        }
        return state;
    }

    /** 读取单篇文档的既有状态（单曲增量路径用，避免装载整表）；不存在返回 {@code null}。 */
    public synchronized DocState loadDocState(String trackId) throws SQLException {
        ensureOpen();
        if (trackId == null || !tableExists(connection, "search_docs")) {
            return null;
        }
        try (PreparedStatement cmd = connection.prepareStatement("""
                SELECT doc_id, indexed_mtime, indexed_lrc_mtime, indexed_lrc_size, indexed_generation
                FROM search_docs
                WHERE track_id = ?
                LIMIT 1
                """)) {
            cmd.setString(1, trackId);
            try (ResultSet reader = cmd.executeQuery()) {
                if (!reader.next()) {
                    return null;
                }
                return new DocState(
                        reader.getLong(1),
                        reader.getLong(2),
                        reader.getLong(3),
                        reader.getLong(4),
                        reader.getLong(5));
            }
        }
    }

    /** 世代化收尾：build 模式 fsync 后原子替换为正式库；增量模式为 no-op。 */
    public synchronized void seal() throws SQLException, IOException {
        if (sealed) {
            return;
        }
        sealed = true;
        ensureOpen();
        commitTx();
        if (build) {
            // 全量库是独立 tmp 文件，不会与读会话竞争，可以截断 WAL 后再原子替换。
            exec("PRAGMA wal_checkpoint(TRUNCATE)");
        } else {
            // 增量库有常驻只读会话，截断必然被挡住；被动回写即可。
            checkpointPassive();
        }

        if (build) {
            closeQuietly();
            fsync(tmpPath);
            // 目标库可能残留旧读会话未 checkpoint 的 WAL / SHM，必须先清掉：
            // 否则替换后新库会回放旧 WAL（旧数据复现、新数据丢失，甚至 SQLITE_CORRUPT）。
            deleteSidecars(path);
            Files.move(tmpPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            fsyncDirectory(path);
            deleteFileAndSidecars(tmpPath);
            logger.info("世代化替换完成: {} -> {}", tmpPath, path);
        }
    }

    @Override
    public synchronized void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            commitTx();
        } catch (SQLException e) {
            logger.warn("关闭前提交失败: {}", e.getMessage());
        }

        boolean wasSealed = sealed;
        closeQuietly();

        if (build && !wasSealed) {
            deleteFileAndSidecars(tmpPath);
            logger.warn("全量构建未完成，已丢弃临时世代（正式库不受影响）");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 静态辅助                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 预检：只读读取版本 / 选项元数据。
     *
     * <p>返回 null 表示「库不存在或无法确认」；返回的 {@link StoreMeta#readFailed}
     * 为 true 表示瞬时读失败（已重试），与「读到了但版本不匹配」区分开。
     */
    public static StoreMeta peekMeta(String pathValue) {
        Path file = Paths.get(pathValue).toAbsolutePath();
        if (!Files.exists(file)) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = openReadOnlyConnection(file);
                 Statement cmd = conn.createStatement()) {
                if (!tableExists(conn, "search_meta")) {
                    // 空库 / 无表：属「读到但不匹配」，交给全量重建。
                    return new StoreMeta();
                }
                StoreMeta meta = new StoreMeta();
                try (ResultSet reader = cmd.executeQuery("SELECT key, value FROM search_meta")) {
                    while (reader.next()) {
                        applyMeta(meta, reader.getString(1), reader.getString(2) == null ? "" : reader.getString(2));
                    }
                }
                return meta;
            } catch (Exception e) {
                if (attempt == 0) {
                    logger.warn("读取既有索引元数据失败，重试一次: {}", e.getMessage());
                    continue;
                }
                // 读失败（BUSY / IO）不能当成版本升级，否则会误触发全量重建。
                logger.warn("读取既有索引元数据仍失败（不据此判定版本不符）: {}", e.getMessage());
                StoreMeta failed = new StoreMeta();
                failed.readFailed = true;
                return failed;
            }
        }
        return null;
    }

    /** 预检：既有库的 schema / analyzer 版本是否与当前代码一致（读失败视为「无法确认」，不触发重建）。 */
    public static boolean versionsMatch(String pathValue) {
        StoreMeta meta = peekMeta(pathValue);
        if (meta == null) {
            return false;
        }
        if (meta.readFailed) {
            return true;
        }
        return meta.schemaVersion == SCHEMA_VERSION
                && meta.analyzerVersion == Analyzer.ANALYZER_VERSION;
    }

    /* ------------------------------------------------------------------ */
    /* 内部实现                                                            */
    /* ------------------------------------------------------------------ */

    private void ensureSchema() throws SQLException {
        exec("""
                CREATE TABLE IF NOT EXISTS search_docs (
                  doc_id INTEGER PRIMARY KEY,
                  track_id TEXT NOT NULL UNIQUE,
                  indexed_mtime INTEGER, indexed_lrc_mtime INTEGER, indexed_lrc_size INTEGER,
                  indexed_generation INTEGER, schema_version INTEGER,
                  reading_tok TEXT NOT NULL DEFAULT ''
                )
                """);
        exec("""
                CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(
                  %s,
                  content='', contentless_delete=1, tokenize='unicode61'
                )
                """.formatted(FTS_COLUMNS));
        exec("CREATE TABLE IF NOT EXISTS search_meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    private void afterWrite() throws SQLException {
        pending++;
        writeEpoch++;
        if (batchSize > 0 && pending >= batchSize) {
            commitTx();
        }
    }

    private void ensureTx() throws SQLException {
        if (!autoCommit) {
            return;
        }
        connection.setAutoCommit(false);
        autoCommit = false;
        pending = 0;
    }

    private void commitTx() throws SQLException {
        if (autoCommit || connection == null) {
            return;
        }
        connection.commit();
        connection.setAutoCommit(true);
        autoCommit = true;
        pending = 0;
    }

    private void ensureOpen() {
        if (connection == null) {
            throw new IllegalStateException("存储尚未打开（open 未调用）");
        }
    }

    private void exec(String sql) throws SQLException {
        ensureOpen();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalarLong(String sql) throws SQLException {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet reader = statement.executeQuery(sql)) {
            return reader.next() ? reader.getLong(1) : 0;
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement cmd = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name = ? LIMIT 1")) {
            cmd.setString(1, table);
            try (ResultSet reader = cmd.executeQuery()) {
                return reader.next();
            }
        }
    }

    private static Connection openReadOnlyConnection(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Properties properties = config.toProperties();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file, properties);
        configureBusyTimeout(conn);
        return conn;
    }

    /** 连接级忙等待：读写并发时最多等 5 秒，避免直接抛 SQLITE_BUSY。 */
    private static void configureBusyTimeout(Connection conn) {
        try (Statement statement = conn.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            logger.debug("设置 busy_timeout 失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 被动 checkpoint：只回写能回写的页，不截断 WAL、不阻塞读会话。
     * 返回的 busy 标记非 0 表示本轮被读会话挡住，属正常情况，仅记录调试日志。
     */
    private void checkpointPassive() {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet reader = statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")) {
            if (reader.next() && reader.getInt(1) != 0) {
                logger.debug("WAL checkpoint 被读会话阻塞，本轮跳过（WAL 由后续轮次回收）");
            }
        } catch (SQLException e) {
            logger.debug("WAL checkpoint 失败（忽略）: {}", e.getMessage());
        }
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭异常不影响调用方。
            }
            connection = null;
            autoCommit = true;
        }
    }

    private static void fsync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void fsyncDirectory(Path file) {
        Path directory = file.toAbsolutePath().getParent();
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception e) {
            logger.warn("目录 fsync 失败（已忽略）: {}", e.getMessage());
        }
    }

    /** 只清理数据库的 -wal / -shm / -journal 侧边文件，保留主库文件本身。 */
    private static void deleteSidecars(Path file) {
        if (file == null) {
            return;
        }
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            try {
                Files.deleteIfExists(Paths.get(file + suffix));
            } catch (IOException e) {
                logger.warn("清理侧边文件失败 {}{}: {}", file, suffix, e.getMessage());
            }
        }
    }

    private static void deleteFileAndSidecars(Path file) {
        if (file == null) {
            return;
        }
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            try {
                Files.deleteIfExists(Paths.get(file + suffix));
            } catch (IOException e) {
                logger.warn("清理文件失败 {}{}: {}", file, suffix, e.getMessage());
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean parseBool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static void applyMeta(StoreMeta meta, String key, String value) {
        switch (key) {
            case "schema_version" -> meta.schemaVersion = parseInt(value);
            case "analyzer_version" -> meta.analyzerVersion = parseInt(value);
            case "index_lrc" -> meta.indexLrc = parseBool(value);
            case "pinyin" -> meta.pinyin = parseBool(value);
            case "romaji_lyrics" -> meta.romajiLyrics = parseBool(value);
            case "applied_generation" -> meta.appliedGeneration = parseLong(value);
            case "built_at" -> meta.builtAt = parseLong(value);
            case "full_build_at" -> meta.fullBuildAt = parseLong(value);
            case "has_japanese" -> meta.hasJapanese = parseBool(value);
            case "has_korean" -> meta.hasKorean = parseBool(value);
            case "has_han" -> meta.hasHan = parseBool(value);
            case "has_latin" -> meta.hasLatin = parseBool(value);
            case "updates_since_merge" -> meta.updatesSinceMerge = parseInt(value);
            default -> {
                // 未知键忽略（向前兼容）。
            }
        }
    }
}
