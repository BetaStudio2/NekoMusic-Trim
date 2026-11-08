package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 登录用户搜索画像写入。
 *
 * <p>搜索是高频读路径，画像记录不能让 MySQL 往返卡住响应，因此写入有界队列后异步落库。
 * 队列满时直接丢弃本条画像信号：推荐仍可依赖收藏、歌单与协同过滤继续工作。</p>
 */
public class UserSearchHistoryService {
    private static final Logger logger = LoggerFactory.getLogger(UserSearchHistoryService.class);
    private static final int QUEUE_CAPACITY = 2048;
    private static final int RETENTION_DAYS = 180;
    private static final String UPSERT_SQL = """
            INSERT INTO user_search_history
                (user_id, query_text, normalized_query, search_count, first_searched_at, last_searched_at)
            VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                query_text = VALUES(query_text),
                search_count = IF(last_searched_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE),
                                  search_count + 1, search_count),
                last_searched_at = CURRENT_TIMESTAMP
            """;

    private final DatabaseManager databaseManager;
    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService maintenance;

    public UserSearchHistoryService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.writer = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofPlatform().daemon().name("search-history-writer-", 0).factory(),
                new ThreadPoolExecutor.DiscardPolicy());
        this.maintenance = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("search-history-maintenance-", 0).factory());
        this.maintenance.scheduleAtFixedRate(this::pruneExpired, 5, 24 * 60, TimeUnit.MINUTES);
    }

    public void recordAsync(int userId, String rawQuery) {
        String query = normalizeQuery(rawQuery);
        if (userId <= 0 || query.isEmpty()) {
            return;
        }
        writer.execute(() -> upsert(userId, query));
    }

    void upsert(int userId, String normalizedQuery) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setInt(1, userId);
            ps.setString(2, normalizedQuery);
            ps.setString(3, normalizedQuery.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
            pruneOldQueries(conn, userId);
        } catch (Exception e) {
            logger.warn("写入用户搜索画像失败 userId={}", userId, e);
        }
    }

    private static void pruneOldQueries(Connection conn, int userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM user_search_history
                WHERE user_id = ? AND last_searched_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)
                """)) {
            ps.setInt(1, userId);
            ps.setInt(2, RETENTION_DAYS);
            ps.executeUpdate();
        }
    }

    private void pruneExpired() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     DELETE FROM user_search_history
                     WHERE last_searched_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)
                     """)) {
            ps.setInt(1, RETENTION_DAYS);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                logger.info("已清理 {} 条过期用户搜索画像", deleted);
            }
        } catch (Exception e) {
            logger.warn("清理过期用户搜索画像失败", e);
        }
    }

    static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 255) {
            cleaned = cleaned.substring(0, 255).trim();
        }
        return cleaned;
    }

    public void shutdown() {
        maintenance.shutdownNow();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
