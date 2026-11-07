package com.neko.music.database;

import com.neko.music.config.ConfigManager;
import com.neko.music.model.ThreatAssessment;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * 日志库：访问日志与操作日志的**独立 MySQL 库**（不共用 {@code neko_music} 主库）。
 *
 * <p>日志写入量远大于业务写入，且需要保留原始 UA / 路径明细做安全分析；放进主库会污染业务库的
 * 备份、慢查询与容量规划。因此这里单独建库、单独连池，主库只保留业务数据。</p>
 *
 * <p>库名取自 {@code system_settings} 的 {@code logging.database}；留空则回落到「主库名 + {@code _logs}」。
 * 库不存在时用主库连接执行 {@code CREATE DATABASE IF NOT EXISTS}（需要账号具备该权限）。
 * 任何一步失败都不会让服务启动失败：日志能力降级为「仅写应用日志」，业务不受影响。</p>
 */
public class LogDatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(LogDatabaseManager.class);

    /** 合法的库名：只允许字母、数字、下划线，避免拼接 DDL 时被注入。 */
    private static final Pattern VALID_DATABASE_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** 历史回填也复用的高危路径 / 查询串判定，迁移 409 误标时不能误伤真实攻击。 */
    private static final String HISTORICAL_DANGEROUS_SQL = "(LOWER(COALESCE(path, '')) LIKE '%/.env%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%/.git/%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%wp-admin%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%wp-login%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%phpmyadmin%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%/etc/passwd%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%../%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%..%2f%' "
            + "OR LOWER(COALESCE(path, '')) LIKE '%/.aws/%' "
            + "OR LOWER(COALESCE(query, '')) LIKE '%union%select%' "
            + "OR LOWER(COALESCE(query, '')) LIKE '%<script%' "
            + "OR LOWER(COALESCE(query, '')) LIKE '%sleep(%')";

    private static final int LEGACY_BUGGY_ENGINE_VERSION = 3;
    private static final int HISTORICAL_SINGLE_PATH_MIN_REQUESTS = 300;
    private static final String LEGACY_REPAIR_SCOPE =
            "threat_engine_version = " + LEGACY_BUGGY_ENGINE_VERSION;

    /** 日志库连接池：写入走批量异步，池不需要很大，避免与主库抢占连接预算。 */
    private static final int POOL_MAX_SIZE = 8;
    private static final int POOL_MIN_IDLE = 2;

    private final ConfigManager configManager;
    private final String databaseName;
    private HikariDataSource dataSource;

    public LogDatabaseManager(ConfigManager configManager, String databaseName) {
        this.configManager = configManager;
        this.databaseName = databaseName;
    }

    /**
     * 解析日志库名：优先用配置值，留空则用「主库名 + {@code _logs}」；非法时返回空串（调用方降级）。
     */
    public static String resolveDatabaseName(ConfigManager configManager) {
        String configured = configManager.getLoggingDatabase();
        String name = (configured == null || configured.isBlank())
                ? configManager.getMysqlDatabase() + "_logs"
                : configured.trim();
        if (!VALID_DATABASE_NAME.matcher(name).matches()) {
            logger.error("日志库名不合法（只允许字母/数字/下划线），已停用日志库: {}", name);
            return "";
        }
        return name;
    }

    /** 用主库连接确保日志库存在（需要账号具备 CREATE 权限）。 */
    public static void ensureDatabaseExists(DatabaseManager mainDatabase, String databaseName) throws SQLException {
        String ddl = "CREATE DATABASE IF NOT EXISTS `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Connection conn = mainDatabase.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    /** 打开日志库连接池并建表。 */
    public void init() throws SQLException {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl("jdbc:mysql://" + configManager.getMysqlHost() + ":" +
                configManager.getMysqlPort() + "/" + databaseName +
                "?useSSL=false&serverTimezone=Asia/Shanghai&connectionTimeZone=Asia/Shanghai" +
                "&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true" +
                "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true");
        poolConfig.setUsername(configManager.getMysqlUsername());
        poolConfig.setPassword(configManager.getMysqlPassword());
        poolConfig.setMaximumPoolSize(POOL_MAX_SIZE);
        poolConfig.setMinimumIdle(POOL_MIN_IDLE);
        poolConfig.setPoolName("neko-log-pool");
        poolConfig.setConnectionTimeout(30000);
        poolConfig.setIdleTimeout(600000);
        poolConfig.setMaxLifetime(1800000);
        poolConfig.setConnectionInitSql("SET time_zone = '+08:00'");
        this.dataSource = new HikariDataSource(poolConfig);
        initializeTables();
    }

    private void initializeTables() throws SQLException {
        String accessLogTable = """
            CREATE TABLE IF NOT EXISTS access_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                occurred_at BIGINT NOT NULL COMMENT '请求时间（epoch 毫秒）',
                method VARCHAR(16) NOT NULL,
                path VARCHAR(512) NOT NULL,
                query VARCHAR(2048) NULL,
                category VARCHAR(16) NOT NULL COMMENT 'api/static/media/page/admin/other',
                status INT NOT NULL,
                duration_us BIGINT NOT NULL COMMENT '处理耗时（微秒）',
                response_bytes BIGINT NOT NULL DEFAULT 0,
                client_ip VARCHAR(64) NOT NULL,
                ip_subnet VARCHAR(64) NULL COMMENT 'IPv4 /24 或 IPv6 /48，便于聚合同一网段',
                user_agent VARCHAR(1024) NULL,
                referer VARCHAR(1024) NULL,
                origin VARCHAR(255) NULL,
                accept_language VARCHAR(255) NULL,
                authenticated TINYINT(1) NOT NULL DEFAULT 0,
                user_id INT NULL COMMENT '已登录用户 ID（用户令牌有效时解析，否则 NULL）',
                user_name VARCHAR(255) NULL COMMENT '已登录用户昵称（随日志快照保存，改名不改历史）',
                admin_id INT NULL,
                admin_username VARCHAR(255) NULL,
                host VARCHAR(255) NULL,
                scheme VARCHAR(16) NULL,
                protocol VARCHAR(16) NULL,
                thread VARCHAR(128) NULL,
                seo_render TINYINT(1) NULL COMMENT '本次是否返回服务端 SEO 渲染 HTML；与 SEO 无关为 NULL',
                threat_level VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '威胁判定：normal/suspected/attack',
                threat_score INT NOT NULL DEFAULT 0 COMMENT '综合风险分 0-100',
                threat_reasons VARCHAR(512) NULL COMMENT '命中原因摘要（内部运维词表）',
                threat_engine_version SMALLINT NOT NULL DEFAULT 0 COMMENT '威胁判定引擎版本',
                KEY idx_access_occurred (occurred_at),
                KEY idx_access_ip (client_ip),
                KEY idx_access_path (path(191)),
                KEY idx_access_status (status),
                KEY idx_access_threat (threat_level),
                KEY idx_access_threat_engine (threat_engine_version),
                KEY idx_access_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        String operationLogTable = """
            CREATE TABLE IF NOT EXISTS operation_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                occurred_at BIGINT NOT NULL COMMENT '操作时间（epoch 毫秒）',
                admin_id INT NULL,
                admin_username VARCHAR(255) NULL,
                admin_role VARCHAR(32) NULL,
                module VARCHAR(64) NULL COMMENT '业务模块，如「用户管理」',
                action VARCHAR(64) NULL,
                method VARCHAR(16) NOT NULL,
                path VARCHAR(512) NOT NULL,
                query VARCHAR(2048) NULL,
                status INT NOT NULL,
                success TINYINT(1) NOT NULL DEFAULT 0,
                duration_us BIGINT NOT NULL COMMENT '处理耗时（微秒）',
                client_ip VARCHAR(64) NOT NULL,
                user_agent VARCHAR(1024) NULL,
                detail VARCHAR(1024) NULL,
                KEY idx_operation_occurred (occurred_at),
                KEY idx_operation_admin (admin_id),
                KEY idx_operation_module (module)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(accessLogTable);
            stmt.execute(operationLogTable);
            migrate(conn);
            logger.info("日志库 {} 已就绪（access_log / operation_log）", databaseName);
        }
    }

    /**
     * 老库升级：{@code CREATE TABLE IF NOT EXISTS} 不会给已存在的表补列，新增字段必须显式迁移。
     *
     * <p>MySQL 不支持 {@code ADD COLUMN IF NOT EXISTS}（那是 MariaDB 的扩展），因此先查
     * {@code information_schema} 再决定是否执行 DDL；整个过程幂等，可在每次启动时安全重跑。</p>
     */
    private static void migrate(Connection conn) throws SQLException {
        ensureColumn(conn, "access_log", "seo_render",
                "TINYINT(1) NULL COMMENT '本次是否返回服务端 SEO 渲染 HTML；与 SEO 无关为 NULL'");
        ensureColumn(conn, "access_log", "threat_level",
                "VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '威胁判定：normal/suspected/attack'"
                        + " AFTER `seo_render`");
        ensureColumn(conn, "access_log", "threat_score",
                "INT NOT NULL DEFAULT 0 COMMENT '综合风险分 0-100' AFTER `threat_level`");
        ensureColumn(conn, "access_log", "threat_reasons",
                "VARCHAR(512) NULL COMMENT '命中原因摘要（内部运维词表）' AFTER `threat_score`");
        ensureColumn(conn, "access_log", "threat_engine_version",
                "SMALLINT NOT NULL DEFAULT 0 COMMENT '威胁判定引擎版本' AFTER `threat_reasons`");
        ensureIndex(conn, "access_log", "idx_access_threat", "threat_level");
        ensureIndex(conn, "access_log", "idx_access_occurred_threat", "occurred_at, threat_level");
        ensureIndex(conn, "access_log", "idx_access_threat_engine", "threat_engine_version");
        ensureColumn(conn, "access_log", "user_id",
                "INT NULL COMMENT '已登录用户 ID（用户令牌有效时解析，否则 NULL）'");
        ensureColumn(conn, "access_log", "user_name",
                "VARCHAR(255) NULL COMMENT '已登录用户昵称（随日志快照保存）'");
        ensureIndex(conn, "access_log", "idx_access_user", "user_id");
        // 概览聚合的覆盖索引：列表检索只按单列过滤，但概览要对窗口做 GROUP BY。
        // 只有 occurred_at 的索引必须逐行回聚簇索引取聚合列（窗口内几万行就是几万次随机读），
        // 把聚合列接在 occurred_at 后面即可只走索引；occurred_at 必须在最左，范围条件才用得上。
        ensureIndex(conn, "access_log", "idx_access_occurred_path", "occurred_at, path");
        ensureIndex(conn, "access_log", "idx_access_occurred_ip", "occurred_at, client_ip");
        ensureIndex(conn, "access_log", "idx_access_occurred_category", "occurred_at, category");
        ensureIndex(conn, "access_log", "idx_access_occurred_user", "occurred_at, user_id, user_name");
        backfillThreatLabels(conn);
        repairLegacyFloodLabels(conn);
        // 耗时精度升级：老库的毫秒列（duration_ms）改名为微秒列（duration_us）并 ×1000 换算。
        migrateDurationToMicros(conn, "access_log");
        migrateDurationToMicros(conn, "operation_log");
    }

    /**
     * 旧日志的一次性威胁回填。
     *
     * <p>实时算法需要请求进入时才能计算；历史行先按来源 IP 与 User-Agent 的失败簇识别成规模攻击，
     * 剩下的再按已保存的状态码、路径与 UA 分类保守分类。只处理引擎版本为 0 的行，
     * 新记录一律写入当前版本，不会每次启动都被重新标记。</p>
     */
    private static void backfillThreatLabels(Connection conn) {
        String dangerous = HISTORICAL_DANGEROUS_SQL;
        String attack = dangerous;
        String suspected = "status IN (401,403,404,429)";
        String directSql = "UPDATE access_log SET "
                + "threat_level = CASE "
                + "WHEN " + attack + " THEN 'attack' "
                + "WHEN " + suspected + " THEN 'suspected' ELSE 'normal' END, "
                + "threat_score = CASE "
                + "WHEN " + attack + " THEN 70 "
                + "WHEN " + suspected + " THEN 35 "
                + "ELSE 0 END, "
                + "threat_reasons = CASE "
                + "WHEN " + attack + " THEN '历史回填：高危扫描特征' "
                + "WHEN " + suspected + " THEN '历史回填：4xx / 爬虫 / 验证失败（建议复核）' "
                + "ELSE NULL END, "
                + "threat_engine_version = " + ThreatAssessment.CURRENT_ENGINE_VERSION + " "
                + "WHERE threat_engine_version = 0 LIMIT 5000";
        int sourceClusters = 0;
        int clientClusters = 0;
        int remaining = 0;
        try {
            prepareThreatClusters(conn);
            sourceClusters = executeUpdate(conn,
                    "UPDATE access_log a JOIN threat_source_clusters c ON a.client_ip = c.client_ip "
                            + "SET a.threat_level = 'attack', a.threat_score = 80, "
                            + "a.threat_reasons = '历史回填：同来源高频 4xx 失败簇', "
                            + "a.threat_engine_version = " + ThreatAssessment.CURRENT_ENGINE_VERSION + " "
                            + "WHERE a.threat_engine_version = 0 "
                            + "AND a.status IN (401,403,404,429)");
            clientClusters = executeUpdate(conn,
                    "UPDATE access_log a JOIN threat_client_clusters c ON a.user_agent = c.user_agent "
                            + "SET a.threat_level = 'attack', a.threat_score = 80, "
                            + "a.threat_reasons = '历史回填：同 User-Agent 跨网段失败簇', "
                            + "a.threat_engine_version = " + ThreatAssessment.CURRENT_ENGINE_VERSION + " "
                            + "WHERE a.threat_engine_version = 0 "
                            + "AND a.status IN (401,403,404,429)");
            remaining = updateInBatches(conn, directSql);
            int total = sourceClusters + clientClusters + remaining;
            if (total > 0) {
                logger.info("日志库回填历史威胁判定 {} 条（来源簇 {}，User-Agent 簇 {}）",
                        total, sourceClusters, clientClusters);
            }
        } catch (SQLException e) {
            // 回填失败不应连带停用整个日志能力；版本仍为 0，下次启动会重试
            logger.warn("历史威胁判定回填失败，将在下次启动重试: {}", e.getMessage());
        } finally {
            dropThreatClusters(conn);
        }
    }

    /**
     * 修正第 3 版规则漏掉的高频单资源请求，并把上一版规则产出的行归档到当前版本。
     */
    private static void repairLegacyFloodLabels(Connection conn) {
        try {
            prepareLegacyBursts(conn);
            int pathBursts = executeUpdate(conn,
                    "UPDATE access_log a JOIN threat_single_path_bursts b "
                            + "ON a.client_ip = b.client_ip AND a.path = b.path "
                            + "AND a.occurred_at DIV 600000 = b.bucket_start "
                            + "SET a.threat_level = 'attack', a.threat_score = 70, "
                            + "a.threat_reasons = '历史修正：同来源短时高频重复请求同一路径', "
                            + "a.threat_engine_version = " + ThreatAssessment.CURRENT_ENGINE_VERSION + " "
                            + "WHERE a.threat_engine_version = " + LEGACY_BUGGY_ENGINE_VERSION + " "
                            + "AND a.status IN (200,206,304)");
            int archived = executeUpdate(conn,
                    "UPDATE access_log SET threat_engine_version = "
                            + ThreatAssessment.CURRENT_ENGINE_VERSION + " "
                            + "WHERE threat_engine_version = " + LEGACY_BUGGY_ENGINE_VERSION);
            if (pathBursts > 0 || archived > 0) {
                logger.info("日志库修正历史威胁标记（单路径刷取 {}；归档 {}）", pathBursts, archived);
            }
        } catch (SQLException e) {
            // 修正失败不影响日志写入；下一次启动会继续尝试
            logger.warn("历史威胁标记修正失败，将在下次启动重试: {}", e.getMessage());
        } finally {
            dropLegacyBursts(conn);
        }
    }

    /** 基于 10 分钟桶识别历史上的单路径高频刷取。 */
    private static void prepareLegacyBursts(Connection conn) throws SQLException {
        String scope = LEGACY_REPAIR_SCOPE;
        try (Statement stmt = conn.createStatement()) {
            dropLegacyBursts(conn);
            stmt.execute("CREATE TEMPORARY TABLE threat_single_path_bursts ("
                    + "client_ip VARCHAR(64) NOT NULL, path VARCHAR(512) NOT NULL, "
                    + "bucket_start BIGINT NOT NULL, PRIMARY KEY (client_ip, path, bucket_start))");
            stmt.execute("INSERT INTO threat_single_path_bursts (client_ip, path, bucket_start) "
                    + "SELECT p.client_ip, p.path, p.bucket_start FROM ("
                    + "SELECT client_ip, path, occurred_at DIV 600000 AS bucket_start, "
                    + "COUNT(*) AS hits FROM access_log "
                    + "WHERE " + scope + " AND status IN (200,206,304) "
                    + "AND client_ip IS NOT NULL AND client_ip <> '' "
                    + "GROUP BY client_ip, path, occurred_at DIV 600000 "
                    + "HAVING COUNT(*) >= " + HISTORICAL_SINGLE_PATH_MIN_REQUESTS + ") p");
        }
    }

    /**
     * 为历史回填建立来源 / User-Agent 聚类。
     *
     * <p>历史行没有实时滑动窗口，但可以用全量日志近似出「短时高频失败来源」和「同一 UA 跨多个
     * 网段失败」。这能在升级后立刻把成簇的扫描型 4xx 标成攻击，而不是全部保守地塞进疑似。</p>
     */
    private static void prepareThreatClusters(Connection conn) throws SQLException {
        String denied = "status IN (401,403,404,429)";
        String scanFailure = "status IN (403,404,429)";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TEMPORARY TABLE IF EXISTS threat_source_clusters");
            stmt.execute("DROP TEMPORARY TABLE IF EXISTS threat_client_clusters");
            stmt.execute("CREATE TEMPORARY TABLE threat_source_clusters "
                    + "(client_ip VARCHAR(64) NOT NULL PRIMARY KEY)");
            stmt.execute("INSERT INTO threat_source_clusters (client_ip) "
                    + "SELECT client_ip FROM access_log "
                    + "WHERE threat_engine_version = 0 AND client_ip IS NOT NULL AND client_ip <> '' "
                    + "GROUP BY client_ip "
                    + "HAVING SUM(" + denied + ") >= 8 "
                    + "AND SUM(" + denied + ") / COUNT(*) >= 0.70 "
                    + "AND (SUM(" + scanFailure + ") >= 5 OR SUM(status = 401) >= 50)");
            stmt.execute("CREATE TEMPORARY TABLE threat_client_clusters "
                    + "(user_agent VARCHAR(1024) NOT NULL)");
            stmt.execute("INSERT INTO threat_client_clusters (user_agent) "
                    + "SELECT COALESCE(user_agent, '') FROM access_log "
                    + "WHERE threat_engine_version = 0 AND COALESCE(user_agent, '') <> '' "
                    + "GROUP BY COALESCE(user_agent, '') "
                    + "HAVING SUM(" + scanFailure + ") >= 6 "
                    + "AND COUNT(DISTINCT CASE WHEN client_ip LIKE '%:%' "
                    + "THEN SUBSTRING_INDEX(client_ip, ':', 2) "
                    + "ELSE SUBSTRING_INDEX(client_ip, '.', 2) END) >= 4 "
                    + "AND SUM(" + denied + ") / COUNT(*) >= 0.50");
        }
    }

    private static int updateInBatches(Connection conn, String sql) throws SQLException {
        int total = 0;
        try (Statement stmt = conn.createStatement()) {
            while (true) {
                int updated = stmt.executeUpdate(sql);
                total += updated;
                if (updated < 5000) {
                    return total;
                }
            }
        }
    }

    /** 多表 UPDATE 在 MySQL 下不支持 LIMIT，聚合成簇的一次性回填走这里。 */
    private static int executeUpdate(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        }
    }

    private static void dropThreatClusters(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TEMPORARY TABLE IF EXISTS threat_source_clusters");
            stmt.execute("DROP TEMPORARY TABLE IF EXISTS threat_client_clusters");
        } catch (SQLException e) {
            logger.debug("清理历史威胁聚类临时表失败: {}", e.getMessage());
        }
    }

    private static void dropLegacyBursts(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TEMPORARY TABLE IF EXISTS threat_single_path_bursts");
        } catch (SQLException e) {
            logger.debug("清理历史威胁修正临时表失败: {}", e.getMessage());
        }
    }

    /**
     * 把 {@code duration_ms} 升级成 {@code duration_us}（微秒），幂等。
     *
     * <p>亚毫秒的请求用毫秒计时只会得到 0，后台日志的「耗时」列因此长期全是 0 ms。这里把列改成
     * 微秒：老库先 {@code CHANGE COLUMN} 改名，再把已有的毫秒值 ×1000（历史数据本来就只有毫秒精度，
     * 不夸大也不丢失）；新库由建表语句直接生成 duration_us，走到这里已是空操作。</p>
     */
    private static void migrateDurationToMicros(Connection conn, String table) throws SQLException {
        if (hasColumn(conn, table, "duration_us")) {
            return;
        }
        if (hasColumn(conn, table, "duration_ms")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE `" + table + "` CHANGE COLUMN `duration_ms` `duration_us` "
                        + "BIGINT NOT NULL COMMENT '处理耗时（微秒）'");
                stmt.execute("UPDATE `" + table + "` SET `duration_us` = `duration_us` * 1000");
            }
            logger.info("日志库迁移: {}.duration_ms → duration_us（毫秒换算为微秒）", table);
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE `" + table + "` ADD COLUMN `duration_us` BIGINT NOT NULL DEFAULT 0 "
                    + "COMMENT '处理耗时（微秒）'");
        }
        logger.info("日志库补列: {}.duration_us", table);
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        String check = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(check)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private static void ensureColumn(Connection conn, String table, String column, String definition)
            throws SQLException {
        if (hasColumn(conn, table, column)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
            logger.info("日志库补列: {}.{}", table, column);
        }
    }

    /**
     * 幂等补索引。{@code columns} 可以是单列，也可以是逗号分隔的多列（复合索引），
     * 每列各自加反引号——DDL 里的列名来自代码常量，不接受外部输入。
     */
    private static void ensureIndex(Connection conn, String table, String index, String columns)
            throws SQLException {
        String check = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(check)) {
            stmt.setString(1, table);
            stmt.setString(2, index);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
        }
        StringBuilder quoted = new StringBuilder();
        for (String column : columns.split(",")) {
            if (quoted.length() > 0) {
                quoted.append(", ");
            }
            quoted.append('`').append(column.trim()).append('`');
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE `" + table + "` ADD INDEX `" + index + "` (" + quoted + ")");
            logger.info("日志库补索引: {}.{} ({})", table, index, columns);
        }
    }

    public String getDatabaseName() {
        return databaseName;
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
