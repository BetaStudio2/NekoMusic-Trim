package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import com.neko.music.model.User;
import com.neko.music.util.DbTimeUtil;
import com.neko.music.util.RequestScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;

public class UserAuthService {
    private static final Logger logger = LoggerFactory.getLogger(UserAuthService.class);
    private final DatabaseManager databaseManager;
    private final NekoAuthAccountService nekoAuthAccountService;
    private final NicknameResolver nicknameResolver;
    private static final String INTROSPECTION_SCOPE_PREFIX = "nekoAuthIntrospection:";

    public UserAuthService(DatabaseManager databaseManager,
                           NekoAuthAccountService nekoAuthAccountService) {
        this(databaseManager, nekoAuthAccountService, null);
    }

    public UserAuthService(DatabaseManager databaseManager,
                           NekoAuthAccountService nekoAuthAccountService,
                           NicknameResolver nicknameResolver) {
        this.databaseManager = databaseManager;
        this.nekoAuthAccountService = nekoAuthAccountService;
        this.nicknameResolver = nicknameResolver == null
                ? new NicknameResolver(databaseManager, nekoAuthAccountService)
                : nicknameResolver;
    }

    /**
     * 按 NekoAuth 的稳定用户 ID 查找本地资料；首次见到该账号时自动创建本地资料。
     * 同时兼容旧数据：本地已有同邮箱账号但尚未绑定 auth_user_id 时会自动补绑定。
     * 昵称不落本地库，返回结果直接使用本次 Auth 提供的昵称。
     */
    public User findOrLinkNekoAuthUser(long authUserId, String email, String authNickname) {
        if (authUserId <= 0) {
            throw new IllegalStateException("NekoAuth 用户 ID 无效");
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isEmpty()) {
            throw new IllegalStateException("NekoAuth 未返回邮箱");
        }

        User byAuthId = findUserByAuthId(authUserId, authNickname);
        if (byAuthId != null) {
            if (!normalizedEmail.equalsIgnoreCase(byAuthId.email())) {
                updateLocalAccountBinding(byAuthId.id(), authUserId, normalizedEmail);
                byAuthId = findUserById(byAuthId.id()).orElse(byAuthId);
            }
            return byAuthId;
        }

        User byEmail = findUserByEmail(normalizedEmail, authNickname);
        if (byEmail != null) {
            Long boundAuthUserId = findAuthUserIdByLocalId(byEmail.id());
            if (boundAuthUserId != null && boundAuthUserId != authUserId) {
                throw new IllegalStateException("该邮箱已绑定其他 NekoAuth 账号");
            }
            updateLocalAccountBinding(byEmail.id(), authUserId, normalizedEmail);
            logger.info("已关联本地用户资料: localUserId={} authUserId={}", byEmail.id(), authUserId);
            User linked = findUserById(byEmail.id()).orElse(byEmail);
            return withNickname(linked, authNickname);
        }

        String sql = "INSERT INTO users (email, auth_user_id, created_at) VALUES (?, ?, "
                + DbTimeUtil.SQL_NOW_SHANGHAI + ")";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, normalizedEmail);
            stmt.setLong(2, authUserId);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    int localId = keys.getInt(1);
                    return withNickname(findUserById(localId).orElseThrow(
                            () -> new IllegalStateException("创建本地用户资料失败")), authNickname);
                }
            }
            throw new IllegalStateException("创建本地用户资料失败");
        } catch (SQLException exception) {
            User raced = findUserByAuthId(authUserId, authNickname);
            if (raced != null) {
                return raced;
            }
            User racedByEmail = findUserByEmail(normalizedEmail, authNickname);
            if (racedByEmail != null) {
                updateLocalAccountBinding(racedByEmail.id(), authUserId, normalizedEmail);
                User linked = findUserById(racedByEmail.id()).orElse(racedByEmail);
                return withNickname(linked, authNickname);
            }
            logger.error("创建本地用户资料失败: authUserId={} email={} error={}",
                    authUserId, normalizedEmail, exception.getMessage(), exception);
            throw new IllegalStateException("创建本地用户资料失败", exception);
        }
    }

    public boolean usesNekoAuthBridge() {
        return nekoAuthAccountService != null;
    }

    private User findUserByAuthId(long authUserId, String nicknameOverride) {
        String sql = "SELECT id, email, created_at, vip_expires_at, auth_user_id "
                + "FROM users WHERE auth_user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, authUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUser(rs, nicknameOverride) : null;
            }
        } catch (SQLException e) {
            logger.error("按 NekoAuth 用户 ID 查询本地资料失败: authUserId={} error={}",
                    authUserId, e.getMessage(), e);
            return null;
        }
    }

    private User findUserByEmail(String email, String nicknameOverride) {
        String sql = "SELECT id, email, created_at, vip_expires_at, auth_user_id "
                + "FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUser(rs, nicknameOverride) : null;
            }
        } catch (SQLException e) {
            logger.error("按邮箱查询本地资料失败: email={} error={}", email, e.getMessage(), e);
            return null;
        }
    }

    private Long findAuthUserIdByLocalId(int localUserId) {
        String sql = "SELECT auth_user_id FROM users WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, localUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long value = rs.getLong("auth_user_id");
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            logger.error("查询本地账号绑定失败: localUserId={} error={}", localUserId, e.getMessage(), e);
            return null;
        }
    }

    private void updateLocalAccountBinding(int localUserId, long authUserId, String email) {
        String sql = "UPDATE users SET email = ?, auth_user_id = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setLong(2, authUserId);
            stmt.setInt(3, localUserId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("更新本地账号绑定失败: localUserId={} authUserId={} error={}",
                    localUserId, authUserId, e.getMessage(), e);
            throw new IllegalStateException("更新本地账号绑定失败", e);
        }
    }

    private static User withNickname(User user, String authNickname) {
        String nickname = authNickname == null ? "" : authNickname.trim();
        if (nickname.isEmpty() || nickname.equals(user.nickname())) {
            return user;
        }
        return new User(user.id(), nickname, user.password(), user.email(), user.emailVerified(),
                user.avatar(), user.createdAt(), user.vipExpiresAt());
    }

    /**
     * 按用户 ID 查询用户信息（不含密码字段），用于扫码登录等需要回传用户资料的场景。
     */
    public Optional<User> findUserById(int userId) {
        String sql = "SELECT id, email, created_at, vip_expires_at, auth_user_id "
                + "FROM users WHERE id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = mapUser(rs);
                    return Optional.of(user);
                }
            }
        } catch (SQLException e) {
            logger.error("按ID查询用户失败: {}", e.getMessage(), e);
        }

        return Optional.empty();
    }

    /**
     * 将 users 查询结果行映射为 {@link User}；昵称每次从 NekoAuth 解析，不读本地列。
     */
    private User mapUser(ResultSet rs) throws SQLException {
        return mapUser(rs, null);
    }

    private User mapUser(ResultSet rs, String nicknameOverride) throws SQLException {
        java.sql.Timestamp vipTs = rs.getTimestamp("vip_expires_at");
        boolean hasVip = !rs.wasNull();
        String email = rs.getString("email");
        long rawAuthUserId = rs.getLong("auth_user_id");
        Long authUserId = rs.wasNull() ? null : rawAuthUserId;
        String nickname = nicknameOverride == null || nicknameOverride.isBlank()
                ? nicknameResolver.resolveByAuthId(authUserId, email)
                : nicknameOverride.trim();
        return new User(
                rs.getInt("id"),
                nickname,
                null,
                email,
                false,
                null,
                DbTimeUtil.formatStoredWallClock(rs.getString("created_at")),
                hasVip ? vipTs : null);
    }

    /**
     * 注销某用户全部会话（改密、重置密码后须重新登录）。
     */
    public void revokeAllTokensForUser(int userId) {
        // 用户令牌只在 NekoAuth 保存。改密 / 重置密码会推进 Auth 的 tokenVersion，
        // introspection 随即使所有旧令牌失效，因此这里不再维护本地 token 索引。
        logger.debug("用户 {} 的会话由 NekoAuth tokenVersion 统一失效", userId);
    }

    private NekoAuthAccountService requireBridge() {
        if (nekoAuthAccountService == null) {
            throw new NekoAuthAccountService.AccountBridgeException(
                    503, "NEKO_AUTH_NOT_CONFIGURED", "未配置 NekoAuth 账号桥接", 0);
        }
        return nekoAuthAccountService;
    }
    
    public Optional<String> findEmailByUserId(int userId) {
        String sql = "SELECT email FROM users WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String email = rs.getString("email");
                    if (email != null && !email.isBlank()) {
                        return Optional.of(email.trim());
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("查询用户邮箱失败 userId={}: {}", userId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public Optional<java.sql.Timestamp> findVipExpiresAtByUserId(int userId) {
        String sql = "SELECT vip_expires_at FROM users WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp vip = rs.getTimestamp("vip_expires_at");
                    return Optional.ofNullable(rs.wasNull() ? null : vip);
                }
            }
        } catch (SQLException e) {
            logger.error("查询 VIP 到期时间失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /** 通过 NekoAuth introspection 验证令牌，并把稳定 Auth 用户 ID 映射到本地资料。 */
    public Optional<Integer> validateToken(String token) {
        Optional<NekoAuthAccountService.Introspection> introspection = introspectToken(token);
        if (introspection.isEmpty()) {
            return Optional.empty();
        }
        NekoAuthAccountService.Introspection identity = introspection.get();
        try {
            User user = findOrLinkNekoAuthUser(identity.userId(), identity.email(), identity.nickname());
            return Optional.of(user.id());
        } catch (RuntimeException exception) {
            logger.warn("NekoAuth 令牌有效但本地资料映射失败: authUserId={} error={}",
                    identity.userId(), exception.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * 只读解析 token 对应的本地用户 ID（给审计 / 日志使用，不创建本地资料）。
     *
     * <p>给审计 / 日志这类旁路使用：它们需要知道「是谁」，但不该因为记了一条日志就把会话
     * 悄悄续上——那会让「开不开日志」影响会话时长。认证路径请继续用 {@link #validateToken}。</p>
     */
    public Optional<Integer> findUserIdByToken(String token) {
        Optional<NekoAuthAccountService.Introspection> introspection = introspectToken(token);
        if (introspection.isEmpty()) {
            return Optional.empty();
        }
        return findLocalUserIdByAuthId(introspection.get().userId());
    }

    private Optional<Integer> findLocalUserIdByAuthId(long authUserId) {
        String sql = "SELECT id FROM users WHERE auth_user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, authUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt("id")) : Optional.empty();
            }
        } catch (SQLException e) {
            logger.error("按 NekoAuth 用户 ID 查询本地资料失败: authUserId={} error={}",
                    authUserId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** 在 NekoAuth 吊销用户令牌。 */
    public boolean revokeToken(String token) {
        try {
            requireBridge().revoke(token);
            logger.info("NekoAuth 用户令牌已注销");
            return true;
        } catch (NekoAuthAccountService.AccountBridgeException exception) {
            logger.warn("NekoAuth 用户令牌注销失败: status={} code={}",
                    exception.status(), exception.code());
            return false;
        }
    }

    /** 把当前短期 OAuth 令牌换成 NekoAuth 所有的长期第一方令牌。 */
    public String exchangeAccessToken(String token) {
        return requireBridge().exchangeToken(token).accessToken();
    }

    private Optional<NekoAuthAccountService.Introspection> introspectToken(String token) {
        if (token == null || token.isBlank() || nekoAuthAccountService == null) {
            return Optional.empty();
        }
        // 兼容两种 Authorization 写法：`<token>` 与 `Bearer <token>`。
        // 旧实现走 RedisTokenStore（内部会归一化），迁移到 NekoAuth introspection 后
        // 若只 trim，则所有直接把原始 Authorization 头传进来的处理器（UserFavoriteHandler
        // 等）会带着 "Bearer " 去 introspect，导致「登录成功但受保护接口一律 401」。
        String normalized = RedisTokenStore.normalizeBearerToken(token);
        NekoAuthAccountService.Introspection cached = RequestScope.computeIfAbsent(
                INTROSPECTION_SCOPE_PREFIX + normalized, () -> {
                    try {
                        return nekoAuthAccountService.introspect(normalized).orElse(null);
                    } catch (NekoAuthAccountService.AccountBridgeException exception) {
                        logger.warn("NekoAuth 令牌校验失败: status={} code={}",
                                exception.status(), exception.code());
                        return null;
                    }
                });
        return Optional.ofNullable(cached);
    }

}
