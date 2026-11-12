package com.neko.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.neko.music.config.ConfigManager;
import com.neko.music.filter.SanitizedErrorHandler;
import com.neko.music.database.AdminDatabaseManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.database.DatabaseInitializer;
import com.neko.music.database.CommentDatabaseManager;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.database.LogDatabaseManager;
import com.neko.music.service.*;
import com.neko.music.database.VipPayOrderDatabaseManager;
import com.neko.music.database.VipPricingDatabaseManager;
import com.neko.music.database.SystemSettingsDatabaseManager;
import com.neko.music.database.UserNotificationDatabaseManager;
import com.neko.music.service.UserNotificationService;
import com.neko.music.handlers.*;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionConfig;
import org.eclipse.jetty.compression.server.CompressionHandler;
import com.neko.music.filter.CacheControlFilter;
import com.neko.music.filter.RequestAuditFilter;
import com.neko.music.filter.RequestLocaleFilter;
import com.neko.music.filter.IPRateLimitFilter;
import com.neko.music.filter.SecurityHeadersFilter;
import com.neko.music.filter.SourceBanFilter;
import com.neko.music.filter.StaticPageSeoFilter;
import com.neko.music.filter.MusicRecognitionWebSocketAdmissionFilter;
import com.neko.music.util.ClientReleaseStorage;
import com.neko.music.util.SiteResourceStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    static {
        // 在类加载时尽早设置字符编码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        // 服务器无图形环境：必须显式 headless，否则 BufferedImage.createGraphics()
        // 会在 X11GraphicsEnvironment 初始化时阻塞（封面缩略图会卡死请求线程）。
        System.setProperty("java.awt.headless", "true");
    }
    
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** 源站压缩默认关闭，传输压缩统一交给 CDN。 */
    private static final boolean SOURCE_COMPRESSION_ENABLED = false;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Gson gson = new Gson();

    private static Server server;
    private static DatabaseManager databaseManager;
    private static ConfigManager configManager;
    private static AdminDatabaseManager adminDatabaseManager;
    private static AdminAuthService adminAuthService;
    private static UserManagementService userManagementService;
    private static AdminLoginThrottleService adminLoginThrottleService;
    private static AuthAbuseLimiter authAbuseLimiter;
    private static ExternalFillQuotaService externalFillQuotaService;
    private static SourceBanService sourceBanService;
    private static UserAuthService userAuthService;
    private static NekoAuthAccountService nekoAuthAccountService;
    private static SharedWebSessionService sharedWebSessionService;
    private static NicknameResolver nicknameResolver;
    private static NekoAuthOAuthService nekoAuthOAuthService;
    private static QrLoginService qrLoginService;
    private static EmailService emailService;
    private static RedisService redisService;
    private static PlaylistService playlistService;
    private static PlaylistRecommendationService playlistRecommendationService;
    private static UserSearchHistoryService userSearchHistoryService;
    private static NotificationService notificationService;
    private static IPRateLimitService ipRateLimitService;
    private static CommentDatabaseManager commentDatabaseManager;
    private static UserNotificationDatabaseManager userNotificationDatabaseManager;
    private static UserNotificationService userNotificationService;
    private static IpRegionService ipRegionService;
    private static VipPricingDatabaseManager vipPricingDatabaseManager;
    private static VipPayOrderDatabaseManager vipPayOrderDatabaseManager;
    private static SystemSettingsDatabaseManager systemSettingsDatabaseManager;
    private static VideoRenderJobStore videoRenderJobStore;
    private static VideoRenderQuotaService videoRenderQuotaService;
    private static VideoRenderService videoRenderService;
    private static SliderCaptchaService sliderCaptchaService;
    private static AdminMusicIngestService adminMusicIngestService;
    private static NeteaseCloudMusicClient neteaseCloudMusicClient;
    private static NeteaseLoginMonitor neteaseLoginMonitor;
    private static QQLoginMonitor qqLoginMonitor;
    private static NeteaseSearchFillService neteaseSearchFillService;
    private static ExternalImportService externalImportService;
    private static QQMusicClient qqMusicClient;
    private static QQMusicLoginService qqMusicLoginService;
    private static KugouMusicClient kugouMusicClient;
    private static AppReleaseService appReleaseService;
    private static DailyRecommendationService dailyRecommendationService;
    private static LyricsSearchIndex lyricsSearchIndex;
    private static LyricsDatabaseManager lyricsDatabaseManager;
    private static MusicRecognitionService musicRecognitionService;
    private static ScheduledExecutorService dailyRecommendationScheduler;
    private static LogDatabaseManager logDatabaseManager;
    private static AccessLogService accessLogService;
    private static OperationLogService operationLogService;
    private static ScheduledExecutorService logMaintenanceScheduler;

    public static void main(String[] args) throws Exception {
        // 设置JVM默认时区为中国标准时间（UTC+8）
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));

        // 重新设置System.out和System.err的编码
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));

        logger.info("正在启动NekoMusic音乐平台...");
        
        // 初始化配置管理器
        configManager = new ConfigManager();
        configManager.loadConfig();

        try {
            ClientReleaseStorage.ensureStorageDir();
            logger.info("客户端安装包目录: {}", ClientReleaseStorage.storageDir().toAbsolutePath());
        } catch (Exception e) {
            logger.warn("创建客户端安装包目录失败: {}", e.getMessage());
        }

        try {
            // 前端资源不解压到运行目录，直接从 JAR 内部读；这里只预热并确认打包时带上了前端产物。
            logger.info("前端站点资源: 内嵌于 JAR（{} 个文件）", SiteResourceStorage.index().size());
        } catch (Exception e) {
            logger.warn("加载前端站点资源失败: {}", e.getMessage());
        }
        
        // 初始化数据库管理器
        databaseManager = new DatabaseManager(configManager);
        databaseManager.init();
        lyricsDatabaseManager = new LyricsDatabaseManager(databaseManager);
        
        // 初始化数据库表
        DatabaseInitializer.initializeTables(databaseManager);

        // 修复审核文件与数据库之间的半完成状态，并清理上次进程退出留下的暂存/孤儿文件。
        try {
            UploadStorageRecovery.recover(databaseManager);
        } catch (Exception e) {
            logger.warn("审核文件一致性恢复失败，服务继续启动", e);
        }

        appReleaseService = new AppReleaseService();

        vipPricingDatabaseManager = new VipPricingDatabaseManager(databaseManager);
        vipPayOrderDatabaseManager = new VipPayOrderDatabaseManager(databaseManager);

        // 运行时配置改由主库 system_settings 表承载：首次启动把 config.yml 的现值灌进表里，
        // 之后用表里的值覆盖配置树。必须在依赖配置的服务（Redis 等）之前完成。
        systemSettingsDatabaseManager = new SystemSettingsDatabaseManager(databaseManager);
        systemSettingsDatabaseManager.seedIfEmpty(configManager);
        systemSettingsDatabaseManager.pruneUnregistered();
        java.util.Map<String, String> storedSettings = systemSettingsDatabaseManager.loadAll();
        configManager.applyOverrides(storedSettings);
        logger.info("已从数据库加载 {} 条系统设置", storedSettings.size());

        // 访问日志 / 操作日志：独立日志库（不共用主库），失败只停用日志能力
        initializeLogging();

        // 初始化Redis服务（视频配额依赖 Redis，须在 video 服务之前）
        redisService = new RedisService(configManager);
        ipRateLimitService = new IPRateLimitService(configManager, redisService);

        // 歌曲评论：表读写 + 本地 MaxMind 归属地解析
        commentDatabaseManager = new CommentDatabaseManager(databaseManager);
        userNotificationDatabaseManager = new UserNotificationDatabaseManager(databaseManager);
        userNotificationService = new UserNotificationService(userNotificationDatabaseManager, redisService);
        ipRegionService = new IpRegionService();
        RedisTokenStore tokenStore = new RedisTokenStore(redisService);

        VideoRenderArtifactCleanup videoRenderArtifactCleanup =
                new VideoRenderArtifactCleanup(configManager.getVideoRenderArtifactRetentionHours());
        videoRenderJobStore = new VideoRenderJobStore(
                redisService, objectMapper, configManager.getVideoRenderArtifactRetentionHours());
        videoRenderQuotaService = new VideoRenderQuotaService(configManager, redisService);
        videoRenderService = new VideoRenderService(configManager, videoRenderJobStore, videoRenderArtifactCleanup);
        Runtime.getRuntime().addShutdownHook(new Thread(videoRenderService::shutdown, "video-render-shutdown"));

        // 初始化管理员数据库管理器和认证服务
        adminDatabaseManager = new AdminDatabaseManager(databaseManager);
        adminAuthService = new AdminAuthService(adminDatabaseManager, tokenStore);
        adminLoginThrottleService =
                new AdminLoginThrottleService(new AdminLoginThrottleService.RedisStore(redisService));
        authAbuseLimiter = new AuthAbuseLimiter(new AuthAbuseLimiter.RedisStore(redisService));
        // 外站补全来源配额（复用认证限流的 Redis 固定窗口实现，阈值是类常量）
        externalFillQuotaService =
                new ExternalFillQuotaService(new AuthAbuseLimiter.RedisStore(redisService));
        
        // 初始化邮件服务
        emailService = new EmailService(configManager);

        // 初始化用户认证服务
        nekoAuthAccountService = new NekoAuthAccountService(configManager, objectMapper);
        sharedWebSessionService = new SharedWebSessionService(redisService, nekoAuthAccountService);
        nicknameResolver = new NicknameResolver(databaseManager, nekoAuthAccountService);
        userAuthService = new UserAuthService(
                databaseManager, nekoAuthAccountService, nicknameResolver, sharedWebSessionService);
        nekoAuthOAuthService = new NekoAuthOAuthService(
                configManager, redisService, userAuthService, objectMapper);
        userManagementService = new UserManagementService(
                new com.neko.music.database.JdbcUserAccountStore(databaseManager),
                new UserManagementService.SessionRevoker() {
                    @Override
                    public void revokeAdmin(int adminId) {
                        adminAuthService.revokeAllAdminTokens(adminId);
                    }

                    @Override
                    public void revokeUser(int userId) {
                        userAuthService.revokeAllTokensForUser(userId);
                    }
                },
                nekoAuthAccountService,
                nicknameResolver);
        // 扫码登录会话（Redis 短 TTL，PC 轮询取 token）
        qrLoginService = new QrLoginService(redisService, objectMapper);

        sliderCaptchaService = new SliderCaptchaService();

        adminMusicIngestService = new AdminMusicIngestService();
        neteaseCloudMusicClient = new NeteaseCloudMusicClient(configManager, objectMapper);
        qqMusicClient = new QQMusicClient(objectMapper, configManager);
        qqMusicLoginService = new QQMusicLoginService(objectMapper);
        neteaseSearchFillService = new NeteaseSearchFillService(
                configManager,
                neteaseCloudMusicClient,
                qqMusicClient,
                adminMusicIngestService,
                redisService);
        Runtime.getRuntime().addShutdownHook(new Thread(neteaseSearchFillService::shutdown, "netease-fill-shutdown"));
        kugouMusicClient = new KugouMusicClient(objectMapper);
        dailyRecommendationService = new DailyRecommendationService(
                databaseManager, redisService, configManager, objectMapper);
        startDailyRecommendationScheduler();

        lyricsSearchIndex = new LyricsSearchIndex();
        lyricsSearchIndex.buildIndexAsync();

        musicRecognitionService = new MusicRecognitionService(databaseManager, configManager);
        musicRecognitionService.warmUp();
        Runtime.getRuntime().addShutdownHook(new Thread(musicRecognitionService::close, "music-recognition-shutdown"));

        // 初始化歌单服务
        playlistService = new PlaylistService(databaseManager);
        playlistRecommendationService = new PlaylistRecommendationService(databaseManager);
        userSearchHistoryService = new UserSearchHistoryService(databaseManager);
        Runtime.getRuntime().addShutdownHook(
                new Thread(userSearchHistoryService::shutdown, "search-history-shutdown"));
        externalImportService = new ExternalImportService(
                neteaseCloudMusicClient,
                neteaseSearchFillService,
                adminMusicIngestService,
                playlistService,
                qqMusicClient,
                kugouMusicClient,
                new QishuiMusicClient(objectMapper),
                userNotificationService);
        Runtime.getRuntime().addShutdownHook(new Thread(externalImportService::shutdown, "external-import-shutdown"));
        
        // 初始化通知服务
        notificationService = new NotificationService(configManager);

        // 认证失败来源封禁：触发封禁时经 Webhook 告警（复用通知服务），让处置可见
        sourceBanService = new SourceBanService(new SourceBanService.RedisStore(redisService),
                notificationService::scheduleAuthSourceBanNotification);

        // 网易云登录掉线告警（事件驱动，无定时巡检）：Cookie 失效时由真实请求结果触发一次 hook
        neteaseLoginMonitor = new NeteaseLoginMonitor(configManager, neteaseCloudMusicClient, notificationService);
        // QQ 音乐登录掉线告警（同样事件驱动，由 QQ 补全失败时探测上报）
        qqLoginMonitor = new QQLoginMonitor(configManager, qqMusicClient, notificationService);
        
        // 创建默认管理员账号（如果不存在）
        createDefaultAdminIfNotExists();

        // 确保至少有一个超级管理员
        ensureSuperAdminExists();
        
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("jetty-worker");
        threadPool.setMinThreads(configManager.getJettyMinThreads());
        threadPool.setMaxThreads(configManager.getJettyMaxThreads());
        threadPool.setIdleTimeout((int) Math.min(Integer.MAX_VALUE, configManager.getJettyIdleTimeoutMs()));

        server = new Server(threadPool);
        // 统一错误页：未捕获异常的类名/消息/原始输入只进日志，不回显给调用方（CWE-209）
        server.setErrorHandler(new SanitizedErrorHandler());
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(configManager.getPort());
        // 关闭带版本号的 Server / X-Powered-By 响应头，避免泄露实现版本（版本名由 SecurityHeadersFilter 覆盖）
        HttpConnectionFactory httpConnectionFactory = connector.getConnectionFactory(HttpConnectionFactory.class);
        if (httpConnectionFactory != null) {
            HttpConfiguration httpConfiguration = httpConnectionFactory.getHttpConfiguration();
            httpConfiguration.setSendServerVersion(false);
            httpConfiguration.setSendXPoweredBy(false);
        }
        server.addConnector(connector);
        logger.info("Jetty 线程池: minThreads={}, maxThreads={}, idleTimeoutMs={}, listenPort={}",
                configManager.getJettyMinThreads(), configManager.getJettyMaxThreads(),
                configManager.getJettyIdleTimeoutMs(), configManager.getPort());
        
        // 创建上下文处理器
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // 源站压缩默认关闭，避免与 CDN 的 gzip 二次处理叠加；前端与 API 的传输压缩统一由 CDN 负责。
        if (SOURCE_COMPRESSION_ENABLED) {
            GzipCompression gzipCompression = new GzipCompression();
            gzipCompression.setMinCompressSize(256);
            CompressionHandler compressionHandler = new CompressionHandler();
            compressionHandler.putCompression(gzipCompression);
            compressionHandler.putConfiguration("/", CompressionConfig.builder()
                    .compressIncludeMethod("GET")
                    .compressIncludeMethod("HEAD")
                    .compressIncludeMimeType("text/html")
                    .compressIncludeMimeType("text/css")
                    .compressIncludeMimeType("text/plain")
                    .compressIncludeMimeType("text/javascript")
                    .compressIncludeMimeType("application/javascript")
                    .compressIncludeMimeType("application/json")
                    .compressIncludeMimeType("application/xml")
                    .compressIncludeMimeType("text/xml")
                    .compressIncludeMimeType("image/svg+xml")
                    .compressIncludeMimeType("application/manifest+json")
                    .build());
            compressionHandler.setHandler(context);
            server.setHandler(compressionHandler);
        } else {
            logger.info("源站 HTTP 压缩已默认关闭，传输压缩由 CDN 统一处理");
            server.setHandler(context);
        }

        // 注册服务到 ServletContext，供 Filter 使用
        context.setAttribute("configManager", configManager);
        context.setAttribute("ipRateLimitService", ipRateLimitService);
        context.setAttribute("sourceBanService", sourceBanService);

        // 访问日志 + 操作日志：必须注册在最外层，才能看到经过后续门禁（限流/封禁）
        // 之后的真实状态码与总耗时——被挡下的请求同样要留痕
        context.addFilter(RequestAuditFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // 响应语言：按 Accept-Language 决定 API 文案用中文还是英文。紧跟访问日志注册，
        // 让限流 / 封禁等门禁写出的错误体也带上正确语言
        context.addFilter(RequestLocaleFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // 通用安全加固：禁用 TRACE/TRACK、补安全响应头、隐藏版本（最先注册，作用于全部响应）
        context.addFilter(SecurityHeadersFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // IP 限流需在嵌入式 Jetty 中显式注册（@WebFilter 不会生效）
        context.addFilter(IPRateLimitFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // 认证失败来源封禁：反复失败的来源在进入路由前被挡掉（429）。
        // 不依赖前置门禁，门禁被绕过时仍独立生效
        context.addFilter(SourceBanFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // 静态路由（首页 / 下载 / 关于等）对爬虫返回服务端 SEO HTML，浏览器仍走 SPA
        context.addFilter(StaticPageSeoFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        // 动态接口默认禁止缓存；需要公开缓存的接口自行覆盖 Cache-Control
        context.addFilter(CacheControlFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        context.addFilter(MusicRecognitionWebSocketAdmissionFilter.class,
                MusicRecognitionWebSocketEndpoint.PATH, EnumSet.of(DispatcherType.REQUEST));

        // 前端静态资源 + 全部 API/页面路由（详见 ServletRegistrar）
        ServletRegistrar.register(context, configManager);
        // 启动服务器
        server.start();
        logger.info("NekoMusic服务器已在端口{}启动", configManager.getPort());
        server.join();
    }
    
    /**
     * 初始化日志库与日志服务：访问日志 / 操作日志落在**独立库**（不共用 neko_music 主库）。
     *
     * <p>日志是旁路能力：建库、连池、建表任一步失败都只记错误并停用日志，绝不影响业务启动。</p>
     */
    private static void initializeLogging() {
        String logDatabase = LogDatabaseManager.resolveDatabaseName(configManager);
        if (logDatabase.isEmpty()) {
            logger.warn("日志库名不可用，日志功能已停用");
            return;
        }
        if (!configManager.isLoggingAccessEnabled() && !configManager.isLoggingOperationEnabled()) {
            logger.info("访问日志与操作日志均已关闭，跳过日志库初始化");
            return;
        }
        try {
            LogDatabaseManager.ensureDatabaseExists(databaseManager, logDatabase);
            LogDatabaseManager manager = new LogDatabaseManager(configManager, logDatabase);
            manager.init();
            logDatabaseManager = manager;

            if (configManager.isLoggingAccessEnabled()) {
                AccessLogService accessService = new AccessLogService(manager);
                accessService.start();
                accessLogService = accessService;
                AccessLogService.setCurrent(accessService);
            }
            if (configManager.isLoggingOperationEnabled()) {
                OperationLogService operationService = new OperationLogService(manager);
                operationService.start();
                operationLogService = operationService;
                OperationLogService.setCurrent(operationService);
            }

            logMaintenanceScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "log-maintenance");
                thread.setDaemon(true);
                return thread;
            });
            // 保留期清理：启动 10 分钟后跑一次，之后每天一次
            logMaintenanceScheduler.scheduleAtFixedRate(Main::purgeExpiredLogs, 10, 24 * 60, TimeUnit.MINUTES);
            Runtime.getRuntime().addShutdownHook(new Thread(Main::closeLogResources, "log-shutdown"));
            logger.info("日志功能已启用（访问日志={}, 操作日志={}），日志库: {}",
                    accessLogService != null, operationLogService != null, logDatabase);
        } catch (Exception e) {
            logger.error("初始化日志库失败，日志功能已停用: {}", e.getMessage(), e);
            closeLogResources();
        }
    }

    /** 按保留期清理两张日志表；0 表示永久保留。 */
    private static void purgeExpiredLogs() {
        int retentionDays = configManager == null ? 0 : configManager.getLoggingRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            if (accessLogService != null) {
                int deleted = accessLogService.purgeOlderThan(retentionDays);
                if (deleted > 0) {
                    logger.info("已清理 {} 条过期访问日志（保留 {} 天）", deleted, retentionDays);
                }
            }
            if (operationLogService != null) {
                int deleted = operationLogService.purgeOlderThan(retentionDays);
                if (deleted > 0) {
                    logger.info("已清理 {} 条过期操作日志（保留 {} 天）", deleted, retentionDays);
                }
            }
        } catch (Exception e) {
            logger.warn("清理过期日志失败: {}", e.getMessage());
        }
    }

    /** 停机时把队列里的日志刷盘并释放日志库连接池。 */
    private static void closeLogResources() {
        if (logMaintenanceScheduler != null) {
            logMaintenanceScheduler.shutdownNow();
            logMaintenanceScheduler = null;
        }
        if (accessLogService != null) {
            accessLogService.close();
            AccessLogService.setCurrent(null);
            accessLogService = null;
        }
        if (operationLogService != null) {
            operationLogService.close();
            OperationLogService.setCurrent(null);
            operationLogService = null;
        }
        if (logDatabaseManager != null) {
            logDatabaseManager.close();
            logDatabaseManager = null;
        }
    }

    private static void createDefaultAdminIfNotExists() {
        String defaultUsername = "admin";
        String defaultPassword = "admin";

        // 检查管理员表是否为空，仅在首次初始化时创建默认管理员
        if (adminDatabaseManager.getAllAdmins().isEmpty()) {
            boolean created = adminAuthService.createAdmin(defaultUsername, defaultPassword, "admin@nekomusic.com");
            if (created) {
                logger.info("默认管理员账号已创建: {}/{}", defaultUsername, defaultPassword);
                // 将默认管理员设置为超级管理员
                try (Connection conn = databaseManager.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("UPDATE admins SET role = 'super_admin' WHERE username = ?")) {
                    stmt.setString(1, defaultUsername);
                    stmt.executeUpdate();
                    logger.info("已将默认管理员设置为超级管理员");
                } catch (Exception e) {
                    logger.error("设置默认管理员角色失败", e);
                }
            } else {
                logger.error("创建默认管理员账号失败");
            }
        } else {
            logger.info("管理员表中已有数据，跳过创建默认管理员账号");
        }
    }

    private static void ensureSuperAdminExists() {
        try (Connection conn = databaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM admins WHERE role = 'super_admin'");
            if (rs.next() && rs.getInt(1) == 0) {
                // 没有超级管理员，将第一个管理员设置为super_admin
                int updated = stmt.executeUpdate("UPDATE admins SET role = 'super_admin' WHERE id = (SELECT MIN(id) FROM admins)");
                if (updated > 0) {
                    logger.info("已将第一个管理员设置为超级管理员");
                }
            }
        } catch (Exception e) {
            logger.error("确保超级管理员存在时出错", e);
        }
    }
    
    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public static ConfigManager getConfigManager() {
        return configManager;
    }
    
    public static AdminDatabaseManager getAdminDatabaseManager() {
        return adminDatabaseManager;
    }
    
    public static AdminAuthService getAdminAuthService() {
        return adminAuthService;
    }

    public static UserManagementService getUserManagementService() {
        return userManagementService;
    }

    public static AdminLoginThrottleService getAdminLoginThrottleService() {
        return adminLoginThrottleService;
    }

    public static AuthAbuseLimiter getAuthAbuseLimiter() {
        return authAbuseLimiter;
    }

    public static ExternalFillQuotaService getExternalFillQuotaService() {
        return externalFillQuotaService;
    }

    public static SourceBanService getSourceBanService() {
        return sourceBanService;
    }
    
    public static UserAuthService getUserAuthService() {
        return userAuthService;
    }

    public static NekoAuthAccountService getNekoAuthAccountService() {
        return nekoAuthAccountService;
    }

    public static SharedWebSessionService getSharedWebSessionService() {
        return sharedWebSessionService;
    }

    public static NicknameResolver getNicknameResolver() {
        return nicknameResolver;
    }

    public static NekoAuthOAuthService getNekoAuthOAuthService() {
        return nekoAuthOAuthService;
    }

    public static QrLoginService getQrLoginService() {
        return qrLoginService;
    }

    public static SliderCaptchaService getSliderCaptchaService() {
        return sliderCaptchaService;
    }

    public static AdminMusicIngestService getAdminMusicIngestService() {
        return adminMusicIngestService;
    }

    public static NeteaseSearchFillService getNeteaseSearchFillService() {
        return neteaseSearchFillService;
    }

    public static ExternalImportService getExternalImportService() {
        return externalImportService;
    }

    public static QQMusicClient getQQMusicClient() {
        return qqMusicClient;
    }

    public static QQMusicLoginService getQQMusicLoginService() {
        return qqMusicLoginService;
    }

    public static KugouMusicClient getKugouMusicClient() {
        return kugouMusicClient;
    }


    public static NeteaseCloudMusicClient getNeteaseCloudMusicClient() {
        return neteaseCloudMusicClient;
    }

    public static NeteaseLoginMonitor getNeteaseLoginMonitor() {
        return neteaseLoginMonitor;
    }

    public static QQLoginMonitor getQqLoginMonitor() {
        return qqLoginMonitor;
    }

    public static AppReleaseService getAppReleaseService() {
        return appReleaseService;
    }

    public static DailyRecommendationService getDailyRecommendationService() {
        return dailyRecommendationService;
    }

    public static PlaylistRecommendationService getPlaylistRecommendationService() {
        return playlistRecommendationService;
    }

    public static UserSearchHistoryService getUserSearchHistoryService() {
        return userSearchHistoryService;
    }

    public static LyricsSearchIndex getLyricsSearchIndex() {
        return lyricsSearchIndex;
    }

    public static LyricsDatabaseManager getLyricsDatabaseManager() {
        return lyricsDatabaseManager;
    }

    public static MusicRecognitionService getMusicRecognitionService() {
        return musicRecognitionService;
    }
    
    public static EmailService getEmailService() {
        return emailService;
    }
    
    public static RedisService getRedisService() {
        return redisService;
    }

    public static IPRateLimitService getIPRateLimitService() {
        return ipRateLimitService;
    }

    public static CommentDatabaseManager getCommentDatabaseManager() {
        return commentDatabaseManager;
    }

    public static UserNotificationDatabaseManager getUserNotificationDatabaseManager() {
        return userNotificationDatabaseManager;
    }

    public static UserNotificationService getUserNotificationService() {
        return userNotificationService;
    }

    public static IpRegionService getIpRegionService() {
        return ipRegionService;
    }

    public static PlaylistService getPlaylistService() {
        return playlistService;
    }

    public static VipPricingDatabaseManager getVipPricingDatabaseManager() {
        return vipPricingDatabaseManager;
    }

    public static VipPayOrderDatabaseManager getVipPayOrderDatabaseManager() {
        return vipPayOrderDatabaseManager;
    }

    public static SystemSettingsDatabaseManager getSystemSettingsDatabaseManager() {
        return systemSettingsDatabaseManager;
    }

    public static VideoRenderJobStore getVideoRenderJobStore() {
        return videoRenderJobStore;
    }

    public static VideoRenderQuotaService getVideoRenderQuotaService() {
        return videoRenderQuotaService;
    }

    public static VideoRenderService getVideoRenderService() {
        return videoRenderService;
    }

    public static NotificationService getNotificationService() {
        return notificationService;
    }

    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public static Gson getGson() {
        return gson;
    }

    private static void startDailyRecommendationScheduler() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        long initialDelayMs = Duration.between(now, nextMidnight).toMillis();
        long periodMs = Duration.ofDays(1).toMillis();

        dailyRecommendationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-reco-scheduler");
            t.setDaemon(true);
            return t;
        });

        dailyRecommendationScheduler.scheduleAtFixedRate(() -> {
            LocalDate today = LocalDate.now(zone);
            try {
                logger.info("开始执行每日推荐任务 date={} timezone=Asia/Shanghai", today);
                dailyRecommendationService.regenerateForAllUsers(today);
                logger.info("每日推荐任务完成 date={}", today);
            } catch (Exception e) {
                logger.error("每日推荐任务执行失败 date={}", today, e);
            }
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);

        logger.info("每日推荐定时任务已启动：Asia/Shanghai 每日00:00执行，首次延迟 {} 秒", initialDelayMs / 1000);
    }
}
