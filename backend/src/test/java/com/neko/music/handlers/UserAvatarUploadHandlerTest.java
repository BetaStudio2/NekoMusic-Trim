package com.neko.music.handlers;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAvatarUploadHandlerTest {

    @TempDir
    Path avatarDir;

    @Test
    void successfulUploadDeletesPreviousAvatarOnlyAfterDatabaseUpdate() throws Exception {
        Path oldAvatar = Files.writeString(avatarDir.resolve("old.png"), "old");
        FakeStore store = new FakeStore();
        store.avatarPath = oldAvatar.toString();
        UserAvatarUploadHandler handler = handler(store);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(pngPart()), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"success\":true"));
        assertFalse(Files.exists(oldAvatar));
        assertTrue(Files.exists(Path.of(store.avatarPath)));
        assertEquals(1, avatarFileCount());
    }

    @Test
    void failedDatabaseUpdateKeepsOldAvatarAndRemovesNewUpload() throws Exception {
        Path oldAvatar = Files.writeString(avatarDir.resolve("old.png"), "old");
        FakeStore store = new FakeStore();
        store.avatarPath = oldAvatar.toString();
        store.failAvatarUpdate = true;
        UserAvatarUploadHandler handler = handler(store);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(pngPart()), responseProxy(capture));

        assertEquals(500, capture.status);
        assertTrue(capture.body().contains("更新数据库失败") || capture.body().contains("db_update_failed"));
        assertTrue(Files.exists(oldAvatar));
        assertEquals(1, avatarFileCount());
    }

    @Test
    void missingLoginDoesNotWriteFiles() throws Exception {
        FakeStore store = new FakeStore();
        UserAvatarUploadHandler handler = new UserAvatarUploadHandler(
                new UserManagementService(store, NOOP_REVOKER), request -> null, avatarDir);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(pngPart()), responseProxy(capture));

        assertEquals(401, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertEquals(0, avatarFileCount());
    }

    private UserAvatarUploadHandler handler(FakeStore store) {
        return new UserAvatarUploadHandler(
                new UserManagementService(store, NOOP_REVOKER), request -> 7, avatarDir);
    }

    private long avatarFileCount() throws IOException {
        try (Stream<Path> files = Files.list(avatarDir)) {
            return files.count();
        }
    }

    private static HttpServletRequest request(Part avatarPart) {
        return proxy(HttpServletRequest.class, (ignored, method, args) -> switch (method.getName()) {
            case "getPart" -> "avatar".equals(args[0]) ? avatarPart : null;
            case "setCharacterEncoding" -> null;
            case "getHeader" -> null;
            case "getParameter" -> null;
            default -> defaultValue(method);
        });
    }

    private static Part pngPart() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes = output.toByteArray();
        return proxy(Part.class, (ignored, method, args) -> switch (method.getName()) {
            case "getSize" -> (long) bytes.length;
            case "getInputStream" -> new ByteArrayInputStream(bytes);
            case "getSubmittedFileName" -> "avatar.png";
            case "getContentType" -> "image/png";
            default -> defaultValue(method);
        });
    }

    private static final UserManagementService.SessionRevoker NOOP_REVOKER =
            new UserManagementService.SessionRevoker() {
                @Override
                public void revokeAdmin(int adminId) {
                }

                @Override
                public void revokeUser(int userId) {
                }
            };

    private static final class FakeStore implements UserAccountStore {
        private String avatarPath;
        private boolean failAvatarUpdate;

        @Override
        public List<AdminAccount> listAdmins() {
            return List.of();
        }

        @Override
        public Optional<AdminAccount> findAdmin(int id) {
            return Optional.empty();
        }

        @Override
        public boolean adminUsernameExists(String username) {
            return false;
        }

        @Override
        public void createAdmin(String username, String passwordHash, String email, String role, long createdAt) {
        }

        @Override
        public boolean updateAdminPassword(int id, String passwordHash) {
            return false;
        }

        @Override
        public AdminDeletionResult deleteAdmin(int id) {
            return AdminDeletionResult.NOT_FOUND;
        }

        @Override
        public List<UserAccount> listUsers() {
            return List.of();
        }

        @Override
        public Optional<UserAccount> findUser(int id) {
            return id == 7 ? Optional.of(new UserAccount(7, "listener", "listener@example.com",
                    "2026-01-01", false, null)) : Optional.empty();
        }

        @Override
        public Optional<UserProfile> findUserProfile(int id) {
            return id == 7 ? Optional.of(new UserProfile(7, "listener", "listener@example.com",
                    "2026-01-01", null)) : Optional.empty();
        }

        @Override
        public Optional<String> findUserAvatar(int id) {
            return id == 7 ? Optional.ofNullable(avatarPath) : Optional.empty();
        }

        @Override
        public boolean userEmailExists(String email) {
            return false;
        }

        @Override
        public void createUser(String email) {
        }

        @Override
        public boolean updateUserVip(int id, Timestamp vipExpiresAt) {
            return id == 7;
        }

        @Override
        public AvatarUpdateResult updateUserAvatar(int id, String avatarPath) throws SQLException {
            if (failAvatarUpdate) {
                throw new SQLException("update failed");
            }
            if (id != 7) {
                return AvatarUpdateResult.notFound();
            }
            String previous = this.avatarPath;
            this.avatarPath = avatarPath;
            return AvatarUpdateResult.updated(previous);
        }

        @Override
        public boolean deleteUser(int id) {
            return false;
        }
    }

    private static final class CapturingResponse {
        private final StringWriter output = new StringWriter();
        private final PrintWriter writer = new PrintWriter(output);
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int status = 200;

        private String body() {
            writer.flush();
            return output.toString();
        }
    }

    private static HttpServletResponse responseProxy(CapturingResponse capture) {
        return proxy(HttpServletResponse.class, (ignored, method, args) -> switch (method.getName()) {
            case "getWriter" -> capture.writer;
            case "setStatus" -> {
                capture.status = (Integer) args[0];
                yield null;
            }
            case "setHeader" -> {
                capture.headers.put((String) args[0], (String) args[1]);
                yield null;
            }
            case "setContentType" -> null;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
