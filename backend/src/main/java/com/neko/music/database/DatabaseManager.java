package com.neko.music.database;

import com.neko.music.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;
    private ConfigManager configManager;

    public DatabaseManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void init() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + configManager.getMysqlHost() + ":" +
                         configManager.getMysqlPort() + "/" + configManager.getMysqlDatabase() +
                         "?useSSL=false&serverTimezone=Asia/Shanghai&connectionTimeZone=Asia/Shanghai" +
                         "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true" +
                         "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true");
        config.setUsername(configManager.getMysqlUsername());
        config.setPassword(configManager.getMysqlPassword());
        config.setMaximumPoolSize(configManager.getHikariMaximumPoolSize());
        config.setMinimumIdle(Math.min(configManager.getHikariMinimumIdle(), configManager.getHikariMaximumPoolSize()));
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // 设置连接初始化SQL，确保每次连接都使用UTC+8时区
        config.setConnectionInitSql("SET time_zone = '+08:00'");
        
        this.dataSource = new HikariDataSource(config);
        
        // 初始化数据库表
        initializeTables();
    }

    private void initializeTables() {
        try (Connection conn = dataSource.getConnection()) {
            // 创建用户表
            String createUserTable = """
                CREATE TABLE IF NOT EXISTS users (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    email VARCHAR(100) UNIQUE NOT NULL,
                    auth_user_id BIGINT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """;
            try (PreparedStatement stmt = conn.prepareStatement(createUserTable)) {
                stmt.execute();
            }
            
            // 创建音乐表
            String createMusicTable = """
                CREATE TABLE IF NOT EXISTS music (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    artist VARCHAR(255) NOT NULL,
                    album VARCHAR(255),
                    duration INT, -- 时长，单位秒
                    file_format VARCHAR(10) DEFAULT 'mp3', -- 音频文件格式：mp3/flac/wav
                    language VARCHAR(50) NOT NULL DEFAULT '未知语言',
                    tags VARCHAR(500),
                    upload_user_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (upload_user_id) REFERENCES users(id)
                )
                """;
            try (PreparedStatement stmt = conn.prepareStatement(createMusicTable)) {
                stmt.execute();
            }

            String createMusicLyricsTable = """
                CREATE TABLE IF NOT EXISTS music_lyrics (
                    music_id INT NOT NULL PRIMARY KEY,
                    content MEDIUMTEXT NOT NULL,
                    plain_text MEDIUMTEXT NULL,
                    content_hash CHAR(64) NOT NULL,
                    format VARCHAR(16) NOT NULL DEFAULT 'lrc',
                    is_placeholder BOOLEAN NOT NULL DEFAULT FALSE,
                    size_bytes INT UNSIGNED NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT fk_music_lyrics_music
                        FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    KEY idx_music_lyrics_updated_at (updated_at),
                    KEY idx_music_lyrics_placeholder (is_placeholder)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            try (PreparedStatement stmt = conn.prepareStatement(createMusicLyricsTable)) {
                stmt.execute();
            }

            // 移除旧版 music 表的 file_path / cover_path（一次 ALTER 合并 DROP，减少锁表次数）
            try {
                List<String> dropParts = new ArrayList<>();
                String listLegacyCols = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'music'
                    AND column_name IN ('file_path','cover_path')
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(listLegacyCols);
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String col = rs.getString(1);
                        dropParts.add("DROP COLUMN " + col);
                    }
                }
                if (!dropParts.isEmpty()) {
                    String alter = "ALTER TABLE music " + String.join(", ", dropParts);
                    try (PreparedStatement stmt = conn.prepareStatement(alter)) {
                        stmt.execute();
                        logger.info("已删除 music 表废弃列: {}", dropParts);
                    }
                }
            } catch (SQLException e) {
                logger.debug("删除 music 废弃列失败: {}", e.getMessage());
            }

            // 为已存在的 music 表添加 file_format 字段（如果不存在）
            try {
                String alterTable = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS file_format VARCHAR(10) DEFAULT 'mp3'
                    AFTER duration
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(alterTable)) {
                stmt.execute();
            }
                logger.info("已为 music 表添加 file_format 字段");
            } catch (SQLException e) {
                logger.debug("file_format 字段可能已存在，跳过添加");
            }

            String[] qualityColumns = {
                    "ADD COLUMN IF NOT EXISTS max_quality VARCHAR(16) DEFAULT NULL AFTER file_format",
                    "ADD COLUMN IF NOT EXISTS bitrate_bps INT DEFAULT 0 AFTER max_quality",
                    "ADD COLUMN IF NOT EXISTS sample_rate_hz INT DEFAULT 0 AFTER bitrate_bps",
                    "ADD COLUMN IF NOT EXISTS bits_per_sample INT DEFAULT 0 AFTER sample_rate_hz",
                    "ADD COLUMN IF NOT EXISTS channels INT DEFAULT 0 AFTER bits_per_sample"
            };
            for (String column : qualityColumns) {
                try (PreparedStatement stmt = conn.prepareStatement("ALTER TABLE music " + column)) {
                    stmt.execute();
                } catch (SQLException e) {
                    logger.debug("音质字段可能已存在，跳过: {}", column);
                }
            }

            // 为已存在的 music 表添加拼音索引列（如果不存在）
            try {
                String alterPinyin = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS title_pinyin VARCHAR(500) DEFAULT NULL
                    AFTER tags
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterPinyin)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 title_pinyin 字段");
            } catch (SQLException e) {
                logger.debug("title_pinyin 字段可能已存在，跳过添加");
            }
            try {
                String alterPinyinInitials = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS title_pinyin_initials VARCHAR(200) DEFAULT NULL
                    AFTER title_pinyin
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterPinyinInitials)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 title_pinyin_initials 字段");
            } catch (SQLException e) {
                logger.debug("title_pinyin_initials 字段可能已存在，跳过添加");
            }
            try {
                String alterArtistPinyin = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS artist_pinyin VARCHAR(500) DEFAULT NULL
                    AFTER title_pinyin_initials
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterArtistPinyin)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 artist_pinyin 字段");
            } catch (SQLException e) {
                logger.debug("artist_pinyin 字段可能已存在，跳过添加");
            }
            try {
                String alterArtistPinyinInitials = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS artist_pinyin_initials VARCHAR(200) DEFAULT NULL
                    AFTER artist_pinyin
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterArtistPinyinInitials)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 artist_pinyin_initials 字段");
            } catch (SQLException e) {
                logger.debug("artist_pinyin_initials 字段可能已存在，跳过添加");
            }
            try {
                String alterAlbumPinyin = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS album_pinyin VARCHAR(500) DEFAULT NULL
                    AFTER artist_pinyin_initials
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterAlbumPinyin)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 album_pinyin 字段");
            } catch (SQLException e) {
                logger.debug("album_pinyin 字段可能已存在，跳过添加");
            }

            // 为已存在的 music 表添加词首字母索引列（英文单词首字母，如 "just h party" → "jhp"）
            try {
                String alterTitleWordInitials = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS title_word_initials VARCHAR(200) DEFAULT NULL
                    AFTER album_pinyin
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterTitleWordInitials)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 title_word_initials 字段");
            } catch (SQLException e) {
                logger.debug("title_word_initials 字段可能已存在，跳过添加");
            }
            try {
                String alterArtistWordInitials = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS artist_word_initials VARCHAR(200) DEFAULT NULL
                    AFTER title_word_initials
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterArtistWordInitials)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 artist_word_initials 字段");
            } catch (SQLException e) {
                logger.debug("artist_word_initials 字段可能已存在，跳过添加");
            }

            // 为拼音列添加索引
            try {
                String createPinyinIndex = """
                    ALTER TABLE music ADD INDEX idx_title_pinyin (title_pinyin(100))
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(createPinyinIndex)) {
                    stmt.execute();
                }
                logger.info("已为 title_pinyin 添加索引");
            } catch (SQLException e) {
                logger.debug("title_pinyin 索引可能已存在，跳过");
            }
            try {
                String createPinyinInitialsIndex = """
                    ALTER TABLE music ADD INDEX idx_title_pinyin_initials (title_pinyin_initials(50))
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(createPinyinInitialsIndex)) {
                    stmt.execute();
                }
                logger.info("已为 title_pinyin_initials 添加索引");
            } catch (SQLException e) {
                logger.debug("title_pinyin_initials 索引可能已存在，跳过");
            }
            try {
                String createArtistPinyinIndex = """
                    ALTER TABLE music ADD INDEX idx_artist_pinyin (artist_pinyin(100))
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(createArtistPinyinIndex)) {
                    stmt.execute();
                }
                logger.info("已为 artist_pinyin 添加索引");
            } catch (SQLException e) {
                logger.debug("artist_pinyin 索引可能已存在，跳过");
            }
            try {
                String createTitleWordInitialsIndex = """
                    ALTER TABLE music ADD INDEX idx_title_word_initials (title_word_initials(50))
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(createTitleWordInitialsIndex)) {
                    stmt.execute();
                }
                logger.info("已为 title_word_initials 添加索引");
            } catch (SQLException e) {
                logger.debug("title_word_initials 索引可能已存在，跳过");
            }
            try {
                String createArtistWordInitialsIndex = """
                    ALTER TABLE music ADD INDEX idx_artist_word_initials (artist_word_initials(50))
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(createArtistWordInitialsIndex)) {
                    stmt.execute();
                }
                logger.info("已为 artist_word_initials 添加索引");
            } catch (SQLException e) {
                logger.debug("artist_word_initials 索引可能已存在，跳过");
            }

            try {
                String idxCreated = "ALTER TABLE music ADD INDEX idx_music_created_at (created_at)";
                try (PreparedStatement stmt = conn.prepareStatement(idxCreated)) {
                    stmt.execute();
                }
                logger.info("已为 music.created_at 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_music_created_at 可能已存在，跳过");
            }
            try {
                String idxPlay = "ALTER TABLE music ADD INDEX idx_music_play_count (play_count)";
                try (PreparedStatement stmt = conn.prepareStatement(idxPlay)) {
                    stmt.execute();
                }
                logger.info("已为 music.play_count 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_music_play_count 可能已存在，跳过");
            }
            try {
                String idxArtist = "ALTER TABLE music ADD INDEX idx_music_artist (artist(191))";
                try (PreparedStatement stmt = conn.prepareStatement(idxArtist)) {
                    stmt.execute();
                }
                logger.info("已为 music.artist 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_music_artist 可能已存在，跳过");
            }

            // 为歌单表添加拼音索引列（如果不存在）
            try {
                String alterNamePinyin = """
                    ALTER TABLE playlists
                    ADD COLUMN IF NOT EXISTS name_pinyin VARCHAR(500) DEFAULT NULL
                    AFTER description
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterNamePinyin)) {
                    stmt.execute();
                }
                logger.info("已为 playlists 表添加 name_pinyin 字段");
            } catch (SQLException e) {
                logger.debug("name_pinyin 字段可能已存在，跳过添加");
            }
            try {
                String alterNamePinyinInitials = """
                    ALTER TABLE playlists
                    ADD COLUMN IF NOT EXISTS name_pinyin_initials VARCHAR(200) DEFAULT NULL
                    AFTER name_pinyin
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterNamePinyinInitials)) {
                    stmt.execute();
                }
                logger.info("已为 playlists 表添加 name_pinyin_initials 字段");
            } catch (SQLException e) {
                logger.debug("name_pinyin_initials 字段可能已存在，跳过添加");
            }
            try {
                String alterNameWordInitials = """
                    ALTER TABLE playlists
                    ADD COLUMN IF NOT EXISTS name_word_initials VARCHAR(200) DEFAULT NULL
                    AFTER name_pinyin_initials
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterNameWordInitials)) {
                    stmt.execute();
                }
                logger.info("已为 playlists 表添加 name_word_initials 字段");
            } catch (SQLException e) {
                logger.debug("name_word_initials 字段可能已存在，跳过添加");
            }

            try {
                String idxPlCreated = "ALTER TABLE playlists ADD INDEX idx_playlists_created_at (created_at)";
                try (PreparedStatement stmt = conn.prepareStatement(idxPlCreated)) {
                    stmt.execute();
                }
                logger.info("已为 playlists.created_at 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_playlists_created_at 可能已存在，跳过");
            }
            try {
                String idxNp = "ALTER TABLE playlists ADD INDEX idx_playlists_name_pinyin (name_pinyin(100))";
                try (PreparedStatement stmt = conn.prepareStatement(idxNp)) {
                    stmt.execute();
                }
                logger.info("已为 playlists.name_pinyin 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_playlists_name_pinyin 可能已存在，跳过");
            }
            try {
                String idxNpi = "ALTER TABLE playlists ADD INDEX idx_playlists_name_pinyin_initials (name_pinyin_initials(50))";
                try (PreparedStatement stmt = conn.prepareStatement(idxNpi)) {
                    stmt.execute();
                }
                logger.info("已为 playlists.name_pinyin_initials 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_playlists_name_pinyin_initials 可能已存在，跳过");
            }
            try {
                String idxNwi = "ALTER TABLE playlists ADD INDEX idx_playlists_name_word_initials (name_word_initials(50))";
                try (PreparedStatement stmt = conn.prepareStatement(idxNwi)) {
                    stmt.execute();
                }
                logger.info("已为 playlists.name_word_initials 添加索引");
            } catch (SQLException e) {
                logger.debug("idx_playlists_name_word_initials 可能已存在，跳过");
            }

            // 为已存在的 music 表添加 play_count 字段（如果不存在）
            try {
                String alterPlayCountTable = """
                    ALTER TABLE music
                    ADD COLUMN IF NOT EXISTS play_count INT DEFAULT 0
                    AFTER file_format
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterPlayCountTable)) {
                    stmt.execute();
                }
                logger.info("已为 music 表添加 play_count 字段");
            } catch (SQLException e) {
                // 字段可能已存在，忽略错误
                logger.debug("play_count 字段可能已存在，跳过添加");
            }

            migrateUsersCreatedAtToDatetime(conn);
            dropUsersNicknameColumn(conn);
            dropUsersAvatarColumn(conn);

            try {
                // 检查email字段是否有唯一约束
                String checkEmailUnique = """
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                    AND table_name = 'users'
                    AND index_name != 'PRIMARY'
                    AND column_name = 'email'
                    AND non_unique = 0
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(checkEmailUnique);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // 添加email的唯一索引
                        String addIndex = "ALTER TABLE users ADD UNIQUE INDEX idx_email (email)";
                        try (PreparedStatement addStmt = conn.prepareStatement(addIndex)) {
                            addStmt.execute();
                            logger.info("已为 users 表的 email 字段添加唯一索引");
                        }
                    }
                }
            } catch (SQLException e) {
                logger.debug("添加 email 唯一索引失败（可能已存在）: {}", e.getMessage());
            }

            try {
                // 检查email字段是否允许NULL
                String checkNull = """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                    AND table_name = 'users'
                    AND column_name = 'email'
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(checkNull);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && "YES".equals(rs.getString("is_nullable"))) {
                        // 修改email字段为NOT NULL
                        String alterEmail = "ALTER TABLE users MODIFY email VARCHAR(100) UNIQUE NOT NULL";
                        try (PreparedStatement alterStmt = conn.prepareStatement(alterEmail)) {
                            alterStmt.execute();
                            logger.info("已修改 users 表的 email 字段为 NOT NULL");
                        }
                    }
                }
            } catch (SQLException e) {
                logger.debug("修改 email 字段为 NOT NULL 失败（可能已修改）: {}", e.getMessage());
            }

            // 为 users 表添加 VIP 到期时间（NULL 表示非会员）
            try {
                String alterVip = """
                    ALTER TABLE users
                    ADD COLUMN IF NOT EXISTS vip_expires_at TIMESTAMP NULL DEFAULT NULL
                    AFTER email
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterVip)) {
                    stmt.execute();
                    logger.info("已为 users 表添加 vip_expires_at 字段");
                }
            } catch (SQLException e) {
                logger.debug("vip_expires_at 字段可能已存在，跳过添加: {}", e.getMessage());
            }

            // 绑定 NekoAuth 账号 ID；与本地资料解耦，密码不再使用本地列。
            try {
                String alterAuthUser = """
                    ALTER TABLE users
                    ADD COLUMN IF NOT EXISTS auth_user_id BIGINT NULL
                    AFTER email
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(alterAuthUser)) {
                    stmt.execute();
                    logger.info("已为 users 表添加 auth_user_id 字段");
                }
            } catch (SQLException e) {
                logger.debug("auth_user_id 字段可能已存在，跳过添加: {}", e.getMessage());
            }
            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "ALTER TABLE users ADD UNIQUE INDEX uk_users_auth_user_id (auth_user_id)")) {
                    stmt.execute();
                    logger.info("已为 users.auth_user_id 添加唯一索引");
                }
            } catch (SQLException e) {
                logger.debug("users.auth_user_id 唯一索引可能已存在，跳过添加: {}", e.getMessage());
            }

            // 用户密码只存 NekoAuth；旧测试库上的 password 列不再保留。
            try {
                String checkPassword = """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                    AND table_name = 'users'
                    AND column_name = 'password'
                    """;
                boolean hasPassword;
                try (PreparedStatement stmt = conn.prepareStatement(checkPassword);
                     ResultSet rs = stmt.executeQuery()) {
                    hasPassword = rs.next() && rs.getInt(1) > 0;
                }
                if (hasPassword) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "ALTER TABLE users DROP COLUMN password")) {
                        stmt.execute();
                        logger.info("已移除 users.password；用户密码仅由 NekoAuth 保存");
                    }
                }
            } catch (SQLException e) {
                logger.warn("移除 users.password 失败: {}", e.getMessage());
            }

            // 创建歌单表
            String createPlaylistTable = """
                CREATE TABLE IF NOT EXISTS playlists (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description VARCHAR(500),
                    name_pinyin VARCHAR(500) DEFAULT NULL,
                    name_pinyin_initials VARCHAR(200) DEFAULT NULL,
                    name_word_initials VARCHAR(200) DEFAULT NULL,
                    music_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                    INDEX idx_user_id (user_id),
                    INDEX idx_playlists_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            try (PreparedStatement stmt = conn.prepareStatement(createPlaylistTable)) {
                stmt.execute();
                logger.info("playlists 表创建完成");
            } catch (SQLException e) {
                logger.debug("playlists 表可能已存在: {}", e.getMessage());
            }

            // 创建歌单音乐关联表
            String createPlaylistMusicTable = """
                CREATE TABLE IF NOT EXISTS playlist_music (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    playlist_id INT NOT NULL,
                    music_id INT NOT NULL,
                    position INT NOT NULL,
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY unique_playlist_music_position (playlist_id, position),
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY (music_id) REFERENCES music(id) ON DELETE CASCADE,
                    INDEX idx_playlist_position (playlist_id, position),
                    INDEX idx_music_id (music_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
            try (PreparedStatement stmt = conn.prepareStatement(createPlaylistMusicTable)) {
                stmt.execute();
                logger.info("playlist_music 表创建完成");
            } catch (SQLException e) {
                logger.debug("playlist_music 表可能已存在: {}", e.getMessage());
            }

            try {
                String addPmIdx = "ALTER TABLE playlist_music ADD INDEX idx_playlist_position (playlist_id, position)";
                try (PreparedStatement stmt = conn.prepareStatement(addPmIdx)) {
                    stmt.execute();
                }
                logger.info("已为 playlist_music 添加复合索引 idx_playlist_position");
            } catch (SQLException e) {
                logger.debug("idx_playlist_position 可能已存在，跳过");
            }

            // 删除 playlists 表的 cover_path 字段（如果存在）
            try {
                String checkColumn = """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                    AND table_name = 'playlists'
                    AND column_name = 'cover_path'
                    """;
                try (PreparedStatement stmt = conn.prepareStatement(checkColumn);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        // 删除cover_path字段
                        String dropColumn = "ALTER TABLE playlists DROP COLUMN cover_path";
                        try (PreparedStatement dropStmt = conn.prepareStatement(dropColumn)) {
                            dropStmt.execute();
                            logger.info("已删除 playlists 表的 cover_path 字段");
                        }
                    }
                }
            } catch (SQLException e) {
                logger.debug("删除 cover_path 字段失败（可能不存在）: {}", e.getMessage());
            }

            logger.info("数据库表初始化完成");
        } catch (SQLException e) {
            logger.error("数据库表初始化失败", e);
        }
    }

    /**
     * users.created_at 使用 DATETIME 存东八区墙钟，避免 TIMESTAMP 在客户端显示为 UTC。
     */
    private void migrateUsersCreatedAtToDatetime(Connection conn) {
        try {
            String checkType = """
                SELECT DATA_TYPE FROM information_schema.columns
                WHERE table_schema = DATABASE()
                AND table_name = 'users'
                AND column_name = 'created_at'
                """;
            try (PreparedStatement stmt = conn.prepareStatement(checkType);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && "timestamp".equalsIgnoreCase(rs.getString(1))) {
                    try (var tz = conn.createStatement()) {
                        tz.execute("SET time_zone = '+08:00'");
                    }
                    String alter = """
                        ALTER TABLE users
                        MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                        """;
                    try (PreparedStatement alterStmt = conn.prepareStatement(alter)) {
                        alterStmt.execute();
                        logger.info("已将 users.created_at 从 TIMESTAMP 迁移为 DATETIME（东八区墙钟）");
                    }
                }
            }
        } catch (SQLException e) {
            logger.debug("users.created_at 迁移为 DATETIME 跳过或失败: {}", e.getMessage());
        }
    }

    /** NekoAuth 是昵称唯一权威源；旧库中的 users.nickname 直接删除。 */
    private void dropUsersNicknameColumn(Connection conn) {
        try {
            String checkColumn = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                AND table_name = 'users'
                AND column_name = 'nickname'
                """;
            try (PreparedStatement stmt = conn.prepareStatement(checkColumn);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    try (PreparedStatement dropStmt = conn.prepareStatement(
                            "ALTER TABLE users DROP COLUMN nickname")) {
                        dropStmt.execute();
                        logger.info("已删除 users 表的 nickname 字段（昵称改由 NekoAuth 实时提供）");
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("删除 users.nickname 字段失败；代码已不再读写该列，请检查数据库迁移权限: {}",
                    e.getMessage());
        }
    }

    /** NekoAuth 是头像唯一权威源；旧库中的 users.avatar 直接删除。 */
    private void dropUsersAvatarColumn(Connection conn) {
        try {
            String checkColumn = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                AND table_name = 'users'
                AND column_name = 'avatar'
                """;
            try (PreparedStatement stmt = conn.prepareStatement(checkColumn);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    try (PreparedStatement dropStmt = conn.prepareStatement(
                            "ALTER TABLE users DROP COLUMN avatar")) {
                        dropStmt.execute();
                        logger.info("已删除 users.avatar 字段（头像改由 NekoAuth 实时提供）");
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("删除 users.avatar 字段失败；代码已不再读写该列，请检查数据库迁移权限: {}",
                    e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
