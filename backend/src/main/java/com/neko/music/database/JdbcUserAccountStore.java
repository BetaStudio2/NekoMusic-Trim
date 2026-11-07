package com.neko.music.database;

import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.util.DbTimeUtil;
import com.neko.music.util.VipUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户管理的 JDBC 实现。所有多步变更都在同一事务内完成。
 */
public final class JdbcUserAccountStore implements UserAccountStore {

    private final DatabaseManager databaseManager;

    public JdbcUserAccountStore(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public List<AdminAccount> listAdmins() throws SQLException {
        String sql = "SELECT id, username, email, role, created_at FROM admins ORDER BY created_at DESC, id DESC";
        List<AdminAccount> accounts = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                long createdAt = rs.getLong("created_at");
                accounts.add(new AdminAccount(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("role"),
                        new Timestamp(createdAt).toString()));
            }
        }
        return accounts;
    }

    @Override
    public Optional<AdminAccount> findAdmin(int id) throws SQLException {
        String sql = "SELECT id, username, email, role, created_at FROM admins WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AdminAccount(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("role"),
                        new Timestamp(rs.getLong("created_at")).toString()));
            }
        }
    }

    @Override
    public boolean adminUsernameExists(String username) throws SQLException {
        return exists("SELECT 1 FROM admins WHERE username = ? LIMIT 1", username);
    }

    @Override
    public void createAdmin(String username, String passwordHash, String email, String role, long createdAt)
            throws SQLException {
        String sql = """
                INSERT INTO admins (username, password_hash, email, active, role, created_at)
                VALUES (?, ?, ?, TRUE, ?, ?)
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, passwordHash);
            stmt.setString(3, email);
            stmt.setString(4, role);
            stmt.setLong(5, createdAt);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean updateAdminPassword(int id, String passwordHash) throws SQLException {
        String sql = "UPDATE admins SET password_hash = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, passwordHash);
            stmt.setInt(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    @Override
    public AdminDeletionResult deleteAdmin(int id) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 先按固定顺序锁住全部超级管理员行，再锁目标行：否则两个超管并发删除时
                // 容易各自持有目标行后再去等对方，形成死锁。
                int superAdminCount = countSuperAdminsForUpdate(conn);
                String role = selectAdminRoleForUpdate(conn, id);
                if (role == null) {
                    conn.rollback();
                    return AdminDeletionResult.NOT_FOUND;
                }
                if ("super_admin".equals(role) && superAdminCount <= 1) {
                    conn.rollback();
                    return AdminDeletionResult.LAST_SUPER_ADMIN;
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM admins WHERE id = ?")) {
                    stmt.setInt(1, id);
                    if (stmt.executeUpdate() == 0) {
                        conn.rollback();
                        return AdminDeletionResult.NOT_FOUND;
                    }
                }
                conn.commit();
                return AdminDeletionResult.DELETED;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public List<UserAccount> listUsers() throws SQLException {
        String sql = """
                SELECT id, auth_user_id, email, created_at, vip_expires_at
                FROM users ORDER BY created_at DESC, id DESC
                """;
        List<UserAccount> users = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp vip = rs.getTimestamp("vip_expires_at");
                boolean hasVip = !rs.wasNull();
                users.add(new UserAccount(
                        rs.getInt("id"),
                        null,
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toString(),
                        hasVip && VipUtil.isVipActiveNow(vip),
                        hasVip ? vip.toInstant().toString() : null,
                        nullableLong(rs, "auth_user_id")));
            }
        }
        return users;
    }

    @Override
    public Optional<UserAccount> findUser(int id) throws SQLException {
        String sql = """
                SELECT id, auth_user_id, email, created_at, vip_expires_at FROM users WHERE id = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp vip = rs.getTimestamp("vip_expires_at");
                boolean hasVip = !rs.wasNull();
                return Optional.of(new UserAccount(
                        rs.getInt("id"),
                        null,
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toString(),
                        hasVip && VipUtil.isVipActiveNow(vip),
                        hasVip ? vip.toInstant().toString() : null,
                        nullableLong(rs, "auth_user_id")));
            }
        }
    }

    @Override
    public Optional<UserProfile> findUserProfile(int id) throws SQLException {
        String sql = """
                SELECT id, auth_user_id, email, created_at, vip_expires_at FROM users WHERE id = ?
                """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp vip = rs.getTimestamp("vip_expires_at");
                boolean hasVip = !rs.wasNull();
                return Optional.of(new UserProfile(
                        rs.getInt("id"),
                        null,
                        rs.getString("email"),
                        DbTimeUtil.formatStoredWallClock(rs.getString("created_at")),
                        hasVip ? vip : null,
                        nullableLong(rs, "auth_user_id")));
            }
        }
    }

    @Override
    public boolean userEmailExists(String email) throws SQLException {
        return exists("SELECT 1 FROM users WHERE email = ? LIMIT 1", email);
    }

    @Override
    public Optional<Long> findUserAuthIdByEmail(String email) throws SQLException {
        String sql = "SELECT auth_user_id FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long value = rs.getLong("auth_user_id");
                return rs.wasNull() ? Optional.empty() : Optional.of(value);
            }
        }
    }

    @Override
    public void bindUserAuthId(String email, long authUserId) throws SQLException {
        String sql = "UPDATE users SET auth_user_id = ? WHERE email = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, authUserId);
            stmt.setString(2, email);
            stmt.executeUpdate();
        }
    }

    @Override
    public void createUser(String email) throws SQLException {
        String sql = """
                INSERT INTO users (email, created_at)
                VALUES (?, %s)
                """.formatted(DbTimeUtil.SQL_NOW_SHANGHAI);
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean updateUserVip(int id, Timestamp vipExpiresAt) throws SQLException {
        String sql = "UPDATE users SET vip_expires_at = ? WHERE id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (vipExpiresAt == null) {
                stmt.setNull(1, Types.TIMESTAMP);
            } else {
                stmt.setTimestamp(1, vipExpiresAt);
            }
            stmt.setInt(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    @Override
    public boolean deleteUser(int id) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                    lock.setInt(1, id);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                    }
                }
                // 音乐是平台公共资产，不应跟着上传者一起删除；旧表的外键没有 ON DELETE 约束。
                try (PreparedStatement detach = conn.prepareStatement(
                        "UPDATE music SET upload_user_id = NULL WHERE upload_user_id = ?")) {
                    detach.setInt(1, id);
                    detach.executeUpdate();
                }
                int removed;
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    delete.setInt(1, id);
                    removed = delete.executeUpdate();
                }
                if (removed == 0) {
                    conn.rollback();
                    return false;
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private boolean exists(String sql, String value) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, value);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Optional<String> findUserColumn(String sql, int id) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static String selectAdminRoleForUpdate(Connection conn, int id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT role FROM admins WHERE id = ? FOR UPDATE")) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("role") : null;
            }
        }
    }

    private static int countSuperAdminsForUpdate(Connection conn) throws SQLException {
        String sql = "SELECT id FROM admins WHERE role = 'super_admin' ORDER BY id FOR UPDATE";
        int count = 0;
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                count++;
            }
        }
        return count;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
