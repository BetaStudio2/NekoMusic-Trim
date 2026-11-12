package com.neko.music.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 为音频/图片等磁盘资源设置 ETag、Last-Modified、Cache-Control，并处理 If-None-Match → 304。
 * <p>前置 CDN 常对同一 URL 做 Range 回源分段拉取，因此使用 {@code public} 以便边缘缓存，
 * 并以 {@code must-revalidate} + ETag 在源文件被替换后触发再校验。</p>
 */
public final class HttpResourceCache {

    /** 六个月（180 天）。静态/固定文件统一使用该时长。 */
    public static final long MAX_AGE_SIX_MONTHS = 15552000L;
    /** 一天。sitemap、txt 等低频更新的固定文件使用。 */
    public static final long MAX_AGE_ONE_DAY = 86400L;
    /** 半小时。{@code /api/music/latest}、{@code /api/music/ranking} 使用。 */
    public static final long MAX_AGE_HALF_HOUR = 1800L;

    /** 动态 API / 登录 / 头像等敏感响应：禁止任何缓存落盘。 */
    public static final String CACHE_CONTROL_NO_STORE = "private, no-store";

    /**
     * 允许浏览器与共享缓存（CDN）缓存；上传替换同一 id 后 ETag 变化，再校验可拿到新对象。
     */
    public static final String CACHE_CONTROL_FILE =
            "public, max-age=" + MAX_AGE_SIX_MONTHS + ", must-revalidate";

    /**
     * 与 {@link #CACHE_CONTROL_FILE} 同时长（六个月），但只允许浏览器私有缓存：用于需要鉴权的
     * 管理端预览等「被共享缓存 / CDN 命中后会免鉴权分发」的资源。源文件被替换后 ETag 变化。
     */
    public static final String CACHE_CONTROL_PRIVATE_FILE =
            "private, max-age=" + MAX_AGE_SIX_MONTHS + ", must-revalidate";

    /**
     * 允许浏览器私有缓存，但每次使用前都必须回源再校验（{@code max-age=0}）：适合「同一 URL
     * 内容可能被替换、又不想重复传输整份响应」的资源（如用户头像）。共享缓存 / CDN 不得缓存。
     */
    public static final String CACHE_CONTROL_PRIVATE_REVALIDATE = "private, max-age=0, must-revalidate";

    /** 同一 URL 的曲库封面可被管理员替换，必须每次使用前由浏览器 / CDN 再校验。 */
    public static final String CACHE_CONTROL_COVER = "public, max-age=0, must-revalidate";

    /** 内嵌默认图标，内容不变 */
    public static final String DEFAULT_ICON_ETAG = "\"DefaultIcon-v1\"";
    public static final String CACHE_CONTROL_DEFAULT_ICON =
            "public, max-age=" + MAX_AGE_SIX_MONTHS + ", immutable";

    private HttpResourceCache() {
    }

    public static String strongEtagForFile(Path path) throws IOException {
        return etagFor(Files.readAttributes(path, BasicFileAttributes.class));
    }

    /** 与 {@link #strongEtagForFile(Path)} 同值；供同一请求里已经读到文件属性的场景复用，省一次 stat。 */
    public static String etagFor(BasicFileAttributes attrs) {
        return "\"" + Long.toHexString(attrs.lastModifiedTime().toMillis())
                + "-" + Long.toHexString(attrs.size()) + "\"";
    }

    private static String stripEtagValue(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2).trim();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * If-None-Match 与当前 ETag 是否匹配（支持逗号分隔的多值与 W/ 弱标签）。
     */
    public static boolean ifNoneMatchEquals(HttpServletRequest request, String etag) {
        String inm = request.getHeader("If-None-Match");
        if (inm == null || inm.isEmpty()) {
            return false;
        }
        if ("*".equals(inm.trim())) {
            return true;
        }
        String normalized = stripEtagValue(etag);
        for (String part : inm.split(",")) {
            if (stripEtagValue(part).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 若客户端缓存仍新鲜，发送 304 并返回 true。
     */
    public static boolean sendNotModifiedIfFresh(HttpServletRequest request, HttpServletResponse response, String etag) {
        return sendNotModifiedIfFresh(request, response, etag, CACHE_CONTROL_FILE);
    }

    /**
     * 若客户端缓存仍新鲜，按指定缓存策略发送 304 并返回 true。
     */
    public static boolean sendNotModifiedIfFresh(HttpServletRequest request, HttpServletResponse response, String etag,
                                                 String cacheControl) {
        if (!ifNoneMatchEquals(request, etag)) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", cacheControl);
        return true;
    }

    public static void applyFileCachingHeaders(Path path, HttpServletResponse response) throws IOException {
        applyFileCachingHeaders(path, response, CACHE_CONTROL_FILE);
    }

    /** 按指定缓存策略写入 {@code ETag} / {@code Last-Modified} / {@code Cache-Control}。 */
    public static void applyFileCachingHeaders(Path path, HttpServletResponse response, String cacheControl)
            throws IOException {
        applyFileCachingHeaders(Files.readAttributes(path, BasicFileAttributes.class), response, cacheControl);
    }

    /**
     * 用调用方已读到的文件属性写头。
     *
     * <p>ETag 与 Last-Modified 本来就来自同一次 {@code stat}，拆成两次读会多一次系统调用；
     * 响应长度、Range 边界也常需要同一份属性，因此提供这个重载让调用方只读一次。</p>
     */
    public static void applyFileCachingHeaders(BasicFileAttributes attrs, HttpServletResponse response,
                                               String cacheControl) {
        response.setHeader("ETag", etagFor(attrs));
        response.setDateHeader("Last-Modified", attrs.lastModifiedTime().toMillis());
        response.setHeader("Cache-Control", cacheControl);
    }

    public static boolean sendNotModifiedDefaultIcon(HttpServletRequest request, HttpServletResponse response) {
        return sendNotModifiedDefaultIcon(request, response, CACHE_CONTROL_DEFAULT_ICON);
    }

    public static boolean sendNotModifiedDefaultIcon(HttpServletRequest request, HttpServletResponse response,
                                                     String cacheControl) {
        if (!ifNoneMatchEquals(request, DEFAULT_ICON_ETAG)) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        response.setHeader("ETag", DEFAULT_ICON_ETAG);
        response.setHeader("Cache-Control", cacheControl);
        return true;
    }

    public static void applyDefaultIconCachingHeaders(HttpServletResponse response) {
        applyDefaultIconCachingHeaders(response, CACHE_CONTROL_DEFAULT_ICON);
    }

    /** 内嵌默认图标按指定缓存策略写头（{@code /default} 长期缓存，用户暂无头像时逐次再校验）。 */
    public static void applyDefaultIconCachingHeaders(HttpServletResponse response, String cacheControl) {
        response.setHeader("ETag", DEFAULT_ICON_ETAG);
        response.setHeader("Cache-Control", cacheControl);
    }

    /** 声明支持字节范围，便于 CDN / 播放器分段请求与回源。 */
    public static void setAcceptRangesBytes(HttpServletResponse response) {
        response.setHeader("Accept-Ranges", "bytes");
    }
}
