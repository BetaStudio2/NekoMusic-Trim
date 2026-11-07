package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NekoAuth 标准 OAuth 与公开资料客户端。
 *
 * <p>令牌校验与吊销使用标准 OAuth introspection/revoke，
 * 昵称查询使用 Auth 的公开账号摘要接口。</p>
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

    /** 按 NekoAuth 用户 ID 批量读取公开昵称；NekoAuth 是唯一权威源。 */
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
        HttpResponse<String> response = postPublic(
                "/api/public/users/lookup", Map.of("ids", ids), "查询昵称");
        Map<Long, String> nicknames = new LinkedHashMap<>();
        for (AccountSummary account : parseSummaries(response)) {
            nicknames.put(account.id(), account.nickname());
        }
        return nicknames;
    }

    /** 在 NekoAuth 中按昵称、用户名或邮箱搜索公开账号摘要。 */
    public List<AccountSummary> searchAccounts(String query) {
        HttpResponse<String> response = postPublic(
                "/api/public/users/search", Map.of("query", query), "搜索账号");
        return parseSummaries(response);
    }

    /** 校验用户令牌是否仍有效，并返回 Auth 权威身份。 */
    public Optional<Introspection> introspect(String accessToken) {
        HttpResponse<String> response = postForm(
                "/oauth2/introspect", Map.of("token", accessToken), "校验访问令牌");
        JsonNode json = parseJson(response.body());
        if (json == null || !json.path("active").asBoolean(false)) {
            return Optional.empty();
        }
        long authUserId = subjectId(json.get("sub"));
        if (authUserId <= 0) {
            throw new NekoAuthServiceException(502, "NEKO_AUTH_BAD_RESPONSE",
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

    /** 吊销当前用户令牌；RFC 7009 语义下重复吊销同样成功。 */
    public void revoke(String accessToken) {
        postForm("/oauth2/revoke", Map.of("token", accessToken), "吊销访问令牌");
    }

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

    private List<AccountSummary> parseSummaries(HttpResponse<String> response) {
        JsonNode json = parseJson(response.body());
        if (json == null || !json.isArray()) {
            throw new NekoAuthServiceException(502, "NEKO_AUTH_BAD_RESPONSE",
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
                    "",
                    text(item, "username"),
                    firstNonBlank(text(item, "nickname"), text(item, "username"))));
        }
        return accounts;
    }

    private HttpResponse<String> postPublic(String path, Map<String, Object> payload,
                                            String operation) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new NekoAuthServiceException(500, "NEKO_AUTH_REQUEST_ENCODE_FAILED",
                    "无法构造 NekoAuth 请求", 0);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + path))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        return sendChecked(request, operation, path);
    }

    private HttpResponse<String> postForm(String path, Map<String, String> payload,
                                          String operation) {
        String clientId = requireConfigured(configManager.getOAuthClientId(), "oauth.client_id");
        String clientSecret = requireConfigured(configManager.getOAuthClientSecret(), "oauth.client_secret");
        String requestBody = encodeForm(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", basic(clientId, clientSecret))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        return sendChecked(request, operation, path);
    }

    private HttpResponse<String> sendChecked(HttpRequest request, String operation, String path) {
        HttpResponse<String> response;
        try {
            response = HttpTransport.sendString(httpClient, request, "NekoAuth 请求被中断");
        } catch (IOException exception) {
            logger.warn("NekoAuth 请求失败: operation={} path={}", operation, path);
            throw new NekoAuthServiceException(503, "NEKO_AUTH_UNAVAILABLE",
                    "暂时无法连接 NekoAuth", 0);
        }

        if (!HttpTransport.isSuccess(response.statusCode())) {
            JsonNode error = parseJson(response.body());
            String code = text(error, "code");
            String message = text(error, "message");
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode fieldErrors = error == null ? null : error.get("fields");
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
                message = "NekoAuth 拒绝了请求";
            }
            logger.warn("NekoAuth 请求失败: operation={} status={} code={}",
                    operation, response.statusCode(), code);
            throw new NekoAuthServiceException(
                    response.statusCode(), code, message, retryAfterSeconds(response), fields);
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
            throw new NekoAuthServiceException(503, "NEKO_AUTH_NOT_CONFIGURED",
                    "NekoAuth 根地址格式不正确", 0);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (uri.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))
                || uri.getRawQuery() != null || uri.getFragment() != null || uri.getRawUserInfo() != null) {
            throw new NekoAuthServiceException(503, "NEKO_AUTH_NOT_CONFIGURED",
                    "NekoAuth 根地址格式不正确", 0);
        }
        return URI.create(value);
    }

    private String requireConfigured(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new NekoAuthServiceException(503, "NEKO_AUTH_NOT_CONFIGURED",
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

    private static String basic(String clientId, String clientSecret) {
        String encoded = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(encoded.getBytes(StandardCharsets.UTF_8));
    }

    public record AccountSummary(long id, String email, String username, String nickname) {
    }

    public record Introspection(long userId, String email, String nickname, boolean emailVerified,
                                String role, String scope) {
    }

    public static final class NekoAuthServiceException extends RuntimeException {
        private final int status;
        private final String code;
        private final long retryAfterSeconds;
        private final Map<String, String> fields;

        public NekoAuthServiceException(int status, String code, String message, long retryAfterSeconds) {
            this(status, code, message, retryAfterSeconds, Map.of());
        }

        public NekoAuthServiceException(int status, String code, String message, long retryAfterSeconds,
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

        public boolean notConfigured() {
            return "NEKO_AUTH_NOT_CONFIGURED".equals(code);
        }

        public boolean unavailable() {
            return status >= 500 || "NEKO_AUTH_UNAVAILABLE".equals(code);
        }
    }
}
