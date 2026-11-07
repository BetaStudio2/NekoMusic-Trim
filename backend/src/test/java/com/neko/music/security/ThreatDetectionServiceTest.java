package com.neko.music.security;

import com.neko.music.model.ThreatAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreatDetectionServiceTest {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";

    private long now;
    private ThreatDetectionService service;

    @BeforeEach
    void setUp() {
        now = 1_700_000_000_000L;
        service = new ThreatDetectionService(() -> now);
    }

    @Test
    @DisplayName("普通成功请求标记为正常")
    void normalRequestIsNormal() {
        ThreatAssessment result = assess("GET", "/api/music/latest", null, null, "api", 200,
                "203.0.113.10", BROWSER_UA, "browser", null);

        assertEquals(ThreatAssessment.NORMAL, result.level());
        assertEquals(0, result.score());
    }

    @Test
    @DisplayName("单次 404 不足以直接判攻击，但注入/扫描特征直接判攻击")
    void signaturesOverrideSingleNotFound() {
        ThreatAssessment missing = assess("GET", "/api/music/latest", null, null, "api", 404,
                "203.0.113.11", BROWSER_UA, "browser", null);
        assertEquals(ThreatAssessment.NORMAL, missing.level());

        ThreatAssessment injection = assess("GET", "/api/music/search", "q=1%27+UNION+SELECT+password+FROM+users",
                null, "api", 400, "203.0.113.12", BROWSER_UA, "browser", null);
        assertEquals(ThreatAssessment.ATTACK, injection.level());
        assertTrue(injection.reasons().contains("SQL"));

        ThreatAssessment probe = assess("GET", "/.env", null, null, "other", 404,
                "203.0.113.13", "sqlmap/1.8", "scanner", null);
        assertEquals(ThreatAssessment.ATTACK, probe.level());
        assertTrue(probe.reasons().contains("扫描"));

        ThreatAssessment successfulProbe = assess("GET", "/.env", null, null, "other", 200,
                "203.0.113.13", "sqlmap/1.8", "scanner", null);
        assertEquals(ThreatAssessment.ATTACK, successfulProbe.level());

        ThreatAssessment successfulInjection = assess("GET", "/api/music/search",
                "q=1%27+UNION+SELECT+password+FROM+users", null, "api", 200,
                "203.0.113.15", BROWSER_UA, "browser", null);
        assertEquals(ThreatAssessment.ATTACK, successfulInjection.level());
        assertTrue(successfulInjection.reasons().contains("SQL"));
    }

    @Test
    @DisplayName("同一来源高频重复请求单一资源判攻击，低于阈值仍正常")
    void singlePathFloodIsAttack() {
        ThreatAssessment beforeFlood = null;
        for (int i = 0; i < 299; i++) {
            beforeFlood = assess("GET", "/api/user/avatar/77", null, null, "api", 304,
                    "2408:8220:2817:4630:9ae:20bf:fb03:a6bf",
                    "NekoMusic-android/20261027", "official_client", null);
        }
        assertEquals(ThreatAssessment.NORMAL, beforeFlood.level());

        ThreatAssessment flood = assess("GET", "/api/user/avatar/77", null, null, "api", 304,
                "2408:8220:2817:4630:9ae:20bf:fb03:a6bf",
                "NekoMusic-android/20261027", "official_client", null);
        assertEquals(ThreatAssessment.ATTACK, flood.level());
        assertTrue(flood.reasons().contains("重复请求同一路径"));
    }

    @Test
    @DisplayName("混刷多个接口时仍按精确路径识别单资源洪泛")
    void singlePathFloodSurvivesMixedTraffic() {
        for (int i = 0; i < 200; i++) {
            assess("GET", "/api/user/avatar/77", null, null, "api", 304,
                    "2408:8220:2817:4630:9ae:20bf:fb03:a6bf",
                    "NekoMusic-android/20261027", "official_client", null);
            assess("GET", "/api/user/uploaded-music", null, null, "api", 200,
                    "2408:8220:2817:4630:9ae:20bf:fb03:a6bf",
                    "NekoMusic-android/20261027", "official_client", null);
        }

        ThreatAssessment result = null;
        for (int i = 0; i < 100; i++) {
            result = assess("GET", "/api/user/avatar/77", null, null, "api", 304,
                    "2408:8220:2817:4630:9ae:20bf:fb03:a6bf",
                    "NekoMusic-android/20261027", "official_client", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("重复请求同一路径"));
    }

    @Test
    @DisplayName("CGNAT 共享 IP 下多个客户端各自刷同一路径不会互相叠加误判")
    void cgnatClientsDoNotShareSinglePathFlood() {
        ThreatAssessment result = null;
        String sharedIp = "100.64.23.9";
        for (int user = 1; user <= 6; user++) {
            for (int i = 0; i < 100; i++) {
                result = assess("GET", "/api/user/avatar/77", null, null, "api", 304,
                        sharedIp, "NekoMusic-android/20261027", "official_client", null,
                        "cgnat-instance-" + user, user);
            }
        }
        assertEquals(ThreatAssessment.NORMAL, result.level());
        assertEquals(0, result.score());
    }

    @Test
    @DisplayName("同一 IP 反复认证失败升级为攻击")
    void authFailureBurstIsAttack() {
        ThreatAssessment result = null;
        for (int i = 0; i < 8; i++) {
            result = assess("POST", "/api/admin/login", null, null, "admin", 401,
                    "203.0.113.20", BROWSER_UA, "browser", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("认证失败"));
    }

    @Test
    @DisplayName("攻击者轮换 IPv4 /16 时仍能在跨来源窗口内识别攻击")
    void ipv4RotationCampaignIsDetected() {
        ThreatAssessment result = null;
        for (int i = 1; i <= 24; i++) {
            result = assess("GET", "/api/probe/" + i, null, null, "api", 404,
                    "198." + i + ".1.1", BROWSER_UA, "browser", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("网段") || result.reasons().contains("指纹")
                || result.reasons().contains("全站"));
    }

    @Test
    @DisplayName("攻击者轮换 IPv6 /32 时仍能识别全站多网段失败")
    void ipv6RotationCampaignIsDetected() {
        ThreatAssessment result = null;
        for (int i = 1; i <= 24; i++) {
            result = assess("GET", "/api/probe/" + i, null, null, "api", 403,
                    "2001:" + Integer.toHexString(i) + "::1", BROWSER_UA, "browser", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("全站") || result.reasons().contains("网段")
                || result.reasons().contains("指纹"));
    }

    @Test
    @DisplayName("静态资源 404 在多个网段轮换时也参与攻击聚类")
    void staticNotFoundRotationIsDetected() {
        ThreatAssessment result = null;
        for (int i = 1; i <= 10; i++) {
            result = assess("GET", "/src/assets/default-cover.png", null, null, "static", 404,
                    "198." + i + ".1.1", BROWSER_UA, "browser", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("路径族") || result.reasons().contains("指纹")
                || result.reasons().contains("全站"));
    }

    @Test
    @DisplayName("低密度 401 跨网段轮换只判疑似，避免令牌过期洪水误报")
    void distributedAuthRotationIsSuspected() {
        ThreatAssessment result = null;
        for (int i = 1; i <= 12; i++) {
            result = assess("GET", "/api/music/file/" + (1000 + i), null, null, "api", 401,
                    "203." + i + ".1.1", "NekoMusic-android/202601024", "official_client", null);
        }
        assertEquals(ThreatAssessment.SUSPECTED, result.level());
        assertTrue(result.reasons().contains("认证失败"));
    }

    @Test
    @DisplayName("同一普通接口来源持续 401 达到高阈值仍判攻击")
    void repeatedAuthFailureFromOneSourceIsAttack() {
        ThreatAssessment result = null;
        for (int i = 0; i < 20; i++) {
            result = assess("GET", "/api/music/file/1000", null, null, "api", 401,
                    "203.0.113.30", "NekoMusic-android/202601024", "official_client", null);
        }
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("认证失败"));
    }

    @Test
    @DisplayName("纯成功流量即使请求量很大也不判攻击")
    void pureSuccessfulTrafficIsNotAttack() {
        ThreatAssessment result = null;
        for (int i = 1; i <= 300; i++) {
            result = assess("GET", "/api/music/file/" + i, null, null, "api", 200,
                    "198.51.100.50", BROWSER_UA, "browser", null);
        }
        assertEquals(ThreatAssessment.NORMAL, result.level());
        assertEquals(0, result.score());
    }

    @Test
    @DisplayName("成功遍历资源并夹带失败探测的攻击簇仍判攻击")
    void successfulProbeClusterIsAttack() {
        ThreatAssessment result = null;
        int path = 1;
        for (int network = 1; network <= 4; network++) {
            String ip = "198." + network + ".1.1";
            for (int i = 0; i < 90; i++) {
                result = assess("GET", "/api/music/file/" + (path++), null, null, "api", 200,
                        ip, BROWSER_UA, "browser", null);
            }
            for (int i = 0; i < 20; i++) {
                result = assess("GET", "/api/music/missing/" + (path++), null, null, "api", 404,
                        ip, BROWSER_UA, "browser", null);
            }
        }
        result = assess("GET", "/api/music/file/" + (path++), null, null, "api", 200,
                "198.4.1.1", BROWSER_UA, "browser", null);
        assertEquals(ThreatAssessment.ATTACK, result.level());
        assertTrue(result.reasons().contains("成功遍历"));
    }

    @Test
    @DisplayName("官方客户端跨网段大量 200 与少量缺曲 404 仍判正常")
    void officialClientHighVolumeWithFewMissingTracksIsNormal() {
        ThreatAssessment result = null;
        int path = 1;
        for (int network = 1; network <= 4; network++) {
            String ip = "198." + network + ".1.1";
            for (int i = 0; i < 80; i++) {
                result = assess("GET", "/api/music/file/" + (path++), null, null, "api", 200,
                        ip, "NekoMusic-android/202601024", "official_client", null);
            }
            for (int i = 0; i < 8; i++) {
                result = assess("GET", "/api/music/file/-100000" + (path++), null, null, "api", 404,
                        ip, "NekoMusic-android/202601024", "official_client", null);
            }
        }
        result = assess("GET", "/api/music/file/" + (path++), null, null, "api", 200,
                "198.4.1.1", "NekoMusic-android/202601024", "official_client", null);
        assertEquals(ThreatAssessment.NORMAL, result.level());
        assertEquals(0, result.score());
    }

    @Test
    @DisplayName("共享 UA 的历史失败不会把后续正常成功请求连带判成攻击")
    void campaignBonusOnlyAppliesToAnomalousRequest() {
        for (int i = 1; i <= 8; i++) {
            assess("GET", "/api/missing/" + i, null, null, "api", 404,
                    "198." + i + ".1.1", BROWSER_UA, "browser", null);
        }
        ThreatAssessment normal = assess("GET", "/api/music/latest", null, null, "api", 200,
                "192.0.2.99", BROWSER_UA, "browser", null);

        assertEquals(ThreatAssessment.NORMAL, normal.level());
        assertEquals(0, normal.score());
    }

    @Test
    @DisplayName("粗粒度网段聚合到 /16 与 IPv6 /32")
    void coarseNetworkScope() {
        assertEquals("198.51.0.0/16", ThreatDetectionService.networkScope("198.51.23.9"));
        assertEquals("2001:db8::/32", ThreatDetectionService.networkScope("2001:db8:1234::1"));
    }

    private ThreatAssessment assess(String method, String path, String query, String body,
                                    String category, int status, String ip, String ua,
                                    String ignoredClientType, String ignoredLegacyField) {
        return assess(method, path, query, body, category, status, ip, ua, ignoredClientType,
                ignoredLegacyField, null, null);
    }

    private ThreatAssessment assess(String method, String path, String query, String body,
                                    String category, int status, String ip, String ua,
                                    String ignoredClientType, String ignoredLegacyField, String ignoredInstanceId,
                                    Integer userId) {
        now += 1000;
        return service.assess(new ThreatDetectionService.RequestFacts(
                now, method, path, query, category, status, ip, ua, userId));
    }
}
