package com.neko.music.service;

import com.neko.music.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把旧版 NekoMusic 本地头像一次性迁移到 NekoAuth。
 *
 * <p>只处理已绑定 {@code auth_user_id} 且仍有本地头像路径的用户。上传成功后清空本地路径并删除旧文件；
 * 失败时保留原数据，下次启动重试。</p>
 */
public final class NekoAuthAvatarMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(NekoAuthAvatarMigrationService.class);

    private final DatabaseManager databaseManager;
    private final NekoAuthAccountService accountService;
    private final Path avatarRoot = Paths.get("avatars").toAbsolutePath().normalize();

    public NekoAuthAvatarMigrationService(DatabaseManager databaseManager,
                                          NekoAuthAccountService accountService) {
        this.databaseManager = databaseManager;
        this.accountService = accountService;
    }

    public void migrateAsync() {
        if (databaseManager == null || accountService == null || !accountService.isConfigured()) {
            return;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nekoauth-avatar-migration");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::migrate);
        executor.shutdown();
    }

    void migrate() {
        List<PendingAvatar> pending;
        try {
            pending = loadPending();
        } catch (Exception exception) {
            logger.warn("加载待迁移头像失败: {}", exception.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }

        int migrated = 0;
        int failed = 0;
        for (PendingAvatar avatar : pending) {
            Path file = resolveWithinRoot(avatar.avatarPath());
            if (file == null || !Files.isRegularFile(file)) {
                logger.warn("跳过不存在或越界的本地头像: userId={} path={}",
                        avatar.localUserId(), avatar.avatarPath());
                failed++;
                continue;
            }
            try {
                byte[] localBytes = Files.readAllBytes(file);
                accountService.uploadAvatar(avatar.authUserId(), file);
                byte[] remoteBytes = accountService.fetchAvatar(avatar.authUserId());
                if (!MessageDigest.isEqual(sha256(localBytes), sha256(remoteBytes))) {
                    throw new IllegalStateException("NekoAuth 头像内容校验不一致");
                }
                clearLocalAvatar(avatar.localUserId());
                try {
                    Files.deleteIfExists(file);
                } catch (Exception exception) {
                    logger.warn("头像已迁移但本地文件删除失败: {}", file);
                }
                migrated++;
            } catch (Exception exception) {
                failed++;
                logger.warn("头像迁移失败: localUserId={} authUserId={} error={}",
                        avatar.localUserId(), avatar.authUserId(), exception.getMessage());
            }
        }
        logger.info("NekoAuth 头像迁移完成: 成功={} 失败={} 总数={}", migrated, failed, pending.size());
    }

    private List<PendingAvatar> loadPending() throws Exception {
        String sql = """
                SELECT id, auth_user_id, avatar
                FROM users
                WHERE auth_user_id IS NOT NULL AND avatar IS NOT NULL AND avatar <> ''
                """;
        List<PendingAvatar> result = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.add(new PendingAvatar(
                        resultSet.getInt("id"),
                        resultSet.getLong("auth_user_id"),
                        resultSet.getString("avatar")));
            }
        }
        return result;
    }

    private void clearLocalAvatar(int localUserId) throws Exception {
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE users SET avatar = NULL WHERE id = ?")) {
            statement.setInt(1, localUserId);
            statement.executeUpdate();
        }
    }

    private Path resolveWithinRoot(String avatarPath) {
        try {
            Path path = Paths.get(avatarPath);
            Path resolved = path.isAbsolute()
                    ? path.normalize()
                    : Paths.get("").toAbsolutePath().resolve(path).normalize();
            return resolved.startsWith(avatarRoot) ? resolved : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    record PendingAvatar(int localUserId, long authUserId, String avatarPath) {
    }
}
