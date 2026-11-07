package com.neko.music.seo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 判断请求是否应收到服务端渲染的 SEO HTML。
 *
 * <p>详情页、歌单页与静态页（首页 / 下载 / 关于等）共用同一套判定，避免多处逻辑漂移。
     * 浏览器（UA 结构像浏览器，或命中 {@link #KNOWN_BROWSER_UA_TOKENS}）返回 SPA；搜索引擎、
 * AI 抓取器、链接预览机器人、监控与命令行客户端一律返回可被无 JS 解析的 HTML。
 * 不能只看「有没有 {@code Mozilla/}」：该前缀随手可伪造，还会误伤不带它的真实浏览器。</p>
 *
 * <p>UA 列表按用途分组维护，新增抓取器时优先补到最贴近的分组里，不要堆进单个巨型正则。
 * 注意：不要匹配「带有 Mozilla 前缀的应用内浏览器」（如 MicroMessenger、QQ浏览器、
 * SogouMobileBrowser、Electron 桌面端），它们能执行 JS，必须继续拿到 SPA。</p>
 */
public final class UserAgentClassifier {

    /** 通用抓取器关键词，覆盖绝大多数机器人 UA。 */
    private static final Pattern GENERIC_FETCHERS = Pattern.compile(
            "(?i)(?:bot|crawler|crawl|spider|slurp|fetcher|archiver|harvester|scraper|scraperbot|"
                    + "spiderling|inspectiontool|validator|preview|feedfetcher|feedly|qwantify|yeti)");

    /** 搜索引擎官方爬虫 / 索引机器人。 */
    private static final Pattern SEARCH_ENGINE_BOTS = Pattern.compile(
            "(?i)(?:googlebot|google-inspectiontool|google-extended|googleother|google-cloudvertexbot|"
                    + "google-safety|google-read-aloud|storebot-google|adsbot-google|mediapartners-google|"
                    + "apis-google|bingbot|bingpreview|adidxbot|msnbot|"
                    + "baiduspider|sogouspider|sogou web spider|sogou news spider|360spider|qihoobot|"
                    + "yisouspider|shenmaspider|youdaobot|sosospider|petalbot|toutiaospider|bytespider|"
                    + "yandexbot|yandeximages|yandexvideo|yandexmedia|yandexmobilebot|yandexmetrika|"
                    + "naverbot|yeti|seznambot|exabot|gigabot|mojeekbot|duckduckbot|duckassistbot|"
                    + "applebot|applebot-extended|slurp)");

    /** 社交平台 / 聊天软件的链接预览抓取器。 */
    private static final Pattern LINK_PREVIEW_BOTS = Pattern.compile(
            "(?i)(?:facebookexternalhit|facebookcatalog|facebot|facebookbot|"
                    + "meta-externalagent|meta-externalfetcher|"
                    + "linkedinbot|twitterbot|discordbot|telegrambot|whatsapp|pinterest|"
                    + "slackbot|slack-imgproxy|skypeuripreview|vkshare|viber|"
                    + "redditbot|embedly|iframely|outbrain|snapchat|nuzzel|bitlybot|tumblr|"
                    + "qqurlrichservice|qqshareproxy)");

    /** 生成式引擎 / AI 训练与检索抓取器（GEO）。 */
    private static final Pattern AI_CRAWLERS = Pattern.compile(
            "(?i)(?:gptbot|oai-searchbot|chatgpt|openai|perplexity|claudebot|claude-web|claude-user|"
                    + "anthropic|cohere|ccbot|bytespider|youbot|ai2bot|imagesift|diffbot|omgili|"
                    + "webzio|factoid|timpibot|bravebot|kagibot|amazonbot|duckassistbot|"
                    + "google-extended|google-cloudvertexbot|applebot-extended)");

    /** SEO / 外链分析工具爬虫。 */
    private static final Pattern SEO_ANALYTICS_BOTS = Pattern.compile(
            "(?i)(?:semrush|ahrefs|mj12bot|dotbot|rogerbot|megaindex|serpstat|dataforseo|"
                    + "screaming frog|screamingfrog|blexbot|barkrowler|linkdex|seokicks|"
                    + "piplbot|zoominfobot|spbot|peer39|majestic|sistrix|rytebot|cognitiveseo)");

    /** 命令行 / 库 / 原生 App 抓取客户端（无 JS 渲染能力）。 */
    private static final Pattern HTTP_CLIENTS = Pattern.compile(
            "(?i)(?:curl|wget|httpclient|okhttp|python|java/|dalvik|"
                    + "aiohttp|httpx|libwww-perl|go-http-client|node-fetch|undici|axios|superagent|"
                    + "ktor|reqwest|"
                    + "scrapy|mechanize|httrack|postman|insomnia|restsharp|guzzle|"
                    + "aria2|yt-dlp|youtube-dl|gallery-dl|motrix|"
                    + "winhttp|powershell|cfnetwork|dart|libmpv|mpv/|vlc|lavf|ffmpeg|ffprobe)");

    /** 无头浏览器 / 自动化测试框架。 */
    private static final Pattern HEADLESS_BROWSERS = Pattern.compile(
            "(?i)(?:headlesschrome|headless|phantomjs|selenium|playwright|puppeteer|"
                    + "webdriver|cypress|chrome-lighthouse|lighthouse|slimerjs)");

    /** 站点监控 / 归档 / 抓取存档服务。 */
    private static final Pattern MONITORS_ARCHIVERS = Pattern.compile(
            "(?i)(?:uptimerobot|pingdom|statuscake|site24x7|gtmetrix|newrelicpinger|datadog|"
                    + "betteruptime|hetrixtools|freshping|montastic|internetseer|dotcom-monitor|"
                    + "nodeping|uptime-kuma|nagios|zabbix|librenms|"
                    + "ia_archiver|archive\\.org_bot|commoncrawl|heritrix|wayback|cc-main)");

    /**
     * 安全扫描 / 漏洞探测工具（sqlmap、Nikto、Nmap、目录爆破等）。
     * 这类工具常自定义或伪装 UA，内置关键词表若不覆盖就会被当作普通客户端放行。
     */
    private static final Pattern SECURITY_SCANNERS = Pattern.compile(
            "(?i)(?:sqlmap|nikto|nmap|masscan|zgrab|nuclei|acunetix|nessus|openvas|"
                    + "wpscan|gobuster|ffuf|feroxbuster|dirbuster|"
                    + "xray|burpsuite|burp|zaproxy|owasp[ _-]?zap|whatweb|w3af|arachni|skipfish|"
                    + "jaeles|commix|dalfox|wapiti|netsparker|qualys|appscan|"
                    + "hydra|medusa|sslyze|sslscan|testssl|joomscan|droopescan)");


    /**
     * 真实浏览器的 UA token 名单：这些浏览器<b>既不写 {@code Mozilla/}，也不带内核标记</b>，
     * 结构判定（{@link #looksLikeRealBrowser}）识别不出来，只能按 token 显式登记。当前成员为
     * Via（{@code Via/1.9.0 (iPad; iOS 27.0.1; Scale/2.00)}）。
     *
     * <p>漏判会让真实浏览器拿到无交互的 SEO HTML，因此必须在这里认出来。</p>
     *
     * <p><b>只登记真实浏览器，token 必须足够独特并要求 {@code /} 加版本号</b>（如 {@code via/\d}），
     * 否则等于短子串万能放行。判定顺序保持先黑名单、后浏览器识别。</p>
     */
    private static final Pattern KNOWN_BROWSER_UA_TOKENS = Pattern.compile(
            "(?i)\\bvia/\\d");


    private UserAgentClassifier() {
    }

    /**
     * 一个 UA 的判定结果（一次性算完，之后按 UA 复用）。
     *
     * @param bot     命中爬虫 / 无头 / 命令行 / 扫描器黑名单（空 UA 也算）
     * @param browser 结构上像真实浏览器（或命中 {@link #KNOWN_BROWSER_UA_TOKENS}）
     */
    public record Profile(boolean bot, boolean browser) {
    }

    /**
     * UA 判定结果缓存：UA 的取值种类很少（几端客户端 + 主流浏览器），而判定要跑十来条正则、
     * 每条请求又会被 SEO 分流与详情页各问一遍。因此按 UA 缓存结果，命中就是一次 map 查找。
     *
     * <p>缓存有界：UA 由客户端决定，可以被构造成无限多个取值，因此超过 {@link #PROFILE_CACHE_MAX}
     * 就整体清空（宁可重算，也不能被撑爆内存）。缓存里只放判定结论，不含任何请求相关信息。</p>
     */
    private static final Map<String, Profile> PROFILE_CACHE = new ConcurrentHashMap<>();

    /** 判定缓存上限：正常部署里 UA 种类是几十量级。 */
    static final int PROFILE_CACHE_MAX = 512;

    /** 取（或计算）该 UA 的判定结果；{@code null} / 空白 UA 按爬虫口径处理。 */
    public static Profile profile(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.trim();
        Profile cached = PROFILE_CACHE.get(ua);
        if (cached != null) {
            return cached;
        }
        Profile computed = computeProfile(ua);
        if (PROFILE_CACHE.size() >= PROFILE_CACHE_MAX) {
            PROFILE_CACHE.clear();
        }
        PROFILE_CACHE.put(ua, computed);
        return computed;
    }

    /** 计算判定结果（不做缓存）。空 UA：按爬虫处理，其余标记均为 false。 */
    private static Profile computeProfile(String ua) {
        if (ua.isEmpty()) {
            return new Profile(true, false);
        }
        boolean bot = matchesAny(ua,
                GENERIC_FETCHERS,
                SEARCH_ENGINE_BOTS,
                LINK_PREVIEW_BOTS,
                AI_CRAWLERS,
                SEO_ANALYTICS_BOTS,
                HTTP_CLIENTS,
                HEADLESS_BROWSERS,
                MONITORS_ARCHIVERS,
                SECURITY_SCANNERS);
        return new Profile(bot, looksLikeBrowserUncached(ua));
    }

    /** 仅供测试：清空判定缓存。 */
    static void clearProfileCache() {
        PROFILE_CACHE.clear();
    }

    public static boolean shouldRenderSeo(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        Profile profile = profile(userAgent);
        if (profile.bot()) {
            return true;
        }
        // 非浏览器 UA 一律按抓取器处理。不能只看「有没有 Mozilla/」：该前缀随手可伪造，
        // 还会把不带它的真实浏览器（如 Via）误判成抓取器，导致整站 403。
        return !profile.browser();
    }

    /**
     * 结合浏览器特征头判断是否应渲染 SEO HTML。
     *
     * <p>UA 判定为爬虫（{@link #shouldRenderSeo(String)}）时返回 {@code true}；
     * UA 结构像浏览器但缺少浏览器特征头（疑似伪造的未知爬虫）时同样返回 {@code true}；
     * 只有「UA 结构像真浏览器 + 特征头齐全」才返回 {@code false}（走前端 SPA）。</p>
     */
    public static boolean shouldRenderSeo(String userAgent, boolean hasBrowserFetchEvidence) {
        if (shouldRenderSeo(userAgent)) {
            return true;
        }
        return !hasBrowserFetchEvidence;
    }

    /**
     * 判断 UA 是否「结构上像一个真实浏览器」。
     *
     * <p>未知/小众爬虫常自定义 UA 或只伪造 {@code Mozilla/} 前缀，缺少浏览器内核标记；
     * 真浏览器与应用内 WebView 则一定带内核标记。注意 iOS WKWebView（微信 / QQ / 支付宝等）
     * 的 UA <b>常省略 {@code Safari/}、{@code Version/}</b>，因此这里只要求存在内核标记，
     * 不强制 Safari 版本串，避免误伤应用内浏览器。</p>
     *
     * <p>少数真实浏览器（如 Via）既不写 {@code Mozilla/} 也不带内核标记，属于「结构不像浏览器
     * 但确实是浏览器」，由 {@link #KNOWN_BROWSER_UA_TOKENS} 显式识别。</p>
     */
    public static boolean looksLikeRealBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        return profile(userAgent).browser();
    }

    /** 未缓存的浏览器结构判定（供 {@link #computeProfile} 内部使用）。 */
    private static boolean looksLikeBrowserUncached(String ua) {
        // 少数真实浏览器（见 KNOWN_BROWSER_UA_TOKENS）既没有 Mozilla/ 也没有内核标记
        if (KNOWN_BROWSER_UA_TOKENS.matcher(ua).find()) {
            return true;
        }
        if (!ua.contains("Mozilla/")) {
            return false;
        }
        // AppleWebKit/（Chrome/Chromium/Safari/Edge/Opera/iOS WebView）
        // Gecko/（Firefox）、Trident/（旧 Edge/IE11）
        return ua.contains("AppleWebKit/")
                || ua.contains("Gecko/")
                || ua.contains("Trident/");
    }

    private static boolean matchesAny(String userAgent, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(userAgent).find()) {
                return true;
            }
        }
        return false;
    }
}
