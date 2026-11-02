package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import com.neko.music.util.DbTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 NekoAuth 实时解析昵称。NekoMusic 的 MySQL 不再保存昵称，本地只保留稳定的
 * {@code auth_user_id} 映射；展示时按需批量向 Auth 查询。
 */
public final class NicknameResolver {

    private static final Logger logger = LoggerFactory.getLogger(NicknameResolver.class);
    private static final int MAX_NICKNAME_LENGTH = 32;
    private static final int MAX_AUTH_LOOKUP_BATCH = 200;

    private final DatabaseManager databaseManager;
    private final NekoAuthAccountService nekoAuthAccountService;

    public NicknameResolver(DatabaseManager databaseManager, NekoAuthAccountService nekoAuthAccountService) {
        this.databaseManager = databaseManager;
        this.nekoAuthAccountService = nekoAuthAccountService;
    }

    /** 按 Auth 用户 ID 解析昵称，失败时退回邮箱前缀。 */
    public String resolveByAuthId(Long authUserId, String email) {
        if (authUserId != null && authUserId > 0) {
            String nickname = resolveByAuthIds(List.of(authUserId)).get(authUserId);
            if (nickname != null && !nickname.isBlank()) {
                return nickname;
            }
        }
        String fallback = emailFallback(email);
        return fallback == null ? "听众" : fallback;
    }

    /** 批量解析 Auth 用户 ID；查询失败时返回空表，由调用方决定降级文案。 */
    public Map<Long, String> resolveByAuthIds(Collection<Long> authUserIds) {
        if (authUserIds == null || authUserIds.isEmpty() || nekoAuthAccountService == null) {
            return Map.of();
        }
        List<Long> ids = authUserIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        Map<Long, String> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_AUTH_LOOKUP_BATCH) {
            int to = Math.min(ids.size(), from + MAX_AUTH_LOOKUP_BATCH);
            try {
                result.putAll(nekoAuthAccountService.lookupNicknames(ids.subList(from, to)));
            } catch (NekoAuthAccountService.AccountBridgeException e) {
                logger.warn("从 NekoAuth 解析昵称失败: status={} code={}", e.status(), e.code());
                return Map.of();
            }
        }
        return result;
    }

    /** 单个本地用户 ID → 昵称。 */
    public String resolveByLocalUserId(int localUserId, String fallback) {
        return resolveByLocalUserIds(List.of(localUserId), fallback)
                .getOrDefault(localUserId, fallback);
    }

    /** 批量本地用户 ID → 昵称；Auth 查询按每批最多 200 个 ID 发送。 */
    public Map<Integer, String> resolveByLocalUserIds(Collection<Integer> localUserIds, String fallback) {
        List<Integer> ids = localUserIds == null ? List.of() : localUserIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> result = new HashMap<>();
        if (databaseManager == null) {
            ids.forEach(id -> result.put(id, fallback));
            return result;
        }

        Map<Integer, String> emails = new HashMap<>();
        Map<Integer, Long> authIds = new HashMap<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectUsersByIds(ids.size()))) {
            for (int i = 0; i < ids.size(); i++) {
                stmt.setInt(i + 1, ids.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int localId = rs.getInt("id");
                    emails.put(localId, rs.getString("email"));
                    long authId = rs.getLong("auth_user_id");
                    if (!rs.wasNull()) {
                        authIds.put(localId, authId);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("查询本地用户与 NekoAuth 映射失败: {}", e.getMessage());
            ids.forEach(id -> result.put(id, fallback));
            return result;
        }

        Map<Long, String> names = resolveByAuthIds(new LinkedHashSet<>(authIds.values()));
        for (int id : ids) {
            Long authId = authIds.get(id);
            String nickname = authId == null ? null : names.get(authId);
            if (nickname == null || nickname.isBlank()) {
                nickname = emailFallback(emails.get(id));
            }
            result.put(id, nickname == null || nickname.isBlank() ? fallback : nickname);
        }
        return result;
    }

    /**
     * 在 Auth 中搜索账号，再映射到本地用户 ID。Auth 不可用时退回本地邮箱搜索。
     */
    public List<SearchUser> search(String query) {
        if (query == null || query.isBlank() || databaseManager == null) {
            return List.of();
        }
        String normalized = query.trim();
        if (nekoAuthAccountService != null) {
            try {
                return searchLocalUsers(nekoAuthAccountService.searchAccounts(normalized));
            } catch (NekoAuthAccountService.AccountBridgeException e) {
                logger.warn("通过 NekoAuth 搜索用户失败，降级为本地邮箱搜索: status={} code={}",
                        e.status(), e.code());
            }
        }
        return searchLocalUsersByEmail(normalized);
    }

    private List<SearchUser> searchLocalUsers(List<NekoAuthAccountService.AccountSummary> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        Map<Long, NekoAuthAccountService.AccountSummary> authById = new LinkedHashMap<>();
        for (NekoAuthAccountService.AccountSummary account : accounts) {
            authById.put(account.id(), account);
        }
        List<Long> authIds = List.copyOf(authById.keySet());
        Map<Long, LocalUser> localByAuthId = new HashMap<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectLocalUsersByAuthIds(authIds.size()))) {
            for (int i = 0; i < authIds.size(); i++) {
                stmt.setLong(i + 1, authIds.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long authId = rs.getLong("auth_user_id");
                    if (!rs.wasNull()) {
                        localByAuthId.put(authId, new LocalUser(
                                rs.getInt("id"),
                                rs.getString("email"),
                                DbTimeUtil.formatStoredWallClock(rs.getString("created_at"))));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("映射 NekoAuth 搜索结果到本地用户失败: {}", e.getMessage());
            return List.of();
        }

        List<SearchUser> results = new ArrayList<>();
        for (NekoAuthAccountService.AccountSummary account : accounts) {
            LocalUser local = localByAuthId.get(account.id());
            if (local == null) {
                continue;
            }
            String nickname = account.nickname();
            if (nickname == null || nickname.isBlank()) {
                nickname = emailFallback(local.email());
            }
            results.add(new SearchUser(local.id(), nickname, local.email(), local.createdAt()));
        }
        return results;
    }

    private List<SearchUser> searchLocalUsersByEmail(String query) {
        String sql = "SELECT id, auth_user_id, email, created_at FROM users "
                + "WHERE email LIKE ? ORDER BY created_at DESC, id DESC LIMIT 20";
        List<LocalUser> locals = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "%" + query + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    locals.add(new LocalUser(
                            rs.getInt("id"),
                            rs.getString("email"),
                            DbTimeUtil.formatStoredWallClock(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            logger.error("本地邮箱搜索用户失败: {}", e.getMessage());
            return List.of();
        }
        if (locals.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> names = resolveByLocalUserIds(
                locals.stream().map(LocalUser::id).toList(), "");
        return locals.stream()
                .map(user -> new SearchUser(user.id(), names.getOrDefault(user.id(), ""), user.email(),
                        user.createdAt()))
                .toList();
    }

    private static String selectUsersByIds(int size) {
        return "SELECT id, auth_user_id, email FROM users WHERE id IN (" + placeholders(size) + ")";
    }

    private static String selectLocalUsersByAuthIds(int size) {
        return "SELECT id, auth_user_id, email, created_at FROM users WHERE auth_user_id IN ("
                + placeholders(size) + ")";
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String emailFallback(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int separator = email.indexOf('@');
        String candidate = (separator > 0 ? email.substring(0, separator) : email).trim();
        if (candidate.isEmpty()) {
            return null;
        }
        return candidate.length() <= MAX_NICKNAME_LENGTH
                ? candidate
                : candidate.substring(0, MAX_NICKNAME_LENGTH);
    }

    private record LocalUser(int id, String email, String createdAt) {
    }

    public record SearchUser(int id, String nickname, String email, String createdAt) {
    }
}
