package com.neko.music.seo;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器特征头判定：{@code Accept} 与 {@code Accept-Language} 必须同时存在。
 *
 * <p>{@code Accept-Language} 是硬性要求，缺失一律按环境异常拒绝；且不再接受 {@code Sec-Fetch-*}
 * 作为替代——该组头脚本随手可伪造，单独拿它当证据等于开一个不设防的入口。这条规则决定
 * SEO / SPA 分流（静态页与详情页），因此单独锁住。</p>
 */
class BrowserEvidenceTest {

    @Test
    void requiresBothAcceptAndAcceptLanguage() {
        assertTrue(BrowserEvidence.hasFetchEvidence(request(Map.of(
                "Accept", "application/json, text/plain, */*",
                "Accept-Language", "zh-CN,zh;q=0.9"))));

        // 缺任一项即判环境异常
        assertFalse(BrowserEvidence.hasFetchEvidence(request(Map.of("Accept", "*/*"))));
        assertFalse(BrowserEvidence.hasFetchEvidence(request(Map.of("Accept-Language", "zh-CN"))));
        // 空白值不算
        assertFalse(BrowserEvidence.hasFetchEvidence(
                request(Map.of("Accept", "*/*", "Accept-Language", "   "))));
        assertFalse(BrowserEvidence.hasFetchEvidence(request(Map.of())));
        assertFalse(BrowserEvidence.hasFetchEvidence(null));
    }

    @Test
    void secFetchHeadersCannotReplaceAcceptLanguage() {
        assertFalse(BrowserEvidence.hasFetchEvidence(request(Map.of(
                "Accept", "*/*",
                "Sec-Fetch-Mode", "cors",
                "Sec-Fetch-Site", "same-origin",
                "Sec-Fetch-Dest", "empty"))));
    }

    /** 只为 getHeader 服务的轻量请求桩。 */
    private static HttpServletRequest request(Map<String, String> headers) {
        Map<String, String> lowered = new HashMap<>();
        headers.forEach((key, value) -> lowered.put(key.toLowerCase(), value));
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> lowered.get(((String) args[0]).toLowerCase());
                    case "toString" -> "HttpServletRequestStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
