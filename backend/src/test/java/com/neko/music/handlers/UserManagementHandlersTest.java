package com.neko.music.handlers;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.Admin;
import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import com.neko.music.service.NekoAuthAccountService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserManagementHandlersTest {

    @Test
    void auditorListReturnsOnlyOwnAccountAndDisablesCaching() throws Exception {
        FakeStore store = new FakeStore();
        store.admins.add(new AdminAccount(1, "root", "root@example.com", "super_admin", "2026-01-01"));
        UserManagementService service = service(store);
        AdminUserManagementHandler handler = new AdminUserManagementHandler(service, req -> admin(2, "auditor"));
        CapturingResponse capture = new CapturingResponse();

        handler.doGet(request(null, null), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("auditor"));
        assertFalse(capture.body().contains("root@example.com"));
    }

    @Test
    void auditorCannotListRegularUsers() throws Exception {
        UserManagementHandler handler = new UserManagementHandler(service(new FakeStore()),
                req -> admin(2, "auditor"));
        CapturingResponse capture = new CapturingResponse();

        handler.doGet(request(null, null), responseProxy(capture));

        assertEquals(403, capture.status);
        assertTrue(capture.body().contains("common.forbidden") || capture.body().contains("权限不足"));
    }

    @Test
    void regularUserEditWithoutAnyMutableFieldIsRejected() throws Exception {
        UserManagementHandler handler = new UserManagementHandler(service(new FakeStore()),
                req -> admin(2, "admin"));
        CapturingResponse capture = new CapturingResponse();

        handler.doPut(request("/7/edit", "{}"), responseProxy(capture));

        assertEquals(400, capture.status);
        assertTrue(capture.body().contains("admin.user.update_fields_required")
                || capture.body().contains("password"));
    }

    @Test
    void dedicatedCreateUserHandlerCreatesOrdinaryUser() throws Exception {
        FakeStore store = new FakeStore();
        AdminCreateUserHandler handler = new AdminCreateUserHandler(service(store),
                req -> admin(1, "super_admin"));
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(null, "{\"username\":\"listener\",\"email\":\"listener@example.com\","
                + "\"password\":\"secret12\"}"), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals(1, store.users.size());
        assertEquals("listener@example.com", store.users.get(0).email());
        assertEquals(null, store.users.get(0).nickname(), "本地资料不应保存昵称");
        assertTrue(capture.body().contains("\"success\":true"));
    }

    @Test
    void createAdminHandlerRejectsPrivilegeEscalationToSuperAdmin() throws Exception {
        FakeStore store = new FakeStore();
        AdminCreateHandler handler = new AdminCreateHandler(service(store),
                req -> admin(1, "super_admin"));
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request(null, "{\"username\":\"newadmin\",\"email\":\"new@example.com\","
                + "\"password\":\"secret12\",\"role\":\"super_admin\"}"), responseProxy(capture));

        assertEquals(400, capture.status);
        assertTrue(capture.body().contains("管理员角色无效"));
        assertTrue(store.admins.isEmpty());
    }

    @Test
    void malformedTargetPathIsRejectedBeforeServiceCall() throws Exception {
        FakeStore store = new FakeStore();
        UserManagementHandler handler = new UserManagementHandler(service(store), req -> admin(2, "admin"));
        CapturingResponse capture = new CapturingResponse();

        handler.doDelete(request("/not-a-number", null), responseProxy(capture));

        assertEquals(400, capture.status);
        assertEquals(0, store.deleteCalls.get());
    }

    private static UserManagementService service(FakeStore store) {
        return new UserManagementService(store, new UserManagementService.SessionRevoker() {
            @Override
            public void revokeAdmin(int adminId) {
            }

            @Override
            public void revokeUser(int userId) {
            }
        }, new RecordingAuthBridge());
    }

    private static final class RecordingAuthBridge extends NekoAuthAccountService {
        RecordingAuthBridge() {
            super(null, null);
        }

        @Override
        public Account adminCreate(String email, String password, String nickname) {
            return new Account(9001, email, "listener", nickname, "USER", true,
                    "test-access-token", "Bearer", 3600);
        }
    }

    private static Admin admin(int id, String role) {
        return new Admin(id, "actor" + id, "hash", "actor@example.com", true, role,
                System.currentTimeMillis(), 0L);
    }

    private static HttpServletRequest request(String pathInfo, String body) {
        Map<String, Object> attributes = new HashMap<>();
        return proxy(HttpServletRequest.class, (ignored, method, args) -> switch (method.getName()) {
            case "getPathInfo" -> pathInfo;
            case "getReader" -> new BufferedReader(new StringReader(body == null ? "" : body));
            case "setAttribute" -> {
                attributes.put((String) args[0], args[1]);
                yield null;
            }
            case "getAttribute" -> attributes.get((String) args[0]);
            default -> defaultValue(method);
        });
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
        private final List<AdminAccount> admins = new ArrayList<>();
        private final List<UserAccount> users = new ArrayList<>();
        private final AtomicInteger deleteCalls = new AtomicInteger();

        @Override
        public List<AdminAccount> listAdmins() {
            return List.copyOf(admins);
        }

        @Override
        public Optional<AdminAccount> findAdmin(int id) {
            return admins.stream().filter(a -> a.id() == id).findFirst();
        }

        @Override
        public boolean adminUsernameExists(String username) {
            return admins.stream().anyMatch(a -> a.username().equals(username));
        }

        @Override
        public void createAdmin(String username, String passwordHash, String email, String role, long createdAt) {
            admins.add(new AdminAccount(admins.size() + 10, username, email, role, "2026-01-01"));
        }

        @Override
        public boolean updateAdminPassword(int id, String passwordHash) {
            return findAdmin(id).isPresent();
        }

        @Override
        public AdminDeletionResult deleteAdmin(int id) {
            deleteCalls.incrementAndGet();
            return admins.removeIf(a -> a.id() == id) ? AdminDeletionResult.DELETED : AdminDeletionResult.NOT_FOUND;
        }

        @Override
        public List<UserAccount> listUsers() {
            return List.copyOf(users);
        }

        @Override
        public Optional<UserAccount> findUser(int id) {
            if (id == 7) {
                return Optional.of(new UserAccount(7, "listener", "listener@example.com", "2026-01-01", false, null));
            }
            return users.stream().filter(u -> u.id() == id).findFirst();
        }

        @Override
        public Optional<UserProfile> findUserProfile(int id) {
            return findUser(id).map(user -> new UserProfile(
                    user.id(), user.nickname(), user.email(), user.registerTime(), null));
        }

        @Override
        public Optional<String> findUserAvatar(int id) {
            return Optional.empty();
        }

        @Override
        public boolean userEmailExists(String email) {
            return users.stream().anyMatch(u -> u.email().equals(email));
        }

        @Override
        public void createUser(String email) {
            users.add(new UserAccount(users.size() + 20, null, email, "2026-01-01", false, null));
        }

        @Override
        public boolean updateUserVip(int id, Timestamp vipExpiresAt) {
            return findUser(id).isPresent();
        }

        @Override
        public AvatarUpdateResult updateUserAvatar(int id, String avatarPath) {
            return findUser(id).isPresent()
                    ? AvatarUpdateResult.updated(null)
                    : AvatarUpdateResult.notFound();
        }

        @Override
        public boolean deleteUser(int id) {
            deleteCalls.incrementAndGet();
            return users.removeIf(u -> u.id() == id);
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
