package com.neko.music.util;

import java.util.Set;

/**
 * SSE（Server-Sent Events）长连接端点清单：供按请求计数的门禁过滤器共享，避免各处清单漂移。
 *
 * <p>SSE 由浏览器 {@code EventSource} 建立，有两个服务端无法改变的行为：连接断开后按 {@code retry}
 * 间隔自动重连；且无法像 fetch / XHR 一样携带自定义请求头。于是「按请求计数」的门禁会把一条长连接
 * 及其自动重连当成高频请求——计数又按 IPv4 的 /24 聚合，共享出口（NAT、移动网络）下正常用户很容易
 * 被打成 {@code 429}，连接再也建不起来。这些端点因此对齐
 * 统一豁免按请求计数的 {@code 429}。</p>
 *
 * <p>清单刻意只登记「确实是 SSE 长连接」的<b>精确</b>端点，不允许用 {@code /api/user/*} 这类宽前缀：
 * 豁免面必须收敛到长连接本身，否则会把同前缀下的普通动态接口一并放过。</p>
 */
public final class SsePaths {

    /** 所有 SSE 长连接端点（精确路径）。 */
    private static final Set<String> STREAM_PATHS = Set.of(
            "/api/user/qrlogin/status",        // 扫码登录状态流：EventSource，浏览器自动重连
            "/api/user/notifications/stream",  // 站内消息实时推送：EventSource，无法自定义请求头
            "/api/admin/logs/access/stream",   // 后台访问日志实时流：同为 EventSource，仅靠管理端令牌鉴权
            "/loser/netease/pull",             // 歌单导入进度流：四家音源统一的 SSE 长连接
            "/loser/qq/pull",
            "/loser/kugou/pull",
            "/loser/qishui/pull");

    private SsePaths() {
    }

    /**
     * 是否为 SSE 长连接端点。传入的应为路由视角的规范化路径（见 {@link RequestPath}）；尾部斜杠会被
     * 归一化，避免 {@code /api/.../stream/} 之类写法绕过豁免判定。
     */
    public static boolean isStreamPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return STREAM_PATHS.contains(path);
    }
}
