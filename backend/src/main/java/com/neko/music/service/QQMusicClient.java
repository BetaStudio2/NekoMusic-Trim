package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.config.ConfigManager;
import com.neko.music.util.HttpTransport;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.OutboundUrlGuard;
import com.neko.music.util.QqLyricParser;
import com.neko.music.util.QqQrcCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

/**
 * QQ 音乐歌单只读客户端：封装 musicu.fcg 的歌单详情接口，供代理接口与歌单导入共用。
 *
 * <p>使用 {@code music.srfDissInfo.DissInfo/CgiGetDiss}，它对新版歌单有效；
 * 老的 qzone 接口（fcg_ucc_getcdinfo_byids_cp.fcg）对不少公开歌单会直接返回
 * {@code check privacy error!}。</p>
 */
public class QQMusicClient {
    private static final Logger logger = LoggerFactory.getLogger(QQMusicClient.class);

    private static final String UPSTREAM = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    /** 桌面协议搜索入口：需对请求体计算 {@code ?sign=zzc...}（见 {@link #zzcSign(String)}）。 */
    private static final String DESKTOP_UPSTREAM = "https://u.y.qq.com/cgi-bin/musics.fcg";
    /**
     * 歌词接口：响应的 {@code lyric} / {@code trans} 字段是 **Base64 编码**的 LRC 文本，
     * 必须在代码里解码后才是可用歌词（见 {@link #fetchLyric(String)}）。返回体形如
     * {@code {"retcode":0,"lyric":"W3RpOi4u","trans":""}}。
     */
    private static final String LYRIC_UPSTREAM = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
    /** 只允许访问 QQ 官方域名：qq.com（u.y.qq.com / ssl.ptlogin2.qq.com 等）与其图片 CDN gtimg.cn。 */
    private static final Set<String> ALLOWED_HOSTS = Set.of("qq.com", "gtimg.cn");
    private static final String MODULE = "music.srfDissInfo.DissInfo";
    private static final String METHOD = "CgiGetDiss";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** 音频直链重定向跟随上限（上游偶有 302 到其它 QQ CDN）。 */
    private static final int MAX_AUDIO_REDIRECTS = 3;
    /** 单个音质档位探活的超时；探活只取前 256 字节，属轻量请求。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    /**
     * 一次取链里所有档位探活的总时限：档位变多、CDN 变慢时兜底，避免逐档串行探测把耗时放大
     * （且在持有 per-mid 锁内）。
     */
    private static final Duration PROBE_TOTAL_BUDGET = Duration.ofSeconds(15);
    /** 每个档位最多尝试的 sip 镜像数（上游常给多个等价 CDN 地址，首镜像故障可在同档内轮换）。 */
    private static final int MAX_SIP_MIRRORS = 3;
    private static final String SEARCH_MODULE = "music.search.SearchCgiService";
    private static final String DESKTOP_GUID = "1F70E520B2EAA7D25E11760783C53CA9";
    private static final String DESKTOP_WID = "7223299733393904640";
    private static final String SEARCH_UA = "QQMusic 14090508(android 12)";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 外站补全共用「优先音质」设置，这里按网易云 level 命名给出 QQ 音乐音质顺位（高 → 低），
     * 仅保留平台可入库的 flac / mp3 档；无匹配档位时从最高档逐级降级。
     */
    private static final List<QqQualityTemplate> QUALITY_TEMPLATES = List.of(
            new QqQualityTemplate("RS01", ".flac", "hires"),
            new QqQualityTemplate("AI00", ".flac", "hires"),
            new QqQualityTemplate("Q001", ".flac", "hires"),
            new QqQualityTemplate("Q000", ".flac", "hires"),
            new QqQualityTemplate("F000", ".flac", "lossless"),
            new QqQualityTemplate("M800", ".mp3", "exhigh"),
            new QqQualityTemplate("M500", ".mp3", "standard"));

    private record QqQualityTemplate(String prefix, String ext, String level) {
    }

    /** 探活结果三态：只有确凿的 404/403 才判定为「档位不可用」，网络异常一律按「未知」处理。 */
    private enum ProbeOutcome {
        PLAYABLE,
        UNAVAILABLE,
        UNKNOWN
    }

    /** 同一音质档位的多个 CDN 镜像（同一 purl 对每个 sip 都有效）。 */
    private record MirrorGroup(String tier, String ext, String level, List<String> urls) {
    }

    private final ObjectMapper objectMapper;
    /**
     * 出站统一不自动跟随重定向：{@link OutboundUrlGuard} 要求放行后对每个 3xx 目标重新校验，
     * 否则 QQ 侧可用重定向把请求引到非白名单 / 内网地址。需要跟随的音频 GET 走
     * {@link #sendAudioGet(String, Duration, String, String)} 手动逐跳校验。
     */
    private final HttpClient httpClient = HttpTransport.createWithoutRedirects(REQUEST_TIMEOUT);
    private final ConfigManager config;
    /** 当前 Cookie 下已在 CDN 确认取不到的档位（假直链），避免反复探活；Cookie 变化即失效重探。 */
    private final Set<String> phantomTiers = ConcurrentHashMap.newKeySet();
    private volatile String phantomTierCookieSignature = "";

    public QQMusicClient(ObjectMapper objectMapper, ConfigManager config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public record QqTrack(String mid, String title, String artist) {
    }

    public record QqPlaylist(String disstid, String name, List<QqTrack> tracks) {
    }

    /** 上游返回非 2xx 时抛出，携带状态码供上层映射响应。 */
    public static class UpstreamException extends IOException {
        private final int statusCode;

        public UpstreamException(int statusCode) {
            super("QQ 音乐接口 HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * 歌单详情，整理成与传统 qzone 接口一致的 {@code {code, subcode, msg, cdlist[]}} 结构，
     * 供 /loser/qq/getSongListDetail 代理与导入共用。
     */
    public JsonNode fetchPlaylistDetailRaw(String disstid) throws IOException {
        long numericDisstid;
        try {
            numericDisstid = Long.parseLong(disstid);
        } catch (NumberFormatException e) {
            throw new IOException("QQ 歌单 ID 无效");
        }
        if (numericDisstid <= 0) {
            throw new IOException("QQ 歌单 ID 无效");
        }

        ObjectNode param = objectMapper.createObjectNode();
        param.put("disstid", numericDisstid);
        param.put("dirid", 0);
        param.put("tag", 1);
        param.put("song_begin", 0);
        param.put("song_num", 0); // 0 表示返回歌单内全部曲目
        param.put("userinfo", 1);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("module", MODULE);
        request.put("method", METHOD);
        request.set("param", param);

        ObjectNode comm = objectMapper.createObjectNode();
        comm.put("ct", 6);
        comm.put("cv", "80600");
        comm.put("mn", 0);
        comm.put("format", "json");
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("notice", 0);
        comm.put("platform", "yqq.json");
        comm.put("needNewCode", 0);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("comm", comm);
        body.set("req_0", request);

        HttpRequest upstreamRequest = HttpRequest.newBuilder()
                .uri(URI.create(UPSTREAM))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0 (NekoMusic QQMusic playlist proxy)")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpTransport.requireAllowedTarget(upstreamRequest, ALLOWED_HOSTS);
        HttpResponse<String> upstreamResponse =
                HttpTransport.sendString(httpClient, upstreamRequest, "请求 QQ 音乐接口被中断");
        if (!HttpTransport.isSuccess(upstreamResponse.statusCode())) {
            throw new UpstreamException(upstreamResponse.statusCode());
        }
        JsonNode json = objectMapper.readTree(upstreamResponse.body());
        if (json == null || json.isMissingNode()) {
            throw new IOException("QQ 音乐接口返回为空");
        }
        return toLegacyPlaylistNode(json);
    }

    /** 把 musicu.fcg 的响应转换成旧接口的 cdlist 结构，保持对上层与客户端的兼容。 */
    private ObjectNode toLegacyPlaylistNode(JsonNode root) {
        JsonNode data = root.path("req_0").path("data");

        ObjectNode response = objectMapper.createObjectNode();
        int code = data.path("code").asInt(0);
        int subcode = data.path("subcode").asInt(0);
        String msg = data.path("msg").asText("");
        response.put("code", code);
        response.put("subcode", subcode);
        if (!msg.isEmpty()) {
            response.put("msg", msg);
        }
        if (code != 0) {
            // 业务错误（歌单不存在 / 不可访问等）只回错误信息，不带 cdlist
            return response;
        }

        JsonNode dirinfo = data.path("dirinfo");
        ObjectNode cd = objectMapper.createObjectNode();
        cd.put("disstid", dirinfo.path("id").asLong(0));
        cd.put("dissid", dirinfo.path("id").asLong(0));
        cd.put("dirid", dirinfo.path("dirid").asInt(0));
        cd.put("dissname", dirinfo.path("title").asText(""));
        cd.put("desc", dirinfo.path("desc").asText(""));
        cd.put("logo", dirinfo.path("picurl").asText(""));
        cd.put("songnum", data.path("total_song_num").asInt(0));

        ArrayNode songlist = cd.putArray("songlist");
        for (JsonNode song : data.path("songlist")) {
            ObjectNode entry = songlist.addObject();
            entry.put("mid", song.path("mid").asText(song.path("songmid").asText("")));
            entry.put("name", song.path("name").asText(song.path("title").asText("")).trim());
            ArrayNode singers = entry.putArray("singer");
            for (JsonNode singer : song.path("singer")) {
                ObjectNode item = singers.addObject();
                item.put("name", singer.path("name").asText("").trim());
            }
        }

        response.putArray("cdlist").add(cd);
        return response;
    }

    /** 解析歌单名与曲目（仅元数据，QQ 歌单详情不含可下载直链）。 */
    public QqPlaylist fetchPlaylist(String disstid) throws IOException {
        JsonNode root = fetchPlaylistDetailRaw(disstid);
        JsonNode cdlist = root.path("cdlist");
        JsonNode first = cdlist.isArray() && !cdlist.isEmpty() ? cdlist.get(0) : null;
        String name = first == null ? "" : first.path("dissname").asText("");

        List<QqTrack> tracks = new ArrayList<>();
        if (first != null) {
            for (JsonNode song : first.path("songlist")) {
                String mid = song.path("mid").asText(song.path("songmid").asText(""));
                String title = song.path("name").asText(song.path("songname").asText("")).trim();
                String artist = joinSingers(song.path("singer"));
                if (!title.isEmpty()) {
                    tracks.add(new QqTrack(mid, title, artist));
                }
            }
        }
        return new QqPlaylist(disstid, name, tracks);
    }

    private static String joinSingers(JsonNode singers) {
        if (!singers.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode singer : singers) {
            String candidate = singer.path("name").asText("").trim();
            if (!candidate.isEmpty()) {
                names.add(candidate);
            }
        }
        return String.join(" / ", names);
    }

    // ───────────────────── 外站补全：搜索 / 取链 / 下载 / 登录态 ─────────────────────

    /** 一次搜索命中的 QQ 曲目（含取链所需的 media_mid）。 */
    public record QqSongCandidate(long songId, String mid, String mediaMid, String title, String artist,
                                  String album, String coverUrl, int durationMs) {
    }

    /** 一个可下载的直链候选；同一曲按音质顺位给出多个。 */
    public record QqPlayUrl(String url, String type, String level) {
    }

    /** 当前登录账号资料。 */
    public record AccountProfile(long userId, String nickname, String avatarUrl) {
    }

    /** 下载进度回调：totalBytes 为 -1 表示上游未返回 Content-Length。 */
    @FunctionalInterface
    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    /** 按关键词搜索单曲（桌面协议，需对请求体签名；单页最多 50 条）。 */
    public List<QqSongCandidate> searchSongs(String keywords, int limit) throws IOException {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        ObjectNode param = objectMapper.createObjectNode();
        param.put("grp", 1);
        param.put("num_per_page", Math.max(1, Math.min(50, limit)));
        param.put("page_num", 1);
        param.put("query", keywords.trim());
        param.put("remoteplace", "txt.newclient.top");
        param.put("search_type", 0);
        param.put("searchid", randomSearchId());

        ObjectNode module = objectMapper.createObjectNode();
        module.put("module", SEARCH_MODULE);
        module.put("method", "DoSearchForQQMusicDesktop");
        module.set("param", param);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("comm", desktopSearchComm());
        body.set("req_0", module);

        // 签名只覆盖请求体，因此参与签名的字符串必须与实际发送的字节完全一致
        String payload = objectMapper.writeValueAsString(body);
        String sign = zzcSign(payload);
        JsonNode root = postJson(DESKTOP_UPSTREAM + "?sign=" + sign, payload,
                Map.of("User-Agent", SEARCH_UA));

        JsonNode node = root.path("req_0");
        int outer = root.path("code").asInt(0);
        int inner = node.path("code").asInt(0);
        if (outer != 0 || inner != 0) {
            throw new IOException("QQ 音乐搜索失败 code=" + outer + "/" + inner);
        }
        List<QqSongCandidate> songs = new ArrayList<>();
        for (JsonNode song : node.path("data").path("body").path("song").path("list")) {
            QqSongCandidate candidate = toCandidate(song);
            if (candidate != null) {
                songs.add(candidate);
            }
        }
        return songs;
    }

    /**
     * 解析曲目直链候选（{@code music.vkey.GetVkey / UrlGetVkey}）。
     *
     * <p>按音质顺位依次给出可用 URL：服务端对无权档位返回空 purl，自动落到下一档。
     * 登录态下用桌面 ct=19 下发完整音质字段，未登录按游客 ct=24 请求。</p>
     */
    public List<QqPlayUrl> resolvePlayUrls(String mid, String mediaMid, String preferredLevel)
            throws IOException {
        if (mid == null || mid.isBlank()) {
            return List.of();
        }
        String fileBase = mediaMid == null || mediaMid.isBlank() ? mid + mid : mediaMid;
        List<QqQualityTemplate> candidates = qualityCandidates(preferredLevel);
        List<String> filenames = new ArrayList<>(candidates.size());
        for (QqQualityTemplate template : candidates) {
            filenames.add(template.prefix() + fileBase + template.ext());
        }

        String uin = loggedInUin();
        String musicKey = musicKey();
        boolean loggedIn = !uin.equals("0") && !musicKey.isEmpty();
        long gtk = hash33(musicKey, 5381);

        ObjectNode comm = objectMapper.createObjectNode();
        comm.put("uin", loggedIn ? uin : "");
        comm.put("format", "json");
        comm.put("ct", loggedIn ? 19 : 24);
        comm.put("cv", 4747474);
        comm.put("platform", "yqq.json");
        comm.put("chid", "0");
        comm.put("g_tk", gtk);
        comm.put("g_tk_new_20200303", gtk);
        comm.put("inCharset", "utf-8");
        comm.put("outCharset", "utf-8");
        comm.put("notice", 0);
        comm.put("needNewCode", 1);
        if (loggedIn) {
            comm.put("authst", musicKey);
            comm.put("tmeLoginType", 2);
        }

        ObjectNode param = objectMapper.createObjectNode();
        param.put("guid", randomGuid());
        ArrayNode songmids = param.putArray("songmid");
        ArrayNode files = param.putArray("filename");
        ArrayNode songTypes = param.putArray("songtype");
        for (String filename : filenames) {
            songmids.add(mid);
            files.add(filename);
            songTypes.add(0);
        }
        param.put("uin", loggedIn ? uin : "");
        param.put("loginflag", 1);
        param.put("platform", "20");
        param.put("ctx", 0);

        ObjectNode module = objectMapper.createObjectNode();
        module.put("module", "music.vkey.GetVkey");
        module.put("method", "UrlGetVkey");
        module.set("param", param);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("comm", comm);
        body.set("req_0", module);
        JsonNode root = postJson(UPSTREAM, objectMapper.writeValueAsString(body),
                Map.of("User-Agent", WEB_UA, "Origin", "https://y.qq.com"));
        JsonNode data = root.path("req_0").path("data");

        List<String> sips = new ArrayList<>();
        for (JsonNode sip : data.path("sip")) {
            String value = sip.asText("").trim();
            if (value.startsWith("https://")) {
                sips.add(value);
            }
        }
        if (sips.isEmpty()) {
            for (JsonNode sip : data.path("sip")) {
                String value = sip.asText("").trim();
                if (value.startsWith("http://")) {
                    sips.add(value);
                }
            }
        }
        if (sips.isEmpty()) {
            sips.add("https://isure.stream.qqmusic.qq.com/");
        }

        Map<String, String> purlByFilename = new LinkedHashMap<>();
        for (JsonNode info : data.path("midurlinfo")) {
            purlByFilename.put(info.path("filename").asText(""), info.path("purl").asText(""));
        }

        // 同一档位对多个 sip 镜像都有效：逐个生成镜像候选，首镜像故障可在同档内轮换（见 MirrorGroup）。
        Set<String> phantom = refreshPhantomTiers();
        List<MirrorGroup> groups = new ArrayList<>();
        for (QqQualityTemplate candidate : candidates) {
            if (phantom.contains(candidate.prefix())) {
                continue;
            }
            String purl = purlByFilename.get(candidate.prefix() + fileBase + candidate.ext());
            if (purl == null || purl.isBlank()) {
                continue;
            }
            String ext = candidate.ext().replace(".", "");
            List<String> mirrors = new ArrayList<>();
            if (purl.startsWith("http")) {
                mirrors.add(purl);
            } else {
                for (String sip : sips) {
                    if (mirrors.size() >= MAX_SIP_MIRRORS) {
                        break;
                    }
                    mirrors.add(sip + purl);
                }
            }
            if (!mirrors.isEmpty()) {
                groups.add(new MirrorGroup(candidate.prefix(), ext, candidate.level(), mirrors));
            }
        }

        // 上游会对「下发了 purl 但实际取不到」的档位（常见于当前账号无权限的臻品 Hi-Res 档）
        // 在 CDN 上直接返回 404。这里按顺位探活，跳过这种假直链，返回首个真正可下载的档位，
        // 其后更低档位（含同档其它镜像）作为降级备选。
        long deadline = System.nanoTime() + PROBE_TOTAL_BUDGET.toNanos();
        for (int i = 0; i < groups.size(); i++) {
            MirrorGroup group = groups.get(i);
            ProbeOutcome outcome = ProbeOutcome.UNKNOWN;
            for (int m = 0; m < group.urls().size(); m++) {
                if (System.nanoTime() > deadline) {
                    logger.info("QQ 音质探活超出总时限，停止探测 tier={} level={}", group.tier(), group.level());
                    outcome = ProbeOutcome.UNKNOWN;
                    break;
                }
                outcome = probeUrl(group.urls().get(m), group.level());
                if (outcome != ProbeOutcome.UNKNOWN) {
                    // PLAYABLE / UNAVAILABLE 都是确定结论，无需再试同档其它镜像。
                    break;
                }
            }
            if (outcome == ProbeOutcome.PLAYABLE) {
                return flattenFrom(groups, i);
            }
            if (outcome == ProbeOutcome.UNKNOWN) {
                // 探测本身失败（限流 / 超时）无法证明该档不可用：交给下载侧决定，避免丢源。
                return flattenFrom(groups, i);
            }
            // UNAVAILABLE：该档位在 CDN 上是假直链，记入缓存并继续看更低档位。
            phantom.add(group.tier());
        }
        if (groups.isEmpty()) {
            return List.of();
        }
        // 所有档位都在 CDN 确认取不到：返回空，避免把 404 直链交给下载侧白下载一轮。
        logger.warn("QQ 音乐所有音质档位在 CDN 均不可取（假直链），本次不补全");
        return List.of();
    }

    /** 展平从第 {@code from} 组起的镜像候选，供下载侧按顺位降级重试。 */
    private static List<QqPlayUrl> flattenFrom(List<MirrorGroup> groups, int from) {
        List<QqPlayUrl> urls = new ArrayList<>();
        for (int i = from; i < groups.size(); i++) {
            MirrorGroup group = groups.get(i);
            for (String url : group.urls()) {
                urls.add(new QqPlayUrl(url, group.ext(), group.level()));
            }
        }
        return urls;
    }

    /** 取当前 Cookie 对应的「假直链档位」缓存；Cookie 变化（重新登录）时清空重探。 */
    private Set<String> refreshPhantomTiers() {
        String signature = cookieHeader();
        if (!signature.equals(phantomTierCookieSignature)) {
            phantomTiers.clear();
            phantomTierCookieSignature = signature;
        }
        return phantomTiers;
    }

    /**
     * 轻量探活：拉候选直链的前 256 字节，确认 2xx/206 且不是错误页（text/json/xml/html）。
     * 用于识别「purl 非空但 CDN 实际 404」的假直链。
     *
     * <p>返回三态：确凿的 404/403/410 或错误页判为 {@link ProbeOutcome#UNAVAILABLE}（档位不可用）；
     * 2xx/206 且拿到音频字节判为 {@link ProbeOutcome#PLAYABLE}；其余（超时 / IO 异常 / 5xx 等）
     * 一律 {@link ProbeOutcome#UNKNOWN}，不据此下结论。</p>
     */
    private ProbeOutcome probeUrl(String url, String level) {
        try {
            HttpResponse<InputStream> response =
                    sendAudioGet(url, PROBE_TIMEOUT, "bytes=0-255", "QQ 音质探活被中断");
            int status = response.statusCode();
            if (status == 404 || status == 403 || status == 410) {
                response.body().close();
                return ProbeOutcome.UNAVAILABLE;
            }
            byte[] head = new byte[64];
            int read = 0;
            try (InputStream body = response.body()) {
                if (status == 200 || status == 206) {
                    int r;
                    while (read < head.length
                            && (r = body.read(head, read, head.length - read)) != -1) {
                        read += r;
                    }
                }
            }
            if (status != 200 && status != 206) {
                return ProbeOutcome.UNKNOWN;
            }
            String mime = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            boolean errorPage = mime.contains("text") || mime.contains("json")
                    || mime.contains("xml") || mime.contains("html");
            if (errorPage || read == 0) {
                return ProbeOutcome.UNAVAILABLE;
            }
            return ProbeOutcome.PLAYABLE;
        } catch (IOException e) {
            logger.debug("QQ 音质探活失败（按未知处理）level={}: {}", level, e.getMessage());
            return ProbeOutcome.UNKNOWN;
        }
    }

    /**
     * 音频直链 GET：手动逐跳跟随重定向，每一跳先过 {@link OutboundUrlGuard}，离开白名单即拒绝。
     *
     * @param rangeHeader 传 {@code bytes=0-255} 即探活请求，传 null 则完整下载
     */
    private HttpResponse<InputStream> sendAudioGet(
            String url,
            Duration timeout,
            String rangeHeader,
            String interruptMessage
    ) throws IOException {
        URI targetUri = OutboundUrlGuard.requireAllowedHttpUrl(url, ALLOWED_HOSTS);
        for (int hop = 0; ; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(targetUri)
                    .timeout(timeout)
                    .header("User-Agent", WEB_UA)
                    .header("Referer", "https://y.qq.com/")
                    .GET();
            if (rangeHeader != null) {
                builder.header("Range", rangeHeader);
            }
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(interruptMessage, e);
            }
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            String location = response.headers().firstValue("location").orElse("");
            response.body().close();
            if (hop >= MAX_AUDIO_REDIRECTS) {
                throw new IOException("音频直链重定向次数过多");
            }
            targetUri = resolveRedirect(targetUri, location);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    /** 解析重定向目标并重新做一次出站校验（协议 + 白名单 + 公网地址）。 */
    private static URI resolveRedirect(URI current, String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("重定向缺少目标地址");
        }
        URI next = current.resolve(location.trim());
        return OutboundUrlGuard.requireAllowedHttpUrl(next.toString(), ALLOWED_HOSTS);
    }

    /** 当前 Cookie 是否含有效登录凭据（uin + musickey）。 */
    public boolean isLoggedIn() {
        return !loggedInUin().equals("0") && !musicKey().isEmpty();
    }

    /**
     * 服务端登录态探测：当前 Cookie 凭据是否仍被上游接受。
     *
     * <p>与 {@link #isLoggedIn()}（只看本地 Cookie 是否含凭据）不同，这里真的请求一次
     * {@code GetLoginUserInfo}，用于识别「Cookie 还在、但已被风控 / 改密失效」的掉线。</p>
     *
     * @return {@code Optional.of(true)} 有效；{@code Optional.of(false)} 有凭据但被上游拒绝（掉线）；
     *         {@code Optional.empty()} 请求失败（网络 / 接口异常，状态未知）
     */
    public Optional<Boolean> probeLoginState() {
        if (!isLoggedIn()) {
            return Optional.of(false);
        }
        try {
            return Optional.of(fetchAccountProfile() != null);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 取一首歌的标准 LRC 歌词：请求上游拿到 Base64 编码的 {@code lyric}，**在代码里解码**后返回。
     * 无歌词或接口异常返回空串，由调用方决定是否回落占位歌词。
     *
     * <p>上游 {@code lyric} / {@code trans} 字段均为 Base64 编码（并非明文），必须解码，且主歌词
     * 为空时退用翻译轨，取值口径与网易云补全一致。</p>
     */
    public String fetchLyric(String mid) throws IOException {
        if (mid == null || mid.isBlank()) {
            return "";
        }
        String url = LYRIC_UPSTREAM + "?songmid=" + URLEncoder.encode(mid.trim(), StandardCharsets.UTF_8)
                + "&g_tk=5381&format=json&inCharset=utf-8&outCharset=utf-8&platform=h5&needNewCode=1";
        URI targetUri = OutboundUrlGuard.requireAllowedHttpUrl(url, ALLOWED_HOSTS);
        HttpRequest request = HttpRequest.newBuilder().uri(targetUri)
                .timeout(requestTimeout())
                .header("User-Agent", WEB_UA)
                .header("Referer", "https://y.qq.com/")
                .GET().build();
        HttpResponse<String> response =
                HttpTransport.sendString(httpClient, request, "请求 QQ 歌词接口被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) {
            throw new UpstreamException(response.statusCode());
        }
        JsonNode json = objectMapper.readTree(response.body());
        if (json == null || json.isMissingNode()) {
            return "";
        }
        String lyric = decodeLyricBase64(json.path("lyric").asText(""));
        return lyric.isBlank() ? decodeLyricBase64(json.path("trans").asText("")) : lyric;
    }

    /**
     * 拉取 QQ 音乐 QRC 富歌词并转换成 Neko 统一格式。
     * 请求失败或无 QRC 时回退到旧 LRC 接口，保证仍能生成行级歌词。
     */
    public NekoLyrics.Document fetchNekoLyrics(QqSongCandidate candidate) throws IOException {
        if (candidate == null) {
            return new NekoLyrics.Document(NekoLyrics.VERSION, List.of());
        }
        if (candidate.songId() > 0) {
            try {
                ObjectNode param = objectMapper.createObjectNode();
                param.put("albumName", base64Text(candidate.album()));
                param.put("crypt", 1);
                param.put("ct", 19);
                param.put("cv", 2111);
                param.put("interval", candidate.durationMs());
                param.put("lrc_t", 0);
                param.put("qrc", 1);
                param.put("qrc_t", 0);
                param.put("roma", 1);
                param.put("roma_t", 0);
                param.put("singerName", base64Text(candidate.artist()));
                param.put("songID", candidate.songId());
                param.put("songName", base64Text(candidate.title()));
                param.put("trans", 1);
                param.put("trans_t", 0);
                param.put("type", 0);

                ObjectNode module = objectMapper.createObjectNode();
                module.put("module", "music.musichallSong.PlayLyricInfo");
                module.put("method", "GetPlayLyricInfo");
                module.set("param", param);

                ObjectNode body = objectMapper.createObjectNode();
                body.set("comm", moduleComm());
                body.set("req_0", module);
                JsonNode root = postJson(UPSTREAM, objectMapper.writeValueAsString(body),
                        Map.of("User-Agent", WEB_UA));
                JsonNode data = root.path("req_0").path("data");
                String lyric = QqQrcCrypto.decrypt(data.path("lyric").asText(""));
                String trans = QqQrcCrypto.decrypt(data.path("trans").asText(""));
                String roma = QqQrcCrypto.decrypt(data.path("roma").asText(""));
                NekoLyrics.Document document = QqLyricParser.parse(lyric, trans, roma);
                if (!document.isEmpty()) {
                    return document;
                }
            } catch (Exception e) {
                logger.warn("获取 QQ QRC 歌词失败，回退 LRC: songId={} mid={} err={}",
                        candidate.songId(), candidate.mid(), e.getMessage());
            }
        }
        String lrc = fetchLyric(candidate.mid());
        return QqLyricParser.parse(lrc, "", "");
    }

    /** 解码歌词字段的 Base64 文本（容忍换行）；空值或非法 Base64 返回空串。 */
    private static String decodeLyricBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getMimeDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** 按当前 Cookie 拉取登录账号资料；未登录或失败返回 null。 */
    public AccountProfile fetchAccountProfile() throws IOException {
        if (!isLoggedIn()) {
            return null;
        }
        ObjectNode module = objectMapper.createObjectNode();
        module.put("module", "music.UserInfo.userInfoServer");
        module.put("method", "GetLoginUserInfo");
        module.set("param", objectMapper.createObjectNode());
        ObjectNode body = objectMapper.createObjectNode();
        body.set("comm", moduleComm());
        body.set("req_0", module);
        JsonNode root = postJson(UPSTREAM, objectMapper.writeValueAsString(body),
                Map.of("User-Agent", WEB_UA));
        JsonNode info = root.path("req_0").path("data").path("info");
        String nickname = info.path("nick").asText(info.path("nickname").asText("")).trim();
        String avatar = info.path("logo").asText("").trim();
        if (nickname.isEmpty() && avatar.isEmpty()) {
            return null;
        }
        long userId;
        try {
            userId = Long.parseLong(loggedInUin());
        } catch (NumberFormatException e) {
            userId = 0L;
        }
        return new AccountProfile(userId, nickname, normalizeCover(avatar));
    }

    /** 下载 QQ 音乐直链到本地文件（只放行官方 CDN 域名）。 */
    public void downloadToFile(String url, Path destination, DownloadProgressListener listener) throws IOException {
        HttpResponse<InputStream> response =
                sendAudioGet(url, Duration.ofMinutes(15), null, "下载被中断");
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("下载失败 HTTP " + response.statusCode());
        }
        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long bytesRead;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            bytesRead = 0;
            long lastReported = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                bytesRead += read;
                if (listener != null && (bytesRead - lastReported >= 512 * 1024 || bytesRead == totalBytes)) {
                    lastReported = bytesRead;
                    listener.onProgress(bytesRead, totalBytes);
                }
            }
            if (listener != null && lastReported != bytesRead) {
                listener.onProgress(bytesRead, totalBytes);
            }
        }
        // 中途断流不一定抛异常，会对齐 Content-Length 兜一次底，避免截断文件入库。
        if (totalBytes >= 0 && bytesRead != totalBytes) {
            throw new IOException("下载不完整：期望 " + totalBytes + " 字节，实际 " + bytesRead);
        }
    }

    private QqSongCandidate toCandidate(JsonNode song) {
        long songId = song.path("id").asLong(0);
        String mid = song.path("mid").asText("").trim();
        String title = song.path("title").asText(song.path("name").asText("")).trim();
        if (mid.isEmpty() || title.isEmpty()) {
            return null;
        }
        String mediaMid = song.path("file").path("media_mid").asText("").trim();
        JsonNode album = song.path("album");
        String albumName = album.path("name").asText(album.path("title").asText("")).trim();
        String albumMid = album.path("mid").asText("").trim();
        if (albumMid.isEmpty()) {
            albumMid = album.path("pmid").asText("").trim();
        }
        String cover = albumMid.isEmpty()
                ? ""
                : "https://y.gtimg.cn/music/photo_new/T002R800x800M000" + albumMid + ".jpg";
        return new QqSongCandidate(songId, mid, mediaMid, title, joinSingers(song.path("singer")),
                albumName, cover, song.path("interval").asInt(0) * 1000);
    }

    private static String base64Text(String value) {
        return Base64.getEncoder().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** 移动端 comm：模块调用（用户资料等）。 */
    private ObjectNode moduleComm() {
        String uin = loggedInUin();
        String musicKey = musicKey();
        boolean loggedIn = !uin.equals("0") && !musicKey.isEmpty();
        ObjectNode comm = objectMapper.createObjectNode();
        comm.put("ct", 11);
        comm.put("cv", 14090008);
        comm.put("v", 14090008);
        comm.put("chid", "10003505");
        comm.put("os_ver", "15");
        comm.put("phonetype", "24122RKC7C");
        comm.put("tmeAppID", "qqmusic");
        comm.put("nettype", "NETWORK_WIFI");
        comm.put("udid", "0");
        comm.put("OpenUDID", "0");
        comm.put("QIMEI36", "0");
        comm.put("uin", loggedIn ? uin : "0");
        if (loggedIn) {
            comm.put("qq", uin);
            comm.put("authst", musicKey);
            comm.put("tmeLoginType", 2);
        }
        return comm;
    }

    /** 桌面搜索 comm：ct=19 下发完整音质字段，登录态注入 uin/authst。 */
    private ObjectNode desktopSearchComm() {
        String uin = loggedInUin();
        String musicKey = musicKey();
        boolean loggedIn = !uin.equals("0") && !musicKey.isEmpty();
        ObjectNode comm = objectMapper.createObjectNode();
        comm.put("_channelid", "0");
        comm.put("_os_version", "6.2.9200-2");
        comm.put("ct", "19");
        comm.put("cv", "2151");
        comm.put("guid", DESKTOP_GUID);
        comm.put("patch", "118");
        comm.put("psrf_access_token_expiresAt", 0);
        comm.put("psrf_qqaccess_token", "");
        comm.put("psrf_qqopenid", "");
        comm.put("psrf_qqunionid", "");
        comm.put("tmeAppID", "qqmusic");
        comm.put("tmeLoginType", loggedIn ? 2 : 0);
        comm.put("uin", loggedIn ? uin : "0");
        comm.put("wid", DESKTOP_WID);
        if (loggedIn) {
            comm.put("qq", uin);
            comm.put("authst", musicKey);
        }
        return comm;
    }

    private JsonNode postJson(String url, String payload, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", WEB_UA);
        headers.put("Referer", "https://y.qq.com/");
        headers.putAll(extraHeaders);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout());
        headers.forEach(builder::header);
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpTransport.requireAllowedTarget(request, ALLOWED_HOSTS);
        HttpResponse<String> response = HttpTransport.sendString(httpClient, request, "请求 QQ 音乐接口被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) {
            throw new UpstreamException(response.statusCode());
        }
        JsonNode json = objectMapper.readTree(response.body());
        if (json == null || json.isMissingNode()) {
            throw new IOException("QQ 音乐接口返回为空");
        }
        return json;
    }

    /** 根据首选档位生成音质顺位候选（含该档及其以下所有档）。 */
    private static List<QqQualityTemplate> qualityCandidates(String preferredLevel) {
        String target = normalizeQualityLevel(preferredLevel);
        int index = -1;
        for (int i = 0; i < QUALITY_TEMPLATES.size(); i++) {
            if (QUALITY_TEMPLATES.get(i).level().equals(target)) {
                index = i;
                break;
            }
        }
        return index >= 0 ? QUALITY_TEMPLATES.subList(index, QUALITY_TEMPLATES.size()) : QUALITY_TEMPLATES;
    }

    /** 把共用的「优先音质」取值（网易云 level 命名）归并到 QQ 档位名，无匹配时返回原值。 */
    private static String normalizeQualityLevel(String preferredLevel) {
        String level = preferredLevel == null ? "" : preferredLevel.trim().toLowerCase(Locale.ROOT);
        return switch (level) {
            case "jymaster", "hires", "jyeffect", "sky" -> "hires";
            case "exhigh", "higher" -> "exhigh";
            default -> level;
        };
    }

    private String cookieHeader() {
        return config == null ? "" : config.getQqCookie();
    }

    /** 单次请求超时：复用「外站补全」共用的 HTTP 超时设置，运行期改配置即时生效。 */
    private Duration requestTimeout() {
        if (config == null) {
            return REQUEST_TIMEOUT;
        }
        int seconds = config.getNeteaseHttpTimeoutSeconds();
        return seconds > 0 ? Duration.ofSeconds(seconds) : REQUEST_TIMEOUT;
    }

    /** 解析登录 Cookie 里的音乐账号（去掉 QQ 前缀 'o'）；未登录返回 "0"。 */
    private String loggedInUin() {
        Map<String, String> cookies = cookieMap();
        String raw = firstNonEmpty(cookies.get("qm_str_musicid"), cookies.get("uin"),
                cookies.get("wxuin"), cookies.get("p_uin"));
        if (raw.startsWith("o")) {
            raw = raw.substring(1);
        }
        return raw.isEmpty() ? "0" : raw;
    }

    private String musicKey() {
        Map<String, String> cookies = cookieMap();
        return firstNonEmpty(cookies.get("qm_keyst"), cookies.get("qqmusic_key"));
    }

    private Map<String, String> cookieMap() {
        Map<String, String> cookies = new LinkedHashMap<>();
        String header = cookieHeader();
        if (header.isEmpty()) {
            return cookies;
        }
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static String normalizeCover(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("http://")) {
            return "https://" + value.substring(7);
        }
        return value;
    }

    private static String randomGuid() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString();
    }

    /** 桌面 searchid：32 位随机 hex（大写）+ 5 位随机数字。 */
    private static String randomSearchId() {
        StringBuilder sb = new StringBuilder(37);
        for (int i = 0; i < 32; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString().toUpperCase(Locale.ROOT)
                + String.format(Locale.ROOT, "%05d", RANDOM.nextInt(100000));
    }

    /**
     * QQ 桌面协议请求签名 {@code zzcSign}。
     *
     * <p>对即将发送的 JSON 串计算 SHA1（大写十六进制），按固定下标取字拼出 part1/part2，
     * 再把摘要每 2 个十六进制字符转字节、与固定常量逐位异或后 base64 并去掉 {@code / + =}
     * 得到 part3，最终拼成 {@code zzc{part1}{part3}{part2}} 并整体小写。签名只覆盖请求体，
     * 因此调用方必须保证「参与签名的字符串」与实际发送的字节完全一致。</p>
     */
    static String zzcSign(String text) {
        String hash = sha1HexUpper(text);
        StringBuilder sign = new StringBuilder(48);
        sign.append("zzc");
        appendPicked(sign, hash, SIGN_PART_1_INDEXES);
        byte[] scrambled = new byte[SIGN_SCRAMBLE_VALUES.length];
        for (int i = 0; i < SIGN_SCRAMBLE_VALUES.length; i++) {
            int value = (hexAt(hash, i * 2) << 4) | hexAt(hash, i * 2 + 1);
            scrambled[i] = (byte) (value ^ SIGN_SCRAMBLE_VALUES[i]);
        }
        sign.append(Base64.getEncoder().encodeToString(scrambled).replaceAll("[/+=]", ""));
        appendPicked(sign, hash, SIGN_PART_2_INDEXES);
        return sign.toString().toLowerCase(Locale.ROOT);
    }

    /** QQ 系哈希（ptqrtoken / g_tk 共用）：逐字符 {@code h=(h<<5)+h+code}，保留低 32 位后清符号位。 */
    static long hash33(String str, long seed) {
        long h = seed & 0xFFFFFFFFL;
        if (str != null) {
            for (int i = 0; i < str.length(); i++) {
                h = ((h << 5) + h + str.charAt(i)) & 0xFFFFFFFFL;
            }
        }
        return h & 0x7FFFFFFFL;
    }

    private static void appendPicked(StringBuilder sb, String hash, int[] indexes) {
        for (int index : indexes) {
            if (index >= 0 && index < hash.length()) {
                sb.append(hash.charAt(index));
            }
        }
    }

    private static int hexAt(String hash, int index) {
        if (index < 0 || index >= hash.length()) {
            return 0;
        }
        int digit = Character.digit(hash.charAt(index), 16);
        return digit < 0 ? 0 : digit;
    }

    private static String sha1HexUpper(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** SHA1 摘要中参与 part1 的字符下标。 */
    private static final int[] SIGN_PART_1_INDEXES = {23, 14, 6, 36, 16, 40, 7, 19};
    /** SHA1 摘要中参与 part2 的字符下标。 */
    private static final int[] SIGN_PART_2_INDEXES = {16, 1, 32, 12, 19, 27, 8, 5};
    /** part3 的 XOR 混淆常量（20 字节）。 */
    private static final int[] SIGN_SCRAMBLE_VALUES = {
            89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
            186, 123, 120, 64, 242, 133, 143, 161, 121, 179,
    };
}
