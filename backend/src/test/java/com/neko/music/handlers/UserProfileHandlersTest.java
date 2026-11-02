package com.neko.music.handlers;

import com.neko.music.database.UserAccountStore;
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
    void passwordChangeRejectsWrongOldPasswordWithoutRevokingSessions() throws Exception {
        FakeStore store = new FakeStore();
        RecordingRevoker revoker = new RecordingRevoker();
        UserPasswordChangeHandler handler = new UserPasswordChangeHandler(
                new UserManagementService(store, revoker, new RejectingAuthBridge()), request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request("{\"oldPassword\":\"wrong-pass\",\"newPassword\":\"new-pass\"}"),
                responseProxy(capture));

        assertEquals(400, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"error\""));
        assertTrue(capture.body().contains("原密码错误") || capture.body().contains("old_wrong"));
        assertTrue(revoker.users.isEmpty());
    }

    @Test
    void passwordChangeDelegatesToNekoAuthAndRevokesSessions() throws Exception {
        FakeStore store = new FakeStore();
        RecordingRevoker revoker = new RecordingRevoker();
        RecordingAuthBridge authBridge = new RecordingAuthBridge();
        UserPasswordChangeHandler handler = new UserPasswordChangeHandler(
                new UserManagementService(store, revoker, authBridge), request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request("{\"oldPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}"),
                responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"success\":true"));
        assertEquals(List.of(7), revoker.users);
        assertEquals("listener@example.com", authBridge.email);
        assertEquals("old-pass", authBridge.currentPassword);
        assertEquals("new-pass", authBridge.newPassword);
    }

    @Test
    void nicknameChangeReturnsSavedNicknameAndDisablesCaching() throws Exception {
        FakeStore store = new FakeStore();
        RecordingAuthBridge authBridge = new RecordingAuthBridge();
        UserNicknameChangeHandler handler = new UserNicknameChangeHandler(
                new UserManagementService(store, new RecordingRevoker(), authBridge), request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request("{\"nickname\":\"  喵喵  \"}"), responseProxy(capture));

        assertEquals(200, capture.status);
        assertEquals("private, no-store", capture.headers.get("Cache-Control"));
        assertTrue(capture.body().contains("\"nickname\":\"喵喵\""));
        assertEquals("喵喵", authBridge.nickname);
    }

    @Test
    void nicknameChangeRejectsInvalidLength() throws Exception {
        UserNicknameChangeHandler handler = new UserNicknameChangeHandler(
                new UserManagementService(new FakeStore(), new RecordingRevoker(),
                        new RecordingAuthBridge()), request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request("{\"nickname\":\"" + "x".repeat(33) + "\"}"), responseProxy(capture));

        assertEquals(400, capture.status);
        assertTrue(capture.body().contains("昵称长度"));
    }

    @Test
    void nicknameChangePropagatesAuthNicknameFieldError() throws Exception {
        UserNicknameChangeHandler handler = new UserNicknameChangeHandler(
                new UserManagementService(new FakeStore(), new RecordingRevoker(),
                        new RejectingNicknameAuthBridge(
                                new NekoAuthAccountService.AccountBridgeException(
                                        400, "VALIDATION_ERROR", "提交的数据不符合要求", 0,
                                        Map.of("nickname", "昵称不能包含控制字符")))),
                request -> 7);
        CapturingResponse capture = new CapturingResponse();

        handler.doPost(request("{\"nickname\":\"合法昵称\"}"), responseProxy(capture));

        assertEquals(400, capture.status);
        assertTrue(capture.body().contains("昵称不能包含控制字符"));
    }

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
        private String avatarPath;

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
            if (id != 7) {
                return false;
            }
            this.vipExpiresAt = vipExpiresAt;
            return true;
        }

        @Override
        public AvatarUpdateResult updateUserAvatar(int id, String avatarPath) {
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

    private static class RecordingAuthBridge extends NekoAuthAccountService {
        private String email;
        private String currentPassword;
        private String newPassword;
        private String nicknameEmail;
        private String nickname;

        RecordingAuthBridge() {
            super(null, null);
        }

        @Override
        public void changePassword(String email, String currentPassword, String newPassword) {
            this.email = email;
            this.currentPassword = currentPassword;
            this.newPassword = newPassword;
        }

        @Override
        public void updateNickname(String email, String nickname) {
            this.nicknameEmail = email;
            this.nickname = nickname;
        }
    }

    private static final class RejectingAuthBridge extends RecordingAuthBridge {
        @Override
        public void changePassword(String email, String currentPassword, String newPassword) {
            throw new NekoAuthAccountService.AccountBridgeException(
                    401, "INVALID_CREDENTIALS", "当前密码错误", 0);
        }
    }

    private static final class RejectingNicknameAuthBridge extends RecordingAuthBridge {
        private final NekoAuthAccountService.AccountBridgeException exception;

        RejectingNicknameAuthBridge(NekoAuthAccountService.AccountBridgeException exception) {
            this.exception = exception;
        }

        @Override
        public void updateNickname(String email, String nickname) {
            throw exception;
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
