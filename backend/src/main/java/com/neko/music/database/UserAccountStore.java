package com.neko.music.database;

import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理的数据访问边界。Servlet 与业务规则不直接拼 SQL，便于测试和复用。
 */
public interface UserAccountStore {

    enum AdminDeletionResult {
        DELETED,
        NOT_FOUND,
        LAST_SUPER_ADMIN
    }

    record AvatarUpdateResult(boolean updated, String previousAvatarPath) {
        public static AvatarUpdateResult updated(String previousAvatarPath) {
            return new AvatarUpdateResult(true, previousAvatarPath);
        }

        public static AvatarUpdateResult notFound() {
            return new AvatarUpdateResult(false, null);
        }
    }

    List<AdminAccount> listAdmins() throws SQLException;

    Optional<AdminAccount> findAdmin(int id) throws SQLException;

    boolean adminUsernameExists(String username) throws SQLException;

    void createAdmin(String username, String passwordHash, String email, String role, long createdAt)
            throws SQLException;

    boolean updateAdminPassword(int id, String passwordHash) throws SQLException;

    AdminDeletionResult deleteAdmin(int id) throws SQLException;

    List<UserAccount> listUsers() throws SQLException;

    Optional<UserAccount> findUser(int id) throws SQLException;

    Optional<UserProfile> findUserProfile(int id) throws SQLException;

    Optional<String> findUserAvatar(int id) throws SQLException;

    boolean userEmailExists(String email) throws SQLException;

    default Optional<Long> findUserAuthIdByEmail(String email) throws SQLException {
        return Optional.empty();
    }

    default void bindUserAuthId(String email, long authUserId) throws SQLException {
    }

    void createUser(String email) throws SQLException;

    boolean updateUserVip(int id, Timestamp vipExpiresAt) throws SQLException;

    AvatarUpdateResult updateUserAvatar(int id, String avatarPath) throws SQLException;

    boolean deleteUser(int id) throws SQLException;
}
