package com.neko.music.util;

import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 请求路径的「路由视角」解析：**所有按路径做判定的过滤器都必须用它**，不要直接读
 * {@link HttpServletRequest#getRequestURI()}。
 *
 * <p>为什么：Jetty 的 servlet 映射按**规范化**后的路径匹配，而 {@code getRequestURI()} 返回**原始**
 * URI。于是 {@code /./api/x}、{@code /a/../api/x}、{@code /api;/x}、{@code /%61pi/x} 这些等价写法
 * 会被路由到真实的 {@code /api/*} handler，却在原始字符串上前缀匹配失败 —— 判定「是否受保护」的
 * 过滤器把它们当成非受保护路径直接放行，相关门禁被整体绕过（CWE-436）。</p>
 *
 * <p>本类改用容器已经算好的 {@code servletPath + pathInfo}：它来自与路由同一套解释，任何等价写法
 * 在进入过滤器时都已是同一个规范路径，判定与路由不可能再分叉。取不到映射时（非正常分派）
 * 退化为自归一，宁可多保护也不漏保护。</p>
 */
public final class RequestPath {

    /** 单请求缓存键：一个请求的路径在整条过滤器链里是常量，算一次即可。 */
    private static final String SCOPE_KEY = "requestPath";

    private RequestPath() {
    }

    /**
     * 与 servlet 路由一致的规范化路径（不以 {@code /} 开头的内容会被补齐）。
     *
     * <p>结果按请求缓存在 {@link RequestScope} 里：七八道门禁各自都要拿它做前缀判定，
     * 而它只取决于请求本身。</p>
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String cached = RequestScope.get(SCOPE_KEY);
        if (cached != null) {
            return cached;
        }
        String path = of(request.getServletPath(), request.getPathInfo(),
                request.getRequestURI(), request.getContextPath());
        RequestScope.put(SCOPE_KEY, path);
        return path;
    }

    /** 便于测试的纯函数版本。 */
    static String of(String servletPath, String pathInfo, String requestUri, String contextPath) {
        if (servletPath != null) {
            String combined = servletPath + (pathInfo == null ? "" : pathInfo);
            if (!combined.isEmpty()) {
                return combined.startsWith("/") ? combined : "/" + combined;
            }
        }
        return canonicalize(stripContextPath(requestUri, contextPath));
    }

    /** 去掉 context path 前缀（无 context path 时原样返回）。 */
    private static String stripContextPath(String uri, String contextPath) {
        String path = uri == null ? "" : uri;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * 自归一：解开百分号编码 → 逐段去掉 {@code ;} 路径参数 → 消解 {@code .} / {@code ..} 段 →
     * 折叠重复斜杠。仅在容器未给出 servlet 映射时使用。
     */
    private static String canonicalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String rawSegment : path.split("/", -1)) {
            String segment = decodePathSegment(rawSegment);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    /** 去掉分段内的 {@code ;} 路径参数，并解开 {@code %XX}（不把 {@code +} 当空格，与路径语义一致）。 */
    private static String decodePathSegment(String segment) {
        int semicolon = segment.indexOf(';');
        String value = semicolon < 0 ? segment : segment.substring(0, semicolon);
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
