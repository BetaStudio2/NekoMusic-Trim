package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.config.ConfigManager;
import com.neko.music.util.HttpTransport;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.NeteaseLyricParser;
import com.neko.music.util.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct NetEase client with the weapi/eapi algorithms used by NeteaseCloudMusicApi. */
public class NeteaseCloudMusicClient {
    private static final Logger logger = LoggerFactory.getLogger(NeteaseCloudMusicClient.class);
    private static final String MUSIC_BASE = "https://music.163.com";
    private static final String INTERFACE_BASE = "https://interface.music.163.com";
    /** eapi 加密接口专用域名（与 ArchoeraMusic/SPlayer 对齐；api 明文接口仍走 INTERFACE_BASE）。 */
    private static final String EAPI_BASE = "https://interfacepc.music.163.com";
    /** xeapi（反爬）接口域名。 */
    private static final String XEAPI_BASE = "https://interface3.music.163.com";
    /**
     * 网易云客户端允许访问的上游域名：music.163.com / interface*.music.163.com 等接口域名，
     * 以及媒体与封面所在的 *.music.126.net 等 CDN 域名。
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of("163.com", "126.net", "netease.com");
    /** 移动端 api UA（xeapi / api 明文接口使用）。 */
    private static final String ANDROID_API_UA =
            "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)";
    private static final String IV = "0102030405060708";
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n"
            + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB\n"
            + "-----END PUBLIC KEY-----";
    private static final PublicKey WEAPI_PUBLIC_KEY = loadPublicKey();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    /**
     * 扫码登录类型：3 = 移动端。配合 eapi 使用，避免 web（type=1）触发网易云 8821 行为验证。
     */
    private static final int QR_LOGIN_TYPE = 3;
    /** eapi 移动端 User-Agent（SPlayer-Next 在非 osx 时统一使用 iPhone UA）。 */
    private static final String EAPI_USER_AGENT = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    /** SPlayer 的 pc 设备默认值（未在 cookie 中指定 os 时使用）。 */
    private static final String DEFAULT_OS = "pc";
    private static final String DEFAULT_OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit";
    private static final String DEFAULT_APPVER = "3.1.17.204416";
    private static final String DEFAULT_CHANNEL = "netease";
    /** 进程级设备指纹：52 位大写十六进制，eapi 风控需要。 */
    private static final String DEVICE_ID = generateDeviceId();
    /** 匿名会话设备 Cookie（与 NeteaseCloudMusicApi 的 processCookieObject 对齐）。 */
    private static final String NTES_NUID = randomHex(16);
    private static final String NTES_NNID = NTES_NUID + "," + System.currentTimeMillis();
    private static final String WNMCID = randomWnmcid();
    /** 匿名注册 username 的 XOR 密钥（网易云 cloudmusic_dll_encode_id）。 */
    private static final String ID_XOR_KEY = "3go8&$8*3*3h0k(2)2";
    /** Set-Cookie 里的属性名（非 Cookie 本身），拼装登录 Cookie 时需剔除。 */
    private static final Set<String> SET_COOKIE_ATTRIBUTES =
            Set.of("path", "domain", "expires", "max-age", "httponly", "secure", "samesite");
    /** 从 Set-Cookie 中提取服务端下发的 NMTID。 */
    private static final Pattern NMTID_PATTERN = Pattern.compile("(?:^|;\\s*)NMTID=([^;]+)");

    private final ConfigManager config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    /** 匿名会话（MUSIC_A）：扫码登录前先注册，满足网易云「设备环境」校验。 */
    private volatile String anonymousToken = "";
    private volatile String anonymousCsrf = "";
    private volatile boolean anonymousAttempted = false;
    /** 服务端在「不带 NMTID 的 eapi 请求」响应里下发的合法 NMTID，缓存复用。 */
    private volatile String cachedNmtid = "";
    /** xeapi 公钥与会话（游客注册用）。 */
    private volatile NeteaseXeapi.Key xeapiKey;
    private volatile NeteaseXeapi.Session xeapiSession = new NeteaseXeapi.Session("", "");

    public NeteaseCloudMusicClient(ConfigManager config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpTransport.create(Duration.ofSeconds(config.getNeteaseHttpTimeoutSeconds()));
    }

    public record NeteaseSongCandidate(long id, String title, String artist, String album) {}
    public record SongPlayUrl(String url, String type, int durationMs, String level) {}
    public record SongDetail(String title, String artist, String album, String coverUrl, int durationMs) {}
    public record LyricApiPayload(String primaryLrc, String rawMain, NekoLyrics.Document rich) {}
    /** 扫码轮询结果：code 800 过期 / 801 待扫码 / 802 待确认 / 803 已确认（cookie 已下发）。 */
    public record QrLoginCheck(int code, String cookie, String nickname, String avatarUrl) {}
    /** 当前登录账号资料。 */
    public record AccountProfile(long userId, String nickname, String avatarUrl) {}

    public boolean isLoggedIn() {
        return probeLoginState().orElse(false);
    }

    /**
     * 三态登录态探测：{@code true} 在线 / {@code false} 明确掉线 / {@code empty} 查询失败（网络或接口异常）。
     *
     * <p>掉线巡检必须用这个而不是 {@link #isLoggedIn()}：后者在查询失败时也返回 {@code false}，
     * 网络抖动会被误判为掉线并发出错误告警。</p>
     */
    public Optional<Boolean> probeLoginState() {
        if (!parseCookie().containsKey("MUSIC_U")) return Optional.of(false);
        try {
            return Optional.of(postWeapi("/weapi/w/nuser/account/get", Map.of()).path("code").asInt(0) == 200);
        } catch (IOException e) {
            logger.warn("查询网易云登录状态失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 扫码登录：申请二维码 unikey（eapi 移动端，规避 web 风控）。 */
    public String fetchQrKey() throws IOException {
        ensureAnonymousSession();
        HttpResponse<String> response = postEapiResponse(
                "/api/login/qrcode/unikey", Map.of("type", QR_LOGIN_TYPE));
        JsonNode root = objectMapper.readTree(decryptEapi(response.body()));
        String unikey = textOrEmpty(root.path("unikey"));
        if (unikey.isEmpty()) {
            throw new IOException("网易云未返回二维码 key（code=" + root.path("code").asInt(0) + "）");
        }
        return unikey;
    }

    /**
     * 扫码登录：轮询扫码状态（eapi 移动端）。确认（803）时从 Set-Cookie 提取登录 Cookie（含 MUSIC_U），
     * 供上层持久化到 {@code netease_search_fill.cookie}。
     */
    public QrLoginCheck checkQrLogin(String key) throws IOException {
        ensureAnonymousSession();
        HttpResponse<String> response = postEapiResponse(
                "/api/login/qrcode/client/login", Map.of("key", key, "type", QR_LOGIN_TYPE));
        JsonNode root = objectMapper.readTree(decryptEapi(response.body()));
        int code = root.path("code").asInt(0);
        String cookie = code == 803 ? extractLoginCookie(response) : "";
        if (code == 803 && cookie.isEmpty()) {
            // 本地排查用：只打印 Cookie 名，不打印值
            logger.warn("网易云扫码返回 803，但未从 Set-Cookie 取到 MUSIC_U；响应体={} Set-Cookie 名称={}",
                    root.toString(), cookieNames(response));
        }
        return new QrLoginCheck(code, cookie,
                textOrEmpty(root.path("nickname")), textOrEmpty(root.path("avatarUrl")));
    }

    /** 按当前 Cookie 拉取登录账号资料；未登录或游客态返回 null。 */
    public AccountProfile fetchAccountProfile() throws IOException {
        JsonNode root = postWeapi("/weapi/w/nuser/account/get", Map.of());
        if (root.path("code").asInt(0) != 200) return null;
        JsonNode profile = root.path("profile");
        long userId = profile.path("userId").asLong(0);
        String nickname = textOrEmpty(profile.path("nickname"));
        if (userId <= 0 || nickname.isEmpty()) return null;
        return new AccountProfile(userId, nickname, textOrEmpty(profile.path("avatarUrl")));
    }

    /**
     * 确保匿名会话已注册（获取 MUSIC_A / __csrf）。
     *
     * <p>网易云扫码登录会校验「设备环境」：请求必须带一个匿名会话与设备指纹，否则扫码确认时报
     * {@code 8821} / 「设备环境异常」。优先用 xeapi（反爬接口，与用户端 Dart 移植一致的
     * {@code register/anonimous}）注册，失败再回退到 weapi。</p>
     */
    private void ensureAnonymousSession() {
        if (!anonymousToken.isEmpty() || anonymousAttempted) return;
        synchronized (this) {
            if (!anonymousToken.isEmpty() || anonymousAttempted) return;
            anonymousAttempted = true;
            if (registerAnonymousViaXeapi()) {
                return;
            }
            registerAnonymousViaWeapi();
            if (anonymousToken.isEmpty()) {
                anonymousAttempted = false; // 允许下次再试
            }
        }
    }

    /** xeapi 游客注册：X25519 封装动态密钥 + AES-ECB 分层加密，取回 MUSIC_A。 */
    private boolean registerAnonymousViaXeapi() {
        try {
            NeteaseXeapi.Key key = ensureXeapiKey();
            if (key == null) {
                return false;
            }
            String username = anonymousUsername(DEVICE_ID);
            Map<String, Object> registerData = new LinkedHashMap<>();
            registerData.put("username", username);
            registerData.put("e_r", false);
            NeteaseXeapi.Encrypted encrypted = NeteaseXeapi.encrypt(
                    "/api/register/anonimous", registerData, key, xeapiSession, "android");
            String buildver = String.valueOf(System.currentTimeMillis() / 1000);
            Map<String, String> header = new LinkedHashMap<>();
            header.put("User-Agent", ANDROID_API_UA);
            header.put("X-Client-Enc-State", "ENCRYPTED");
            header.put("x-aeapi", "true");
            header.put("x-deviceid", DEVICE_ID);
            header.put("x-os", "android");
            header.put("x-osver", "16");
            header.put("x-appver", "9.1.65");
            header.put("x-sdeviceid", DEVICE_ID);
            header.put("x-buildver", buildver);
            header.put("Cookie", xeapiDeviceCookie(buildver));

            String body = encodeForm(Map.of(
                    "B", encrypted.b(), "S", encrypted.s(), "R", encrypted.r()));
            HttpResponse<byte[]> response = postBytes(XEAPI_BASE + "/xeapi/register/anonimous", body, header);
            logger.debug("xeapi 注册响应: status={} len={}", response.statusCode(), response.body().length);
            String ssid = response.headers().firstValue("x-encr-ssid").orElse("");
            String sskey = response.headers().firstValue("x-encr-sskey").orElse("");
            if (!ssid.isEmpty() && !sskey.isEmpty()) {
                xeapiSession = new NeteaseXeapi.Session(ssid, sskey);
            }
            JsonNode root = NeteaseXeapi.decryptResponse(response.body(), objectMapper);
            if (root.path("code").asInt(0) == 200) {
                Map<String, String> setCookies = readSetCookies(response);
                anonymousToken = textOrEmpty(root.path("token"));
                if (anonymousToken.isEmpty()) {
                    anonymousToken = setCookies.getOrDefault("MUSIC_A", "");
                }
                anonymousCsrf = setCookies.getOrDefault("__csrf", "");
                logger.info("网易云匿名会话已就绪(xeapi): deviceId={} MUSIC_A={} csrf={}",
                        DEVICE_ID, !anonymousToken.isEmpty(), !anonymousCsrf.isEmpty());
                return true;
            }
            logger.warn("网易云 xeapi 匿名注册未成功: {}", root.toString());
        } catch (Exception e) {
            logger.warn("网易云 xeapi 匿名注册异常: {}", e.getMessage());
        }
        return false;
    }

    /** weapi 回退：游客注册（部分环境/限频下 xeapi 不可用）。 */
    private void registerAnonymousViaWeapi() {
        try {
            HttpResponse<String> response = postWeapiResponse(
                    "/weapi/register/anonimous",
                    Map.of("username", anonymousUsername(DEVICE_ID)), deviceCookie());
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(0) == 200) {
                Map<String, String> setCookies = readSetCookies(response);
                anonymousToken = setCookies.getOrDefault("MUSIC_A", "");
                anonymousCsrf = setCookies.getOrDefault("__csrf", "");
                logger.info("网易云匿名会话已就绪(weapi): deviceId={} MUSIC_A={} csrf={}",
                        DEVICE_ID, !anonymousToken.isEmpty(), !anonymousCsrf.isEmpty());
            } else {
                logger.warn("网易云 weapi 匿名注册未成功: {}", root.toString());
            }
        } catch (IOException e) {
            logger.warn("网易云 weapi 匿名注册异常: {}", e.getMessage());
        }
    }

    /** 拉取并缓存 xeapi 公钥（POST /api/gorilla/anti/crawler/security/key/get）。 */
    private NeteaseXeapi.Key ensureXeapiKey() {
        if (xeapiKey != null && xeapiKey.sk() != null && !xeapiKey.sk().isEmpty()) {
            return xeapiKey;
        }
        try {
            String nonce = randomDigits(16);
            String timestamp = String.valueOf(System.currentTimeMillis());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("appVersion", "9.1.65");
            data.put("currentKeyVersion", xeapiKey == null ? "" : xeapiKey.version());
            data.put("deviceId", DEVICE_ID);
            data.put("nonce", nonce);
            data.put("os", "android");
            data.put("requestType", "active");
            data.put("signature", NeteaseXeapi.sign(timestamp, nonce));
            data.put("t1", "");
            data.put("t2", "");
            data.put("timestamp", timestamp);
            data.put("uid", "");

            HttpResponse<String> response = postFormResponse(
                    INTERFACE_BASE + "/api/gorilla/anti/crawler/security/key/get",
                    encodeForm(data), ANDROID_API_UA, "deviceId=" + DEVICE_ID, null);
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(0) != 200) {
                logger.warn("xeapi 公钥请求失败: code={}", root.path("code").asInt(0));
                return null;
            }
            JsonNode payload = root.path("data");
            String encryptedData = textOrEmpty(payload.path("encryptedData"));
            if (encryptedData.isEmpty()) {
                logger.warn("xeapi 公钥响应缺少 encryptedData");
                return null;
            }
            String signature = textOrEmpty(payload.path("signature"));
            if (!signature.isEmpty()
                    && !NeteaseXeapi.sign(textOrEmpty(payload.path("timestamp")), nonce).equals(signature)) {
                logger.warn("xeapi 公钥响应签名不匹配");
                return null;
            }
            NeteaseXeapi.Key key = NeteaseXeapi.decryptKey(encryptedData, objectMapper);
            if (key.sk() == null || key.sk().isEmpty()) {
                logger.warn("xeapi 公钥缺少 sk");
                return null;
            }
            xeapiKey = key;
            return key;
        } catch (IOException | RuntimeException e) {
            logger.warn("xeapi 公钥获取失败: {}", e.getMessage());
            return null;
        }
    }

    /** NeteaseCloudMusicApi 的匿名 username：base64(`${deviceId} ${base64(md5(xor(deviceId,key)))}`)。 */
    private static String anonymousUsername(String deviceId) throws IOException {
        StringBuilder xored = new StringBuilder(deviceId.length());
        for (int i = 0; i < deviceId.length(); i++) {
            xored.append((char) (deviceId.charAt(i) ^ ID_XOR_KEY.charAt(i % ID_XOR_KEY.length())));
        }
        String encodedId = Base64.getEncoder()
                .encodeToString(md5Bytes(xored.toString().getBytes(StandardCharsets.UTF_8)));
        String raw = deviceId + " " + encodedId;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** xeapi 请求的 Cookie（os 覆写为 android，与用户端请求层一致）。 */
    private String xeapiDeviceCookie(String buildver) {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("__remember_me", "true");
        cookies.put("ntes_kaola_ad", "1");
        cookies.put("_ntes_nuid", NTES_NUID);
        cookies.put("_ntes_nnid", NTES_NNID);
        cookies.put("WNMCID", WNMCID);
        cookies.put("WEVNSM", "1.0.0");
        cookies.put("deviceId", DEVICE_ID);
        cookies.put("os", "android");
        cookies.put("osver", "16");
        cookies.put("appver", "9.1.65");
        cookies.put("channel", DEFAULT_CHANNEL);
        cookies.put("buildver", buildver);
        cookies.put("sDeviceId", DEVICE_ID);
        if (!anonymousToken.isEmpty()) {
            cookies.put("MUSIC_A", anonymousToken);
        }
        return urlEncodeCookie(cookies);
    }

    /** 匿名会话设备 Cookie：weapi 回退用（含 MUSIC_A）。 */
    private String deviceCookie() {
        Map<String, String> cookies = new LinkedHashMap<>(parseCookie());
        cookies.putIfAbsent("__remember_me", "true");
        cookies.putIfAbsent("ntes_kaola_ad", "1");
        cookies.putIfAbsent("_ntes_nuid", NTES_NUID);
        cookies.putIfAbsent("_ntes_nnid", NTES_NNID);
        cookies.putIfAbsent("WNMCID", WNMCID);
        cookies.putIfAbsent("WEVNSM", "1.0.0");
        if (!cookies.containsKey("MUSIC_U") && !anonymousToken.isEmpty()) {
            cookies.putIfAbsent("MUSIC_A", anonymousToken);
        }
        if (!cookies.containsKey("__csrf") && !anonymousCsrf.isEmpty()) {
            cookies.put("__csrf", anonymousCsrf);
        }
        return stringifyCookie(cookies);
    }

    public List<NeteaseSongCandidate> searchSongs(String keywords, int limit) throws IOException {
        JsonNode songs = searchRaw(keywords, 1, limit, 0).path("result").path("songs");
        if (!songs.isArray()) return List.of();
        List<NeteaseSongCandidate> result = new ArrayList<>();
        for (JsonNode song : songs) {
            long id = song.path("id").asLong(0);
            String title = textOrEmpty(song.path("name"));
            if (id > 0 && !title.isEmpty()) {
                result.add(new NeteaseSongCandidate(id, title, joinArtists(song.path("ar")),
                        textOrEmpty(song.path("al").path("name"))));
            }
        }
        return result;
    }

    public JsonNode searchRaw(String keywords, int type, int limit, int offset) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("s", keywords == null ? "" : keywords);
        data.put("type", type <= 0 ? 1 : type);
        data.put("limit", Math.max(1, Math.min(100, limit)));
        data.put("offset", Math.max(0, offset));
        return postWeapi("/weapi/search/get", data);
    }

    public Optional<SongDetail> fetchSongDetail(long songId) throws IOException {
        JsonNode songs = fetchSongDetailRaw(List.of(songId)).path("songs");
        if (!songs.isArray() || songs.isEmpty()) return Optional.empty();
        JsonNode song = songs.get(0);
        return Optional.of(new SongDetail(textOrEmpty(song.path("name")), joinArtists(song.path("ar")),
                textOrEmpty(song.path("al").path("name")), textOrEmpty(song.path("al").path("picUrl")),
                song.path("dt").asInt(0)));
    }

    public JsonNode fetchSongDetailRaw(List<Long> songIds) throws IOException {
        if (songIds == null || songIds.isEmpty()) {
            return objectMapper.createObjectNode().put("code", 400).put("msg", "ids 不能为空");
        }
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < songIds.size(); i++) {
            if (i > 0) ids.append(',');
            ids.append("{\"id\":").append(songIds.get(i)).append('}');
        }
        ids.append(']');
        return postWeapi("/weapi/v3/song/detail", Map.of("c", ids.toString()));
    }

    public Optional<SongPlayUrl> resolvePlayUrl(long songId, String preferredLevel) throws IOException {
        for (String requested : qualityFallbackChain(preferredLevel)) {
            JsonNode data = fetchSongUrlRaw(songId, requested).path("data");
            if (!data.isArray() || data.isEmpty()) continue;
            JsonNode item = data.get(0);
            if (item.path("code").asInt(0) != 200) continue;
            String url = textOrEmpty(item.path("url"));
            String actual = textOrEmpty(item.path("level")).toLowerCase(Locale.ROOT);
            if (url.isEmpty() || (!actual.isEmpty() && !actual.equals(requested))) continue;
            return Optional.of(new SongPlayUrl(url, textOrEmpty(item.path("type")).isEmpty() ? "mp3" : textOrEmpty(item.path("type")),
                    item.path("time").asInt(0), actual.isEmpty() ? requested : actual));
        }
        return Optional.empty();
    }

    public JsonNode fetchSongUrlRaw(long songId, String level) throws IOException {
        String requested = level == null || level.isBlank() ? "standard" : level.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ids", "[" + songId + "]");
        data.put("level", requested);
        data.put("encodeType", "flac");
        if ("sky".equals(requested)) data.put("immerseType", "c51");
        return postEapi("/api/song/enhance/player/url/v1", data);
    }

    public String fetchLyricLrc(long songId) throws IOException {
        return fetchLyricPayload(songId).primaryLrc();
    }

    /**
     * 公共歌词读取入口：普通逐行 LRC、新版 YRC、翻译均复用同一个 lyric_new 响应。
     * 当前只向下游输出行级 LRC，字级时间轴解析留待后续数据模型扩展。
     */
    public LyricApiPayload fetchLyricPayload(long songId) throws IOException {
        JsonNode root = fetchLyricRaw(songId);
        NeteaseLyricParser.ParsedLyrics parsed = NeteaseLyricParser.parse(root);
        return new LyricApiPayload(parsed.lrc(), parsed.rawMain(), parsed.rich());
    }

    public JsonNode fetchLyricRaw(long songId) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", songId);
        data.put("cp", false);
        data.put("e_r", false);
        data.put("tv", 0);
        data.put("lv", 0);
        data.put("rv", 0);
        data.put("kv", 0);
        data.put("yv", 0);
        data.put("ytv", 0);
        data.put("yrv", 0);
        HttpResponse<String> response = postEapiResponse("/api/song/lyric/v1", data);
        return objectMapper.readTree(decryptEapi(response.body()));
    }

    /** 获取网易云歌单歌曲，响应兼容 NeteaseCloudMusicApi 的 /playlist/track/all。 */
    public JsonNode fetchPlaylistTrackAll(long playlistId, int limit, int offset) throws IOException {
        JsonNode playlistRoot = postApi("/api/v6/playlist/detail", Map.of("id", playlistId, "n", 100000, "s", 8));
        if (playlistRoot.path("code").asInt(0) != 200) return playlistRoot;
        JsonNode trackIds = playlistRoot.path("playlist").path("trackIds");
        if (!trackIds.isArray()) return objectMapper.createObjectNode().put("code", 404).put("msg", "playlist.not_found");
        int start = Math.max(0, offset);
        int end = Math.min(trackIds.size(), start + (limit <= 0 ? 5000 : Math.min(limit, 5000)));
        ArrayNode songs = objectMapper.createArrayNode();
        ArrayNode privileges = objectMapper.createArrayNode();
        for (int cursor = start; cursor < end; cursor += 1000) {
            int batchEnd = Math.min(end, cursor + 1000);
            List<Long> ids = new ArrayList<>(batchEnd - cursor);
            for (int i = cursor; i < batchEnd; i++) ids.add(trackIds.get(i).path("id").asLong(0));
            JsonNode details = fetchSongDetailRaw(ids);
            if (details.path("songs").isArray()) details.path("songs").forEach(songs::add);
            if (details.path("privileges").isArray()) details.path("privileges").forEach(privileges::add);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("songs", songs); result.set("privileges", privileges); result.put("code", 200);
        return result;
    }

    public JsonNode fetchPlaylistDetail(long playlistId) throws IOException {
        return postApi("/api/v6/playlist/detail", Map.of("id", playlistId, "n", 100000, "s", 8));
    }

    /** 下载进度回调：totalBytes 为 -1 表示上游未返回 Content-Length。 */
    @FunctionalInterface
    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public void downloadToFile(String url, Path destination) throws IOException {
        downloadToFile(url, destination, null);
    }

    public void downloadToFile(String url, Path destination, DownloadProgressListener listener) throws IOException {
        // 地址来自上游响应，只放行网易云官方接口 / CDN 域名，顺带挡掉内网与元数据地址
        URI targetUri = OutboundUrlGuard.requireAllowedHttpUrl(url, ALLOWED_HOSTS);
        // 媒体文件可能很大（母带 FLAC 可达数百 MB）：请求总超时放宽到 15 分钟，
        // 避免用 45s 的接口超时把大文件下载中途掐断（连接超时仍由 httpClient 控制）。
        HttpRequest request = HttpRequest.newBuilder().uri(targetUri)
                .timeout(Duration.ofMinutes(15)).header("User-Agent", userAgent()).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IOException("下载失败 HTTP " + response.statusCode());
            long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            Path parent = destination.getParent(); if (parent != null) Files.createDirectories(parent);
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long bytesRead = 0;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("下载被中断", e);
        }
    }

    private JsonNode postApi(String path, Map<String, ?> data) throws IOException {
        return postForm(MUSIC_BASE + path, encodeForm(data), false, false);
    }

    private JsonNode postWeapi(String path, Map<String, ?> data) throws IOException {
        HttpResponse<String> response = postWeapiResponse(path, data);
        return objectMapper.readTree(response.body());
    }

    /** weapi 原始响应：需要读取 Set-Cookie（匿名注册 / 扫码登录 803 时下发 MUSIC_U）的调用方使用。 */
    private HttpResponse<String> postWeapiResponse(String path, Map<String, ?> data) throws IOException {
        return postWeapiResponse(path, data, cookieHeader(false));
    }

    /** weapi 原始响应，指定 Cookie（匿名注册用设备 Cookie）。 */
    private HttpResponse<String> postWeapiResponse(String path, Map<String, ?> data, String cookie) throws IOException {
        String text = objectMapper.writeValueAsString(data);
        String secret = randomSecretKey();
        Map<String, String> encrypted = new LinkedHashMap<>();
        encrypted.put("params", aesCbcBase64(aesCbcBase64(text, PRESET_KEY, IV), secret, IV));
        encrypted.put("encSecKey", rsaNoPaddingHex(new StringBuilder(secret).reverse().toString()));
        return postFormResponse(MUSIC_BASE + path, encodeForm(encrypted), weapiUserAgent(), cookie);
    }

    private JsonNode postEapi(String path, Map<String, ?> data) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        Map<String, String> header = new LinkedHashMap<>();
        header.put("osver", "17,1,2"); header.put("appver", "8.10.05"); header.put("versioncode", "140");
        header.put("buildver", String.valueOf(System.currentTimeMillis() / 1000)); header.put("resolution", "1920x1080");
        header.put("__csrf", parseCookie().getOrDefault("__csrf", "")); header.put("os", "android");
        payload.put("header", header);
        String text = objectMapper.writeValueAsString(payload);
        String digest = md5Hex("nobody" + path + "use" + text + "md5forencrypt");
        String encrypted = aesEcbHex(path + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest, EAPI_KEY);
        return postForm(INTERFACE_BASE + path.replace("/api/", "/eapi/"), encodeForm(Map.of("params", encrypted)), false, true);
    }

    /**
     * eapi 原始响应（登录流程专用）：与 SPlayer-Next 的 eapi 请求逐项对齐（pc 默认值 + iPhone UA、
     * 无 Referer、URL 编码 Cookie），并保留响应头以便读取 803 下发的 Set-Cookie。
     */
    private HttpResponse<String> postEapiResponse(String path, Map<String, ?> data) throws IOException {
        Map<String, String> header = eapiClientHeader();
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("header", header);
        String text = objectMapper.writeValueAsString(payload);
        String digest = md5Hex("nobody" + path + "use" + text + "md5forencrypt");
        String encrypted = aesEcbHex(path + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest, EAPI_KEY);
        HttpResponse<String> response = postFormResponse(EAPI_BASE + path.replace("/api/", "/eapi/"),
                encodeForm(Map.of("params", encrypted)), EAPI_USER_AGENT, urlEncodeCookie(header), null);
        captureNmtid(response);
        return response;
    }

    /** 服务端仅在「不带 NMTID 的 eapi 请求」里下发 NMTID，捕获后后续请求复用。 */
    private void captureNmtid(HttpResponse<String> response) {
        if (!cachedNmtid.isEmpty()) {
            return;
        }
        for (String header : response.headers().allValues("Set-Cookie")) {
            Matcher matcher = NMTID_PATTERN.matcher(header);
            if (matcher.find()) {
                cachedNmtid = matcher.group(1);
                return;
            }
        }
    }

    /**
     * eapi 请求头：与 SPlayer-Next 的 eapi 分支逐项对齐。
     *
     * <p>SPlayer 未在 cookie 指定 os 时默认按 pc 处理（osver/appver/channel 用桌面端值），
     * 且 eapi 请求头里**不包含 NMTID**（登录类接口尤其不能带）。</p>
     */
    private Map<String, String> eapiClientHeader() {
        Map<String, String> cookies = parseCookie();
        String os = valueOr(cookies.get("os"), DEFAULT_OS);
        String osver = valueOr(cookies.get("osver"), DEFAULT_OSVER);
        String appver = valueOr(cookies.get("appver"), DEFAULT_APPVER);
        String channel = valueOr(cookies.get("channel"), DEFAULT_CHANNEL);

        Map<String, String> header = new LinkedHashMap<>();
        header.put("osver", osver);
        header.put("deviceId", DEVICE_ID);
        header.put("os", os);
        header.put("appver", appver);
        header.put("versioncode", "140");
        header.put("mobilename", valueOr(cookies.get("mobilename"), ""));
        header.put("buildver", valueOr(cookies.get("buildver"), String.valueOf(System.currentTimeMillis() / 1000)));
        header.put("resolution", valueOr(cookies.get("resolution"), "1920x1080"));
        header.put("__csrf", valueOr(cookies.get("__csrf"), anonymousCsrf));
        header.put("channel", channel);
        header.put("requestId", System.currentTimeMillis() + "_" + String.format("%04d", RANDOM.nextInt(1000)));
        if (cookies.containsKey("MUSIC_U")) {
            header.put("MUSIC_U", cookies.get("MUSIC_U"));
        } else if (cookies.containsKey("MUSIC_A")) {
            header.put("MUSIC_A", cookies.get("MUSIC_A"));
        } else if (!anonymousToken.isEmpty()) {
            header.put("MUSIC_A", anonymousToken);
        }
        // 服务端下发的合法 NMTID：捕获后 eapi 请求携带（未捕获时保持探测，不带）
        if (!cachedNmtid.isEmpty()) {
            header.put("NMTID", cachedNmtid);
        }
        return header;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Cookie 串：键值都做 URL 编码（SPlayer 的 cookieObjToString 语义）。 */
    private static String urlEncodeCookie(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            if (!result.isEmpty()) result.append("; ");
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String generateDeviceId() {
        byte[] bytes = new byte[26];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
    }

    /** 随机十六进制串。 */
    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** 随机十进制数字串（xeapi nonce）。 */
    private static String randomDigits(int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    /** WNMCID 形态：6 位小写字母 + 时间戳 + .01.0（与 NeteaseCloudMusicApi 一致）。 */
    private static String randomWnmcid() {
        StringBuilder prefix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            prefix.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return prefix + "." + System.currentTimeMillis() + ".01.0";
    }

    private JsonNode postForm(String url, String body, boolean weapi, boolean eapi) throws IOException {
        HttpResponse<String> response = postFormResponse(url, body, weapi, eapi);
        String raw = eapi ? decryptEapi(response.body()) : response.body();
        return objectMapper.readTree(raw);
    }

    private HttpResponse<String> postFormResponse(String url, String body, boolean weapi, boolean eapi) throws IOException {
        return postFormResponse(url, body,
                weapi ? weapiUserAgent() : userAgent(), cookieHeader(eapi), MUSIC_BASE);
    }

    /** 5 参重载，供需要自定义 UA/Cookie/Referer（含不带 Referer）的登录请求使用。 */
    private HttpResponse<String> postFormResponse(String url, String body, String userAgent, String cookie) throws IOException {
        return postFormResponse(url, body, userAgent, cookie, MUSIC_BASE);
    }

    private HttpResponse<String> postFormResponse(String url, String body, String userAgent,
                                                  String cookie, String referer) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getNeteaseHttpTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", userAgent)
                .header("Cookie", cookie);
        if (referer != null) {
            builder.header("Referer", referer);
        }
        HttpRequest httpRequest =
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpTransport.requireAllowedTarget(httpRequest, ALLOWED_HOSTS);
        HttpResponse<String> response = HttpTransport.sendString(httpClient, httpRequest, "请求被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) throw new IOException("Netease API HTTP " + response.statusCode());
        return response;
    }

    /** xeapi 请求：响应体是二进制（AES-ECB 密文），需按字节读取。 */
    private HttpResponse<byte[]> postBytes(String url, String body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getNeteaseHttpTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8");
        headers.forEach(builder::header);
        HttpRequest httpRequest =
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpTransport.requireAllowedTarget(httpRequest, ALLOWED_HOSTS);
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
    }

    private String cookieHeader(boolean eapi) {
        Map<String, String> cookies = parseCookie();
        if (!eapi) {
            if (cookies.isEmpty()) return "__remember_me=true; NMTID=guest";
            cookies.put("__remember_me", "true"); return stringifyCookie(cookies);
        }
        Map<String, String> header = new LinkedHashMap<>();
        header.put("osver", "17,1,2"); header.put("appver", "8.10.05"); header.put("versioncode", "140");
        header.put("buildver", String.valueOf(System.currentTimeMillis() / 1000)); header.put("resolution", "1920x1080");
        header.put("__csrf", cookies.getOrDefault("__csrf", "")); header.put("os", "android");
        if (cookies.containsKey("MUSIC_U")) header.put("MUSIC_U", cookies.get("MUSIC_U"));
        if (cookies.containsKey("MUSIC_A")) header.put("MUSIC_A", cookies.get("MUSIC_A"));
        return stringifyCookie(header);
    }

    private Map<String, String> parseCookie() {
        Map<String, String> result = new LinkedHashMap<>();
        String cookie = config.getNeteaseCookie(); if (cookie == null) return result;
        for (String pair : cookie.split(";")) { int eq = pair.indexOf('='); if (eq > 0) result.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()); }
        return result;
    }

    private static String stringifyCookie(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            if (!result.isEmpty()) result.append("; "); result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    /** 解析响应 Set-Cookie 为 name→value（丢弃 Path/Expires 等属性）。 */
    private static Map<String, String> readSetCookies(HttpResponse<?> response) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            int eq = header.indexOf('=');
            if (eq <= 0) continue;
            String name = header.substring(0, eq).trim();
            if (name.isEmpty() || SET_COOKIE_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) continue;
            String value = header.substring(eq + 1);
            int semi = value.indexOf(';');
            if (semi >= 0) value = value.substring(0, semi);
            value = value.trim();
            if (!value.isEmpty()) cookies.put(name, value);
        }
        return cookies;
    }

    /** 从响应 Set-Cookie 里拼出登录 Cookie，必须含 MUSIC_U，否则返回空串。 */
    private static String extractLoginCookie(HttpResponse<String> response) {
        Map<String, String> cookies = readSetCookies(response);
        if (!cookies.containsKey("MUSIC_U")) return "";
        return stringifyCookie(cookies);
    }

    /** 仅取 Set-Cookie 的 Cookie 名（不含值），供日志排查。 */
    private static String cookieNames(HttpResponse<String> response) {
        List<String> names = new ArrayList<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            int eq = header.indexOf('=');
            names.add(eq > 0 ? header.substring(0, eq).trim() : header);
        }
        return names.toString();
    }

    private static String encodeForm(Map<String, ?> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String randomSecretKey() {
        StringBuilder key = new StringBuilder(16); for (int i = 0; i < 16; i++) key.append(BASE62.charAt(RANDOM.nextInt(BASE62.length()))); return key.toString();
    }

    private static String aesCbcBase64(String text, String key, String iv) throws IOException { return encryptAes(text.getBytes(StandardCharsets.UTF_8), key, "CBC", iv, true); }
    private static String aesEcbHex(String text, String key) throws IOException { return encryptAes(text.getBytes(StandardCharsets.UTF_8), key, "ECB", "", false).toUpperCase(Locale.ROOT); }

    private static String encryptAes(byte[] plain, String key, String mode, String iv, boolean base64) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/" + mode + "/PKCS5Padding");
            SecretKeySpec secret = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            if ("CBC".equals(mode)) cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            else cipher.init(Cipher.ENCRYPT_MODE, secret);
            byte[] encrypted = cipher.doFinal(plain); return base64 ? Base64.getEncoder().encodeToString(encrypted) : HexFormat.of().formatHex(encrypted);
        } catch (Exception e) { throw new IOException("网易云 AES 加密失败", e); }
    }

    private static String rsaNoPaddingHex(String text) throws IOException {
        try { Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, WEAPI_PUBLIC_KEY); return HexFormat.of().formatHex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IOException("网易云 RSA 加密失败", e); }
    }

    private static String decryptEapi(String raw) throws IOException {
        try {
            String value = raw == null ? "" : raw.trim(); if (value.startsWith("{")) return value;
            byte[] encrypted = HEX.matcher(value).matches() && value.length() % 2 == 0 ? HexFormat.of().parseHex(value) : value.getBytes(StandardCharsets.ISO_8859_1);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IOException("网易云 eapi 响应解密失败", e); }
    }

    private static byte[] md5Bytes(byte[] data) throws IOException {
        try { return java.security.MessageDigest.getInstance("MD5").digest(data); }
        catch (Exception e) { throw new IOException("网易云 MD5 计算失败", e); }
    }

    private static String md5Hex(String text) throws IOException {
        return HexFormat.of().formatHex(md5Bytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static PublicKey loadPublicKey() {
        try {
            String body = PUBLIC_KEY_PEM.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static List<String> qualityFallbackChain(String preferred) {
        String p = preferred == null ? "" : preferred.trim().toLowerCase(Locale.ROOT); List<String> result = new ArrayList<>(); if (!p.isEmpty()) result.add(p);
        for (String level : List.of("jymaster", "hires", "jyeffect", "sky", "lossless", "exhigh", "higher", "standard")) if (!result.contains(level)) result.add(level);
        return result;
    }

    private static String joinArtists(JsonNode artists) {
        if (!artists.isArray()) return ""; StringBuilder result = new StringBuilder();
        for (JsonNode artist : artists) { String name = textOrEmpty(artist.path("name")); if (name.isEmpty()) continue; if (!result.isEmpty()) result.append(" / "); result.append(name); }
        return result.toString();
    }

    private static String textOrEmpty(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText("").trim(); }
    private static String userAgent() { return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"; }
    private static String weapiUserAgent() { return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/116 Safari/537.36"; }
}
