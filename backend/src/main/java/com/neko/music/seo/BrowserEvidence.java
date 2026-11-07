package com.neko.music.seo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 浏览器特征头的提取与判定（供 SEO 分流、详情页等多处复用，避免逻辑漂移）。
 *
 * <p>真实浏览器访问页面或 fetch 接口时，一定同时带 {@code Accept} 与 {@code Accept-Language}。
 * 缺少这两者而仅伪造 {@code Mozilla/} UA 的，视为爬虫 / 脚本 / 环境异常客户端。</p>
 */
public final class BrowserEvidence {

    private BrowserEvidence() {
    }

    /**
     * 请求是否具备浏览器特征头证据。
     *
     * <p><b>{@code Accept-Language} 是硬性要求</b>：必须同时带非空 {@code Accept} 与非空
     * {@code Accept-Language}，缺任一项即判定为环境异常。主流浏览器（含媒体 {@code <audio>}
     * 请求、{@code EventSource}、Service Worker）都会带这两个头，因此不误伤真实浏览器。</p>
     *
     * <p>不再接受其它头作为替代：{@code Sec-Fetch-*} 由脚本随手可伪造，单独拿它当证据
     * 等于开一个不设防的入口；而 {@code Accept-Language} 是脚本与环境异常请求普遍缺失的信号。</p>
     */
    public static boolean hasFetchEvidence(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return hasNonBlankValue(request.getHeader("Accept"))
                && hasNonBlankValue(request.getHeader("Accept-Language"));
    }

    private static boolean hasNonBlankValue(String value) {
        return value != null && !value.isBlank();
    }
}
