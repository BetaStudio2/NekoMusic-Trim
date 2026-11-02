package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.service.AppReleaseService;
import com.neko.music.util.ApkUploadValidator;
import com.neko.music.util.ClientReleaseStorage;
import com.neko.music.util.JsonResponseCache;
import com.neko.music.util.SiteUrlResolver;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 管理员上传客户端安装包（按平台校验类型，落盘为 /version 接口预期的文件名） */
public class AdminClientReleaseUploadHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(AdminClientReleaseUploadHandler.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED_401);
            writeJson(response, false, "auth.unauthorized");
            return;
        }
        if (!Main.getAdminAuthService().canUploadClientReleaseByToken(token)) {
            response.setStatus(HttpStatus.FORBIDDEN_403);
            writeJson(response, false, "admin.release.permission_required");
            return;
        }

        String platform = null;
        Part filePart = null;
        for (Part part : request.getParts()) {
            String name = part.getName();
            if ("platform".equals(name)) {
                platform = readPartText(part);
            } else if ("file".equals(name) && filePart == null) {
                String submitted = part.getSubmittedFileName();
                if (submitted != null && !submitted.isBlank()) {
                    filePart = part;
                }
            }
        }

        if (platform == null || platform.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            writeJson(response, false, ApiMessages.translate("admin.release.upload.platform_required"));
            return;
        }
        if (filePart == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            writeJson(response, false, ApiMessages.translate("admin.release.upload.file_required"));
            return;
        }

        Optional<AppReleaseService.AppRelease> release = Main.getAppReleaseService().getTargetReleaseForUpload();
        if (release.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            writeJson(response, false, ApiMessages.translate("admin.release.upload.versions_required"));
            return;
        }

        String expectedFileName = ClientReleaseStorage.expectedFileNameForPlatform(platform, release.get());
        if (expectedFileName == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            writeJson(response, false, ApiMessages.translate("admin.release.upload.platform_invalid"));
            return;
        }

        String sourceName = Path.of(filePart.getSubmittedFileName()).getFileName().toString();
        long declaredSize = filePart.getSize();

        Path target = null;
        Path temp = null;
        try {
            target = ClientReleaseStorage.resolveTargetForUpload(expectedFileName);
            temp = Files.createTempFile("release-upload-", ".part");

            long written;
            try (InputStream in = filePart.getInputStream()) {
                written = copyWithSizeLimit(in, temp, ClientReleaseStorage.MAX_UPLOAD_BYTES);
            }
            if (declaredSize >= 0) {
                ClientReleaseStorage.validateSourceFileForPlatform(platform, sourceName, declaredSize);
            } else {
                ClientReleaseStorage.validateSourceFileForPlatform(platform, sourceName, written);
            }
            if (written > ClientReleaseStorage.MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException(ApiMessages.translate("admin.release.upload.too_large"));
            }
            validateUploadedArtifact(platform, temp, release.get());

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            cleanupObsoletePlatformPackages(platform, expectedFileName);

            // 安装包体积变了，/version 里展示的 size 必须立刻跟上（成品缓存按需失效）
            JsonResponseCache.invalidate(AppReleaseService.VERSION_JSON_CACHE_KEY);

            long size = Files.size(target);
            String downloadUrl = ClientReleaseStorage.publicDownloadUrl(
                    SiteUrlResolver.resolvePublicSiteBase(request), expectedFileName);

            ObjectNode data = Main.getObjectMapper().createObjectNode();
            data.put("platform", platform.trim().toLowerCase());
            data.put("sourceFileName", sourceName);
            data.put("fileName", expectedFileName);
            data.put("size", size);
            data.put("downloadUrl", downloadUrl);

            ObjectNode root = Main.getObjectMapper().createObjectNode();
            root.put("success", true);
            root.put("message", ApiMessages.translate("admin.release.upload_ok"));
            root.set("data", data);

            response.setStatus(HttpStatus.OK_200);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(Main.getObjectMapper().writeValueAsString(root));
            logger.info("管理员上传安装包 platform={} {} -> {} ({} bytes)",
                    platform, sourceName, expectedFileName, size);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            writeJson(response, false, e.getMessage());
        } catch (Exception e) {
            logger.error("上传安装包失败 platform={} source={}", platform, sourceName, e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            writeJson(response, false, ApiMessages.translate("admin.release.upload.failed", e.getMessage()));
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
            if (target != null && !Files.exists(target)) {
                // 失败且未成功落盘时 target 可能为空文件，已由 move 处理
            }
        }
    }

    private static String readPartText(Part part) throws IOException {
        byte[] bytes = part.getInputStream().readAllBytes();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** 新包落盘后清理同平台历史包；当前对外版本包要保留到 pending 版本真正生效。 */
    private static void cleanupObsoletePlatformPackages(String platform, String targetFileName) {
        Optional<AppReleaseService.AppReleaseState> state =
                Main.getAppReleaseService().getReleaseStateForAdmin();
        if (state.isEmpty()) {
            logger.warn("跳过旧安装包清理：无法读取当前发布版本 platform={}", platform);
            return;
        }

        Set<String> keepFileNames = new HashSet<>();
        keepFileNames.add(targetFileName);
        String publishedFileName = ClientReleaseStorage.expectedFileNameForPlatform(
                platform, state.get().published());
        if (publishedFileName != null) {
            keepFileNames.add(publishedFileName);
        }

        try {
            int removed = ClientReleaseStorage.deleteObsoletePlatformPackages(platform, keepFileNames);
            if (removed > 0) {
                logger.info("已清理旧安装包 platform={} removed={} keep={}",
                        platform, removed, keepFileNames);
            }
        } catch (IOException e) {
            // 新包已经上传成功，清理失败不能让调用方误以为上传失败。
            logger.warn("清理旧安装包失败 platform={}: {}", platform, e.getMessage());
        }
    }

    /** Android APK 校验包名、版本与签名。 */
    static void validateUploadedArtifact(String platform, Path file, AppReleaseService.AppRelease release)
            throws IOException {
        if ("android".equalsIgnoreCase(platform.trim())) {
            ApkUploadValidator.validate(file, release.androidVer());
        }
    }

    private static long copyWithSizeLimit(InputStream in, Path target, long maxBytes) throws IOException {
        Files.createDirectories(target.getParent());
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException(ApiMessages.translate("admin.release.upload.too_large"));
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return total;
    }

    private void writeJson(HttpServletResponse response, boolean success, String message) throws IOException {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", success);
        root.put("message", ApiMessages.translate(message));
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(Main.getObjectMapper().writeValueAsString(root));
    }
}
