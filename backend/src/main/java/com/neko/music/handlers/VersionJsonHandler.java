package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.service.AppReleaseService;
import com.neko.music.util.ClientReleaseStorage;
import com.neko.music.util.JsonResponseCache;
import com.neko.music.util.SiteUrlResolver;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 客户端版本检查 JSON（ver / pc_ver 均来自数据库 app_release） */
public class VersionJsonHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(VersionJsonHandler.class);

    /** 成品响应缓存时长：与响应的 {@code max-age=60} 对齐（键由服务侧维护，便于数据变更时失效）。 */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AppReleaseService releaseService = Main.getAppReleaseService();
        Optional<AppReleaseService.AppRelease> release = releaseService.getPublishedReleaseForClients();
        if (release.isEmpty()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
            response.setContentType("application/json;charset=utf-8");
            ObjectNode err = Main.getObjectMapper().createObjectNode();
            err.put("success", false);
            err.put("message", ApiMessages.translate("admin.release.not_configured"));
            response.getWriter().write(Main.getObjectMapper().writeValueAsString(err));
            return;
        }

        // 版本 JSON 与请求头无关（对外地址取自固定配置），且本来就可以被客户端 / CDN 缓存 60 秒。
        // 缓存成品后，每次请求省掉「两次数据库查询 + 若干次安装包 stat + 序列化」。
        String body = JsonResponseCache.get(AppReleaseService.VERSION_JSON_CACHE_KEY, CACHE_TTL_MILLIS, () -> buildBody(release.get(), request));

        response.setStatus(HttpStatus.OK_200);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=utf-8");
        // 延迟生效窗口内可能切换版本，缩短缓存避免 CDN/浏览器长时间返回旧 JSON
        response.setHeader("Cache-Control", "public, max-age=60, must-revalidate");
        response.getWriter().write(body);
    }

    /** 组装版本 JSON（只在缓存未命中时执行）。 */
    private String buildBody(AppReleaseService.AppRelease r, HttpServletRequest request) {
        String siteBase = SiteUrlResolver.resolvePublicSiteBase(request);

        String androidApk = ClientReleaseStorage.androidApkFileName(r.androidVer());
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("ver", r.androidVer());
        root.put("updateUrl", ClientReleaseStorage.publicDownloadUrl(siteBase, androidApk));
        // 安装包体积（字节，0 表示暂未上传），下载页据此展示「大小」
        root.put("size", ClientReleaseStorage.fileSizeOrZero(androidApk));

        ObjectNode pc = root.putObject("pc");
        String pcVer = r.pcVer();
        String windowsExe = ClientReleaseStorage.windowsExeFileName(pcVer);
        String linuxDeb = ClientReleaseStorage.linuxDebFileName(pcVer);
        String macPkg = ClientReleaseStorage.macPkgFileName(pcVer);
        pc.put("pc_ver", pcVer);
        pc.put("windows", ClientReleaseStorage.publicDownloadUrl(siteBase, windowsExe));
        pc.put("linux", ClientReleaseStorage.publicDownloadUrl(siteBase, linuxDeb));
        pc.put("mac", ClientReleaseStorage.publicDownloadUrl(siteBase, macPkg));
        pc.put("windows_size", ClientReleaseStorage.fileSizeOrZero(windowsExe));
        pc.put("linux_size", ClientReleaseStorage.fileSizeOrZero(linuxDeb));
        pc.put("mac_size", ClientReleaseStorage.fileSizeOrZero(macPkg));

        logger.debug("/version ver={} pc_ver={}", r.androidVer(), pcVer);
        try {
            return Main.getObjectMapper().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
