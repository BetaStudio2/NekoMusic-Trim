package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NekoAuthOAuthServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("未验证邮箱不影响 NekoMusic 登录")
    void unverifiedEmailIsAccepted() throws Exception {
        JsonNode userInfo = OBJECT_MAPPER.readTree("""
                {"sub":"10001","email":"user@example.com","name":"猫娘","email_verified":false}
                """);

        NekoAuthOAuthService.OAuthIdentity identity =
                NekoAuthOAuthService.parseOAuthIdentity(userInfo);

        assertEquals(10001L, identity.authUserId());
        assertEquals("user@example.com", identity.email());
        assertEquals("猫娘", identity.nickname());
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

    @Test
    @DisplayName("PKCE S256 与 RFC 7636 测试向量一致")
    void pkceChallengeMatchesRfcVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                NekoAuthOAuthService.pkceChallenge(
                        "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    @DisplayName("OIDC 端点必须与 issuer 同源且使用安全协议")
    void oidcEndpointsMustBeSameOriginAndSecure() throws Exception {
        JsonNode sameOrigin = OBJECT_MAPPER.readTree("""
                {"authorization_endpoint":"https://auth.example.com/oauth2/authorize"}
                """);
        assertEquals(URI.create("https://auth.example.com/oauth2/authorize"),
                NekoAuthOAuthService.parseEndpoint(
                        sameOrigin, "authorization_endpoint",
                        URI.create("https://auth.example.com")));

        JsonNode crossOrigin = OBJECT_MAPPER.readTree("""
                {"authorization_endpoint":"https://evil.example/oauth2/authorize"}
                """);
        NekoAuthOAuthService.OAuthLoginException mismatch = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.parseEndpoint(
                        crossOrigin, "authorization_endpoint",
                        URI.create("https://auth.example.com")));
        assertEquals("oauth_unavailable", mismatch.code());

        JsonNode insecure = OBJECT_MAPPER.readTree("""
                {"authorization_endpoint":"http://auth.example.com/oauth2/authorize"}
                """);
        NekoAuthOAuthService.OAuthLoginException http = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.parseEndpoint(
                        insecure, "authorization_endpoint",
                        URI.create("http://auth.example.com")));
        assertEquals("oauth_unavailable", http.code());
    }

    @Test
    @DisplayName("SSO 客户端类型只允许 web / android / pc")
    void ssoClientTypeIsValidated() throws Exception {
        assertEquals("web", NekoAuthOAuthService.normalizeClient(null));
        assertEquals("android", NekoAuthOAuthService.normalizeClient(" Android "));
        assertEquals("pc", NekoAuthOAuthService.normalizeClient("PC"));
        NekoAuthOAuthService.OAuthLoginException invalid = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.normalizeClient("webview"));
        assertEquals("invalid_client_type", invalid.code());
    }

    @Test
    @DisplayName("账号页面目标只允许 account / register / forgot-password")
    void accountPortalTargetIsValidated() {
        NekoAuthOAuthService service = new NekoAuthOAuthService(
                null, null, null, null, OBJECT_MAPPER);
        NekoAuthOAuthService.OAuthLoginException invalid = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> service.portalUrl("admin"));
        assertEquals("invalid_portal_target", invalid.code());
    }

    @Test
    @DisplayName("Web 登录回跳使用回调地址的站点根")
    void webLoginEntryUsesCallbackOrigin() throws Exception {
        assertEquals("https://music.nekocore.cn/",
                NekoAuthOAuthService.originOf(
                        "https://music.nekocore.cn/oauth/nekocore/callback"));
    }

    private static void assertInvalid(String value) {
        NekoAuthOAuthService.OAuthLoginException exception = assertThrows(
                NekoAuthOAuthService.OAuthLoginException.class,
                () -> NekoAuthOAuthService.normalizeReturnTo(value));
        assertEquals("invalid_return_to", exception.code());
    }
}
