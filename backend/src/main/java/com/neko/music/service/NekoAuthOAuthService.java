package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.User;
import com.neko.music.util.HttpTransport;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NekoAuth 原生客户端登录（OpenID Connect 授权码 + 机密客户端 + PKCE S256）。
 *
 * <p>端点来自 NekoAuth 的 OIDC 发现文档。授权码只在服务端用 HTTP Basic 交换，客户端密钥不会进入
 * 浏览器；ID Token 通过 JWKS 验签并校验 issuer、audience、azp、nonce 与 UserInfo 的 subject。
 * 浏览器只拿到最终的一次性 handoff，再用它领取 NekoAuth 签发的用户 Token。state 存 Redis、只消费一次，
 * 并把登录前的相对路径绑定在里面，回调时不再接受客户端传入的跳转地址，避免开放重定向。</p>
 *
 * <p>Web 不使用本服务，而是通过 NekoAuth 父域共享会话恢复登录态。</p>
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
    private static final long DISCOVERY_TTL_MS = Duration.ofHours(1).toMillis();
    private static final long JWKS_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final Set<String> SUPPORTED_CLIENTS = Set.of("web", "android", "pc");

    private final ConfigManager configManager;
    private final RedisService redisService;
    private final UserAuthService userAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpTransport.createWithoutRedirects(HTTP_TIMEOUT);
    private volatile OidcDiscovery discoveryCache;
    private volatile JwksCache jwksCache;

    public NekoAuthOAuthService(ConfigManager configManager, RedisService redisService,
                                UserAuthService userAuthService,
                                ObjectMapper objectMapper) {
        this.configManager = configManager;
        this.redisService = redisService;
        this.userAuthService = userAuthService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成 NekoAuth 授权地址，并把相对返回路径写入一次性 state。
     *
     * @param returnTo 登录成功后的站内相对路径（必须以单个 / 开头）
     */
    public String authorizationUrl(String returnTo) throws OAuthLoginException {
        return authorizationUrl(returnTo, "web", false);
    }

    public String authorizationUrl(String returnTo, String client) throws OAuthLoginException {
        return authorizationUrl(returnTo, client, false);
    }

    /**
     * @param silent 为 true 时使用 {@code prompt=none}，仅复用 NekoAuth 已有浏览器会话
     */
    public String authorizationUrl(String returnTo, String client, boolean silent)
            throws OAuthLoginException {
        requireEnabled();
        OidcDiscovery discovery = discovery();
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        String scopes = requiredScopes();
        validateRedirectUri(redirectUri);
        String safeReturnTo = normalizeReturnTo(returnTo);
        String safeClient = normalizeOidcClient(client);

        String state = randomToken(32);
        String codeVerifier = randomToken(48);
        String nonce = randomToken(32);
        String stateValue;
        try {
            stateValue = objectMapper.writeValueAsString(new OAuthState(
                    safeReturnTo, System.currentTimeMillis(), codeVerifier, nonce, safeClient, silent));
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
        query.put("scope", scopes);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", pkceChallenge(codeVerifier));
        query.put("code_challenge_method", "S256");
        if (silent) {
            query.put("prompt", "none");
        }
        return discovery.authorizationEndpoint() + "?" + encodeForm(query);
    }

    /**
     * 消费静默登录的 state。NekoAuth 无法复用现有会话时会回调
     * {@code login_required} / {@code consent_required}；此时安静返回原页面，不展示登录失败页。
     */
    public Optional<String> silentReturnTo(String state) {
        if (state == null || state.isBlank() || state.length() > MAX_STATE_LENGTH) {
            return Optional.empty();
        }
        String stateJson = redisService.getDel(STATE_PREFIX + state);
        if (stateJson == null || stateJson.isBlank()) {
            return Optional.empty();
        }
        try {
            OAuthState stored = objectMapper.readValue(stateJson, OAuthState.class);
            long ageMs = System.currentTimeMillis() - stored.issuedAt();
            if (!stored.silent() || ageMs < 0 || ageMs > STATE_TTL_SECONDS * 1000L) {
                return Optional.empty();
            }
            return Optional.of(normalizeReturnTo(stored.returnTo()));
        } catch (IOException | OAuthLoginException exception) {
            return Optional.empty();
        }
    }

    /** NekoAuth 找回密码页地址；不依赖 OAuth 客户端配置，避免入口被登录开关锁死。 */
    public String passwordResetUrl() throws OAuthLoginException {
        return issuerUri().toString() + "/forgot-password";
    }

    /** NekoAuth 账号中心 / 注册页地址；不依赖浏览器 OAuth 开关。 */
    public String portalUrl(String target) throws OAuthLoginException {
        String path = switch (target == null ? "" : target.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "account" -> "/account";
            case "register" -> "/register";
            case "forgot-password" -> "/forgot-password";
            default -> throw new OAuthLoginException("invalid_portal_target", "不支持的账号页面");
        };
        return issuerUri().toString() + path;
    }

    /** Auth 普通登录页地址；登录完成后直接回跳第一方子域，不进入 OIDC 授权码流程。 */
    public String webLoginEntryUrl(String returnTo) throws OAuthLoginException {
        requireEnabled();
        String safeReturnTo = normalizeReturnTo(returnTo);
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        validateRedirectUri(redirectUri);
        String absoluteReturnTo = originOf(redirectUri) + safeReturnTo.substring(1);
        return issuerUri() + "/login?return_to="
                + URLEncoder.encode(absoluteReturnTo, StandardCharsets.UTF_8);
    }

    /** 标准 OIDC RP-Initiated Logout 地址。 */
    public String logoutUrl(String idToken) throws OAuthLoginException {
        requireEnabled();
        if (idToken == null || idToken.isBlank() || idToken.length() > 8192) {
            throw new OAuthLoginException("id_token_missing", "缺少登录凭据，无法同步退出 NekoAuth");
        }
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        validateRedirectUri(redirectUri);
        String postLogoutRedirect = URI.create(redirectUri).resolve("/").toString();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("id_token_hint", idToken);
        query.put("client_id", clientId);
        query.put("post_logout_redirect_uri", postLogoutRedirect);
        return issuerUri() + "/oauth2/logout?" + encodeForm(query);
    }

    /**
     * 消费 state，用授权码换取 UserInfo，再把短期令牌换成 NekoAuth 长期 token 并封装成一次性 handoff。
     */
    public LoginResult complete(String code, String state, String responseIssuer) throws OAuthLoginException {
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
        if (responseIssuer != null && !responseIssuer.isBlank()
                && !trimTrailingSlash(responseIssuer).equals(issuerUri().toString())) {
            throw new OAuthLoginException("state_invalid", "登录会话 issuer 不匹配");
        }

        if (stored.codeVerifier() == null || stored.codeVerifier().isBlank()
                || stored.nonce() == null || stored.nonce().isBlank()) {
            throw new OAuthLoginException("state_invalid", "登录会话无效或已过期");
        }

        OidcDiscovery discovery = discovery();
        TokenResponse tokens = exchangeAuthorizationCode(
                code, stored.codeVerifier(), discovery.tokenEndpoint());
        JsonNode userInfo = fetchUserInfo(discovery.userInfoEndpoint(), tokens.accessToken());
        validateIdToken(discovery, tokens.idToken(), tokens.accessToken(),
                stored.nonce(), textOrEmpty(userInfo, "sub"));
        return createLocalSession(tokens.accessToken(), userInfo, stored.returnTo(),
                normalizeOidcClient(stored.client()), tokens.idToken());
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
                    stored.accessToken(),
                    stored.idToken() == null ? "" : stored.idToken(),
                    normalizeReturnTo(stored.returnTo()),
                    user);
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

    private TokenResponse exchangeAuthorizationCode(String code, String codeVerifier,
                                                    URI tokenEndpoint) throws OAuthLoginException {
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        String clientSecret = requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        String redirectUri = requireConfigured(configManager.getOAuthRedirectUri(), "oauth.redirect_uri");
        validateRedirectUri(redirectUri);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(tokenEndpoint)
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
        String idToken = textOrEmpty(json, "id_token");
        if (idToken.isBlank()) {
            throw new OAuthLoginException("token_exchange_failed", "NekoAuth 未返回 ID Token");
        }
        return new TokenResponse(accessToken, idToken);
    }

    private JsonNode fetchUserInfo(URI userInfoEndpoint, String accessToken) throws OAuthLoginException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(userInfoEndpoint)
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

    private LoginResult createLocalSession(String shortLivedToken, JsonNode userInfo, String returnTo,
                                           String client, String idToken)
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

        String token = shortLivedToken;
        if (token == null || token.isBlank()) {
            throw new OAuthLoginException("token_issue_failed", "登录令牌签发失败");
        }
        String handoff = randomToken(32);
        try {
            String value = objectMapper.writeValueAsString(
                    new Handoff(token, returnTo, idToken, System.currentTimeMillis()));
            if (!redisService.setIfAbsentWithExpiry(
                    HANDOFF_PREFIX + handoff, value, HANDOFF_TTL_SECONDS)) {
                throw new OAuthLoginException("oauth_unavailable", "登录交接暂时不可用");
            }
        } catch (IOException exception) {
            throw new OAuthLoginException("oauth_unavailable", "登录交接暂时不可用", exception);
        }
        logger.info("NekoAuth 登录成功: authUserId={} localUserId={}", authUserId, localUser.id());
        return new LoginResult(handoff, returnTo, client);
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

    private OidcDiscovery discovery() throws OAuthLoginException {
        long now = System.currentTimeMillis();
        URI issuer = issuerUri();
        OidcDiscovery cached = discoveryCache;
        if (cached != null && cached.issuer().equals(issuer.toString())
                && now - cached.fetchedAt() < DISCOVERY_TTL_MS) {
            return cached;
        }
        URI discoveryUri = URI.create(issuer + "/.well-known/openid-configuration");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(discoveryUri)
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request, "OIDC 发现文档请求被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) {
            logger.warn("NekoAuth OIDC 发现文档获取失败: status={}", response.statusCode());
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 登录服务暂时不可用");
        }
        JsonNode json = parseJson(response.body());
        if (json == null || !json.isObject()) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档格式不正确");
        }
        String declaredIssuer = trimTrailingSlash(textOrEmpty(json, "issuer"));
        if (!issuer.toString().equals(declaredIssuer)) {
            logger.warn("NekoAuth 发现文档 issuer 不匹配: configured={} declared={}",
                    issuer, declaredIssuer);
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档 issuer 不匹配");
        }
        OidcDiscovery loaded = new OidcDiscovery(
                declaredIssuer,
                parseEndpoint(json, "authorization_endpoint", issuer),
                parseEndpoint(json, "token_endpoint", issuer),
                parseEndpoint(json, "userinfo_endpoint", issuer),
                parseEndpoint(json, "jwks_uri", issuer),
                now);
        discoveryCache = loaded;
        return loaded;
    }

    private void validateIdToken(OidcDiscovery discovery, String idToken, String accessToken,
                                 String expectedNonce, String expectedSubject)
            throws OAuthLoginException {
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        Map<String, PublicKey> keys = jwks(discovery.jwksUri(), false);
        PublicKey key = signingKey(idToken, keys);
        if (key == null) {
            keys = jwks(discovery.jwksUri(), true);
            key = signingKey(idToken, keys);
        }
        if (key == null) {
            throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 签名公钥不存在");
        }
        try {
            PublicKey verifiedKey = key;
            Claims claims = Jwts.parser()
                    .keyLocator(header -> verifiedKey)
                    .requireIssuer(discovery.issuer())
                    .requireAudience(clientId)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
            String nonce = claims.get("nonce", String.class);
            if (nonce == null || !constantTimeEquals(expectedNonce, nonce)) {
                throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token nonce 不匹配");
            }
            String atHash = claims.get("at_hash", String.class);
            if (atHash == null || !constantTimeEquals(atHash, leftHalfHash(accessToken))) {
                throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token at_hash 不匹配");
            }
            String subject = claims.getSubject();
            if (expectedSubject == null || expectedSubject.isBlank()
                    || !constantTimeEquals(expectedSubject, subject)) {
                throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 用户标识不匹配");
            }
            String authorizedParty = claims.get("azp", String.class);
            if (authorizedParty != null && !authorizedParty.equals(clientId)) {
                throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 授权方不匹配");
            }
        } catch (JwtException | IllegalArgumentException exception) {
            logger.warn("NekoAuth ID Token 校验失败: {}", exception.getMessage());
            throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 校验失败", exception);
        }
    }

    private Map<String, PublicKey> jwks(URI jwksUri, boolean forceRefresh) throws OAuthLoginException {
        long now = System.currentTimeMillis();
        JwksCache cached = jwksCache;
        if (!forceRefresh && cached != null && cached.uri().equals(jwksUri)
                && now - cached.fetchedAt() < JWKS_TTL_MS) {
            return cached.keys();
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(jwksUri)
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = send(request, "OIDC JWKS 请求被中断");
        if (!HttpTransport.isSuccess(response.statusCode())) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 签名公钥暂时不可用");
        }
        JsonNode json = parseJson(response.body());
        JsonNode keysNode = json == null ? null : json.get("keys");
        if (keysNode == null || !keysNode.isArray()) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth JWKS 格式不正确");
        }
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (JsonNode keyNode : keysNode) {
            if (!"RSA".equals(textOrEmpty(keyNode, "kty"))) {
                continue;
            }
            String alg = textOrEmpty(keyNode, "alg");
            if (!alg.isBlank() && !"RS256".equals(alg)) {
                continue;
            }
            String kid = textOrEmpty(keyNode, "kid");
            String modulus = textOrEmpty(keyNode, "n");
            String exponent = textOrEmpty(keyNode, "e");
            if (kid.isBlank() || modulus.isBlank() || exponent.isBlank()) {
                continue;
            }
            try {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(exponent));
                keys.put(kid, KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e)));
            } catch (Exception exception) {
                logger.warn("忽略无法解析的 NekoAuth JWK: kid={}", kid);
            }
        }
        if (keys.isEmpty()) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 未提供可用的 RS256 公钥");
        }
        Map<String, PublicKey> immutable = Map.copyOf(keys);
        jwksCache = new JwksCache(jwksUri, now, immutable);
        return immutable;
    }

    private PublicKey signingKey(String idToken, Map<String, PublicKey> keys)
            throws OAuthLoginException {
        String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
        if (parts.length != 3) {
            throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 格式不正确");
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(textOrEmpty(header, "alg"))) {
                throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 签名算法不受支持");
            }
            return keys.get(textOrEmpty(header, "kid"));
        } catch (IllegalArgumentException | IOException exception) {
            throw new OAuthLoginException("id_token_invalid", "NekoAuth ID Token 头部无效", exception);
        }
    }

    static URI parseEndpoint(JsonNode discovery, String field, URI issuer)
            throws OAuthLoginException {
        String raw = textOrEmpty(discovery, field);
        if (raw.isBlank()) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档缺少 " + field);
        }
        URI endpoint;
        try {
            endpoint = URI.create(raw);
        } catch (IllegalArgumentException exception) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档地址格式不正确", exception);
        }
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase();
        if (endpoint.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null
                || endpoint.getRawUserInfo() != null) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档包含不安全的端点");
        }
        if (scheme.equals("http") && !isLoopbackHost(endpoint.getHost())) {
            throw new OAuthLoginException("oauth_unavailable", "非本机 NekoAuth 端点必须使用 HTTPS");
        }
        if (!sameOrigin(issuer, endpoint)) {
            throw new OAuthLoginException("oauth_unavailable", "NekoAuth 发现文档端点与 issuer 不同源");
        }
        return endpoint;
    }

    private String requiredScopes() throws OAuthLoginException {
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : configManager.getOAuthScopes().trim().split("\\s+")) {
            if (!scope.isBlank()) {
                scopes.add(scope);
            }
        }
        if (!scopes.containsAll(Set.of("openid", "profile", "email"))) {
            throw new OAuthLoginException(
                    "oauth_not_configured", "oauth.scopes 必须包含 openid profile email");
        }
        return String.join(" ", scopes);
    }

    static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String leftHalfHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            byte[] half = new byte[16];
            System.arraycopy(digest, 0, half, 0, half.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(half);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
        String value = trimTrailingSlash(
                requireConfigured(configManager.getOAuthIssuer(), "oauth.issuer"));
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

    static String normalizeClient(String client) throws OAuthLoginException {
        if (client == null || client.isBlank()) {
            return "web";
        }
        String value = client.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_CLIENTS.contains(value)) {
            throw new OAuthLoginException("invalid_client_type", "不支持的客户端类型");
        }
        return value;
    }

    /** OIDC 只供原生客户端使用；Web 必须走 NekoAuth 父域共享会话。 */
    static String normalizeOidcClient(String client) throws OAuthLoginException {
        String normalized = normalizeClient(client);
        if ("web".equals(normalized)) {
            throw new OAuthLoginException(
                    "web_oidc_disabled", "Web 登录必须使用 NekoAuth 共享会话");
        }
        return normalized;
    }

    static String originOf(String value) throws OAuthLoginException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host missing");
            }
            if (host.contains(":")) {
                host = "[" + host + "]";
            }
            return uri.getScheme() + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + "/";
        } catch (IllegalArgumentException exception) {
            throw new OAuthLoginException("oauth_not_configured", "回调地址格式不正确", exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        String value = host == null ? "" : host;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return "localhost".equalsIgnoreCase(value) || "127.0.0.1".equals(value) || "::1".equals(value);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
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

    private record OAuthState(String returnTo, long issuedAt, String codeVerifier, String nonce,
                              String client, boolean silent) {
    }

    private record OidcDiscovery(String issuer, URI authorizationEndpoint, URI tokenEndpoint,
                                 URI userInfoEndpoint, URI jwksUri, long fetchedAt) {
    }

    private record JwksCache(URI uri, long fetchedAt, Map<String, PublicKey> keys) {
    }

    private record TokenResponse(String accessToken, String idToken) {
    }

    record OAuthIdentity(long authUserId, String email, String nickname) {
    }

    private record Handoff(String accessToken, String returnTo, String idToken, long issuedAt) {
    }

    public record LoginResult(String handoff, String returnTo, String client) {
    }

    public record ClaimResult(String accessToken, String idToken, String returnTo, User user) {
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
