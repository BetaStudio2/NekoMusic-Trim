package com.neko.music.service;

import com.neko.music.config.ConfigManager;
import com.neko.music.database.DatabaseManager;
import com.neko.music.model.User;
import com.neko.music.util.DbTimeUtil;
import com.neko.music.util.RequestScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UserAuthService {
    private static final Logger logger = LoggerFactory.getLogger(UserAuthService.class);
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final EmailService emailService;
    private final RedisService redisService;
    private final RedisTokenStore tokenStore;
    private final VerificationCodeRateLimitService verificationCodeRateLimitService;
    private final NekoAuthAccountService nekoAuthAccountService;
    private static final String INTROSPECTION_SCOPE_PREFIX = "nekoAuthIntrospection:";

    /**
     * 验证码异步发信线程：密码重置必须让「已注册 / 未注册」的响应耗时不可区分，
     * 因此发信放到后台线程，调用方立即返回统一响应。单线程足够——真正的串行瓶颈在 SMTP。
     */
    private static final ExecutorService ASYNC_CODE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "verification-code-async");
        thread.setDaemon(true);
        return thread;
    });
    
    private static final String VERIFICATION_CODE_PREFIX = "verification_code:";
    private static final String VERIFICATION_CODE_ATTEMPT_PREFIX = "verification_code_attempts:";
    private static final String VERIFICATION_PURPOSE_REGISTER = "register";
    private static final String VERIFICATION_PURPOSE_PASSWORD_RESET = "password_reset";
    private static final int VERIFICATION_CODE_EXPIRY = 300; // 5分钟过期
    private static final int VERIFICATION_CODE_MAX_ATTEMPTS = 5;

    public UserAuthService(DatabaseManager databaseManager, ConfigManager configManager,
                           EmailService emailService, RedisService redisService,
                           RedisTokenStore tokenStore,
                           VerificationCodeRateLimitService verificationCodeRateLimitService) {
        this(databaseManager, configManager, emailService, redisService, tokenStore,
                verificationCodeRateLimitService, null);
    }

    public UserAuthService(DatabaseManager databaseManager, ConfigManager configManager,
                           EmailService emailService, RedisService redisService,
                           RedisTokenStore tokenStore,
                           VerificationCodeRateLimitService verificationCodeRateLimitService,
                           NekoAuthAccountService nekoAuthAccountService) {
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.emailService = emailService;
        this.redisService = redisService;
        this.tokenStore = tokenStore;
        this.verificationCodeRateLimitService = verificationCodeRateLimitService;
        this.nekoAuthAccountService = nekoAuthAccountService;
    }

    /**
     * 用户登录（仅支持邮箱登录）。
     *
     * <p>密码校验完全交给 NekoAuth；本地 users 表不保存密码。</p>
     */
    public Optional<LoginSession> authenticate(String email, String password) {
        logger.info("用户登录尝试: {}", email);

        try {
            NekoAuthAccountService.Account account = requireBridge().login(email, password);
            User user = findOrLinkNekoAuthUser(account.id(), account.email(), account.nickname());
            logger.info("NekoAuth 用户登录成功: authUserId={} localUserId={}",
                    account.id(), user.id());
            return Optional.of(new LoginSession(user, account.accessToken()));
        } catch (NekoAuthAccountService.AccountBridgeException exception) {
            if (exception.invalidCredentials()) {
                logger.warn("NekoAuth 用户登录失败: {}", email);
                return Optional.empty();
            }
            throw exception;
        }
    }

    /**
     * 通过 NekoAuth 注册账号，再在 NekoMusic 创建或关联本地资料。
     * 昵称由 NekoAuth 保存，本地资料只保留用于 JOIN 与展示的同步缓存。
     */
    public boolean registerUserWithCode(String nickname, String password, String email,
                                        String verificationCode) {
        NekoAuthAccountService.Account account =
                requireBridge().register(email, password, verificationCode, nickname);
        findOrLinkNekoAuthUser(account.id(), account.email(), account.nickname());
        logger.info("NekoAuth 注册成功并已关联本地资料: authUserId={}", account.id());
        return true;
    }

    /**
     * 按 NekoAuth 的稳定用户 ID 查找本地资料；首次见到该账号时自动创建本地资料。
     * 同时兼容旧数据：本地已有同邮箱账号但尚未绑定 auth_user_id 时会自动补绑定。
     * 已存在本地资料时以 NekoAuth 昵称为准，仅在值变化时更新本地缓存。
     */
    public User findOrLinkNekoAuthUser(long authUserId, String email, String authNickname) {
        if (authUserId <= 0) {
            throw new IllegalStateException("NekoAuth 用户 ID 无效");
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isEmpty()) {
            throw new IllegalStateException("NekoAuth 未返回邮箱");
        }

        User byAuthId = findUserByAuthId(authUserId);
        if (byAuthId != null) {
            if (!normalizedEmail.equalsIgnoreCase(byAuthId.email())) {
                updateLocalAccountBinding(byAuthId.id(), authUserId, normalizedEmail);
                byAuthId = findUserById(byAuthId.id()).orElse(byAuthId);
            }
            return syncLocalNickname(byAuthId, authNickname);
        }

        User byEmail = findUserByEmail(normalizedEmail);
        if (byEmail != null) {
            Long boundAuthUserId = findAuthUserIdByLocalId(byEmail.id());
            if (boundAuthUserId != null && boundAuthUserId != authUserId) {
                throw new IllegalStateException("该邮箱已绑定其他 NekoAuth 账号");
            }
            updateLocalAccountBinding(byEmail.id(), authUserId, normalizedEmail);
            logger.info("已关联本地用户资料: localUserId={} authUserId={}", byEmail.id(), authUserId);
            User linked = findUserById(byEmail.id()).orElse(byEmail);
            return syncLocalNickname(linked, authNickname);
        }

        String nickname = safeLocalNickname(authNickname, normalizedEmail);
        String sql = "INSERT INTO users (nickname, email, auth_user_id, created_at) VALUES (?, ?, ?, "
                + DbTimeUtil.SQL_NOW_SHANGHAI + ")";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, nickname);
            stmt.setString(2, normalizedEmail);
            stmt.setLong(3, authUserId);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    int localId = keys.getInt(1);
                    return findUserById(localId).orElseThrow(
                            () -> new IllegalStateException("创建本地用户资料失败"));
                }
            }
            throw new IllegalStateException("创建本地用户资料失败");
        } catch (SQLException exception) {
            User raced = findUserByAuthId(authUserId);
            if (raced != null) {
                return syncLocalNickname(raced, authNickname);
            }
            User racedByEmail = findUserByEmail(normalizedEmail);
            if (racedByEmail != null) {
                updateLocalAccountBinding(racedByEmail.id(), authUserId, normalizedEmail);
                User linked = findUserById(racedByEmail.id()).orElse(racedByEmail);
                return syncLocalNickname(linked, authNickname);
            }
            logger.error("创建本地用户资料失败: authUserId={} email={} error={}",
                    authUserId, normalizedEmail, exception.getMessage(), exception);
            throw new IllegalStateException("创建本地用户资料失败", exception);
        }
    }

    public boolean usesNekoAuthBridge() {
        return nekoAuthAccountService != null;
    }

    private User findUserByAuthId(long authUserId) {
        String sql = "SELECT id, nickname, email, created_at, vip_expires_at FROM users WHERE auth_user_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, authUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
            }
        } catch (SQLException e) {
            logger.error("按 NekoAuth 用户 ID 查询本地资料失败: authUserId={} error={}",
                    authUserId, e.getMessage(), e);
            return null;
        }
    }

    private User findUserByEmail(String email) {
        String sql = "SELECT id, nickname, email, created_at, vip_expires_at FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapUser(rs) : null;
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

    private User syncLocalNickname(User user, String authNickname) {
        String nickname = authNickname == null ? "" : authNickname.trim();
        if (nickname.isEmpty() || nickname.equals(user.nickname())) {
            return user;
        }
        String sql = "UPDATE users SET nickname = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nickname);
            stmt.setInt(2, user.id());
            if (stmt.executeUpdate() == 0) {
                throw new IllegalStateException("更新本地昵称缓存失败");
            }
            logger.info("已同步 NekoAuth 昵称到本地资料: localUserId={}", user.id());
            return findUserById(user.id()).orElse(user);
        } catch (SQLException exception) {
            logger.error("同步 NekoAuth 昵称失败: localUserId={} error={}",
                    user.id(), exception.getMessage(), exception);
            throw new IllegalStateException("同步 NekoAuth 昵称失败", exception);
        }
    }

    private static String safeLocalNickname(String authNickname, String email) {
        String candidate = authNickname == null ? "" : authNickname.trim();
        if (candidate.isEmpty()) {
            int separator = email.indexOf('@');
            candidate = separator > 0 ? email.substring(0, separator) : email;
        }
        candidate = candidate.trim();
        if (candidate.isEmpty()) {
            candidate = "听众";
        }
        if (candidate.length() > 32) {
            candidate = candidate.substring(0, 32);
        }
        return candidate;
    }

    /**
     * 按用户 ID 查询用户信息（不含密码字段），用于扫码登录等需要回传用户资料的场景。
     */
    public Optional<User> findUserById(int userId) {
        String sql = "SELECT id, nickname, email, created_at, vip_expires_at FROM users WHERE id = ?";

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
     * 将 users 查询结果行映射为 {@link User}（读取 id/nickname/email/created_at/vip_expires_at）。
     */
    private static User mapUser(ResultSet rs) throws SQLException {
        java.sql.Timestamp vipTs = rs.getTimestamp("vip_expires_at");
        boolean hasVip = !rs.wasNull();
        return new User(
                rs.getInt("id"),
                rs.getString("nickname"),
                null,
                rs.getString("email"),
                false,
                null,
                DbTimeUtil.formatStoredWallClock(rs.getString("created_at")),
                hasVip ? vipTs : null);
    }

    /**
     * 发送注册验证码；同邮箱有发信冷却。
     */
    public SendVerificationCodeResult sendVerificationCode(String email, String nickname) {
        if (nekoAuthAccountService != null) {
            try {
                nekoAuthAccountService.sendRegistrationCode(email);
                return SendVerificationCodeResult.ok();
            } catch (NekoAuthAccountService.AccountBridgeException exception) {
                if (exception.rateLimited()) {
                    return SendVerificationCodeResult.cooldown(exception.retryAfterSeconds());
                }
                throw exception;
            }
        }
        return sendVerificationCode(email, nickname, VERIFICATION_PURPOSE_REGISTER);
    }

    /**
     * 发送密码重置验证码；与注册验证码隔离，避免跨流程复用。
     */
    public SendVerificationCodeResult sendResetPasswordCode(String email, String nickname) {
        return sendVerificationCode(email, nickname, VERIFICATION_PURPOSE_PASSWORD_RESET);
    }

    private SendVerificationCodeResult sendVerificationCode(String email, String nickname, String purpose) {
        var cooldown = verificationCodeRateLimitService.tryAcquireSendSlot(email);
        if (cooldown.isPresent()) {
            return SendVerificationCodeResult.cooldown(cooldown.get());
        }
        return deliverVerificationCode(email, nickname, purpose);
    }

    /**
     * 重置密码验证码：统一限流通过后**异步**发信，调用方立即返回。
     *
     * <p>为什么异步：密码重置接口必须对「邮箱是否注册」给出完全一致的响应。同步发信时，已注册
     * 邮箱要多走一次 SMTP（可达数百毫秒到数秒），未注册邮箱只查一次库，响应时间差本身就是
     * 账号存在性信号。放到后台线程后两条分支的耗时都只剩「一次查询」，失败只记日志
     * （对外文案本就统一）。</p>
     */
    public void sendResetPasswordCodeAsync(String email, String nickname) {
        if (nekoAuthAccountService != null) {
            ASYNC_CODE_EXECUTOR.execute(() -> {
                try {
                    nekoAuthAccountService.sendPasswordResetCode(email);
                } catch (RuntimeException e) {
                    logger.warn("提交 NekoAuth 找回密码验证码失败: {}", email);
                }
            });
            return;
        }
        ASYNC_CODE_EXECUTOR.execute(() -> {
            try {
                deliverVerificationCode(email, nickname, VERIFICATION_PURPOSE_PASSWORD_RESET);
            } catch (RuntimeException e) {
                logger.error("异步发送重置密码验证码失败: {}", email, e);
            }
        });
    }

    /**
     * 重置密码发码的统一限流：来源 IP 与邮箱各占一个槽位，两者都必须在「邮箱是否注册」之前完成，
     * 否则 {@code 429} 会变成账号存在性信号。
     *
     * @return empty 表示放行；非 empty 为需等待的秒数
     */
    public Optional<Long> acquireResetCodeSlot(String email, String clientIp) {
        Optional<Long> byIp = verificationCodeRateLimitService.tryAcquireIpSlot(clientIp);
        if (byIp.isPresent()) {
            return byIp;
        }
        return verificationCodeRateLimitService.tryAcquireSendSlot(email);
    }

    /**
     * 注册发码的来源 IP 限流：只占 IP 维度一个槽位。
     *
     * <p>邮箱级冷却（{@link #sendVerificationCode}）只拦「同邮箱重复发码」，挡不住换收件人批量发码；
     * 因此发码入口还必须按来源 IP 封顶。邮箱槽位仍由发信逻辑内部占用，此处不重复占用，否则会把
     * 同一次发信的邮箱冷却误判成超限。</p>
     *
     * @return empty 表示放行；非 empty 为需等待的秒数
     */
    public Optional<Long> acquireVerificationIpSlot(String clientIp) {
        return verificationCodeRateLimitService.tryAcquireIpSlot(clientIp);
    }

    /** 生成验证码 → 发信 → 落 Redis；冷却槽位由调用方（同步 / 异步两条路径）负责。 */
    private SendVerificationCodeResult deliverVerificationCode(String email, String nickname, String purpose) {
        logger.info("发送验证码至: {}", email);

        String verificationCode = emailService.generateVerificationCode();

        boolean emailSent = emailService.sendVerificationCode(email, nickname, verificationCode);

        if (emailSent) {
            String key = verificationCodeKey(purpose, email);
            redisService.setWithExpiry(key, verificationCode, VERIFICATION_CODE_EXPIRY);
            redisService.del(verificationCodeAttemptKey(purpose, email));
            logger.info("验证码已生成并存储到Redis: {}, purpose={}", email, purpose);
            return SendVerificationCodeResult.ok();
        }

        verificationCodeRateLimitService.releaseSendSlot(email);
        logger.error("发送验证码邮件失败: {}", email);
        return SendVerificationCodeResult.sendFailed();
    }
    
    /**
     * 检查邮箱是否在白名单中
     */
    public boolean isWhitelistedEmail(String email) {
        if (nekoAuthAccountService != null) {
            return true;
        }
        String emailWhitelist = configManager.getEmailWhitelist();
        
        if (emailWhitelist == null || emailWhitelist.trim().isEmpty()) {
            return true;
        }
        
        String emailDomain = email.substring(email.lastIndexOf("@") + 1).toLowerCase().trim();
        String[] whitelist = emailWhitelist.split(",");
        
        for (String whitelistedDomain : whitelist) {
            if (emailDomain.equals(whitelistedDomain.trim())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 验证注册验证码，并对同一邮箱短时间内的错误尝试次数封顶。
     *
     * <p>注册验证码是 6 位纯数字、有效期 5 分钟，若不做尝试次数限制，配合「无限速发码」可被穷举
     * （10^6 量级）。这里复用与密码重置一致的 {@link #VERIFICATION_CODE_MAX_ATTEMPTS} 策略：
     * 同一邮箱累计错误到上限即删除验证码，须重新发码才能继续。</p>
     */
    public boolean verifyCode(String email, String code) {
        return verifyCode(email, code, VERIFICATION_PURPOSE_REGISTER, true);
    }

    /**
     * 验证密码重置验证码，并限制同一邮箱短时间内的错误尝试次数。
     */
    public boolean verifyResetPasswordCode(String email, String code) {
        return verifyCode(email, code, VERIFICATION_PURPOSE_PASSWORD_RESET, true);
    }

    private boolean verifyCode(String email, String code, String purpose, boolean limitAttempts) {
        if (email == null || code == null) {
            return false;
        }

        if (limitAttempts && isVerificationAttemptBlocked(purpose, email)) {
            logger.warn("验证码错误次数过多，暂时拒绝验证: {}, purpose={}", email, purpose);
            return false;
        }

        String key = verificationCodeKey(purpose, email);
        String storedCode = redisService.get(key);
        
        if (storedCode == null) {
            if (limitAttempts) {
                recordFailedVerificationAttempt(purpose, email);
            }
            logger.warn("未找到邮箱对应的验证码: {}, purpose={}", email, purpose);
            return false;
        }
        
        if (storedCode.equals(code)) {
            redisService.del(key);
            redisService.del(verificationCodeAttemptKey(purpose, email));
            logger.info("验证码验证成功: {}, purpose={}", email, purpose);
            return true;
        } else {
            if (limitAttempts) {
                long attempts = recordFailedVerificationAttempt(purpose, email);
                if (attempts >= VERIFICATION_CODE_MAX_ATTEMPTS) {
                    redisService.del(key);
                    logger.warn("验证码错误次数达到上限，已删除验证码: {}, purpose={}", email, purpose);
                }
            }
            logger.warn("验证码不匹配: {}, purpose={}", email, purpose);
            return false;
        }
    }

    private String verificationCodeKey(String purpose, String email) {
        return VERIFICATION_CODE_PREFIX + purpose + ":" + normalizeEmail(email);
    }

    private String verificationCodeAttemptKey(String purpose, String email) {
        return VERIFICATION_CODE_ATTEMPT_PREFIX + purpose + ":" + normalizeEmail(email);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private boolean isVerificationAttemptBlocked(String purpose, String email) {
        String attempts = redisService.get(verificationCodeAttemptKey(purpose, email));
        if (attempts == null || attempts.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(attempts) >= VERIFICATION_CODE_MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            redisService.del(verificationCodeAttemptKey(purpose, email));
            return false;
        }
    }

    private long recordFailedVerificationAttempt(String purpose, String email) {
        String key = verificationCodeAttemptKey(purpose, email);
        Object result = redisService.eval(
                "local count = redis.call('INCR', KEYS[1]) " +
                        "if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end " +
                        "return count",
                new String[]{key},
                new String[]{String.valueOf(VERIFICATION_CODE_EXPIRY)}
        );
        if (result instanceof Number) {
            return ((Number) result).longValue();
        }
        return 0;
    }

    private boolean emailExists(String email) {
        return emailExistsWithLog(email, "检查邮箱存在性失败:");
    }

    /**
     * 根据邮箱检查用户是否存在（公开方法，用于密码重置）
     */
    public boolean userExistsByEmail(String email) {
        return emailExistsWithLog(email, "检查用户邮箱失败:");
    }

    /**
     * 查询邮箱是否存在；失败日志前缀由调用方传入以保持各自原有文案。
     */
    private boolean emailExistsWithLog(String email, String errorLogPrefix) {
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error(errorLogPrefix + " {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * 通过 NekoAuth 验证邮箱验证码并重置密码；成功后撤销 NekoMusic 本地会话。
     */
    public boolean resetPasswordWithCode(String email, String code, String newPassword) {
        requireBridge().resetPassword(email, code, newPassword);
        findUserIdByEmail(email).ifPresent(this::revokeAllTokensForUser);
        logger.info("NekoAuth 密码重置成功，已撤销本地会话: {}", email);
        return true;
    }

    public Optional<Integer> findUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            logger.error("按邮箱查询用户 ID 失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
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
        User user = findUserByAuthId(introspection.get().userId());
        return user == null ? Optional.empty() : Optional.of(user.id());
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

    public record LoginSession(User user, String accessToken) {
    }

}
