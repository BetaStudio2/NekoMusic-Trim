package com.neko.music.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    
    private String mysqlHost = "localhost";
    private int mysqlPort = 3306;
    private String mysqlDatabase = "nek_music";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
    private int port = 65535; // 默认端口
    
    // SMTP邮件服务器配置
    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 587;
    private String smtpUsername = "";
    private String smtpPassword = "";
    private boolean smtpSsl = false;
    private boolean smtpTls = true;
    private String emailWhitelist = ""; // 默认为空，表示不限制
    
    // Redis配置
    private String redisHost = "localhost";
    private int redisPort = 6379;
    private String redisPassword = "";
    
    // JWT配置
    private String jwtSecret = "defaultSecretKeyForNekoMusic";
    private int jwtExpiration = 86400; // 24小时（秒）

    // NekoAuth 统一登录（OAuth 2.0 授权码 + 机密客户端）
    private boolean oauthEnabled = false;
    private String oauthIssuer = "https://auth.nekocore.cn";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthRedirectUri = "https://music.nekocore.cn/oauth/nekocore/callback";
    private String oauthScopes = "openid profile email roles";
    
    // Msg配置
    private String msgUrl = "";
    private String msgToken = "";

    // IP频率限制配置
    private boolean rateLimitEnabled = true;
    private int rateLimitTimeWindow = 60; // 时间窗口（秒）
    private int rateLimitMaxRequests = 10; // 最大请求数
    private int rateLimitBlockDuration = 3600; // 封锁时间（秒）
    private boolean rateLimitSilentTimeout = true; // 是否静默超时

    /** 验证码发信：同邮箱冷却秒数 */
    private int verificationCodeEmailCooldownSeconds = 60;

    /** 日志：访问日志 / 操作日志落在独立库（不共用主库），见 system_settings 的 logging.* */
    private boolean loggingAccessEnabled = true;
    private boolean loggingOperationEnabled = true;
    private String loggingDatabase = "";
    private int loggingRetentionDays = 90;

    /** Jetty / Hikari / Redis 连接池，可在 config.yml 的 performance、redis.pool_max_total 中覆盖 */
    private int jettyMaxThreads = 200;
    private int jettyMinThreads = 10;
    private long jettyIdleTimeoutMs = 60_000;
    private int hikariMaximumPoolSize = 20;
    private int hikariMinimumIdle = 5;
    private int redisPoolMaxTotal = 32;

    /** 横屏短视频渲染：见 config.yml video_render */
    private boolean videoRenderEnabled = true;
    /** auto / ffmpeg / 绝对路径，见 {@link #getVideoRenderFfmpegPath()} */
    private String videoRenderFfmpegPath = "auto";
    /** true 时优先 JAR 内嵌 linux-x86_64 FFmpeg（BtbN 构建，含 NVENC/CUDA） */
    private boolean videoRenderPreferBundledFfmpeg = true;
    /**
     * 成片视频编码：libx264（CPU）或 h264_nvenc（NVIDIA GPU，需驱动与带 NVENC 的 FFmpeg）。
     * 配置可写别名：cpu / x264 → libx264；nvenc → h264_nvenc。
     */
    private String videoRenderVideoCodec = "h264_nvenc";
    /**
     * 渲染管线：{@code cpu_legacy} 与 {@code cuda_native} 共用同一套 CPU 滤镜图（圆形封面、封面外径向频谱条等）；
     * {@code cuda_native} 仅将成片编码固定为 {@code h264_nvenc}，{@code cpu_legacy} 可由 video_codec 选 libx264 或 nvenc。
     */
    private String videoRenderPipeline = "cuda_native";
    /** 非 VIP 单次最长秒数 */
    /** 非会员单次成片最长秒数；策略值，不在后台设置清单里，只认 config.yml 与这里的默认值 */
    private int videoRenderNonVipMaxDurationSec = 30;
    /** 非 VIP 每日次数上限（Redis，东八区自然日） */
    private int videoRenderNonVipDailyLimit = 10;
    /** 异步渲染线程池大小 */
    private int videoRenderWorkerThreads = 2;
    /** 渲染完成邮件中的前端站点根 URL */
    private String videoRenderNotifyFrontendBaseUrl = "";
    /** 成片与 ASS 在 /tmp/.neko 中的保留小时数，到期自动删除 */
    private int videoRenderArtifactRetentionHours = 3;

    /** 单曲搜索无本地结果时，通过网易云官方接口补全入库 */
    private boolean neteaseSearchFillEnabled = false;
    private String neteaseCookie = "";
    private String neteaseQuality = "hires";
    /** 空或 auto 表示按歌曲元数据自动推断 */
    private String neteaseFillLanguage = "";
    private Integer neteaseFillUploadUserId = null;
    private int neteaseHttpTimeoutSeconds = 45;
    /** 批量搜索中网易云补全的全局最大并发数 */
    private int neteaseMaxParallelFills = 10;

    /** 网易云无结果时，改用 QQ 音乐接口再补全一次（优先级低于网易云） */
    private boolean qqSearchFillEnabled = false;
    private String qqCookie = "";

    /** 运行目录所在分区最低可用空间（GB，十进制 1 GB = 10⁹ 字节），不足时禁止音乐上传与网易云补全 */
    private int storageMinFreeGb = 5;

    private static final long STORAGE_BYTES_PER_GB = 1_000_000_000L;

    /** ZPay（易支付兼容）：见 https://z-pay.cn/doc.html */
    private boolean zpayEnabled = false;
    private String zpayPid = "";
    private String zpayKey = "";
    private String zpayMapiUrl = "https://zpayz.cn/mapi.php";
    /** 异步通知：可为站点根或已是完整 notify URL，见 {@link #getZpayNotifyUrl()} */
    private String zpayPublicBaseUrl = "";
    private String zpayFrontendReturnUrl = "";

    /** 每日推荐：每个用户每天产出的推荐条数 */
    private int recommendationDailyLimit = 300;

    /**
     * 可信客户端 IP 头：{@code auto}（自适应多 CDN 头，默认）/ 具体头名 /
     * {@code direct}（忽略转发头，只用 socket 对端）。
     */
    private String trustedClientIpHeader = "auto";

    /**
     * {@code auto} 模式下额外优先尝试的自定义客户端 IP 头名列表（例如换了新 CDN、
     * 其专用头不在内置清单里时，在 config.yml 里登记即可，不用改代码）。
     */
    private List<String> extraClientIpHeaders = new ArrayList<>();

    /** 本地听歌识曲：服务端声纹索引与短录音保护 */
    private boolean musicRecognitionEnabled = true;
    private long musicRecognitionMaxUploadBytes = 8L * 1024L * 1024L;
    private int musicRecognitionMinSampleDurationSeconds = 3;
    private int musicRecognitionMaxSampleDurationSeconds = 20;
    private int musicRecognitionIndexMaxTrackDurationSeconds = 900;
    private int musicRecognitionIndexBuildThreads = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors()));
    private int musicRecognitionMinimumMatchingLandmarks = 6;
    private double musicRecognitionMinimumConfidence = 0.08d;
    private int musicRecognitionRateLimitPerMinute = 12;
    private int musicRecognitionMaxConcurrentRequests = 2;
    private int musicRecognitionIndexBuildWaitSeconds = 30;
    private int musicRecognitionIndexRefreshSeconds = 300;
    private int musicRecognitionFfmpegTimeoutSeconds = 45;
    private int musicRecognitionIndexFfmpegTimeoutSeconds = 180;

    private ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    /**
     * 最近一次生效的配置树：config.yml 的内容，叠加数据库里的系统设置之后的结果。
     * {@link #effectiveValue} 按点分路径从这里取值。
     */
    private JsonNode configFileNode;

    public void loadConfig() {
        try {
            configFileNode = withRegistryDefaults(readConfigFile());
            applyConfigNode(configFileNode);
        } catch (Exception e) {
            logger.error("加载配置时出错", e);
            clampPerformanceConfig();
        }
    }

    /** 读取运行目录下的 config.yml；不存在时先从 classpath 复制一份，读不到返回 null。 */
    private JsonNode readConfigFile() throws IOException {
            // 首先检查外部配置文件是否存在，如果不存在则从classpath复制一份
            String configPath = System.getProperty("user.dir") + File.separator + "config.yml";
            File externalConfigFile = new File(configPath);

            if (!externalConfigFile.exists()) {
                // 外部配置文件不存在，从classpath复制默认配置
                copyDefaultConfig(externalConfigFile);
            }

            // 从外部文件读取配置（优先级更高）
            if (externalConfigFile.exists()) {
                try (InputStream inputStream = new FileInputStream(externalConfigFile)) {
                    logger.info("从外部文件加载配置: {}", externalConfigFile.getAbsolutePath());
                    return objectMapper.readTree(inputStream);
                }
            }
        return null;
    }

    /**
     * 把 {@link SystemSettingRegistry} 里登记的键补齐到配置树：文件里缺省（或为 null）的键，
     * 用出厂默认值填上。
     *
     * <p>这样「出厂默认值」就是真的生效值，而不只是文档：只读项（值只来自配置文件）能正确落到
     * 默认值，配置文件被裁剪后也不会退化成各字段在 Java 里另写的私有默认值。</p>
     */
    JsonNode withRegistryDefaults(JsonNode base) {
        ObjectNode root = base instanceof ObjectNode object
                ? object.deepCopy()
                : objectMapper.getNodeFactory().objectNode();
        for (SystemSettingDefinition definition : SystemSettingRegistry.all()) {
            JsonNode existing = nodeByPath(root, definition.key());
            if (existing != null && !existing.isNull()) {
                continue;
            }
            JsonNode value = toJsonNode(definition.type(), definition.defaultValue());
            if (value == null || value.isNull()) {
                continue;
            }
            putByPath(root, definition.key(), value);
        }
        return root;
    }

    /**
     * 把配置树里的值读进各字段；同一棵树重复应用结果一致，因此数据库里的覆盖值可以在原配置树
     * 上叠加后重新调用本方法，不必为每个配置项单独写一遍 setter。
     */
    private void applyConfigNode(JsonNode configNode) {
        try {
            // 如果找到了配置节点，读取配置值
            if (configNode != null) {
                // 读取MySQL配置
                JsonNode mysqlNode = configNode.get("mysql");
                if (mysqlNode != null) {
                    if (mysqlNode.has("host")) mysqlHost = mysqlNode.get("host").asText();
                    if (mysqlNode.has("port")) mysqlPort = mysqlNode.get("port").asInt();
                    if (mysqlNode.has("database")) mysqlDatabase = mysqlNode.get("database").asText();
                    if (mysqlNode.has("username")) mysqlUsername = mysqlNode.get("username").asText();
                    if (mysqlNode.has("password")) mysqlPassword = mysqlNode.get("password").asText();
                }
                
                // 读取端口配置
                if (configNode.has("port")) {
                    port = configNode.get("port").asInt();
                }

                JsonNode performanceNode = configNode.get("performance");
                if (performanceNode != null) {
                    if (performanceNode.has("jetty_max_threads")) {
                        jettyMaxThreads = performanceNode.get("jetty_max_threads").asInt();
                    }
                    if (performanceNode.has("jetty_min_threads")) {
                        jettyMinThreads = performanceNode.get("jetty_min_threads").asInt();
                    }
                    if (performanceNode.has("jetty_idle_timeout_ms")) {
                        jettyIdleTimeoutMs = performanceNode.get("jetty_idle_timeout_ms").asLong();
                    }
                    if (performanceNode.has("hikari_maximum_pool_size")) {
                        hikariMaximumPoolSize = performanceNode.get("hikari_maximum_pool_size").asInt();
                    }
                    if (performanceNode.has("hikari_minimum_idle")) {
                        hikariMinimumIdle = performanceNode.get("hikari_minimum_idle").asInt();
                    }
                }
                
                // 读取SMTP服务器配置
                JsonNode smtpNode = configNode.get("smtp");
                if (smtpNode != null) {
                    if (smtpNode.has("host")) smtpHost = smtpNode.get("host").asText();
                    if (smtpNode.has("port")) smtpPort = smtpNode.get("port").asInt();
                    if (smtpNode.has("username")) smtpUsername = smtpNode.get("username").asText();
                    if (smtpNode.has("password")) smtpPassword = smtpNode.get("password").asText();
                    if (smtpNode.has("ssl")) smtpSsl = smtpNode.get("ssl").asBoolean();
                    if (smtpNode.has("tls")) smtpTls = smtpNode.get("tls").asBoolean();
                }
                
                // 读取邮箱白名单（与发验证码、注册共用；缺省或从配置中删除键 = 不限制）
                emailWhitelist = "";
                if (configNode.has("whitelist_email")) {
                    JsonNode whitelistNode = configNode.get("whitelist_email");
                    if (whitelistNode.isArray()) {
                        StringBuilder whitelistBuilder = new StringBuilder();
                        for (JsonNode node : whitelistNode) {
                            if (node == null || !node.isTextual()) {
                                continue;
                            }
                            String part = node.asText().trim();
                            if (part.isEmpty()) {
                                continue;
                            }
                            if (whitelistBuilder.length() > 0) {
                                whitelistBuilder.append(",");
                            }
                            whitelistBuilder.append(part);
                        }
                        emailWhitelist = whitelistBuilder.toString();
                    } else if (whitelistNode.isTextual()) {
                        emailWhitelist = whitelistNode.asText().trim();
                    }
                }
                if (emailWhitelist.isEmpty()) {
                    logger.info("  邮箱白名单: (未配置，不限制域名)");
                } else {
                    logger.info("  邮箱白名单: {}", emailWhitelist);
                }
                
                // 读取Redis配置
                JsonNode redisNode = configNode.get("redis");
                if (redisNode != null) {
                    if (redisNode.has("host")) redisHost = redisNode.get("host").asText();
                    if (redisNode.has("port")) redisPort = redisNode.get("port").asInt();
                    if (redisNode.has("password")) redisPassword = redisNode.get("password").asText();
                    if (redisNode.has("pool_max_total")) {
                        redisPoolMaxTotal = redisNode.get("pool_max_total").asInt();
                    }
                }
                
                // 读取JWT配置
                JsonNode jwtNode = configNode.get("jwt");
                if (jwtNode != null) {
                    if (jwtNode.has("secret")) jwtSecret = jwtNode.get("secret").asText();
                    if (jwtNode.has("expiration")) jwtExpiration = jwtNode.get("expiration").asInt();
                }

                JsonNode oauthNode = configNode.get("oauth");
                if (oauthNode != null) {
                    if (oauthNode.has("enabled")) oauthEnabled = oauthNode.get("enabled").asBoolean();
                    if (oauthNode.has("issuer")) oauthIssuer = oauthNode.get("issuer").asText();
                    if (oauthNode.has("client_id")) oauthClientId = oauthNode.get("client_id").asText();
                    if (oauthNode.has("client_secret")) oauthClientSecret = oauthNode.get("client_secret").asText();
                    if (oauthNode.has("redirect_uri")) oauthRedirectUri = oauthNode.get("redirect_uri").asText();
                    if (oauthNode.has("scopes")) oauthScopes = oauthNode.get("scopes").asText();
                }
                
                // 读取Msg配置
                JsonNode msgNode = configNode.get("Msg");
                if (msgNode != null) {
                    if (msgNode.has("url")) msgUrl = msgNode.get("url").asText();
                    if (msgNode.has("token")) msgToken = msgNode.get("token").asText();
                }

                // 读取IP频率限制配置
                JsonNode rateLimitNode = configNode.get("rate_limit");
                if (rateLimitNode != null) {
                    if (rateLimitNode.has("enabled")) rateLimitEnabled = rateLimitNode.get("enabled").asBoolean();
                    if (rateLimitNode.has("time_window")) rateLimitTimeWindow = rateLimitNode.get("time_window").asInt();
                    if (rateLimitNode.has("max_requests")) rateLimitMaxRequests = rateLimitNode.get("max_requests").asInt();
                    if (rateLimitNode.has("block_duration")) rateLimitBlockDuration = rateLimitNode.get("block_duration").asInt();
                    if (rateLimitNode.has("silent_timeout")) rateLimitSilentTimeout = rateLimitNode.get("silent_timeout").asBoolean();
                }

                JsonNode loggingNode = configNode.get("logging");
                if (loggingNode != null) {
                    if (loggingNode.has("access_enabled")) loggingAccessEnabled = loggingNode.get("access_enabled").asBoolean();
                    if (loggingNode.has("operation_enabled")) loggingOperationEnabled = loggingNode.get("operation_enabled").asBoolean();
                    if (loggingNode.has("database")) loggingDatabase = loggingNode.get("database").asText();
                    if (loggingNode.has("retention_days")) loggingRetentionDays = loggingNode.get("retention_days").asInt();
                }

                // 安全/网络：可信客户端 IP 头
                JsonNode networkNode = configNode.get("network");
                if (networkNode != null) {
                    if (networkNode.has("trusted_client_ip_header")) {
                        trustedClientIpHeader = networkNode.get("trusted_client_ip_header")
                                .asText("").trim();
                    }
                    JsonNode extraIpHdrNode = networkNode.get("extra_client_ip_headers");
                    if (extraIpHdrNode != null && extraIpHdrNode.isArray()) {
                        extraClientIpHeaders = new ArrayList<>();
                        extraIpHdrNode.forEach(item -> {
                            if (item != null && !item.isNull()) {
                                String name = item.asText("").trim();
                                if (!name.isEmpty()) {
                                    extraClientIpHeaders.add(name);
                                }
                            }
                        });
                    }
                }

                JsonNode verificationCodeNode = configNode.get("verification_code");
                if (verificationCodeNode != null && verificationCodeNode.has("email_cooldown_seconds")) {
                    verificationCodeEmailCooldownSeconds = verificationCodeNode.get("email_cooldown_seconds").asInt();
                }

                JsonNode videoRenderNode = configNode.get("video_render");
                if (videoRenderNode != null) {
                    if (videoRenderNode.has("enabled")) {
                        videoRenderEnabled = videoRenderNode.get("enabled").asBoolean();
                    }
                    if (videoRenderNode.has("ffmpeg_path")) {
                        videoRenderFfmpegPath = videoRenderNode.get("ffmpeg_path").asText(videoRenderFfmpegPath).trim();
                    }
                    if (videoRenderNode.has("prefer_bundled_ffmpeg")) {
                        videoRenderPreferBundledFfmpeg = videoRenderNode.get("prefer_bundled_ffmpeg").asBoolean();
                    }
                    if (videoRenderNode.has("video_codec")) {
                        videoRenderVideoCodec = normalizeVideoRenderCodec(
                                videoRenderNode.get("video_codec").asText(videoRenderVideoCodec));
                    }
                    if (videoRenderNode.has("pipeline")) {
                        videoRenderPipeline = normalizeVideoRenderPipeline(
                                videoRenderNode.get("pipeline").asText(videoRenderPipeline));
                    }
                    if (videoRenderNode.has("non_vip_max_duration_sec")) {
                        videoRenderNonVipMaxDurationSec = videoRenderNode.get("non_vip_max_duration_sec").asInt();
                    }
                    if (videoRenderNode.has("non_vip_daily_limit")) {
                        videoRenderNonVipDailyLimit = videoRenderNode.get("non_vip_daily_limit").asInt();
                    }
                    if (videoRenderNode.has("worker_threads")) {
                        videoRenderWorkerThreads = videoRenderNode.get("worker_threads").asInt();
                    }
                    if (videoRenderNode.has("notify_frontend_base_url")) {
                        videoRenderNotifyFrontendBaseUrl = videoRenderNode.get("notify_frontend_base_url").asText("").trim();
                    }
                    if (videoRenderNode.has("artifact_retention_hours")) {
                        videoRenderArtifactRetentionHours = videoRenderNode.get("artifact_retention_hours").asInt();
                    }
                }

                JsonNode storageNode = configNode.get("storage");
                if (storageNode != null && storageNode.has("min_free_gb")) {
                    storageMinFreeGb = storageNode.get("min_free_gb").asInt();
                }

                JsonNode neteaseFillNode = configNode.get("netease_search_fill");
                if (neteaseFillNode != null) {
                    if (neteaseFillNode.has("enabled")) {
                        neteaseSearchFillEnabled = neteaseFillNode.get("enabled").asBoolean();
                    }
                    if (neteaseFillNode.has("cookie")) {
                        neteaseCookie = neteaseFillNode.get("cookie").asText("").trim();
                    }
                    if (neteaseFillNode.has("quality")) {
                        neteaseQuality = neteaseFillNode.get("quality").asText(neteaseQuality).trim();
                    }
                    if (neteaseFillNode.has("language")) {
                        neteaseFillLanguage = neteaseFillNode.get("language").asText(neteaseFillLanguage).trim();
                    }
                    if (neteaseFillNode.has("upload_user_id") && !neteaseFillNode.get("upload_user_id").isNull()) {
                        neteaseFillUploadUserId = neteaseFillNode.get("upload_user_id").asInt();
                    }
                    if (neteaseFillNode.has("http_timeout_seconds")) {
                        neteaseHttpTimeoutSeconds = neteaseFillNode.get("http_timeout_seconds").asInt();
                    }
                    if (neteaseFillNode.has("max_parallel_fills")) {
                        neteaseMaxParallelFills = neteaseFillNode.get("max_parallel_fills").asInt();
                    }
                }
                JsonNode qqFillNode = configNode.get("qq_search_fill");
                if (qqFillNode != null) {
                    if (qqFillNode.has("enabled")) {
                        qqSearchFillEnabled = qqFillNode.get("enabled").asBoolean();
                    }
                    if (qqFillNode.has("cookie")) {
                        qqCookie = qqFillNode.get("cookie").asText("").trim();
                    }
                }

                JsonNode zpayNode = configNode.get("zpay");
                if (zpayNode != null) {
                    if (zpayNode.has("enabled")) zpayEnabled = zpayNode.get("enabled").asBoolean();
                    if (zpayNode.has("pid")) zpayPid = zpayNode.get("pid").asText("").trim();
                    if (zpayNode.has("key")) zpayKey = zpayNode.get("key").asText("").trim();
                    if (zpayNode.has("mapi_url")) zpayMapiUrl = zpayNode.get("mapi_url").asText(zpayMapiUrl).trim();
                    if (zpayNode.has("public_base_url")) zpayPublicBaseUrl = zpayNode.get("public_base_url").asText("").trim();
                    if (zpayNode.has("frontend_return_url")) zpayFrontendReturnUrl = zpayNode.get("frontend_return_url").asText("").trim();
                }

                JsonNode recommendationNode = configNode.get("recommendation");
                if (recommendationNode != null) {
                    if (recommendationNode.has("daily_limit")) {
                        recommendationDailyLimit = recommendationNode.get("daily_limit").asInt();
                    }
                }

                JsonNode recognitionNode = configNode.get("music_recognition");
                if (recognitionNode != null) {
                    if (recognitionNode.has("enabled")) {
                        musicRecognitionEnabled = recognitionNode.get("enabled").asBoolean();
                    }
                    if (recognitionNode.has("max_upload_bytes")) {
                        musicRecognitionMaxUploadBytes = recognitionNode.get("max_upload_bytes").asLong();
                    }
                    if (recognitionNode.has("min_sample_duration_seconds")) {
                        musicRecognitionMinSampleDurationSeconds = recognitionNode.get("min_sample_duration_seconds").asInt();
                    }
                    if (recognitionNode.has("max_sample_duration_seconds")) {
                        musicRecognitionMaxSampleDurationSeconds = recognitionNode.get("max_sample_duration_seconds").asInt();
                    }
                    if (recognitionNode.has("index_max_track_duration_seconds")) {
                        musicRecognitionIndexMaxTrackDurationSeconds = recognitionNode.get("index_max_track_duration_seconds").asInt();
                    }
                    if (recognitionNode.has("index_build_threads")) {
                        musicRecognitionIndexBuildThreads = recognitionNode.get("index_build_threads").asInt();
                    }
                    if (recognitionNode.has("minimum_matching_landmarks")) {
                        musicRecognitionMinimumMatchingLandmarks = recognitionNode.get("minimum_matching_landmarks").asInt();
                    }
                    if (recognitionNode.has("minimum_confidence")) {
                        musicRecognitionMinimumConfidence = recognitionNode.get("minimum_confidence").asDouble();
                    }
                    if (recognitionNode.has("rate_limit_per_minute")) {
                        musicRecognitionRateLimitPerMinute = recognitionNode.get("rate_limit_per_minute").asInt();
                    }
                    if (recognitionNode.has("max_concurrent_requests")) {
                        musicRecognitionMaxConcurrentRequests = recognitionNode.get("max_concurrent_requests").asInt();
                    }
                    if (recognitionNode.has("index_build_wait_seconds")) {
                        musicRecognitionIndexBuildWaitSeconds = recognitionNode.get("index_build_wait_seconds").asInt();
                    }
                    if (recognitionNode.has("index_refresh_seconds")) {
                        musicRecognitionIndexRefreshSeconds = recognitionNode.get("index_refresh_seconds").asInt();
                    }
                    if (recognitionNode.has("ffmpeg_timeout_seconds")) {
                        musicRecognitionFfmpegTimeoutSeconds = recognitionNode.get("ffmpeg_timeout_seconds").asInt();
                    }
                    if (recognitionNode.has("index_ffmpeg_timeout_seconds")) {
                        musicRecognitionIndexFfmpegTimeoutSeconds = recognitionNode.get("index_ffmpeg_timeout_seconds").asInt();
                    }
                }

            }

            clampPerformanceConfig();
            
            logger.info("配置加载完成:");
            logger.info("  MySQL Host: {}", mysqlHost);
            logger.info("  MySQL Port: {}", mysqlPort);
            logger.info("  MySQL Database: {}", mysqlDatabase);
            logger.info("  MySQL Username: {}", mysqlUsername);
            logger.info("  Port: {}", port);
            logger.info("  SMTP Host: {}", smtpHost);
            logger.info("  SMTP Port: {}", smtpPort);
            logger.info("  SMTP Username: {}", smtpUsername);
            logger.info("  SMTP SSL: {}", smtpSsl);
            logger.info("  SMTP TLS: {}", smtpTls);
            logger.info("  Jetty 线程池: min={}, max={}, idleTimeoutMs={}", jettyMinThreads, jettyMaxThreads, jettyIdleTimeoutMs);
            logger.info("  HikariCP: maximumPoolSize={}, minimumIdle={}", hikariMaximumPoolSize, hikariMinimumIdle);
            logger.info("  Redis 连接池 maxTotal: {}", redisPoolMaxTotal);
            logger.info("  视频渲染: enabled={}, pipeline={}, ffmpegPath={}, preferBundled={}, videoCodec={}, nonVipMaxSec={}, nonVipDailyLimit={}",
                    videoRenderEnabled, videoRenderPipeline, videoRenderFfmpegPath, videoRenderPreferBundledFfmpeg,
                    videoRenderVideoCodec, videoRenderNonVipMaxDurationSec, videoRenderNonVipDailyLimit);
            logger.info("  存储保护: minFreeGb={}（不足时禁止音乐上传与网易云补全）", storageMinFreeGb);
            logger.info("  网易云搜索补全: enabled={}, quality={}, maxParallelFills={}, cookieConfigured={}",
                    neteaseSearchFillEnabled, neteaseQuality,
                    neteaseMaxParallelFills, !neteaseCookie.isEmpty());
            logger.info("  QQ 音乐补全: enabled={}, cookieConfigured={}",
                    qqSearchFillEnabled, !qqCookie.isEmpty());
            logger.info("  ZPay 支付: enabled={}, pidConfigured={}, publicBaseUrlConfigured={}",
                    zpayEnabled, !zpayPid.isEmpty(), !zpayPublicBaseUrl.isEmpty());
            logger.info("  每日推荐: dailyLimit={}", recommendationDailyLimit);
            logger.info("  日志: access={}, operation={}, database={}, retentionDays={}",
                    loggingAccessEnabled, loggingOperationEnabled,
                    loggingDatabase.isBlank() ? "(主库名_logs)" : loggingDatabase, loggingRetentionDays);
            logger.info("  听歌识曲: enabled={}, maxUploadBytes={}, sampleDuration={}..{}s, rateLimit={}/min, maxConcurrent={}, indexBuildThreads={}",
                    musicRecognitionEnabled, musicRecognitionMaxUploadBytes,
                    musicRecognitionMinSampleDurationSeconds, musicRecognitionMaxSampleDurationSeconds,
                    musicRecognitionRateLimitPerMinute, musicRecognitionMaxConcurrentRequests,
                    musicRecognitionIndexBuildThreads);
        } catch (Exception e) {
            logger.error("加载配置时出错", e);
            clampPerformanceConfig();
        }
    }

    /**
     * 用数据库里的系统设置覆盖配置文件里的值。
     *
     * <p>只接受登记在 {@link SystemSettingRegistry} 里的键：未登记的键一律忽略，避免管理接口
     * 变成「往配置树里注入任意路径」的入口。单条值非法时只跳过该条并记日志，不影响其它配置。</p>
     *
     * <p>取值语义：文本按原文写入；数值留空表示回落到出厂默认值；布尔按字面量写成真正的布尔；
     * 列表按行拆成数组。</p>
     *
     * @param overrides 键为点分路径（如 {@code video_render.worker_threads}），值为字符串形式
     */
    public void applyOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        ObjectNode merged = configFileNode instanceof ObjectNode existing
                ? existing.deepCopy()
                : objectMapper.getNodeFactory().objectNode();
        boolean changed = false;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String key = entry.getKey();
            Optional<SystemSettingDefinition> definition = SystemSettingRegistry.find(key);
            if (definition.isEmpty()) {
                logger.warn("忽略未登记的系统设置键: {}", key);
                continue;
            }
            SystemSettingDefinition def = definition.get();
            JsonNode value = toJsonNode(def.type(), entry.getValue());
            if (value != null && value.isNull()) {
                // 数值留空表示「恢复出厂默认值」：直接写默认值，绝不让 null 落到读取端被当成 0。
                String fallback = def.defaultValue();
                if (fallback == null || fallback.isBlank()) {
                    logger.warn("系统设置项留空且无出厂默认值，已跳过: {}", key);
                    continue;
                }
                value = toJsonNode(def.type(), fallback);
            }
            if (value == null) {
                logger.warn("系统设置值非法，已跳过: {}={}", key, entry.getValue());
                continue;
            }
            putByPath(merged, key, value);
            changed = true;
        }
        if (!changed) {
            return;
        }
        configFileNode = merged;
        applyConfigNode(merged);
        logger.info("已应用 {} 条数据库系统设置", overrides.size());
    }

    /**
     * 当前生效值（字符串形式，与数据库里的存储形式一致，列表按行分隔）。
     * 键在配置树里缺省或为空时返回 empty，供首次启动把 config.yml 的现值灌进数据库。
     */
    public Optional<String> effectiveValue(String key) {
        JsonNode node = nodeByPath(configFileNode, key);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> {
                if (item == null || item.isNull()) {
                    return;
                }
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            });
            return Optional.of(String.join("\n", items));
        }
        return Optional.of(node.asText("").trim());
    }

    /** 把字符串形式的值按声明类型转成 JSON 节点；非法时返回 null。 */
    private JsonNode toJsonNode(SystemSettingDefinition.Type type, String raw) {
        String value = raw == null ? "" : raw;
        String trimmed = value.trim();
        JsonNodeFactory factory = objectMapper.getNodeFactory();
        try {
            return switch (type) {
                case STRING -> factory.textNode(value);
                case BOOL -> factory.booleanNode(Boolean.parseBoolean(trimmed));
                // 数值型留空表示「未设置」，写成 NullNode，读取端才会跳过
                case INT -> trimmed.isEmpty() ? factory.nullNode() : factory.numberNode(Integer.parseInt(trimmed));
                case LONG -> trimmed.isEmpty() ? factory.nullNode() : factory.numberNode(Long.parseLong(trimmed));
                case DOUBLE -> trimmed.isEmpty() ? factory.nullNode() : factory.numberNode(Double.parseDouble(trimmed));
                case LIST -> {
                    ArrayNode array = factory.arrayNode();
                    for (String item : trimmed.split("\\r?\\n")) {
                        String text = item.trim();
                        if (!text.isEmpty()) {
                            array.add(text);
                        }
                    }
                    yield array;
                }
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 按点分路径写入对象树，中间层不存在或不是对象时补建空对象。 */
    private static void putByPath(ObjectNode root, String dottedPath, JsonNode value) {
        String[] parts = dottedPath.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = current.get(parts[i]);
            if (child instanceof ObjectNode objectChild) {
                current = objectChild;
            } else {
                ObjectNode created = current.objectNode();
                current.set(parts[i], created);
                current = created;
            }
        }
        current.set(parts[parts.length - 1], value);
    }

    /** 按点分路径取值；任一层缺失返回 null。 */
    private static JsonNode nodeByPath(JsonNode root, String dottedPath) {
        if (root == null) {
            return null;
        }
        JsonNode current = root;
        for (String part : dottedPath.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private void clampPerformanceConfig() {
        jettyMaxThreads = Math.max(16, Math.min(2000, jettyMaxThreads));
        jettyMinThreads = Math.max(1, Math.min(jettyMinThreads, jettyMaxThreads - 1));
        jettyIdleTimeoutMs = Math.max(1000L, Math.min(600_000L, jettyIdleTimeoutMs));
        hikariMaximumPoolSize = Math.max(2, Math.min(256, hikariMaximumPoolSize));
        hikariMinimumIdle = Math.max(0, Math.min(hikariMinimumIdle, hikariMaximumPoolSize));
        redisPoolMaxTotal = Math.max(4, Math.min(256, redisPoolMaxTotal));
        videoRenderNonVipMaxDurationSec = Math.max(5, Math.min(120, videoRenderNonVipMaxDurationSec));
        videoRenderNonVipDailyLimit = Math.max(1, Math.min(1000, videoRenderNonVipDailyLimit));
        videoRenderWorkerThreads = Math.max(1, Math.min(16, videoRenderWorkerThreads));
        videoRenderArtifactRetentionHours = Math.max(1, Math.min(168, videoRenderArtifactRetentionHours));
        storageMinFreeGb = Math.max(1, Math.min(1024, storageMinFreeGb));
        neteaseHttpTimeoutSeconds = Math.max(5, Math.min(300, neteaseHttpTimeoutSeconds));
        neteaseMaxParallelFills = Math.max(1, Math.min(10, neteaseMaxParallelFills));
        if (neteaseQuality == null || neteaseQuality.isBlank()) {
            neteaseQuality = "hires";
        }
        if (neteaseFillLanguage == null) {
            neteaseFillLanguage = "";
        }
        recommendationDailyLimit = Math.max(1, Math.min(10_000, recommendationDailyLimit));
        musicRecognitionMaxUploadBytes = Math.max(256L * 1024L,
                Math.min(64L * 1024L * 1024L, musicRecognitionMaxUploadBytes));
        musicRecognitionMinSampleDurationSeconds = Math.max(1,
                Math.min(15, musicRecognitionMinSampleDurationSeconds));
        musicRecognitionMaxSampleDurationSeconds = Math.max(musicRecognitionMinSampleDurationSeconds,
                Math.min(120, musicRecognitionMaxSampleDurationSeconds));
        musicRecognitionIndexMaxTrackDurationSeconds = Math.max(musicRecognitionMaxSampleDurationSeconds,
                Math.min(3_600, musicRecognitionIndexMaxTrackDurationSeconds));
        musicRecognitionIndexBuildThreads = Math.max(1, Math.min(16, musicRecognitionIndexBuildThreads));
        musicRecognitionMinimumMatchingLandmarks = Math.max(2,
                Math.min(100, musicRecognitionMinimumMatchingLandmarks));
        musicRecognitionMinimumConfidence = Math.max(0.01d,
                Math.min(0.95d, musicRecognitionMinimumConfidence));
        musicRecognitionRateLimitPerMinute = Math.max(1, Math.min(600, musicRecognitionRateLimitPerMinute));
        musicRecognitionMaxConcurrentRequests = Math.max(1, Math.min(16, musicRecognitionMaxConcurrentRequests));
        musicRecognitionIndexBuildWaitSeconds = Math.max(1, Math.min(300, musicRecognitionIndexBuildWaitSeconds));
        musicRecognitionIndexRefreshSeconds = Math.max(10, Math.min(86_400, musicRecognitionIndexRefreshSeconds));
        musicRecognitionFfmpegTimeoutSeconds = Math.max(5, Math.min(300, musicRecognitionFfmpegTimeoutSeconds));
        musicRecognitionIndexFfmpegTimeoutSeconds = Math.max(musicRecognitionFfmpegTimeoutSeconds,
                Math.min(900, musicRecognitionIndexFfmpegTimeoutSeconds));
        if (videoRenderFfmpegPath == null || videoRenderFfmpegPath.isBlank()) {
            videoRenderFfmpegPath = "auto";
        }
        videoRenderVideoCodec = normalizeVideoRenderCodec(videoRenderVideoCodec);
        videoRenderPipeline = normalizeVideoRenderPipeline(videoRenderPipeline);
    }

    private static String normalizeVideoRenderPipeline(String raw) {
        if (raw == null || raw.isBlank()) {
            return "cuda_native";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "cpu", "cpu_legacy", "legacy" -> "cpu_legacy";
            case "cuda", "cuda_native", "gpu", "full_gpu" -> "cuda_native";
            default -> {
                LoggerFactory.getLogger(ConfigManager.class).warn(
                        "未知的 video_render.pipeline: {}，回退为 cpu_legacy", raw);
                yield "cpu_legacy";
            }
        };
    }

    private static String normalizeVideoRenderCodec(String raw) {
        if (raw == null || raw.isBlank()) {
            return "h264_nvenc";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "cpu", "x264", "libx264" -> "libx264";
            case "nvenc", "gpu", "h264_nvenc" -> "h264_nvenc";
            default -> {
                LoggerFactory.getLogger(ConfigManager.class).warn(
                        "未知的 video_render.video_codec: {}，回退为 libx264", raw);
                yield "libx264";
            }
        };
    }
    
    /**
     * 从classpath复制默认配置文件到外部位置
     */
    private void copyDefaultConfig(File targetFile) {
        try {
            // 确保目标目录存在
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // 从classpath获取默认配置
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (inputStream != null) {
                    // 将配置文件写入外部位置
                    try (FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                        logger.info("已将默认配置文件复制到: {}", targetFile.getAbsolutePath());
                    }
                } else {
                    logger.warn("无法找到classpath中的默认配置文件");
                }
            }
        } catch (IOException e) {
            logger.error("复制默认配置文件时出错", e);
        }
    }

    // Getters
    public String getMysqlHost() {
        return mysqlHost;
    }

    public int getMysqlPort() {
        return mysqlPort;
    }

    public String getMysqlDatabase() {
        return mysqlDatabase;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }

    /** 是否记录访问日志 */
    public boolean isLoggingAccessEnabled() {
        return loggingAccessEnabled;
    }

    /** 是否记录操作日志 */
    public boolean isLoggingOperationEnabled() {
        return loggingOperationEnabled;
    }

    /** 日志库名；留空表示「主库名_logs」 */
    public String getLoggingDatabase() {
        return loggingDatabase;
    }

    /** 日志保留天数，0 表示永久保留 */
    public int getLoggingRetentionDays() {
        return loggingRetentionDays;
    }
    
    public int getPort() {
        return port;
    }
    
    public String getSmtpHost() {
        return smtpHost;
    }
    
    public int getSmtpPort() {
        return smtpPort;
    }
    
    public String getSmtpUsername() {
        return smtpUsername;
    }
    
    public String getSmtpPassword() {
        return smtpPassword;
    }
    
    public boolean isSmtpSsl() {
        return smtpSsl;
    }
    
    public boolean isSmtpTls() {
        return smtpTls;
    }
    
    public String getEmailWhitelist() {
        return emailWhitelist;
    }
    
    public String getRedisHost() {
        return redisHost;
    }
    
    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }
    
    public String getJwtSecret() {
        return jwtSecret;
    }
    
    public int getJwtExpiration() {
        return jwtExpiration;
    }

    /** 是否在登录面板启用 NekoAuth 统一登录。 */
    public boolean isOAuthEnabled() {
        return oauthEnabled;
    }

    /** NekoAuth 根地址（不带末尾斜杠）。 */
    public String getOAuthIssuer() {
        return oauthIssuer == null ? "" : oauthIssuer.trim();
    }

    public String getOAuthClientId() {
        return oauthClientId == null ? "" : oauthClientId.trim();
    }

    public String getOAuthClientSecret() {
        return oauthClientSecret == null ? "" : oauthClientSecret;
    }

    public String getOAuthRedirectUri() {
        return oauthRedirectUri == null ? "" : oauthRedirectUri.trim();
    }

    /** 空格分隔的 OAuth scope；空值时退回 openid profile email，确保能读取 NekoAuth 昵称。 */
    public String getOAuthScopes() {
        String value = oauthScopes == null ? "" : oauthScopes.trim();
        return value.isEmpty() ? "openid profile email" : value;
    }
    
    public String getMsgUrl() {
        return msgUrl;
    }
    
    public String getMsgToken() {
        return msgToken;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public int getRateLimitTimeWindow() {
        return rateLimitTimeWindow;
    }

    public int getRateLimitMaxRequests() {
        return rateLimitMaxRequests;
    }

    public int getRateLimitBlockDuration() {
        return rateLimitBlockDuration;
    }

    public boolean isRateLimitSilentTimeout() {
        return rateLimitSilentTimeout;
    }

    /** 是否运行在可信反向代理之后（决定客户端 IP 取转发头还是 socket 地址）；头名为空或 {@code direct} 时不信任转发头。 */
    public boolean isTrustedProxyEnabled() {
        String header = trustedClientIpHeader;
        return header != null && !header.isBlank() && !"direct".equalsIgnoreCase(header.trim());
    }

    /** 可信客户端 IP 头名（可能为 {@code direct}/空白）。 */
    public String getTrustedClientIpHeader() {
        return trustedClientIpHeader == null ? "" : trustedClientIpHeader.trim();
    }

    /** {@code auto} 模式下额外优先尝试的自定义客户端 IP 头名列表（可为空）。 */
    public List<String> getExtraClientIpHeaders() {
        return extraClientIpHeaders == null ? List.of() : extraClientIpHeaders;
    }

    public int getVerificationCodeEmailCooldownSeconds() {
        return verificationCodeEmailCooldownSeconds;
    }

    public int getJettyMaxThreads() {
        return jettyMaxThreads;
    }

    public int getJettyMinThreads() {
        return jettyMinThreads;
    }

    public long getJettyIdleTimeoutMs() {
        return jettyIdleTimeoutMs;
    }

    public int getHikariMaximumPoolSize() {
        return hikariMaximumPoolSize;
    }

    public int getHikariMinimumIdle() {
        return hikariMinimumIdle;
    }

    public int getRedisPoolMaxTotal() {
        return redisPoolMaxTotal;
    }

    public boolean isVideoRenderEnabled() {
        return videoRenderEnabled;
    }

    public String getVideoRenderFfmpegPath() {
        return videoRenderFfmpegPath;
    }

    public boolean isVideoRenderPreferBundledFfmpeg() {
        return videoRenderPreferBundledFfmpeg;
    }

    /** 成片编码：{@code libx264} 或 {@code h264_nvenc} */
    public String getVideoRenderVideoCodec() {
        return videoRenderVideoCodec;
    }

    /** {@code cpu_legacy} 或 {@code cuda_native} */
    public String getVideoRenderPipeline() {
        return videoRenderPipeline;
    }

    public int getVideoRenderNonVipMaxDurationSec() {
        return videoRenderNonVipMaxDurationSec;
    }

    public int getVideoRenderNonVipDailyLimit() {
        return videoRenderNonVipDailyLimit;
    }

    public int getVideoRenderWorkerThreads() {
        return videoRenderWorkerThreads;
    }

    public int getVideoRenderArtifactRetentionHours() {
        return videoRenderArtifactRetentionHours;
    }

    /** 渲染完成邮件中的前端站点根（无尾斜杠），用于拼接 /detail/{musicId}?videoJob=… */
    public String getVideoRenderNotifyFrontendBaseUrl() {
        if (videoRenderNotifyFrontendBaseUrl != null && !videoRenderNotifyFrontendBaseUrl.isEmpty()) {
            return trimTrailingSlash(videoRenderNotifyFrontendBaseUrl);
        }
        String vip = getZpayFrontendReturnUrl();
        if (!vip.isEmpty() && vip.endsWith("/vip")) {
            return trimTrailingSlash(vip.substring(0, vip.length() - 4));
        }
        if (!vip.isEmpty()) {
            return vip;
        }
        return zpaySiteRootFromPublicBase(getZpayPublicBaseUrl());
    }

    public int getStorageMinFreeGb() {
        return storageMinFreeGb;
    }

    public long getStorageMinFreeBytes() {
        return storageMinFreeGb * STORAGE_BYTES_PER_GB;
    }

    public long getStorageBytesPerGb() {
        return STORAGE_BYTES_PER_GB;
    }

    public boolean isNeteaseSearchFillEnabled() {
        return neteaseSearchFillEnabled;
    }

    public String getNeteaseCookie() {
        return neteaseCookie == null ? "" : neteaseCookie;
    }

    public String getNeteaseQuality() {
        return neteaseQuality;
    }

    public String getNeteaseFillLanguage() {
        return neteaseFillLanguage;
    }

    public Integer getNeteaseFillUploadUserId() {
        return neteaseFillUploadUserId;
    }

    public int getNeteaseHttpTimeoutSeconds() {
        return neteaseHttpTimeoutSeconds;
    }

    public int getNeteaseMaxParallelFills() {
        return neteaseMaxParallelFills;
    }

    /** 网易云无结果时是否改用 QQ 音乐补全。 */
    public boolean isQqSearchFillEnabled() {
        return qqSearchFillEnabled;
    }

    /** QQ 音乐登录 Cookie（扫码登录写入，通常含 uin / qm_keyst）；为空表示游客态。 */
    public String getQqCookie() {
        return qqCookie == null ? "" : qqCookie;
    }

    public boolean isZpayEnabled() {
        return zpayEnabled;
    }

    public String getZpayPid() {
        return zpayPid;
    }

    public String getZpayKey() {
        return zpayKey;
    }

    public String getZpayMapiUrl() {
        return zpayMapiUrl;
    }

    public String getZpayPublicBaseUrl() {
        return trimTrailingSlash(zpayPublicBaseUrl);
    }

    /**
     * ZPay 异步通知完整 URL：若 {@code public_base_url} 已包含 {@code /api/payment/zpay/notify} 则原样使用，
     * 否则视为站点根并在其后拼接 {@code /api/payment/zpay/notify}。
     */
    public String getZpayNotifyUrl() {
        String pub = zpayPublicBaseUrl == null ? "" : zpayPublicBaseUrl.trim();
        if (pub.isEmpty()) {
            return "";
        }
        pub = trimTrailingSlash(pub);
        if (pub.contains("/api/payment/zpay/notify")) {
            return pub;
        }
        return pub + "/api/payment/zpay/notify";
    }

    public String getZpayFrontendReturnUrl() {
        String raw = zpayFrontendReturnUrl.trim();
        if (!raw.isEmpty()) {
            return trimTrailingSlash(raw);
        }
        String site = zpaySiteRootFromPublicBase(getZpayPublicBaseUrl());
        if (site.isEmpty()) {
            return "";
        }
        return site + "/vip";
    }

    public int getRecommendationDailyLimit() {
        return recommendationDailyLimit;
    }

    public boolean isMusicRecognitionEnabled() { return musicRecognitionEnabled; }
    public long getMusicRecognitionMaxUploadBytes() { return musicRecognitionMaxUploadBytes; }
    public int getMusicRecognitionMinSampleDurationSeconds() { return musicRecognitionMinSampleDurationSeconds; }
    public int getMusicRecognitionMaxSampleDurationSeconds() { return musicRecognitionMaxSampleDurationSeconds; }
    public int getMusicRecognitionIndexMaxTrackDurationSeconds() { return musicRecognitionIndexMaxTrackDurationSeconds; }
    public int getMusicRecognitionIndexBuildThreads() { return musicRecognitionIndexBuildThreads; }
    public int getMusicRecognitionMinimumMatchingLandmarks() { return musicRecognitionMinimumMatchingLandmarks; }
    public double getMusicRecognitionMinimumConfidence() { return musicRecognitionMinimumConfidence; }
    public int getMusicRecognitionRateLimitPerMinute() { return musicRecognitionRateLimitPerMinute; }
    public int getMusicRecognitionMaxConcurrentRequests() { return musicRecognitionMaxConcurrentRequests; }
    public int getMusicRecognitionIndexBuildWaitSeconds() { return musicRecognitionIndexBuildWaitSeconds; }
    public int getMusicRecognitionIndexRefreshSeconds() { return musicRecognitionIndexRefreshSeconds; }
    public int getMusicRecognitionFfmpegTimeoutSeconds() { return musicRecognitionFfmpegTimeoutSeconds; }
    public int getMusicRecognitionIndexFfmpegTimeoutSeconds() { return musicRecognitionIndexFfmpegTimeoutSeconds; }

    /** 从 public_base_url 推出站点根（用于 return_url 默认 /vip） */
    private static String zpaySiteRootFromPublicBase(String publicTrimmed) {
        if (publicTrimmed == null || publicTrimmed.isEmpty()) {
            return "";
        }
        String marker = "/api/payment/zpay/notify";
        int i = publicTrimmed.indexOf(marker);
        if (i > 0) {
            return trimTrailingSlash(publicTrimmed.substring(0, i));
        }
        return publicTrimmed;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
