package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import com.neko.music.util.HttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NekoAuth 服务端账号与令牌桥接客户端。
 *
 * <p>账号、密码、验证码、用户 token 的签发与吊销全部以 NekoAuth 为准。本类只复用
 * NekoMusic 现有的 {@code oauth.issuer}、{@code oauth.client_id}、
 * {@code oauth.client_secret} 设置，不写死客户端 ID。凭据只存在于服务端配置中。</p>
 */
public class NekoAuthAccountService {

    private static final Logger logger = LoggerFactory.getLogger(NekoAuthAccountService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final ConfigManager configManager;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpTransport.createWithoutRedirects(HTTP_TIMEOUT);

    public NekoAuthAccountService(ConfigManager configManager, ObjectMapper objectMapper) {
        this.configManager = configManager;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return !configManager.getOAuthIssuer().isBlank()
                && !configManager.getOAuthClientId().isBlank()
                && !configManager.getOAuthClientSecret().isBlank();
    }

    public Account login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return postAccount("/login", body, "登录", true);
    }

    /** 用父域共享 SSO 会话换取当前应用会话，不发生浏览器跳转。 */
    public Account loginWithSsoSession(String ssoSession) {
        return postAccount("/sso-session", Map.of("ssoSession", ssoSession), "共享会话登录", true);
    }

    /** 撤销 Auth 浏览器会话族并返回需要转发给浏览器的清 Cookie 头。 */
    public List<String> logoutSsoSession(String ssoSession) {
        HttpResponse<String> response = post(
                "/sso-session/logout", Map.of("ssoSession", ssoSession), "退出共享会话");
        return response.headers().allValues("Set-Cookie");
    }

    public void sendRegistrationCode(String email) {
        postNoContent("/register/code", Map.of("email", email), "发送注册验证码");
    }

    public Account register(String email, String password, String verificationCode, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", nickname);
        body.put("email", email);
        body.put("password", password);
        body.put("verificationCode", verificationCode);
        return postAccount("/register", body, "注册", true);
    }

    public Account adminCreate(String email, String password, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", nickname);
        body.put("email", email);
        body.put("password", password);
        return postAccount("/admin-create", body, "创建账号", false);
    }

    /** 昵称以 NekoAuth 为唯一权威源；本地不保存昵称。 */
    public void updateNickname(String email, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("nickname", nickname);
        postNoContent("/nickname", body, "修改昵称");
    }

    /** 将头像文件上传到 NekoAuth。 */
    public void uploadAvatar(long authUserId, Path avatarFile) {
        if (authUserId <= 0) {
            throw new AccountBridgeException(400, "INVALID_AUTH_USER_ID", "NekoAuth 用户 ID 无效", 0);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(avatarFile);
        } catch (IOException exception) {
            throw new AccountBridgeException(400, "AVATAR_READ_FAILED", "无法读取本地头像", 0);
        }
        if (bytes.length == 0) {
            throw new AccountBridgeException(400, "AVATAR_REQUIRED", "头像文件为空", 0);
        }
        String boundary = "----NekoMusicAvatar" + randomToken();
        String fileName = sanitizeMultipartName(avatarFile.getFileName().toString());
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 512);
        try {
            write(body, "--" + boundary + "\r\n");
            write(body, "Content-Disposition: form-data; name=\"authUserId\"\r\n\r\n");
            write(body, Long.toString(authUserId) + "\r\n");
            write(body, "--" + boundary + "\r\n");
            write(body, "Content-Disposition: form-data; name=\"avatar\"; filename=\"" + fileName + "\"\r\n");
            write(body, "Content-Type: " + avatarContentType(fileName) + "\r\n\r\n");
            body.write(bytes);
            write(body, "\r\n--" + boundary + "--\r\n");
        } catch (IOException exception) {
            throw new AccountBridgeException(500, "AVATAR_ENCODE_FAILED", "无法构造头像上传请求", 0);
        }
        HttpRequest request = requestBuilder("/api/internal/accounts/avatar")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        sendChecked(request, "上传头像", "/avatar");
    }

    /** 按 NekoAuth 用户 ID 批量读取当前昵称；NekoAuth 是唯一权威源。 */
    public Map<Long, String> lookupNicknames(Collection<Long> authUserIds) {
        if (authUserIds == null || authUserIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = authUserIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        HttpResponse<String> response = post("/lookup", Map.of("authUserIds", ids), "查询昵称");
        Map<Long, String> nicknames = new LinkedHashMap<>();
        for (AccountSummary account : parseSummaries(response, "查询昵称")) {
            nicknames.put(account.id(), account.nickname());
        }
        return nicknames;
    }

    /** 在 NekoAuth 中按昵称、用户名或邮箱搜索账号。 */
    public List<AccountSummary> searchAccounts(String query) {
        HttpResponse<String> response = post("/search", Map.of("query", query), "搜索账号");
        return parseSummaries(response, "搜索账号");
    }

    public void sendPasswordResetCode(String email) {
        postNoContent("/password/reset-code", Map.of("email", email), "发送找回密码验证码");
    }

    public void resetPassword(String email, String code, String newPassword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("code", code);
        body.put("newPassword", newPassword);
        postNoContent("/password/reset", body, "重置密码");
    }

    public void changePassword(String email, String currentPassword, String newPassword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        postNoContent("/password/change", body, "修改密码");
    }

    public void adminResetPassword(String email, String newPassword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("newPassword", newPassword);
        postNoContent("/password/admin-reset", body, "管理员重置密码");
    }

    /** 把浏览器 OAuth 回调得到的短期令牌换成 NekoAuth 所有的长期第一方令牌。 */
    public IssuedToken exchangeToken(String accessToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        HttpResponse<String> response = post("/token/exchange", body, "交换长期访问令牌");
        JsonNode json = parseJson(response.body());
        String token = text(json, "accessToken");
        long expiresIn = json == null || !json.path("expiresIn").canConvertToLong()
                ? 0 : json.path("expiresIn").asLong();
        if (token.isBlank() || expiresIn <= 0) {
            throw new AccountBridgeException(502, "NEKO_AUTH_BAD_RESPONSE",
                    "NekoAuth 返回的令牌信息格式不正确", 0);
        }
        return new IssuedToken(token, text(json, "tokenType"), expiresIn);
    }

    /** 校验用户令牌是否仍有效，并返回 Auth 权威身份。 */
    public Optional<Introspection> introspect(String accessToken) {
        HttpResponse<String> response = postForm("/oauth2/introspect",
                Map.of("token", accessToken), "校验访问令牌");
        JsonNode json = parseJson(response.body());
        if (json == null || !json.path("active").asBoolean(false)) {
            return Optional.empty();
        }
        long authUserId = subjectId(json.get("sub"));
        if (authUserId <= 0) {
            throw new AccountBridgeException(502, "NEKO_AUTH_BAD_RESPONSE",
                    "NekoAuth introspection 缺少有效用户标识", 0);
        }
        return Optional.of(new Introspection(
                authUserId,
                text(json, "email"),
                firstNonBlank(text(json, "name"), text(json, "username")),
                json.path("email_verified").asBoolean(false),
                text(json, "role"),
                text(json, "scope")));
    }

    /**
     * introspection 的 {@code sub} 在不同实现里可能被序列化为 JSON 数字或十进制字符串。
     * 只接受正整数，拒绝小数、布尔、对象以及带符号 / 空白的伪数字。
     */
    static long subjectId(JsonNode subject) {
        if (subject == null) {
            return 0L;
        }
        if (subject.isIntegralNumber()) {
            return subject.canConvertToLong() && subject.asLong() > 0 ? subject.asLong() : 0L;
        }
        if (!subject.isTextual()) {
            return 0L;
        }
        String value = subject.textValue();
        if (value == null || value.isEmpty() || value.length() > 19
                || value.charAt(0) < '1' || value.charAt(0) > '9') {
            return 0L;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return 0L;
            }
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /** 吊销当前用户令牌；RFC 7009 语义下重复吊销同样成功。 */
    public void revoke(String accessToken) {
        postForm("/oauth2/revoke", Map.of("token", accessToken), "吊销访问令牌");
    }

    private Account postAccount(String path, Map<String, Object> payload, String operation,
                                boolean requireAccessToken) {
        HttpResponse<String> response = post(path, payload, operation);
        JsonNode json = parseJson(response.body());
        JsonNode id = json == null ? null : json.get("id");
        JsonNode email = json == null ? null : json.get("email");
        String accessToken = text(json, "accessToken");
        if (id == null || !id.canConvertToLong() || id.asLong() <= 0
                || email == null || !email.isTextual()
                || (requireAccessToken && accessToken.isBlank())) {
            throw new AccountBridgeException(502, "NEKO_AUTH_BAD_RESPONSE",
                    "NekoAuth 返回的账号或令牌信息格式不正确", 0);
        }
        long expiresIn = json.path("expiresIn").canConvertToLong()
                ? json.path("expiresIn").asLong() : 0L;
        return new Account(
                id.asLong(),
                email.asText(),
                text(json, "username"),
                firstNonBlank(text(json, "nickname"), text(json, "username")),
                text(json, "role"),
                json.path("enabled").asBoolean(true),
                accessToken,
                text(json, "tokenType"),
                expiresIn);
    }

    private List<AccountSummary> parseSummaries(HttpResponse<String> response, String operation) {
        JsonNode json = parseJson(response.body());
        if (json == null || !json.isArray()) {
            throw new AccountBridgeException(502, "NEKO_AUTH_BAD_RESPONSE",
                    "NekoAuth 返回的账号摘要格式不正确", 0);
        }
        List<AccountSummary> accounts = new ArrayList<>();
        for (JsonNode item : json) {
            JsonNode id = item.get("id");
            if (id == null || !id.canConvertToLong() || id.asLong() <= 0) {
                continue;
            }
            accounts.add(new AccountSummary(
                    id.asLong(),
                    text(item, "email"),
                    text(item, "username"),
                    firstNonBlank(text(item, "nickname"), text(item, "username"))));
        }
        return accounts;
    }

    private void postNoContent(String path, Map<String, Object> payload, String operation) {
        post(path, payload, operation);
    }

    private HttpResponse<String> post(String path, Map<String, Object> payload, String operation) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new AccountBridgeException(500, "NEKO_AUTH_REQUEST_ENCODE_FAILED",
                    "无法构造 NekoAuth 请求", 0);
        }
        HttpRequest request = requestBuilder("/api/internal/accounts" + path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        return sendChecked(request, operation, path);
    }

    private HttpResponse<String> postForm(String path, Map<String, String> payload, String operation) {
        String requestBody = encodeForm(payload);
        HttpRequest request = requestBuilder(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        return sendChecked(request, operation, path);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        String clientSecret = requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        return HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", basic(clientId, clientSecret))
                .header("Accept", "application/json");
    }

    private HttpResponse<String> sendChecked(HttpRequest request, String operation, String path) {
        HttpResponse<String> response;
        try {
            response = HttpTransport.sendString(httpClient, request, "NekoAuth 账号请求被中断");
        } catch (IOException exception) {
            logger.warn("NekoAuth 账号桥接请求失败: operation={} path={}", operation, path);
            throw new AccountBridgeException(503, "NEKO_AUTH_UNAVAILABLE",
                    "暂时无法连接 NekoAuth", 0);
        }

        if (!HttpTransport.isSuccess(response.statusCode())) {
            JsonNode error = parseJson(response.body());
            String code = text(error, "code");
            String message = text(error, "message");
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode fieldErrors = error.get("fields");
            if (fieldErrors != null && fieldErrors.isObject()) {
                fieldErrors.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        fields.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            if (code.isBlank()) {
                code = "NEKO_AUTH_ERROR";
            }
            if (message.isBlank()) {
                message = "NekoAuth 拒绝了账号请求";
            }
            long retryAfter = retryAfterSeconds(response);
            if (response.statusCode() == 401 && "INVALID_CLIENT".equals(code)) {
                logger.error("NekoAuth 账号桥接客户端认证失败: code={} message={}；"
                                + "请检查 oauth.client_id/oauth.client_secret 以及客户端是否包含 account:bridge scope",
                        code, message);
            } else {
                logger.warn("NekoAuth 账号桥接失败: operation={} status={} code={}",
                        operation, response.statusCode(), code);
            }
            throw new AccountBridgeException(response.statusCode(), code, message, retryAfter, fields);
        }
        return response;
    }

    private URI issuerUri() {
        String value = requireConfigured(configManager.getOAuthIssuer(), "oauth.issuer");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new AccountBridgeException(503, "NEKO_AUTH_NOT_CONFIGURED",
                    "NekoAuth 根地址格式不正确", 0);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))
                || uri.getRawQuery() != null || uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw new AccountBridgeException(503, "NEKO_AUTH_NOT_CONFIGURED",
                    "NekoAuth 根地址格式不正确", 0);
        }
        return URI.create(value);
    }

    private String requireConfigured(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new AccountBridgeException(503, "NEKO_AUTH_NOT_CONFIGURED",
                    "缺少 NekoAuth 配置：" + key, 0);
        }
        return value.trim();
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static long retryAfterSeconds(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Math.max(1, Long.parseLong(value.trim()));
                    } catch (NumberFormatException ignored) {
                        return 60L;
                    }
                })
                .orElse(60L);
    }

    private static String basic(String clientId, String clientSecret) {
        // RFC 7617：用户名与密钥原样拼接后 Base64，不做 URL 编解码。
        String encoded = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(encoded.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sanitizeMultipartName(String value) {
        if (value == null || value.isBlank()) {
            return "avatar.png";
        }
        String sanitized = value.replace("\\", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
        return sanitized.length() <= 120 ? sanitized : sanitized.substring(sanitized.length() - 120);
    }

    private static String avatarContentType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
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

    public record Account(long id, String email, String username, String nickname, String role, boolean enabled,
                          String accessToken, String tokenType, long expiresIn) {
    }

    public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    }

    public record AccountSummary(long id, String email, String username, String nickname) {
    }

    public record Introspection(long userId, String email, String nickname, boolean emailVerified,
                                String role, String scope) {
    }

    public static final class AccountBridgeException extends RuntimeException {
        private final int status;
        private final String code;
        private final long retryAfterSeconds;
        private final Map<String, String> fields;

        public AccountBridgeException(int status, String code, String message, long retryAfterSeconds) {
            this(status, code, message, retryAfterSeconds, Map.of());
        }

        public AccountBridgeException(int status, String code, String message, long retryAfterSeconds,
                                      Map<String, String> fields) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
            this.fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public String field(String name) {
            return fields.get(name);
        }

        public boolean invalidCredentials() {
            return status == 401 && ("INVALID_CREDENTIALS".equals(code)
                    || "BAD_CREDENTIALS".equals(code)
                    || "AUTH_INVALID_CREDENTIALS".equals(code));
        }

        public boolean rateLimited() {
            return status == 429 || "EMAIL_CODE_COOLDOWN".equals(code)
                    || "EMAIL_CODE_DAILY_LIMIT".equals(code);
        }

        public boolean notConfigured() {
            return "NEKO_AUTH_NOT_CONFIGURED".equals(code);
        }

        public boolean unavailable() {
            return status >= 500 || "NEKO_AUTH_UNAVAILABLE".equals(code);
        }
    }
}
