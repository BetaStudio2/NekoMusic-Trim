package com.neko.music.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class DatabaseInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseInitializer.class);
    
    public static void initializeTables(DatabaseManager databaseManager) {
        logger.info("开始初始化数据库表...");
        
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 创建用户收藏表
            String createFavoritesTable = """
                CREATE TABLE IF NOT EXISTS user_favorites (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    music_id INT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_music (user_id, music_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_music_id (music_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createFavoritesTable,
                    "user_favorites 表创建成功", "创建 user_favorites 表失败（可能是表已存在）");
            
            // 创建用户收藏歌单表
            String createFavoritePlaylistsTable = """
                CREATE TABLE IF NOT EXISTS user_favorite_playlists (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    playlist_id INT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_playlist (user_id, playlist_id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_playlist_id (playlist_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createFavoritePlaylistsTable,
                    "user_favorite_playlists 表创建成功", "创建 user_favorite_playlists 表失败（可能是表已存在）");

            // 登录用户搜索画像：只保存规范化后的查询词，用于站内个性化歌单推荐。
            // 同一查询在短时间内重复出现只更新时间、不重复累计次数，避免前端并行搜索造成权重虚高。
            String createUserSearchHistoryTable = """
                CREATE TABLE IF NOT EXISTS user_search_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    query_text VARCHAR(255) NOT NULL,
                    normalized_query VARCHAR(255) NOT NULL,
                    search_count INT NOT NULL DEFAULT 1,
                    first_searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_user_search_query (user_id, normalized_query),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_search_history_recent (user_id, last_searched_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

            executeTableDdl(stmt, createUserSearchHistoryTable,
                    "user_search_history 表创建成功", "创建 user_search_history 表失败（可能是表已存在）");
            
            // 创建用户上传审核表
            String createUserUploadsTable = """
                CREATE TABLE IF NOT EXISTS user_uploads (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    artist VARCHAR(255) NOT NULL,
                    album VARCHAR(255),
                    language VARCHAR(50) NOT NULL,
                    tags VARCHAR(255),
                    duration INT DEFAULT 0,
                    music_file_path VARCHAR(512) NOT NULL,
                    cover_file_path VARCHAR(512),
                    lyrics_file_path VARCHAR(512),
                    status ENUM('pending', 'approved', 'rejected') DEFAULT 'pending',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_status (status),
                    INDEX idx_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            
            executeTableDdl(stmt, createUserUploadsTable,
                    "user_uploads 表创建成功", "创建 user_uploads 表失败（可能是表已存在）");

            // 用户歌词报错：保存提交版本与审核状态，审核通过后才覆盖正式歌词。
            String createLyricsReportsTable = """
                CREATE TABLE IF NOT EXISTS lyrics_reports (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    music_id INT NOT NULL,
                    user_id INT NOT NULL,
                    status ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending',
                    format VARCHAR(16) NOT NULL DEFAULT 'neko-v1',
                    content MEDIUMTEXT NOT NULL,
                    original_content MEDIUMTEXT NULL,
                    original_format VARCHAR(16) NULL,
                    note VARCHAR(500) NULL,
                    review_note VARCHAR(500) NULL,
                    reviewer_id INT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    reviewed_at DATETIME NULL,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_lyrics_reports_pending (status, created_at),
                    INDEX idx_lyrics_reports_user_music (user_id, music_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createLyricsReportsTable,
                    "lyrics_reports 表创建成功", "创建 lyrics_reports 表失败（可能是表已存在）");

            // 创建歌曲评论表（每首歌的评论与楼层回复）
            String createMusicCommentsTable = """
                CREATE TABLE IF NOT EXISTS music_comments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    music_id INT NOT NULL,
                    user_id INT NOT NULL,
                    parent_id INT NULL,
                    reply_to_user_id INT NULL,
                    content VARCHAR(500) NOT NULL,
                    ip_region VARCHAR(64) NOT NULL DEFAULT '',
                    client_ip VARCHAR(45) NOT NULL DEFAULT '',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_music_comments_music (music_id, parent_id, created_at),
                    INDEX idx_music_comments_user (user_id),
                    INDEX idx_music_comments_parent (parent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

            executeTableDdl(stmt, createMusicCommentsTable,
                    "music_comments 表创建成功", "创建 music_comments 表失败（可能是表已存在）");
            migrateMusicCommentsTable(stmt);
            
            // 回填拼音索引列：为 title_pinyin 等字段为 NULL 的记录计算拼音
            backfillPinyin(conn,
                    "SELECT id, title, artist, album FROM music WHERE title_pinyin IS NULL",
                    "UPDATE music SET title_pinyin=?, title_pinyin_initials=?, artist_pinyin=?, artist_pinyin_initials=?, album_pinyin=?, title_word_initials=?, artist_word_initials=? WHERE id=?",
                    "已回填 {} 条音乐记录的拼音索引",
                    "回填拼音索引失败",
                    (updateStmt, rs) -> {
                        int id = rs.getInt("id");
                        String title = rs.getString("title");
                        String artist = rs.getString("artist");
                        String album = rs.getString("album");
                        updateStmt.setString(1, com.neko.music.util.PinyinUtil.getPinyin(title));
                        updateStmt.setString(2, com.neko.music.util.PinyinUtil.getPinyinInitials(title));
                        updateStmt.setString(3, com.neko.music.util.PinyinUtil.getPinyin(artist));
                        updateStmt.setString(4, com.neko.music.util.PinyinUtil.getPinyinInitials(artist));
                        updateStmt.setString(5, album != null ? com.neko.music.util.PinyinUtil.getPinyin(album) : "");
                        updateStmt.setString(6, com.neko.music.util.PinyinUtil.getWordInitials(title));
                        updateStmt.setString(7, com.neko.music.util.PinyinUtil.getWordInitials(artist));
                        updateStmt.setInt(8, id);
                    });

            // 强制重算所有首字母列（getPinyinInitials和getWordInitials逻辑已变更，需全量更新）
            backfillPinyin(conn,
                    "SELECT id, title, artist FROM music",
                    "UPDATE music SET title_pinyin_initials=?, title_word_initials=?, artist_pinyin_initials=?, artist_word_initials=? WHERE id=?",
                    "已重算 {} 条音乐记录的首字母索引（中文/英文分离）",
                    "重算首字母索引失败",
                    (updateStmt, rs) -> {
                        int id = rs.getInt("id");
                        String title = rs.getString("title");
                        String artist = rs.getString("artist");
                        updateStmt.setString(1, com.neko.music.util.PinyinUtil.getPinyinInitials(title));
                        updateStmt.setString(2, com.neko.music.util.PinyinUtil.getWordInitials(title));
                        updateStmt.setString(3, com.neko.music.util.PinyinUtil.getPinyinInitials(artist));
                        updateStmt.setString(4, com.neko.music.util.PinyinUtil.getWordInitials(artist));
                        updateStmt.setInt(5, id);
                    });

            // 回填歌单拼音列
            backfillPinyin(conn,
                    "SELECT id, name FROM playlists WHERE name_pinyin IS NULL",
                    "UPDATE playlists SET name_pinyin=?, name_pinyin_initials=?, name_word_initials=? WHERE id=?",
                    "已回填 {} 条歌单记录的拼音索引",
                    "回填歌单拼音索引失败",
                    (updateStmt, rs) -> {
                        int id = rs.getInt("id");
                        String name = rs.getString("name");
                        updateStmt.setString(1, com.neko.music.util.PinyinUtil.getPinyin(name));
                        updateStmt.setString(2, com.neko.music.util.PinyinUtil.getPinyinInitials(name));
                        updateStmt.setString(3, com.neko.music.util.PinyinUtil.getWordInitials(name));
                        updateStmt.setInt(4, id);
                    });

            // 强制重算歌单首字母列（逻辑变更）
            backfillPinyin(conn,
                    "SELECT id, name FROM playlists",
                    "UPDATE playlists SET name_pinyin_initials=?, name_word_initials=? WHERE id=?",
                    "已重算 {} 条歌单记录的首字母索引（中文/英文分离）",
                    "重算歌单首字母索引失败",
                    (updateStmt, rs) -> {
                        int id = rs.getInt("id");
                        String name = rs.getString("name");
                        updateStmt.setString(1, com.neko.music.util.PinyinUtil.getPinyinInitials(name));
                        updateStmt.setString(2, com.neko.music.util.PinyinUtil.getWordInitials(name));
                        updateStmt.setInt(3, id);
                    });

            // VIP 价目表（与主库一并备份）
            String createVipPricing = """
                CREATE TABLE IF NOT EXISTS vip_pricing (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    months INT NOT NULL DEFAULT 0,
                    days INT NOT NULL DEFAULT 0,
                    price_yuan DECIMAL(12,2) NOT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_vip_pricing_sort (sort_order, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createVipPricing,
                    "vip_pricing 表已就绪", "创建 vip_pricing 表失败（可能已存在）");

            // 系统设置：原 config.yml 的运行时配置迁移到这里，后台可改
            String createSystemSettings = """
                CREATE TABLE IF NOT EXISTS system_settings (
                    setting_key VARCHAR(128) NOT NULL,
                    setting_value TEXT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    updated_by INT NULL,
                    PRIMARY KEY (setting_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createSystemSettings,
                    "system_settings 表已就绪", "创建 system_settings 表失败（可能已存在）");

            // 站内消息（收件箱）：评论回复等消息落库即视为送达，离线用户靠游标补拉
            String createUserNotifications = """
                CREATE TABLE IF NOT EXISTS user_notifications (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    body VARCHAR(500) NOT NULL DEFAULT '',
                    link VARCHAR(255) NOT NULL DEFAULT '',
                    actor_user_id INT NULL,
                    is_read TINYINT(1) NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_notifications_user (user_id, id),
                    INDEX idx_user_notifications_unread (user_id, is_read)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createUserNotifications,
                    "user_notifications 表已就绪", "创建 user_notifications 表失败（可能已存在）");

            String createVipPayOrders = """
                CREATE TABLE IF NOT EXISTS vip_pay_orders (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    out_trade_no VARCHAR(32) NOT NULL,
                    user_id INT NOT NULL,
                    pricing_id INT NULL,
                    months INT NOT NULL DEFAULT 0,
                    days INT NOT NULL DEFAULT 0,
                    money DECIMAL(12,2) NOT NULL,
                    pay_type VARCHAR(16) NOT NULL,
                    status ENUM('pending','paid','cancelled') NOT NULL DEFAULT 'pending',
                    zpay_trade_no VARCHAR(128) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    paid_at TIMESTAMP NULL DEFAULT NULL,
                    UNIQUE KEY uk_vip_pay_out_trade_no (out_trade_no),
                    KEY idx_vip_pay_user_created (user_id, created_at),
                    CONSTRAINT fk_vip_pay_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            executeTableDdl(stmt, createVipPayOrders,
                    "vip_pay_orders 表已就绪", "创建 vip_pay_orders 表失败（可能已存在）");

            migrateAppReleaseTable(conn, stmt);

            logger.info("数据库表初始化完成");
            
        } catch (Exception e) {
            logger.error("初始化数据库表失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 评论改为物理删除：先清掉历史软删除留下的墓碑行，再把 {@code deleted} 列彻底移除。
     * 老库执行一次即可，新库本就没有该列（两条语句都会因列不存在而失败，忽略即可）。
     */
    private static void migrateMusicCommentsTable(Statement stmt) {
        try {
            stmt.execute("""
                ALTER TABLE music_comments
                ADD COLUMN IF NOT EXISTS client_ip VARCHAR(45) NOT NULL DEFAULT '' AFTER ip_region
                """);
            logger.info("music_comments 已补齐 client_ip 列");
        } catch (Exception e) {
            logger.debug("music_comments 添加 client_ip 列: {}", e.getMessage());
        }
        try {
            int purged = stmt.executeUpdate("DELETE FROM music_comments WHERE deleted = 1");
            if (purged > 0) {
                logger.info("已清理 {} 条软删除的评论墓碑", purged);
            }
        } catch (Exception e) {
            logger.debug("music_comments 无 deleted 列，跳过墓碑清理: {}", e.getMessage());
        }
        try {
            stmt.execute("ALTER TABLE music_comments DROP COLUMN deleted");
            logger.info("music_comments 已移除 deleted 列，评论删除改为物理删除（含级联删除回复）");
        } catch (Exception e) {
            logger.debug("music_comments 移除 deleted 列: {}", e.getMessage());
        }
    }

    /** 执行建表 DDL，失败仅记录告警（表可能已存在）。 */
    private static void executeTableDdl(Statement stmt, String ddl, String successMessage, String failureMessage) {
        try {
            stmt.execute(ddl);
            logger.info(successMessage);
        } catch (Exception e) {
            logger.warn(failureMessage + ": {}", e.getMessage());
        }
    }

    /** 扫描查询结果集，由绑定器写入对应拼音列，每 100 条批量执行一次更新。 */
    private static void backfillPinyin(Connection conn, String selectSql, String updateSql,
                                       String successMessage, String failureMessage,
                                       PinyinRowBinder binder) {
        try {
            try (var selectStmt = conn.prepareStatement(selectSql);
                 var rs = selectStmt.executeQuery();
                 var updateStmt = conn.prepareStatement(updateSql)) {
                int count = 0;
                while (rs.next()) {
                    binder.bind(updateStmt, rs);
                    updateStmt.addBatch();
                    count++;
                    if (count % 100 == 0) {
                        updateStmt.executeBatch();
                    }
                }
                if (count % 100 != 0) {
                    updateStmt.executeBatch();
                }
                if (count > 0) {
                    logger.info(successMessage, count);
                }
            }
        } catch (Exception e) {
            logger.warn(failureMessage + ": {}", e.getMessage());
        }
    }

    /** 将结果集当前行绑定到拼音更新语句的参数。 */
    @FunctionalInterface
    private interface PinyinRowBinder {
        void bind(PreparedStatement updateStmt, ResultSet rs) throws Exception;
    }

    /** 单行版本表：无 id，保存 Android / PC 两端版本 */
    private static void migrateAppReleaseTable(Connection conn, Statement stmt) {
        try {
            boolean tableExists = false;
            boolean hasIdColumn = false;
            String listCols = """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'app_release'
                """;
            try (PreparedStatement ps = conn.prepareStatement(listCols);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tableExists = true;
                    if ("id".equalsIgnoreCase(rs.getString(1))) {
                        hasIdColumn = true;
                    }
                }
            }

            if (tableExists && hasIdColumn) {
                String androidVer = null;
                String pcVer = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT android_ver, pc_ver FROM app_release ORDER BY id LIMIT 1");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        androidVer = rs.getString("android_ver");
                        pcVer = rs.getString("pc_ver");
                    }
                }
                stmt.execute("DROP TABLE app_release");
                logger.info("已删除带 id 的旧 app_release 表，准备重建");
                createAppReleaseTable(stmt);
                if (androidVer != null && !androidVer.isBlank() && pcVer != null && !pcVer.isBlank()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO app_release (android_ver, pc_ver) VALUES (?, ?)")) {
                        ins.setString(1, androidVer.trim());
                        ins.setString(2, pcVer.trim());
                        ins.executeUpdate();
                        logger.info("已迁移 app_release 版本数据");
                    }
                }
                return;
            }

            if (!tableExists) {
                createAppReleaseTable(stmt);
                logger.info("app_release 表已创建");
                return;
            }

            try {
                stmt.execute("""
                    ALTER TABLE app_release
                    ADD COLUMN IF NOT EXISTS pc_ver VARCHAR(64) NOT NULL DEFAULT '' AFTER android_ver
                    """);
            } catch (Exception e) {
                logger.debug("app_release 添加 pc_ver 列: {}", e.getMessage());
            }

            try {
                stmt.execute("""
                    ALTER TABLE app_release
                    ADD COLUMN IF NOT EXISTS pending_android_ver VARCHAR(64) NULL AFTER pc_ver
                    """);
                stmt.execute("""
                    ALTER TABLE app_release
                    ADD COLUMN IF NOT EXISTS pending_pc_ver VARCHAR(64) NULL AFTER pending_android_ver
                    """);
                stmt.execute("""
                    ALTER TABLE app_release
                    ADD COLUMN IF NOT EXISTS pending_effective_at TIMESTAMP NULL AFTER pending_pc_ver
                    """);
            } catch (Exception e) {
                logger.debug("app_release 添加 pending 列: {}", e.getMessage());
            }
            logger.info("app_release 表已就绪");
        } catch (Exception e) {
            logger.warn("app_release 表迁移失败: {}", e.getMessage());
        }
    }

    private static void createAppReleaseTable(Statement stmt) throws Exception {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS app_release (
                android_ver VARCHAR(64) NOT NULL,
                pc_ver VARCHAR(64) NOT NULL,
                pending_android_ver VARCHAR(64) NULL,
                pending_pc_ver VARCHAR(64) NULL,
                pending_effective_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
