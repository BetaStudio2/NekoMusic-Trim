package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.User;
import com.neko.music.util.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NekoAuth 统一登录（OAuth 2.0 授权码 + 机密客户端）。
 *
 * <p>授权码只在服务端用 HTTP Basic 交换，客户端密钥不会进入浏览器；浏览器只拿到最终的一次性
 * handoff，再用它领取 NekoAuth 签发的用户 Token。state 存 Redis、只消费一次，并把登录前的相对路径绑定在里面，回调时不再接受
 * 客户端传入的跳转地址，避免开放重定向。</p>
 *
 * <p><b>昵称边界：</b>NekoAuth 是昵称唯一权威源。本服务读取 UserInfo 的
 * {@code sub}/{@code email} 匹配本地资料；{@code name} 只用于本次响应，不写入本地数据库。</p>
 */
public final class NekoAuthOAuthService {

    private static final Logger logger = LoggerFactory.getLogger(NekoAuthOAuthService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String STATE_PREFIX = "oauth:nekocore:state:";
    private static final String HANDOFF_PREFIX = "oauth:nekocore:handoff:";
    private static final int STATE_TTL_SECONDS = 600;
    private static final int HANDOFF_TTL_SECONDS = 120;
    private static final int MAX_RETURN_TO_LENGTH = 512;
    private static final int MAX_STATE_LENGTH = 256;

    private final ConfigManager configManager;
    private final RedisService redisService;
    private final UserAuthService userAuthService;
    private final NekoAuthAccountService accountService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpTransport.createWithoutRedirects(HTTP_TIMEOUT);

    public NekoAuthOAuthService(ConfigManager configManager, RedisService redisService,
                                UserAuthService userAuthService,
                                NekoAuthAccountService accountService,
                                ObjectMapper objectMapper) {
        this.configManager = configManager;
        this.redisService = redisService;
        this.userAuthService = userAuthService;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成 NekoAuth 授权地址，并把相对返回路径写入一次性 state。
     *
     * @param returnTo 登录成功后的站内相对路径（必须以单个 / 开头）
     */
    public String authorizationUrl(String returnTo) throws OAuthLoginException {
        requireEnabled();
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        validateRedirectUri(redirectUri);
        URI issuer = issuerUri();
        String safeReturnTo = normalizeReturnTo(returnTo);

        String state = randomToken(32);
        String stateValue;
        try {
            stateValue = objectMapper.writeValueAsString(new OAuthState(safeReturnTo, System.currentTimeMillis()));
        } catch (IOException exception) {
            throw new OAuthLoginException("oauth_unavailable", "无法创建登录会话", exception);
        }
        // 随机 state 碰撞概率可忽略；返回 false 说明 Redis 不可用或极端碰撞，直接拒绝而不是生成不可回调的链接。
        if (!redisService.setIfAbsentWithExpiry(STATE_PREFIX + state, stateValue, STATE_TTL_SECONDS)) {
            throw new OAuthLoginException("oauth_unavailable", "登录会话暂时不可用");
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("response_type", "code");
        query.put("scope", configManager.getOAuthScopes());
        query.put("state", state);
        return issuer.toString() + "/oauth2/authorize?" + encodeForm(query);
    }

    /** NekoAuth 找回密码页地址；不依赖 OAuth 客户端配置，避免入口被登录开关锁死。 */
    public String passwordResetUrl() throws OAuthLoginException {
        return issuerUri().toString() + "/forgot-password";
    }

    /**
     * 消费 state，用授权码换取 UserInfo，再把短期令牌换成 NekoAuth 长期 token 并封装成一次性 handoff。
     */
    public LoginResult complete(String code, String state) throws OAuthLoginException {
        requireEnabled();
        if (code == null || code.isBlank()) {
            throw new OAuthLoginException("authorization_failed", "缺少授权码");
        }
        if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
            throw new OAuthLoginException("state_invalid", "登录会话无效或已过期");
        }
        String stateJson = redisService.getDel(STATE_PREFIX + state);
        if (stateJson == null || stateJson.isBlank()) {
            throw new OAuthLoginException("state_invalid", "登录会话无效或已过期");
        }
        OAuthState stored;
        try {
            stored = objectMapper.readValue(stateJson, OAuthState.class);
        } catch (IOException exception) {
            throw new OAuthLoginException("state_invalid", "登录会话无效或已过期", exception);
        }
        long ageMs = System.currentTimeMillis() - stored.issuedAt();
        if (ageMs < 0 || ageMs > STATE_TTL_SECONDS * 1000L) {
            throw new OAuthLoginException("state_invalid", "登录会话无效或已过期");
        }

        String accessToken = exchangeAuthorizationCode(code);
        JsonNode userInfo = fetchUserInfo(accessToken);
        return createLocalSession(accessToken, userInfo, stored.returnTo());
    }

    /**
     * 浏览器用一次性 handoff 领取 Auth token。
     *
     * <p>先读取并校验 Auth token / 本地用户，确认可登录后才用 Redis Lua 原子比对删除。这样网络抖动
     * 或并发领取失败时不会提前烧掉交接；两个并发请求也只有一个能删除成功。</p>
     */
    public ClaimResult claimHandoff(String handoff) throws OAuthLoginException {
        if (handoff == null || handoff.isBlank() || handoff.length() > MAX_STATE_LENGTH) {
            throw new OAuthLoginException("handoff_invalid", "登录交接已过期，请重新登录");
        }
        String key = HANDOFF_PREFIX + handoff;
        String json = redisService.get(key);
        if (json == null || json.isBlank()) {
            throw new OAuthLoginException("handoff_invalid", "登录交接已过期，请重新登录");
        }
        try {
            Handoff stored = objectMapper.readValue(json, Handoff.class);
            long ageMs = System.currentTimeMillis() - stored.issuedAt();
            if (stored.accessToken() == null || stored.accessToken().isBlank()
                    || ageMs < 0 || ageMs > HANDOFF_TTL_SECONDS * 1000L) {
                throw new OAuthLoginException("handoff_invalid", "登录交接已过期，请重新登录");
            }
            Integer userId = userAuthService.validateToken(stored.accessToken()).orElse(null);
            User user = userId == null ? null : userAuthService.findUserById(userId).orElse(null);
            if (user == null) {
                throw new OAuthLoginException("handoff_not_ready", "登录交接暂时无法完成，请重新登录");
            }
            if (!redisService.getAndDeleteIfEquals(key, json)) {
                throw new OAuthLoginException("handoff_invalid", "登录交接已被使用，请重新登录");
            }
            return new ClaimResult(
                    stored.accessToken(), normalizeReturnTo(stored.returnTo()), user);
        } catch (IOException | OAuthLoginException exception) {
            if (exception instanceof OAuthLoginException oauthException) {
                throw oauthException;
            }
            throw new OAuthLoginException("handoff_invalid", "登录交接已过期，请重新登录", exception);
        } catch (RuntimeException exception) {
            throw new OAuthLoginException(
                    "handoff_unavailable", "登录交接暂时无法完成，请重新登录", exception);
        }
    }

    private String exchangeAuthorizationCode(String code) throws OAuthLoginException {
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        String clientSecret = requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        validateRedirectUri(redirectUri);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + "/oauth2/token"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "授权码交换请求被中断");
        JsonNode json = parseJson(response.body());
        if (!HttpTransport.isSuccess(response.statusCode())) {
            logger.warn("NekoAuth 授权码交换失败: status={} error={} description={}",
                    response.statusCode(), textOrEmpty(json, "error"), truncate(textOrEmpty(json, "error_description")));
            throw new OAuthLoginException("token_exchange_failed", "授权码交换失败");
        }
        String accessToken = textOrEmpty(json, "access_token");
        if (accessToken.isBlank()) {
            throw new OAuthLoginException("token_exchange_failed", "NekoAuth 未返回访问令牌");
        }
        return accessToken;
    }

    private JsonNode fetchUserInfo(String accessToken) throws OAuthLoginException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + "/oauth2/userinfo"))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request, "UserInfo 请求被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) {
            logger.warn("NekoAuth UserInfo 获取失败: status={}", response.statusCode());
            throw new OAuthLoginException("userinfo_failed", "获取 NekoAuth 用户信息失败");
        }
        JsonNode json = parseJson(response.body());
        if (json == null || !json.isObject()) {
            throw new OAuthLoginException("userinfo_failed", "NekoAuth 返回的用户信息格式不正确");
        }
        return json;
    }

    private LoginResult createLocalSession(String shortLivedToken, JsonNode userInfo, String returnTo)
            throws OAuthLoginException {
        OAuthIdentity identity = parseOAuthIdentity(userInfo);
        long authUserId = identity.authUserId();

        User localUser;
        try {
            localUser = userAuthService.findOrLinkNekoAuthUser(
                    authUserId, identity.email(), identity.nickname());
        } catch (RuntimeException exception) {
            logger.warn("关联 NekoAuth 账号与本地资料失败: authUserId={}", authUserId, exception);
            throw new OAuthLoginException("account_link_failed", "关联 NekoMusic 账户失败", exception);
        }

        String token;
        try {
            token = accountService.exchangeToken(shortLivedToken).accessToken();
        } catch (NekoAuthAccountService.AccountBridgeException exception) {
            logger.warn("NekoAuth 长期令牌交换失败: status={} code={}",
                    exception.status(), exception.code());
            throw new OAuthLoginException("token_issue_failed", "登录令牌签发失败", exception);
        }
        if (token == null || token.isBlank()) {
            throw new OAuthLoginException("token_issue_failed", "登录令牌签发失败");
        }
        String handoff = randomToken(32);
        try {
            String value = objectMapper.writeValueAsString(
                    new Handoff(token, returnTo, System.currentTimeMillis()));
            if (!redisService.setIfAbsentWithExpiry(
                    HANDOFF_PREFIX + handoff, value, HANDOFF_TTL_SECONDS)) {
                throw new OAuthLoginException("oauth_unavailable", "登录交接暂时不可用");
            }
        } catch (IOException exception) {
            throw new OAuthLoginException("oauth_unavailable", "登录交接暂时不可用", exception);
        }
        logger.info("NekoAuth 登录成功: authUserId={} localUserId={}", authUserId, localUser.id());
        return new LoginResult(handoff, returnTo);
    }

    static OAuthIdentity parseOAuthIdentity(JsonNode userInfo) throws OAuthLoginException {
        String subject = textOrEmpty(userInfo, "sub");
        long authUserId;
        try {
            authUserId = Long.parseLong(subject);
            if (authUserId <= 0) {
                throw new NumberFormatException("out of range");
            }
        } catch (NumberFormatException exception) {
            throw new OAuthLoginException("invalid_subject", "NekoAuth 用户标识无效");
        }

        // email_verified 属于 NekoAuth 的账号状态，不作为 NekoMusic 登录门槛。
        String email = textOrEmpty(userInfo, "email");
        if (email.isBlank()) {
            throw new OAuthLoginException("email_missing", "NekoAuth 未返回邮箱");
        }
        String nickname = textOrEmpty(userInfo, "name");
        if (nickname.isBlank()) {
            nickname = textOrEmpty(userInfo, "preferred_username");
        }
        return new OAuthIdentity(authUserId, email, nickname);
    }

    private HttpResponse<String> send(HttpRequest request, String interruptMessage) throws OAuthLoginException {
        try {
            return HttpTransport.sendString(httpClient, request, interruptMessage);
        } catch (IOException exception) {
            logger.warn("NekoAuth 请求失败: {} {}", request.method(), request.uri().getPath());
            throw new OAuthLoginException("oauth_unavailable", "暂时无法连接 NekoAuth", exception);
        }
    }

    private JsonNode parseJson(String body) throws OAuthLoginException {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new OAuthLoginException("userinfo_failed", "NekoAuth 返回内容不是有效 JSON", exception);
        }
    }

    private void requireEnabled() throws OAuthLoginException {
        if (!configManager.isOAuthEnabled()) {
            throw new OAuthLoginException("oauth_disabled", "NekoAuth 登录未启用");
        }
    }

    private static String requireConfigured(String value, String key) throws OAuthLoginException {
        if (value == null || value.isBlank()) {
            throw new OAuthLoginException("oauth_not_configured", "NekoAuth 登录缺少配置：" + key);
        }
        return value.trim();
    }

    private URI issuerUri() throws OAuthLoginException {
        String value = requireConfigured(configManager.getOAuthIssuer(), "oauth.issuer");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new OAuthLoginException("oauth_not_configured", "NekoAuth 根地址格式不正确", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new OAuthLoginException("oauth_not_configured", "NekoAuth 根地址必须是 http(s) URL");
        }
        if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
            throw new OAuthLoginException("oauth_not_configured", "非本机 NekoAuth 根地址必须使用 HTTPS");
        }
        if (uri.getRawQuery() != null || uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw new OAuthLoginException("oauth_not_configured", "NekoAuth 根地址不能包含查询、片段或用户信息");
        }
        return URI.create(value);
    }

    private static void validateRedirectUri(String value) throws OAuthLoginException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new OAuthLoginException("oauth_not_configured", "回调地址格式不正确", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new OAuthLoginException("oauth_not_configured", "回调地址必须是 http(s) URL");
        }
        if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
            throw new OAuthLoginException("oauth_not_configured", "非本机回调地址必须使用 HTTPS");
        }
        if (uri.getRawQuery() != null || uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw new OAuthLoginException("oauth_not_configured", "回调地址不能包含查询、片段或用户信息");
        }
    }

    /** 只接受站内相对路径，拒绝协议、协议相对地址、反斜杠与控制字符。 */
    static String normalizeReturnTo(String returnTo) throws OAuthLoginException {
        String value = returnTo == null || returnTo.isBlank() ? "/" : returnTo.trim();
        if (value.length() > MAX_RETURN_TO_LENGTH || !value.startsWith("/") || value.startsWith("//")
                || value.indexOf('\\') >= 0) {
            throw new OAuthLoginException("invalid_return_to", "登录后的返回地址无效");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new OAuthLoginException("invalid_return_to", "登录后的返回地址无效");
            }
        }
        try {
            URI relative = URI.create(value);
            if (relative.isAbsolute() || relative.getRawAuthority() != null) {
                throw new OAuthLoginException("invalid_return_to", "登录后的返回地址无效");
            }
        } catch (IllegalArgumentException exception) {
            throw new OAuthLoginException("invalid_return_to", "登录后的返回地址无效", exception);
        }
        return value;
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encodeForm(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private record OAuthState(String returnTo, long issuedAt) {
    }

    record OAuthIdentity(long authUserId, String email, String nickname) {
    }

    private record Handoff(String accessToken, String returnTo, long issuedAt) {
    }

    public record LoginResult(String handoff, String returnTo) {
    }

    public record ClaimResult(String accessToken, String returnTo, User user) {
    }

    public static final class OAuthLoginException extends Exception {
        private final String code;

        public OAuthLoginException(String code, String message) {
            super(message);
            this.code = code;
        }

        public OAuthLoginException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
