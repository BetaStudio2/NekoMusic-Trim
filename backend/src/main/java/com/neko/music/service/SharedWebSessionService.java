package com.neko.music.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Web 第一方共享会话：本地只保存 Auth 会话族句柄，不在浏览器暴露 Auth 凭据。 */
public final class SharedWebSessionService {

    private static final String PREFIX = "neko-web-session:";
    private static final String TOKEN_PREFIX = "nekosso_";
    private static final int TTL_SECONDS = 14 * 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedisService redisService;
    private final NekoAuthAccountService authService;

    public SharedWebSessionService(RedisService redisService, NekoAuthAccountService authService) {
        this.redisService = redisService;
        this.authService = authService;
    }

    public boolean isSharedSessionToken(String token) {
        return token != null && token.startsWith(TOKEN_PREFIX);
    }

    public String create(String sessionFamilyId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = TOKEN_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (redisService.setIfAbsentWithExpiry(
                    PREFIX + token, sessionFamilyId, TTL_SECONDS)) {
                return token;
            }
        }
        throw new IllegalStateException("无法创建 Web 登录会话");
    }

    public Optional<NekoAuthAccountService.Introspection> introspect(String token) {
        String sessionFamilyId = redisService.get(PREFIX + token);
        if (sessionFamilyId == null || sessionFamilyId.isBlank()) {
            return Optional.empty();
        }
        try {
            NekoAuthAccountService.SharedAccount account =
                    authService.resolveSharedSession(sessionFamilyId);
            if (!account.enabled()) {
                redisService.del(PREFIX + token);
                return Optional.empty();
            }
            return Optional.of(new NekoAuthAccountService.Introspection(
                    account.id(),
                    account.email(),
                    account.nickname(),
                    true,
                    account.role(),
                    ""));
        } catch (NekoAuthAccountService.NekoAuthServiceException exception) {
            if (exception.status() == 401) {
                redisService.del(PREFIX + token);
                return Optional.empty();
            }
            throw exception;
        }
    }

    public List<String> revoke(String token) {
        String sessionFamilyId = redisService.get(PREFIX + token);
        redisService.del(PREFIX + token);
        if (sessionFamilyId == null || sessionFamilyId.isBlank()) {
            return List.of();
        }
        return authService.logoutSharedSession(sessionFamilyId);
    }
}
