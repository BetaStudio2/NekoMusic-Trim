/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 本地搜索索引服务：灰度开关、后台构建 / 增量刷新、查询入口。
 * 索引实现移植自 ArchoeraMusic/app/core/indexer（AGPL-3.0-or-later）。
 */

package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.search.LibraryIndexer;
import com.neko.music.search.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 本地搜索索引的生命周期与调用入口。
 *
 * <p>设计要点：
 * <ul>
 *   <li>开关 {@code search_index.enabled} 在每次调用时读取，可在后台切换；</li>
 *   <li>索引构建 / 单曲刷新在**单线程有界队列**里执行，队列满时丢弃，
 *       由周期刷新兜底，绝不阻塞搜索请求或上传主流程；</li>
 *   <li>查询走只读会话（{@link SearchSession}），与写入连接分离。</li>
 * </ul>
 */
public final class SearchIndexService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SearchIndexService.class);

    private final ConfigManager config;
    private final DatabaseManager databaseManager;

    private final Object lock = new Object();

    private LibraryIndexer indexer;
    private Path indexPath;
    private ThreadPoolExecutor writer;
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;
    private volatile boolean started;

    public SearchIndexService(ConfigManager config, DatabaseManager databaseManager) {
        this.config = config;
        this.databaseManager = databaseManager;
        this.indexPath = resolvePath(config.getSearchIndexPath());
    }

    /** 灰度开关：每次实时读取配置。 */
    public boolean enabled() {
        return config.isSearchIndexEnabled();
    }

    /** 索引文件路径（诊断 / 运维用）。 */
    public Path indexPath() {
        return indexPath;
    }

    /**
     * 曲目变更 / 删除后的统一通知入口（供各入库、改歌词、审核路径调用）。
     *
     * <p>索引未启用时直接返回：旧的内存歌词索引已下线，不再有第二条重建路径。</p>
     */
    public static void notifyMusicChanged(int musicId) {
        if (musicId <= 0) {
            return;
        }
        SearchIndexService service = com.neko.music.Main.getSearchIndexService();
        if (service != null && service.enabled()) {
            service.onMusicChanged(musicId);
        }
    }

    /** 启动后台构建与周期刷新（幂等）。开关在任务内部逐次判断，支持后台动态开启。 */
    public void start() {
        synchronized (lock) {
            if (started || closed) {
                return;
            }
            started = true;
            if (!enabled()) {
                logger.info("本地搜索索引未启用（search_index.enabled=false），调度器仍会周期检查开关");
            } else {
                ensureIndexerLocked();
            }
            // 调度器常驻：否则启动时开关关闭、之后在后台打开也没有任何周期刷新。
            int interval = config.getSearchIndexRefreshSeconds();
            if (interval > 0) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "search-index-refresh");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(this::runScheduledRefresh,
                        interval, interval, TimeUnit.SECONDS);
            }
            logger.info("本地搜索索引调度已启动: path={}, refresh={}s, enabled={}",
                    indexPath, interval, enabled());
        }
    }

    /**
     * 查询召回：返回按索引优先级排序的 trackId（即 music.id 的字符串）与 bm25 分数。
     * 索引未就绪 / 未启用时返回空列表，调用方可安全回退到旧检索。
     */
    public List<SearchSession.SearchHit> search(String query, int limit, int minExact) {
        if (!enabled() || query == null || query.isBlank() || closed) {
            return List.of();
        }
        // 首次构建尚未完成时索引文件不存在：直接返回空，避免每次查询都打开失败并打 warn。
        if (!Files.exists(indexPath)) {
            return List.of();
        }
        LibraryIndexer current = ensureIndexer();
        if (current == null) {
            return List.of();
        }
        try {
            SearchSession.SearchResult result = SearchSession.run(indexPath.toString(),
                    new SearchSession.SearchRequest(query, false, limit, 0, true, minExact,
                            com.neko.music.search.FtsStore.DEFAULT_CANDIDATE_CAP));
            return result.items();
        } catch (Exception e) {
            logger.warn("索引查询失败，回退旧检索: query='{}', error={}", query, e.toString());
            return List.of();
        }
    }

    /** 曲目元数据 / 歌词变更后调用（异步、可丢弃）。 */
    public void onMusicChanged(int musicId) {
        if (!enabled() || musicId <= 0 || closed) {
            return;
        }
        LibraryIndexer current = ensureIndexer();
        if (current == null) {
            return;
        }
        submit(() -> {
            try {
                current.refreshOne(musicId);
                SearchSession.invalidateQueryCache();
            } catch (Exception e) {
                logger.warn("单曲索引刷新失败 musicId={}: {}", musicId, e.getMessage());
            }
        });
    }

    /** 曲目删除后调用（异步、可丢弃）。 */
    public void onMusicRemoved(int musicId) {
        if (!enabled() || musicId <= 0 || closed) {
            return;
        }
        LibraryIndexer current = indexer;
        if (current == null) {
            return;
        }
        submit(() -> {
            try {
                current.removeOne(musicId);
                SearchSession.invalidateQueryCache();
            } catch (Exception e) {
                logger.warn("曲目索引删除失败 musicId={}: {}", musicId, e.getMessage());
            }
        });
    }

    /** 立即触发一次增量刷新（运维 / 测试用）。 */
    public void refreshNow() {
        if (!enabled() || closed) {
            return;
        }
        LibraryIndexer current = ensureIndexer();
        if (current == null) {
            return;
        }
        submit(() -> {
            try {
                current.refresh();
                SearchSession.invalidateQueryCache();
            } catch (Exception e) {
                logger.warn("索引增量刷新失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (writer != null) {
            writer.shutdownNow();
            try {
                // refresh() 是 synchronized 且不响应中断，给个短超时等它收尾，避免停机被全量构建长时间阻塞。
                if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("索引写线程未在 5 秒内结束，继续关闭索引器");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SearchSession.closeCached();
        if (indexer != null) {
            indexer.close();
        }
    }

    /* ------------------------------------------------------------------ */

    private LibraryIndexer ensureIndexer() {
        synchronized (lock) {
            if (closed || !enabled()) {
                return null;
            }
            return ensureIndexerLocked();
        }
    }

    private LibraryIndexer ensureIndexerLocked() {
        if (indexer != null) {
            return indexer;
        }
        try {
            Path parent = indexPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            indexer = new LibraryIndexer(indexPath, databaseManager::getConnection);
            writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(512),
                    runnable -> {
                        Thread thread = new Thread(runnable, "search-index-writer");
                        thread.setDaemon(true);
                        return thread;
                    },
                    // 队列满时在提交线程记一条告警后丢弃：不阻塞搜索 / 上传，交给周期刷新兜底。
                    (runnable, executor) -> logger.warn(
                            "索引写任务队列已满，本次任务已丢弃，交由周期刷新兜底"));
            LibraryIndexer created = indexer;
            submit(() -> {
                try {
                    created.ensureReady();
                    SearchSession.invalidateQueryCache();
                    logger.info("本地搜索索引就绪: {}", indexPath);
                } catch (Exception e) {
                    logger.error("本地搜索索引构建失败", e);
                }
            });
        } catch (Exception e) {
            logger.error("初始化本地搜索索引失败", e);
            indexer = null;
        }
        return indexer;
    }

    private void runScheduledRefresh() {
        if (closed || !enabled()) {
            return;
        }
        try {
            // 开关可能是启动后才打开的，这里按需创建索引器与写线程池。
            LibraryIndexer current = ensureIndexer();
            if (current != null) {
                current.refresh();
                SearchSession.invalidateQueryCache();
            }
        } catch (Exception e) {
            logger.warn("周期刷新本地搜索索引失败: {}", e.getMessage());
        }
    }

    private void submit(Runnable task) {
        ThreadPoolExecutor executor = writer;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("索引写任务队列已满，交由周期刷新兜底");
        }
    }

    private static Path resolvePath(String raw) {
        String value = raw == null || raw.isBlank() ? "data/search.db" : raw.trim();
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }
}
