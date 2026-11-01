package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NekoAuthOAuthServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("未验证邮箱不影响 NekoMusic 登录")
    void unverifiedEmailIsAccepted() throws Exception {
        JsonNode userInfo = OBJECT_MAPPER.readTree("""
                {"sub":"10001","email":"user@example.com","email_verified":false}
                """);

        NekoAuthOAuthService.OAuthIdentity identity =
                NekoAuthOAuthService.parseOAuthIdentity(userInfo);

        assertEquals(10001L, identity.authUserId());
        assertEquals("user@example.com", identity.email());
    }

    @Test
    @DisplayName("UserInfo 缺少邮箱时仍然拒绝登录")
    void missingEmailIsRejected() throws Exception {
        JsonNode userInfo = OBJECT_MAPPER.readTree("""
                {"sub":"10001","email_verified":false}
                """);

        NekoAuthOAuthService.OAuthLoginException exception = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.parseOAuthIdentity(userInfo));
        assertEquals("email_missing", exception.code());
    }

    @Test
    @DisplayName("return_to 只接受站内相对路径")
    void returnToAcceptsOnlyLocalPaths() throws Exception {
        assertEquals("/", NekoAuthOAuthService.normalizeReturnTo(null));
        assertEquals("/", NekoAuthOAuthService.normalizeReturnTo("  "));
        assertEquals("/", NekoAuthOAuthService.normalizeReturnTo("/"));
        assertEquals("/favorites?tab=music", NekoAuthOAuthService.normalizeReturnTo("/favorites?tab=music"));
        assertEquals("/playlist/12#tracks", NekoAuthOAuthService.normalizeReturnTo("/playlist/12#tracks"));
    }

    @Test
    @DisplayName("return_to 拒绝绝对地址、协议相对地址、反斜杠与控制字符")
    void returnToRejectsOpenRedirectAndInjection() {
        assertInvalid("https://evil.example/");
        assertInvalid("//evil.example/");
        assertInvalid("/\\evil.example/");
        assertInvalid("/ok\nLocation: https://evil.example/");
        assertInvalid("/" + "a".repeat(513));
    }

    private static void assertInvalid(String value) {
        NekoAuthOAuthService.OAuthLoginException exception = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.normalizeReturnTo(value));
        assertEquals("invalid_return_to", exception.code());
    }
}
