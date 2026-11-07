package com.neko.music.config;

import com.neko.music.config.SystemSettingDefinition.Group;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.neko.music.config.SystemSettingDefinition.flag;
import static com.neko.music.config.SystemSettingDefinition.list;
import static com.neko.music.config.SystemSettingDefinition.number;
import static com.neko.music.config.SystemSettingDefinition.secret;
import static com.neko.music.config.SystemSettingDefinition.text;
import static com.neko.music.config.SystemSettingDefinition.decimal;

/**
 * 系统设置清单：**只有登记在这里的键**才能被数据库覆盖、才能在后台页面里改。
 *
 * <p>不在清单里的键分两类：</p>
 * <ul>
 *   <li>{@code mysql.*} 等启动引导信息 —— 连数据库之前无从读取数据库，只能留在 config.yml。</li>
 *   <li>写死的策略项（如 {@code video_render.non_vip_max_duration_sec}、
 *       {@code netease_search_fill.language|upload_user_id}）—— 不给后台账号改的机会，
 *       值由 config.yml（运维级）或 {@code ConfigManager} 里的字段默认值决定，
 *       既不落库也不会出现在后台设置页里。</li>
 * </ul>
 */
public final class SystemSettingRegistry {

    private static final List<SystemSettingDefinition> ALL = List.of(
            // ---------- 服务器 ----------
            number("port", Group.SERVER, "运行端口", "65535", 1, 65535,
                    "HTTP 监听端口。改动需重启后端").restart(),
            number("performance.jetty_max_threads", Group.SERVER, "Jetty 最大线程数", "800", 16, 100000,
                    "高并发时的工作线程上限，需与 MySQL max_connections 一起规划").restart(),
            number("performance.jetty_min_threads", Group.SERVER, "Jetty 最小线程数", "200", 1, 100000,
                    "常驻线程数").restart(),
            number("performance.jetty_idle_timeout_ms", Group.SERVER, "Jetty 空闲超时（毫秒）", "10000", 0, 3600000,
                    "空闲线程回收时间").restart(),
            number("performance.hikari_maximum_pool_size", Group.SERVER, "数据库连接池上限", "100", 1, 10000,
                    "HikariCP 最大连接数，不得超过 MySQL max_connections").restart(),
            number("performance.hikari_minimum_idle", Group.SERVER, "数据库连接池最小空闲", "50", 0, 10000,
                    "HikariCP 常驻空闲连接数").restart(),

            // ---------- Redis ----------
            text("redis.host", Group.REDIS, "主机", "127.0.0.1", "Redis 地址").restart(),
            number("redis.port", Group.REDIS, "端口", "6379", 1, 65535, "Redis 端口").restart(),
            number("redis.pool_max_total", Group.REDIS, "连接池上限", "32", 1, 10000,
                    "Lettuce 连接池上限，建议不小于峰值并发").restart(),
            secret("redis.password", Group.REDIS, "密码", "Redis 密码，留空表示无密码"),

            // ---------- 登录令牌 ----------
            secret("jwt.secret", Group.JWT, "签名密钥", "用户登录令牌的签名密钥，至少 32 位随机串；改动后所有已发出的令牌立即失效"),
            number("jwt.expiration", Group.JWT, "有效期（秒）", "86400", 60, 31536000,
                    "登录令牌过期时间"),

            // ---------- NekoAuth 统一登录（OAuth 2.0 授权码） ----------
            flag("oauth.enabled", Group.AUTH, "启用浏览器 OAuth 登录", false,
                    "控制浏览器授权码登录入口；旧邮箱密码 API 始终由 NekoAuth 账号桥接处理，需配置下方客户端凭据"),
            text("oauth.issuer", Group.AUTH, "NekoAuth 根地址", "https://auth.nekocore.cn",
                    "OAuth 颁发者根地址，不带末尾斜杠；生产环境必须使用 HTTPS"),
            text("oauth.client_id", Group.AUTH, "客户端 ID", "",
                    "NekoAuth 管理员自行注册的机密客户端 ID；该客户端还需包含 account:bridge scope 才能供服务端账号桥接使用"),
            secret("oauth.client_secret", Group.AUTH, "客户端密钥",
                    "机密客户端密钥，只写不读；泄露后请在 NekoAuth 后台轮换"),
            text("oauth.redirect_uri", Group.AUTH, "回调地址",
                    "https://music.nekocore.cn/oauth/nekocore/callback",
                    "必须与 NekoAuth 应用中登记的回调地址完全一致；本地联调可填 http://localhost:65535/oauth/nekocore/callback"),
            text("oauth.scopes", Group.AUTH, "授权范围", "openid profile email roles",
                    "空格分隔；接入 NekoMusic 至少需要 openid profile email，建议保留 roles 以便后续权限映射"),

            // ---------- 邮件 ----------
            text("smtp.host", Group.MAIL, "SMTP 主机", "", "例如 smtp.qq.com；留空则无法发送验证码邮件"),
            number("smtp.port", Group.MAIL, "SMTP 端口", "465", 1, 65535, "常见 465(SSL) / 587(TLS) / 25"),
            text("smtp.username", Group.MAIL, "SMTP 用户名", "support@nekocore.cn", "通常是完整邮箱地址"),
            secret("smtp.password", Group.MAIL, "SMTP 密码", "部分邮箱需填授权码而非登录密码"),
            flag("smtp.ssl", Group.MAIL, "启用 SSL", true, "465 端口一般需要开启"),
            flag("smtp.tls", Group.MAIL, "启用 TLS", false, "587 端口一般需要开启"),

            // ---------- 注册与验证 ----------
            list("whitelist_email", Group.REGISTER, "注册邮箱域名白名单",
                    "qq.com\ngmail.com\n163.com\n139.com\n126.com\noutlook.com\nhotmail.com",
                    "只允许这些域名的邮箱注册；留空表示不限制"),
            number("verification_code.email_cooldown_seconds", Group.REGISTER, "发码冷却（秒）", "60", 0, 86400,
                    "同一邮箱两次发送验证码之间的最短间隔"),

            // ---------- 访问频率限制 ----------
            flag("rate_limit.enabled", Group.RATE_LIMIT, "启用 IP 频率限制", true,
                    "按 /24 网段统计请求数，超限封锁；关闭后暴力破解防护会明显减弱"),
            number("rate_limit.time_window", Group.RATE_LIMIT, "统计窗口（秒）", "60", 1, 86400,
                    "在此窗口内统计请求数"),
            number("rate_limit.max_requests", Group.RATE_LIMIT, "窗口内最大请求数", "300", 1, 1000000,
                    "同一网段在窗口内的请求上限"),
            number("rate_limit.block_duration", Group.RATE_LIMIT, "封锁时长（秒）", "1200", 1, 86400,
                    "超限后封锁该网段的时间"),
            flag("rate_limit.silent_timeout", Group.RATE_LIMIT, "静默超时", false,
                    "开启后对超频请求不返回任何响应，让对方自己超时"),

            // ---------- 网络与安全 ----------
            text("network.trusted_client_ip_header", Group.NETWORK, "客户端 IP 来源", "auto",
                    "auto=自适应 CDN 头；也可填具体头名（如 X-Real-IP）；填 direct 只信 socket 对端，最严格"),
            list("network.extra_client_ip_headers", Group.NETWORK, "额外客户端 IP 头", "",
                    "仅 auto 模式生效，每行一个头名；用于内置清单之外的新 CDN"),
            // ---------- 消息通知 ----------
            text("Msg.url", Group.NOTIFY, "Hook 地址", "127.0.0.1:8080", "系统通知与告警的消息推送地址，留空表示不推送"),
            secret("Msg.token", Group.NOTIFY, "Hook Token", "消息推送接口的鉴权 Token"),

            // ---------- 存储 ----------
            number("storage.min_free_gb", Group.STORAGE, "最低可用空间（GB）", "3", 0, 100000,
                    "运行目录所在分区剩余空间低于该值时，禁止音乐上传与网易云补全"),

            // ---------- 视频渲染 ----------
            flag("video_render.enabled", Group.VIDEO_RENDER, "启用视频渲染", true, "关闭后不再受理成片生成请求"),
            text("video_render.pipeline", Group.VIDEO_RENDER, "渲染管线", "cuda_native",
                    "cuda_native=全 GPU；cpu_legacy=CPU 滤镜"),
            text("video_render.ffmpeg_path", Group.VIDEO_RENDER, "FFmpeg 路径", "auto",
                    "auto 表示自动解析（优先 JAR 内嵌），也可填绝对路径"),
            flag("video_render.prefer_bundled_ffmpeg", Group.VIDEO_RENDER, "优先使用内嵌 FFmpeg", true,
                    "cuda_native 全 GPU 时须关闭，改用带 NVENC 的系统 FFmpeg"),
            text("video_render.video_codec", Group.VIDEO_RENDER, "编码器", "h264_nvenc",
                    "libx264（CPU）或 h264_nvenc（GPU），仅 cpu_legacy 生效"),
            number("video_render.non_vip_daily_limit", Group.VIDEO_RENDER, "非会员每日次数", "10", 0, 10000,
                    "非 VIP 每自然日最多生成次数，依赖 Redis"),
            number("video_render.worker_threads", Group.VIDEO_RENDER, "渲染线程数", "20", 1, 256,
                    "后台渲染线程数，队列满时新任务返回 503").restart(),
            number("video_render.artifact_retention_hours", Group.VIDEO_RENDER, "产物保留（小时）", "3", 1, 720,
                    "成片与字幕文件的保留时间，到期自动删除"),
            text("video_render.notify_frontend_base_url", Group.VIDEO_RENDER, "通知站点根地址", "https://music.nekocore.cn",
                    "渲染完成邮件里的下载页站点根，如 https://music.nekocore.cn；留空则按支付回调地址推断"),

            // ---------- 外站补全（网易云优先，QQ 音乐兜底） ----------
            flag("netease_search_fill.enabled", Group.EXTERNAL, "启用网易云补全", true,
                    "本地曲库搜索无结果时，通过网易云接口补全入库"),
            secret("netease_search_fill.cookie", Group.EXTERNAL, "网易云登录",
                    "由扫码登录写入，仅服务端保存与使用；后台不提供手工填写入口，未登录时按游客态请求"),
            text("netease_search_fill.quality", Group.EXTERNAL, "优先音质", "hires",
                    "外站补全共用：与网易云 level 取值一致，QQ 音乐按对应档位取链；无该档时自动降级"),
            number("netease_search_fill.http_timeout_seconds", Group.EXTERNAL, "HTTP 超时（秒）", "45", 1, 600,
                    "外站补全共用：请求网易云 / QQ 音乐接口的超时时间"),
            number("netease_search_fill.max_parallel_fills", Group.EXTERNAL, "最大并发补全数", "10", 1, 10,
                    "批量搜索时的全局并发上限"),
            flag("qq_search_fill.enabled", Group.EXTERNAL, "启用 QQ 音乐补全", true,
                    "网易云无结果时，改用 QQ 音乐接口再补全一次（优先级低于网易云）"),
            secret("qq_search_fill.cookie", Group.EXTERNAL, "QQ 音乐登录",
                    "由扫码登录写入（用手机 QQ「扫一扫」，非 QQ 音乐 App），仅服务端保存与使用；"
                            + "后台不提供手工填写入口，未登录时按游客态请求"),

            // ---------- 每日推荐 ----------
            number("recommendation.daily_limit", Group.RECOMMENDATION, "每人每日条数", "300", 1, 10000,
                    "每个用户每天产出的推荐条数"),

            // ---------- 听歌识曲 ----------
            flag("music_recognition.enabled", Group.RECOGNITION, "启用听歌识曲", true,
                    "仅匹配本站曲库，不调用第三方接口").restart(),
            number("music_recognition.max_upload_bytes", Group.RECOGNITION, "上传上限（字节）", "8388608",
                    1, 1073741824, "客户端上传录音的最大体积").restart(),
            number("music_recognition.min_sample_duration_seconds", Group.RECOGNITION, "最短采样（秒）", "3",
                    1, 600, "可识别的最短录音时长").restart(),
            number("music_recognition.max_sample_duration_seconds", Group.RECOGNITION, "最长采样（秒）", "20",
                    1, 600, "可识别的最长录音时长").restart(),
            number("music_recognition.index_max_track_duration_seconds", Group.RECOGNITION, "索引曲目最长（秒）", "900",
                    1, 7200, "超过该时长的曲目不建声纹索引").restart(),
            number("music_recognition.index_build_threads", Group.RECOGNITION, "索引构建线程数", "8",
                    1, 16, "并行生成声纹的线程数，按 CPU 与内存调整").restart(),
            number("music_recognition.minimum_matching_landmarks", Group.RECOGNITION, "最少匹配特征点", "6",
                    1, 1000, "低于该数量判为无法识别").restart(),
            decimal("music_recognition.minimum_confidence", Group.RECOGNITION, "最低置信度", "0.08",
                    0, 1, "低于该置信度判为无法识别").restart(),
            number("music_recognition.rate_limit_per_minute", Group.RECOGNITION, "每分钟限次", "12",
                    1, 10000, "单个来源每分钟的识别请求上限").restart(),
            number("music_recognition.max_concurrent_requests", Group.RECOGNITION, "最大并发请求", "2",
                    1, 64, "同时处理的识别请求数").restart(),
            number("music_recognition.index_build_wait_seconds", Group.RECOGNITION, "索引构建等待（秒）", "30",
                    0, 600, "索引未就绪时等待构建的时间").restart(),
            number("music_recognition.index_refresh_seconds", Group.RECOGNITION, "索引刷新间隔（秒）", "300",
                    1, 86400, "增量刷新声纹索引的间隔").restart(),
            number("music_recognition.ffmpeg_timeout_seconds", Group.RECOGNITION, "FFmpeg 超时（秒）", "45",
                    1, 600, "转码待识别录音的超时时间").restart(),
            number("music_recognition.index_ffmpeg_timeout_seconds", Group.RECOGNITION, "索引 FFmpeg 超时（秒）", "180",
                    1, 3600, "构建索引时解析曲目的超时时间").restart(),

            // ---------- 支付 ----------
            flag("zpay.enabled", Group.PAY, "启用支付", true, "关闭后不再受理充值下单"),
            text("zpay.pid", Group.PAY, "商户 PID", "", "易支付平台分配的商户号"),
            secret("zpay.key", Group.PAY, "商户密钥", "易支付平台分配的密钥，用于签名校验"),
            text("zpay.mapi_url", Group.PAY, "下单接口地址", "https://zpayz.cn/mapi.php",
                    "易支付兼容的下单接口"),
            text("zpay.public_base_url", Group.PAY, "异步通知地址", "",
                    "支付平台回调的站点根地址；留空则无法异步通知"),
            text("zpay.frontend_return_url", Group.PAY, "同步跳转地址", "https://music.nekocore.cn/vip",
                    "支付完成后浏览器跳回的地址"),

            // ---------- 日志（访问日志 / 操作日志，落在独立库，不共用主库） ----------
            flag("logging.access_enabled", Group.LOGGING, "记录访问日志", true,
                    "记录每个请求的访问量、UA、客户端与来源信息，用于安全分析"),
            flag("logging.operation_enabled", Group.LOGGING, "记录操作日志", true,
                    "记录后台写操作：系统设置修改、审核通过/驳回、音乐上传/修改/删除、歌词修改等，"
                            + "仅管理员及以上可查看"),
            text("logging.database", Group.LOGGING, "日志库名称", "",
                    "日志专用 MySQL 库名（与主库分开存放）；留空表示使用「主库名_logs」。改动需重启后端")
                    .restart(),
            number("logging.retention_days", Group.LOGGING, "保留天数", "90", 0, 3650,
                    "访问日志与操作日志的保留天数，超出后自动清理；0 表示永久保留")
    );

    private static final Map<String, SystemSettingDefinition> BY_KEY = buildIndex();

    private SystemSettingRegistry() {
    }

    private static Map<String, SystemSettingDefinition> buildIndex() {
        Map<String, SystemSettingDefinition> map = new LinkedHashMap<>();
        for (SystemSettingDefinition definition : ALL) {
            SystemSettingDefinition previous = map.put(definition.key(), definition);
            if (previous != null) {
                throw new IllegalStateException("系统设置键重复: " + definition.key());
            }
        }
        return Map.copyOf(map);
    }

    public static List<SystemSettingDefinition> all() {
        return ALL;
    }

    public static Optional<SystemSettingDefinition> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
