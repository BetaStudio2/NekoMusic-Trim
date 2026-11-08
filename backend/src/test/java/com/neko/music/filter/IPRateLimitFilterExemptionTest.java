package com.neko.music.filter;

import com.neko.music.util.SsePaths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 限流豁免清单：SSE 长连接（EventSource 自动重连）与大文件上传、支付回调一并豁免，
 * 普通动态接口仍照常计数。
 */
class IPRateLimitFilterExemptionTest {

    private static final String[] SSE_PATHS = {
            "/api/user/qrlogin/status",
            "/api/user/notifications/stream",
            "/api/admin/logs/access/stream",
            "/loser/netease/pull",
            "/loser/qq/pull",
            "/loser/kugou/pull",
            "/loser/qishui/pull",
            "/loser/lxmc/pull"};

    @Test
    void sseStreamsAreExempt() {
        for (String path : SSE_PATHS) {
            assertTrue(SsePaths.isStreamPath(path), path);
            assertTrue(IPRateLimitFilter.isExemptPath(path), path);
        }
    }

    @Test
    void sseExemptionToleratesTrailingSlash() {
        assertTrue(SsePaths.isStreamPath("/api/user/notifications/stream/"));
        assertTrue(IPRateLimitFilter.isExemptPath("/api/user/notifications/stream/"));
    }

    @Test
    void uploadAndPaymentCallbackAreExempt() {
        for (String path : new String[]{
                "/api/music/upload",
                "/api/user/upload",
                "/api/user/upload/chunk",
                "/api/payment/zpay/notify"}) {
            assertTrue(IPRateLimitFilter.isExemptPath(path), path);
        }
    }

    @Test
    void ordinaryApiIsNotExempt() {
        for (String path : new String[]{
                "/api/music/latest",
                "/api/user/notifications",
                "/api/user/notifications/streamX",
                "/loser/qq/getSongListDetail",
                "/loser/qq/pullx",
                ""}) {
            assertFalse(IPRateLimitFilter.isExemptPath(path), path);
            assertFalse(SsePaths.isStreamPath(path), path);
        }
    }
}
