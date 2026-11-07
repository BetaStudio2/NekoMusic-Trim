package com.neko.music.handlers;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserProfileHandlersTest {

    @Test
    void userInfoReturnsProfileWithPrivateNoStoreCache() throws Exception {
        FakeStore store = new FakeStore();
        store.vipExpiresAt = Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"));
        UserInfoHandler handler = new UserInfoHandler(
                new UserManagementService(store, new RecordingRevoker()), request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doGet(request(null), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"nickname\":\"listener\""));
        assertTrue(capture.body().contains("\"isVip\":true"));
        assertFalse(capture.body().contains("password"));
    }

    @Test
    void userInfoMissingUserIsNotFound() throws Exception {
        UserInfoHandler handler = new UserInfoHandler(
                new UserManagementService(new FakeStore(), new RecordingRevoker()), request -> 99);
        CapturingResponse capture = new CapturingResponse();

        handler.doGet(request(null), responseProxy(capture));

        assertEquals(404, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("用户不存在") || capture.body().contains("user.not_exists"));
    }

    private static HttpServletRequest request(String body) {
        return proxy(HttpServletRequest.class, (ignored, method, args) -> switch (method.getName()) {
            case "getReader" -> new BufferedReader(new StringReader(body == null ? "" : body));
            default -> defaultValue(method);
        });
    }

    private static final class RecordingRevoker implements UserManagementService.SessionRevoker {
        private final List<Integer> users = new ArrayList<>();

        @Override
        public void revokeAdmin(int adminId) {
        }

        @Override
        public void revokeUser(int userId) {
            users.add(userId);
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

    private static final class FakeStore implements UserAccountStore {
        private String nickname = "listener";
        private Timestamp vipExpiresAt;

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
            return id == 7 ? Optional.of(new UserAccount(7, nickname, "listener@example.com",
                    "2026-01-01", false, null)) : Optional.empty();
        }

        @Override
        public Optional<UserProfile> findUserProfile(int id) {
            return id == 7 ? Optional.of(new UserProfile(7, nickname, "listener@example.com",
                    "2026-01-01", vipExpiresAt)) : Optional.empty();
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
            if (id != 7) {
                return false;
            }
            this.vipExpiresAt = vipExpiresAt;
            return true;
        }

        @Override
        public boolean deleteUser(int id) {
            return false;
        }
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
