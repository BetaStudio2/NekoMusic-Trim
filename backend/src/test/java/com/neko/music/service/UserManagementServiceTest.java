package com.neko.music.service;

import com.neko.music.database.UserAccountStore;
import com.neko.music.model.Admin;
import com.neko.music.model.AdminAccount;
import com.neko.music.model.UserAccount;
import com.neko.music.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserManagementServiceTest {

    @Test
    void nonSuperAdminOnlySeesOwnAdminAccount() throws Exception {
        FakeStore store = new FakeStore();
        store.admins.add(new AdminAccount(1, "root", "root@example.com", "super_admin", "2026-01-01"));
        store.admins.add(new AdminAccount(2, "auditor", "auditor@example.com", "auditor", "2026-01-02"));
        UserManagementService service = new UserManagementService(store, new RecordingRevoker());

        List<AdminAccount> visible = service.listAdmins(admin(2, "auditor"));

        assertEquals(1, visible.size());
        assertEquals(2, visible.get(0).id());

        List<AdminAccount> adminVisible = service.listAdmins(admin(2, "admin"));
        assertEquals(1, adminVisible.size());
        assertEquals(2, adminVisible.get(0).id());
    }

    @Test
    void ordinaryAdminCannotCreateAnotherAdmin() {
        UserManagementService service = new UserManagementService(new FakeStore(), new RecordingRevoker());

        UserManagementException error = assertThrows(UserManagementException.class,
                () -> service.createAdmin(admin(2, "admin"), "newadmin", "new@example.com", "secret12", "admin"));

        assertEquals(UserManagementException.Kind.FORBIDDEN, error.kind());
        assertEquals("common.forbidden", error.messageKey());
    }

    @Test
    void superAdminCreatesAdminWithHashedPassword() throws Exception {
        FakeStore store = new FakeStore();
        UserManagementService service = new UserManagementService(store, new RecordingRevoker());

        service.createAdmin(admin(1, "super_admin"), "  New.Admin  ", " NEW@Example.COM ",
                "secret12", "auditor");

        assertEquals(1, store.admins.size());
        AdminAccount created = store.admins.get(0);
        assertEquals("New.Admin", created.username());
        assertEquals("new@example.com", created.email());
        assertEquals("auditor", created.role());
        assertNotEquals("secret12", store.createdAdminHash);
        assertTrue(store.createdAdminHash.startsWith("$argon2"));
    }

    @Test
    void adminMayOnlyChangeOwnPassword() {
        FakeStore store = new FakeStore();
        UserManagementService service = new UserManagementService(store, new RecordingRevoker());

        UserManagementException error = assertThrows(UserManagementException.class,
                () -> service.updateAdminPassword(admin(2, "admin"), 3, "secret12"));

        assertEquals(UserManagementException.Kind.FORBIDDEN, error.kind());
        assertEquals("admin.account.edit_self_only", error.messageKey());
    }

    @Test
    void changingAdminPasswordRevokesAllSessions() throws Exception {
        FakeStore store = new FakeStore();
        RecordingRevoker revoker = new RecordingRevoker();
        UserManagementService service = new UserManagementService(store, revoker);

        service.updateAdminPassword(admin(1, "super_admin"), 3, "secret12");

        assertEquals(List.of(3), revoker.admins);
        assertNotEquals("old", store.adminPasswordHash);
    }

    @Test
    void cannotDeleteSelfOrLastSuperAdmin() {
        FakeStore store = new FakeStore();
        store.adminDeletionResult = UserAccountStore.AdminDeletionResult.LAST_SUPER_ADMIN;
        UserManagementService service = new UserManagementService(store, new RecordingRevoker());

        UserManagementException self = assertThrows(UserManagementException.class,
                () -> service.deleteAdmin(admin(1, "super_admin"), 1));
        assertEquals("admin.account.delete_self_forbidden", self.messageKey());

        UserManagementException last = assertThrows(UserManagementException.class,
                () -> service.deleteAdmin(admin(2, "super_admin"), 1));
        assertEquals("admin.account.last_super_forbidden", last.messageKey());
    }

    @Test
    void regularUserEditRequiresAFieldAndRejectsInvalidVip() {
        FakeStore store = new FakeStore();
        UserManagementService service = new UserManagementService(store, new RecordingRevoker());

        UserManagementException empty = assertThrows(UserManagementException.class,
                () -> service.updateUser(admin(2, "admin"), 7, "  ", false, null));
        assertEquals("admin.user.update_fields_required", empty.messageKey());

        UserManagementException vip = assertThrows(UserManagementException.class,
                () -> service.updateUser(admin(2, "admin"), 7, null, true, "not-a-date"));
        assertEquals("admin.user.invalid_vip", vip.messageKey());
    }

    @Test
    void passwordChangeDelegatesToAuthAndRevokesButVipOnlyDoesNotLogOut() throws Exception {
        FakeStore store = new FakeStore();
        RecordingRevoker revoker = new RecordingRevoker();
        RecordingAuthBridge authBridge = new RecordingAuthBridge();
        UserManagementService service = new UserManagementService(store, revoker, authBridge);

        service.updateUser(admin(2, "admin"), 7, "secret12", true, "2027-01-01T00:00:00Z");

        assertEquals(List.of("listener@example.com:secret12"), authBridge.adminResets);
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), store.vip.toInstant());
        assertEquals(List.of(7), revoker.users);

        service.updateUser(admin(2, "admin"), 7, null, true, "2028-01-01T00:00:00+08:00");
        assertEquals(List.of(7), revoker.users, "只改会员时间不应吊销登录会话");
    }

    @Test
    void passwordWhitespaceIsPreservedWhenDelegatingToAuth() throws Exception {
        FakeStore store = new FakeStore();
        RecordingAuthBridge authBridge = new RecordingAuthBridge();
        UserManagementService service = new UserManagementService(store, new RecordingRevoker(), authBridge);

        service.updateUser(admin(2, "admin"), 7, " secret12 ", false, null);

        assertEquals(List.of("listener@example.com: secret12 "), authBridge.adminResets);
    }

    @Test
    void ownNicknameChangeTrimsAndValidates() throws Exception {
        FakeStore store = new FakeStore();
        RecordingAuthBridge authBridge = new RecordingAuthBridge();
        UserManagementService service = new UserManagementService(store, new RecordingRevoker(), authBridge);

        String saved = service.changeUserNickname(7, "  新昵称  ");
        assertEquals("新昵称", saved);
        assertEquals("listener", store.nickname, "昵称不应再写入本地数据库");
        assertEquals(List.of("listener@example.com:新昵称"), authBridge.nicknameChanges);

        UserManagementException invalid = assertThrows(UserManagementException.class,
                () -> service.changeUserNickname(7, "x".repeat(33)));
        assertTrue(invalid.messageKey().contains("昵称长度"));
    }

    @Test
    void missingUserKeepsLegacySelfServiceFailures() {
        UserManagementService service = new UserManagementService(
                new FakeStore(), new RecordingRevoker(), new RecordingAuthBridge());

        UserManagementException profile = assertThrows(UserManagementException.class,
                () -> service.getUserProfile(99));
        assertEquals(UserManagementException.Kind.NOT_FOUND, profile.kind());
        assertEquals("user.not_exists", profile.messageKey());

        UserManagementException nickname = assertThrows(UserManagementException.class,
                () -> service.changeUserNickname(99, "listener"));
        assertEquals("user.not_exists", nickname.messageKey());

    }

    @Test
    void vipParserTreatsShanghaiLocalTimeAsUtcPlusEight() throws Exception {
        Timestamp parsed = UserManagementService.parseVipExpiresAt("2027-01-01T00:00:00");

        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), parsed.toInstant());
        assertEquals(null, UserManagementService.parseVipExpiresAt("  "));
    }

    @Test
    void createUserRequiresSuperAdminAndUniqueEmail() throws Exception {
        FakeStore store = new FakeStore();
        UserManagementService service = new UserManagementService(
                store, new RecordingRevoker(), new RecordingAuthBridge());

        UserManagementException forbidden = assertThrows(UserManagementException.class,
                () -> service.createUser(admin(2, "admin"), "listener", "listener@example.com", "secret12"));
        assertEquals("admin.user.create_forbidden", forbidden.messageKey());

        store.userEmailTaken = true;
        UserManagementException conflict = assertThrows(UserManagementException.class,
                () -> service.createUser(admin(1, "super_admin"), "listener", "listener@example.com", "secret12"));
        assertEquals("admin.user.email_exists", conflict.messageKey());
        assertTrue(store.users.isEmpty());
    }

    @Test
    void accountCreationRejectsWhitespaceOnlyPassword() {
        UserManagementService service = new UserManagementService(new FakeStore(), new RecordingRevoker());

        UserManagementException adminError = assertThrows(UserManagementException.class,
                () -> service.createAdmin(admin(1, "super_admin"), "newadmin", "new@example.com",
                        "      ", "admin"));
        assertEquals("admin.account.password_too_short", adminError.messageKey());

        UserManagementException userError = assertThrows(UserManagementException.class,
                () -> service.createUser(admin(1, "super_admin"), "listener", "listener@example.com",
                        "      "));
        assertEquals("admin.user.password_too_short", userError.messageKey());
    }

    private static Admin admin(int id, String role) {
        return new Admin(id, "actor" + id, "hash", "actor@example.com", true, role,
                System.currentTimeMillis(), 0L);
    }

    private static final class RecordingRevoker implements UserManagementService.SessionRevoker {
        private final List<Integer> admins = new ArrayList<>();
        private final List<Integer> users = new ArrayList<>();

        @Override
        public void revokeAdmin(int adminId) {
            admins.add(adminId);
        }

        @Override
        public void revokeUser(int userId) {
            users.add(userId);
        }
    }

    private static class RecordingAuthBridge extends NekoAuthAccountService {
        private final List<String> adminResets = new ArrayList<>();
        private final List<String> nicknameChanges = new ArrayList<>();

        RecordingAuthBridge() {
            super(null, null);
        }

        @Override
        public void adminResetPassword(String email, String newPassword) {
            adminResets.add(email + ":" + newPassword);
        }

        @Override
        public void updateNickname(String email, String nickname) {
            nicknameChanges.add(email + ":" + nickname);
        }
    }

    private static final class FakeStore implements UserAccountStore {
        private final List<AdminAccount> admins = new ArrayList<>();
        private final List<UserAccount> users = new ArrayList<>();
        private String createdAdminHash;
        private String adminPasswordHash = "old";
        private Timestamp vip;
        private String nickname = "listener";
        private boolean userEmailTaken;
        private AdminDeletionResult adminDeletionResult = AdminDeletionResult.DELETED;

        @Override
        public List<AdminAccount> listAdmins() {
            return List.copyOf(admins);
        }

        @Override
        public Optional<AdminAccount> findAdmin(int id) {
            return id == 3
                    ? Optional.of(new AdminAccount(3, "target", "target@example.com", "admin", "2026-01-01"))
                    : admins.stream().filter(a -> a.id() == id).findFirst();
        }

        @Override
        public boolean adminUsernameExists(String username) {
            return admins.stream().anyMatch(a -> a.username().equals(username));
        }

        @Override
        public void createAdmin(String username, String passwordHash, String email, String role, long createdAt) {
            createdAdminHash = passwordHash;
            admins.add(new AdminAccount(10 + admins.size(), username, email, role, "2026-01-01"));
        }

        @Override
        public boolean updateAdminPassword(int id, String passwordHash) {
            if (findAdmin(id).isEmpty()) {
                return false;
            }
            adminPasswordHash = passwordHash;
            return true;
        }

        @Override
        public AdminDeletionResult deleteAdmin(int id) {
            if (adminDeletionResult != AdminDeletionResult.DELETED) {
                return adminDeletionResult;
            }
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
            if (id != 7) {
                return Optional.empty();
            }
            return Optional.of(new UserProfile(7, nickname, "listener@example.com",
                    "2026-01-01", vip));
        }

        @Override
        public boolean userEmailExists(String email) {
            return userEmailTaken;
        }

        @Override
        public Optional<Long> findUserAuthIdByEmail(String email) {
            return userEmailTaken ? Optional.of(41L) : Optional.empty();
        }

        @Override
        public void createUser(String email) {
            users.add(new UserAccount(20 + users.size(), null, email, "2026-01-01", false, null));
        }

        @Override
        public boolean updateUserVip(int id, Timestamp vipExpiresAt) {
            if (findUser(id).isEmpty()) {
                return false;
            }
            vip = vipExpiresAt;
            return true;
        }

        @Override
        public boolean deleteUser(int id) {
            return users.removeIf(u -> u.id() == id);
        }
    }
}
