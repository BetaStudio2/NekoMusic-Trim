package com.neko.music.service;

import com.neko.music.database.UserAccountStore;
import com.neko.music.database.UserAccountStore.AdminDeletionResult;
import com.neko.music.model.Admin;
import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.util.AdminPermissionUtil;
import com.neko.music.util.AdminPermissionUtil.Permission;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理的业务边界：校验、角色策略、口令哈希、会话吊销与事务调度都集中在这里。
 */
public final class UserManagementService {

    private static final Logger logger = LoggerFactory.getLogger(UserManagementService.class);
    private static final int HASH_ITERATIONS = 10;
    private static final int HASH_MEMORY_KIB = 65536;
    private static final int HASH_PARALLELISM = 1;
    private static final int PASSWORD_MIN_LENGTH = 6;
    private static final int PASSWORD_MAX_LENGTH = 30;
    private static final int ADMIN_USERNAME_MAX_LENGTH = 64;
    private static final String ADMIN_USERNAME_PATTERN = "[A-Za-z0-9_.-]+";
    private static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    /** 后台接口只发放普通管理员 / 审核员；最高权限账号由启动引导与运维维护。 */
    private static final Set<String> CREATABLE_ADMIN_ROLES = Set.of("admin", "auditor");

    /** 会话吊销是可降级副作用：Redis 故障不能把已完成的数据库变更回滚。 */
    public interface SessionRevoker {
        void revokeAdmin(int adminId);

        void revokeUser(int userId);
    }

    private final UserAccountStore store;
    private final SessionRevoker sessionRevoker;
    private final NekoAuthAccountService nekoAuthAccountService;
    private final NicknameResolver nicknameResolver;
    private final Argon2 argon2;

    public UserManagementService(UserAccountStore store, SessionRevoker sessionRevoker) {
        this(store, sessionRevoker, null, null);
    }

    public UserManagementService(UserAccountStore store, SessionRevoker sessionRevoker,
                                 NekoAuthAccountService nekoAuthAccountService) {
        this(store, sessionRevoker, nekoAuthAccountService, null);
    }

    public UserManagementService(UserAccountStore store, SessionRevoker sessionRevoker,
                                 NekoAuthAccountService nekoAuthAccountService,
                                 NicknameResolver nicknameResolver) {
        this.store = store;
        this.sessionRevoker = sessionRevoker;
        this.nekoAuthAccountService = nekoAuthAccountService;
        this.nicknameResolver = nicknameResolver;
        this.argon2 = Argon2Factory.create();
    }

    public List<AdminAccount> listAdmins(Admin actor) throws UserManagementException {
        requireActor(actor);
        // 管理员管理（ADMIN_VIEW）只授予超级管理员；其他角色只看到自己的账号，
        // 既有页面仍能展示当前登录者，也不会泄露其他管理员的邮箱与角色。
        if (!AdminPermissionUtil.hasPermission(actor, Permission.ADMIN_VIEW)) {
            return List.of(AdminAccount.from(actor));
        }
        try {
            return store.listAdmins();
        } catch (SQLException e) {
            throw databaseFailure("admin.user.list_failed", e);
        }
    }

    public List<UserAccount> listUsers(Admin actor) throws UserManagementException {
        requirePermission(actor, Permission.USER_VIEW);
        try {
            List<UserAccount> users = store.listUsers();
            if (nicknameResolver == null || users.isEmpty()) {
                return users;
            }
            Map<Integer, String> nicknames = nicknameResolver.resolveByLocalUserIds(
                    users.stream().map(UserAccount::id).toList(), "");
            return users.stream()
                    .map(user -> withNickname(user, nicknames.get(user.id())))
                    .toList();
        } catch (SQLException e) {
            throw databaseFailure("admin.user.list_failed", e);
        }
    }

    public UserProfile getUserProfile(int userId) throws UserManagementException {
        try {
            UserProfile profile = store.findUserProfile(userId)
                    .orElseThrow(() -> UserManagementException.notFound("user.not_exists"));
            if (nicknameResolver == null) {
                return profile;
            }
            String nickname = nicknameResolver.resolveByLocalUserId(userId, profile.nickname());
            return withNickname(profile, nickname);
        } catch (SQLException e) {
            throw databaseFailure("common.internal_error", e);
        }
    }

    public void createAdmin(Admin actor, String username, String email, String rawPassword, String role)
            throws UserManagementException {
        requirePermission(actor, Permission.ADMIN_ADD);

        String normalizedUsername = trim(username);
        if (normalizedUsername.isEmpty()) {
            throw UserManagementException.validation("admin.account.username_required");
        }
        if (!normalizedUsername.matches(ADMIN_USERNAME_PATTERN)
                || normalizedUsername.length() > ADMIN_USERNAME_MAX_LENGTH) {
            throw UserManagementException.validation("admin.account.username_invalid");
        }

        String normalizedEmail = normalizeEmail(email);
        validateRequiredEmail(normalizedEmail, "admin.account.email_required", "admin.account.email_invalid");
        validatePassword(rawPassword, "admin.account.password_too_short");

        String normalizedRole = trim(role).toLowerCase(Locale.ROOT);
        if (normalizedRole.isEmpty()) {
            throw UserManagementException.validation("admin.account.role_required");
        }
        if (!CREATABLE_ADMIN_ROLES.contains(normalizedRole)) {
            throw UserManagementException.validation("admin.account.role_invalid");
        }

        try {
            if (store.adminUsernameExists(normalizedUsername)) {
                throw UserManagementException.conflict("admin.account.username_exists");
            }
            store.createAdmin(
                    normalizedUsername,
                    hash(rawPassword),
                    normalizedEmail,
                    normalizedRole,
                    System.currentTimeMillis());
            logger.info("管理员 {} 创建管理员账号 {}", actor.username(), normalizedUsername);
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw UserManagementException.conflict("admin.account.username_exists");
            }
            throw databaseFailure("admin.account.create_failed", e);
        }
    }

    public void updateAdminPassword(Admin actor, int adminId, String rawPassword)
            throws UserManagementException {
        requireActor(actor);
        if (!AdminPermissionUtil.isSuperAdmin(actor) && actor.id() != adminId) {
            throw UserManagementException.forbidden("admin.account.edit_self_only");
        }
        if (trim(rawPassword).isEmpty()) {
            throw UserManagementException.validation("admin.account.password_required");
        }
        validatePassword(rawPassword, "admin.account.password_too_short");

        try {
            if (store.findAdmin(adminId).isEmpty()) {
                throw UserManagementException.notFound("admin.account.not_found");
            }
            if (!store.updateAdminPassword(adminId, hash(rawPassword))) {
                throw UserManagementException.notFound("admin.account.not_found");
            }
            revokeAdminSessions(adminId);
            logger.info("管理员 {} 重置管理员 {} 的密码", actor.username(), adminId);
        } catch (SQLException e) {
            throw databaseFailure("admin.account.update_failed", e);
        }
    }

    public void deleteAdmin(Admin actor, int adminId) throws UserManagementException {
        requirePermission(actor, Permission.ADMIN_DELETE);
        if (actor.id() == adminId) {
            throw UserManagementException.forbidden("admin.account.delete_self_forbidden");
        }
        try {
            AdminDeletionResult result = store.deleteAdmin(adminId);
            if (result == AdminDeletionResult.NOT_FOUND) {
                throw UserManagementException.notFound("admin.account.not_found");
            }
            if (result == AdminDeletionResult.LAST_SUPER_ADMIN) {
                throw UserManagementException.forbidden("admin.account.last_super_forbidden");
            }
            revokeAdminSessions(adminId);
            logger.info("管理员 {} 删除管理员 {}", actor.username(), adminId);
        } catch (SQLException e) {
            throw databaseFailure("admin.account.delete_failed", e);
        }
    }

    /** 解析前端提交的 ISO-8601 时间；空值表示清除会员。 */
    static Timestamp parseVipExpiresAt(String raw) throws UserManagementException {
        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Timestamp.from(Instant.parse(value));
        } catch (DateTimeException ignored) {
            // 继续尝试带偏移量的 ISO 时间
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeException ignored) {
            // 继续尝试无偏移量的本地日期时间
        }
        try {
            return Timestamp.from(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant());
        } catch (DateTimeException e) {
            throw UserManagementException.validation("admin.user.invalid_vip");
        }
    }

    private void requirePermission(Admin actor, Permission permission) throws UserManagementException {
        requireActor(actor);
        if (!AdminPermissionUtil.hasPermission(actor, permission)) {
            throw UserManagementException.forbidden("common.forbidden");
        }
    }

    private static void requireActor(Admin actor) throws UserManagementException {
        if (actor == null || !actor.active()) {
            throw UserManagementException.forbidden("auth.unauthorized");
        }
    }

    private void validatePassword(String rawPassword, String tooShortKey) throws UserManagementException {
        int length = rawPassword == null ? 0 : rawPassword.length();
        if (rawPassword == null || rawPassword.isBlank() || length < PASSWORD_MIN_LENGTH) {
            throw UserManagementException.validation(tooShortKey);
        }
        if (length > PASSWORD_MAX_LENGTH) {
            throw UserManagementException.validation("admin.common.password_too_long");
        }
    }

    private static void validateRequiredEmail(String email, String requiredKey, String invalidKey)
            throws UserManagementException {
        if (email.isEmpty()) {
            throw UserManagementException.validation(requiredKey);
        }
        if (email.length() > 100 || !email.matches(EMAIL_PATTERN)) {
            throw UserManagementException.validation(invalidKey);
        }
    }

    private String hash(String rawPassword) {
        return argon2.hash(HASH_ITERATIONS, HASH_MEMORY_KIB, HASH_PARALLELISM, rawPassword.toCharArray());
    }

    private void revokeAdminSessions(int adminId) {
        try {
            sessionRevoker.revokeAdmin(adminId);
        } catch (Exception e) {
            logger.error("吊销管理员会话失败 adminId={}", adminId, e);
        }
    }

    private static UserManagementException databaseFailure(String messageKey, SQLException cause) {
        logger.error("用户管理数据库操作失败", cause);
        return UserManagementException.internal(messageKey, cause);
    }

    private static boolean isDuplicateKey(SQLException e) {
        return e instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(e.getSQLState())
                || e.getErrorCode() == 1062;
    }

    private static UserAccount withNickname(UserAccount user, String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.equals(user.nickname())) {
            return user;
        }
        return new UserAccount(user.id(), nickname, user.email(), user.registerTime(), user.vip(),
                user.vipExpiresAt(), user.authUserId());
    }

    private static UserProfile withNickname(UserProfile profile, String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.equals(profile.nickname())) {
            return profile;
        }
        return new UserProfile(profile.id(), nickname, profile.email(), profile.createdAt(),
                profile.vipExpiresAt(), profile.authUserId());
    }

    private static String normalizeEmail(String email) {
        return trim(email).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
