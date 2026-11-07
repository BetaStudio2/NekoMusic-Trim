package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.UserProfile;
import com.neko.music.service.UserManagementException;
import com.neko.music.service.UserManagementService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.HttpTransport;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * 兼容旧客户端的头像入口：NekoMusic 不读取本地头像，统一从 NekoAuth 拉取并原样返回。
 */
public final class UserAvatarHandler extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(UserAvatarHandler.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final UserManagementService userManagementService;
    private final String configuredIssuer;
    private final Function<Integer, Long> authIdResolver;
    private final AvatarFetcher avatarFetcher;

    public UserAvatarHandler() {
        this(Main.getUserManagementService(), Main.getConfigManager());
    }

    UserAvatarHandler(UserManagementService userManagementService, ConfigManager configManager) {
        this.userManagementService = userManagementService;
        this.configuredIssuer = configManager == null ? "" : configManager.getOAuthIssuer();
        this.authIdResolver = this::findAuthUserId;
        this.avatarFetcher = defaultFetcher();
    }

    UserAvatarHandler(Function<Integer, Long> authIdResolver, String issuer, AvatarFetcher avatarFetcher) {
        this.userManagementService = null;
        this.configuredIssuer = issuer;
        this.authIdResolver = authIdResolver;
        this.avatarFetcher = avatarFetcher;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_PRIVATE_REVALIDATE);
        String pathInfo = request.getPathInfo();
        Long authUserId = authUserId(pathInfo);
        String path = authUserId == null ? "/avatars/default" : "/avatars/" + authUserId;
        URI target = avatarUri(path);
        if (target == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "NekoAuth 地址未配置");
            return;
        }

        HttpResponse<byte[]> upstream;
        try {
            upstream = avatarFetcher.fetch(target, request.getHeader("If-None-Match"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "获取头像被中断");
            return;
        } catch (IOException exception) {
            logger.warn("从 NekoAuth 获取头像失败: {}", target);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "NekoAuth 头像暂时不可用");
            return;
        }

        int status = upstream.statusCode();
        copyHeader(upstream, response, "ETag");
        copyHeader(upstream, response, "Last-Modified");
        copyHeader(upstream, response, "Cache-Control");
        if (status == HttpServletResponse.SC_NOT_MODIFIED) {
            response.setStatus(status);
            return;
        }
        if (!HttpTransport.isSuccess(status)) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "NekoAuth 头像暂时不可用");
            return;
        }

        String contentType = upstream.headers().firstValue("Content-Type").orElse("image/x-icon");
        response.setContentType(contentType);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(upstream.body().length);
        response.getOutputStream().write(upstream.body());
    }

    private Long authUserId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.equals("/default")) {
            return null;
        }
        String[] parts = pathInfo.split("/");
        try {
            int localUserId = Integer.parseInt(parts.length > 1 ? parts[1] : "");
            return authIdResolver == null ? null : authIdResolver.apply(localUserId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long findAuthUserId(int localUserId) {
        if (userManagementService == null) {
            return null;
        }
        try {
            UserProfile profile = userManagementService.getUserProfile(localUserId);
            return profile.authUserId();
        } catch (UserManagementException exception) {
            logger.warn("读取用户 {} 的 NekoAuth 映射失败，回退默认头像", localUserId);
            return null;
        }
    }

    private URI avatarUri(String path) {
        String issuer = configuredIssuer == null ? "" : configuredIssuer.trim();
        if (issuer.isEmpty()) {
            return null;
        }
        while (issuer.endsWith("/")) {
            issuer = issuer.substring(0, issuer.length() - 1);
        }
        try {
            URI uri = URI.create(issuer + path);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (uri.getHost() == null || (!scheme.equals("https") && !scheme.equals("http"))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void copyHeader(HttpResponse<?> upstream, HttpServletResponse response, String name) {
        Optional<String> value = upstream.headers().firstValue(name);
        value.ifPresent(text -> response.setHeader(name, text));
    }

    private static AvatarFetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, ifNoneMatch) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "image/*")
                    .GET();
            if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
                builder.header("If-None-Match", ifNoneMatch);
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        };
    }

    @FunctionalInterface
    interface AvatarFetcher {
        HttpResponse<byte[]> fetch(URI uri, String ifNoneMatch) throws IOException, InterruptedException;
    }
}
