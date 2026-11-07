package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.UserManagementException;
import com.neko.music.service.UserManagementService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.ImageUploadValidator;
import com.neko.music.util.RequestAuthUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 当前登录用户上传头像：{@code POST /api/user/avatar/upload}，实际写入 NekoAuth。 */
public final class UserAvatarUploadHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(UserAvatarUploadHandler.class);
    private static final long MAX_FILE_SIZE = ImageUploadValidator.DEFAULT_MAX_IMAGE_BYTES;

    private final Function<HttpServletRequest, Integer> authResolver;
    private final AvatarUploader uploader;
    private final Path temporaryDirectory;

    public UserAvatarUploadHandler() {
        this(Main.getUserManagementService(), RequestAuthUtil::authenticate,
                Paths.get(System.getProperty("java.io.tmpdir")));
    }

    UserAvatarUploadHandler(
            UserManagementService service,
            Function<HttpServletRequest, Integer> authResolver,
            Path temporaryDirectory) {
        this(authResolver, service::uploadUserAvatar, temporaryDirectory);
    }

    UserAvatarUploadHandler(
            Function<HttpServletRequest, Integer> authResolver,
            AvatarUploader uploader,
            Path temporaryDirectory) {
        this.authResolver = authResolver;
        this.uploader = uploader;
        this.temporaryDirectory = temporaryDirectory;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        Integer userId = authResolver.apply(request);
        if (userId == null) {
            String token = RequestAuthUtil.extractToken(request);
            sendErrorObject(response, HttpStatus.UNAUTHORIZED_401,
                    token == null ? "auth.unauthorized" : "auth.invalid_token");
            return;
        }

        Path uploadedFile = null;
        try {
            request.setCharacterEncoding("UTF-8");
            Part avatarPart = request.getPart("avatar");
            if (avatarPart == null || avatarPart.getSize() == 0) {
                sendErrorObject(response, HttpStatus.BAD_REQUEST_400, "user.avatar.no_file");
                return;
            }

            ImageUploadValidator.ValidationResult imageValidation =
                    ImageUploadValidator.validatePart(avatarPart, MAX_FILE_SIZE);
            if (!imageValidation.isValid()) {
                sendErrorObject(response, HttpStatus.BAD_REQUEST_400, imageValidation.getErrorMessage());
                return;
            }

            Files.createDirectories(temporaryDirectory);
            uploadedFile = temporaryDirectory.resolve(
                    "neko-avatar-" + userId + "-" + UUID.randomUUID() + imageValidation.getExtension());
            try (InputStream inputStream = avatarPart.getInputStream()) {
                Files.copy(inputStream, uploadedFile, StandardCopyOption.REPLACE_EXISTING);
            }

            uploader.upload(userId, uploadedFile);
            logger.info("用户 {} 通过 NekoAuth 上传头像成功", userId);
            sendUploadSuccess(response, userId);
        } catch (UserManagementException exception) {
            logger.warn("用户 {} 头像上传失败: {}", userId, exception.messageKey());
            sendErrorObject(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                    "user.avatar.upload_failed");
        } catch (Exception exception) {
            logger.error("上传头像时出错", exception);
            sendErrorObject(response, HttpStatus.INTERNAL_SERVER_ERROR_500,
                    ApiMessages.translate("user.avatar.upload_failed", exception.getMessage()));
        } finally {
            if (uploadedFile != null) {
                try {
                    Files.deleteIfExists(uploadedFile);
                } catch (IOException exception) {
                    logger.warn("清理头像临时文件失败: {}", uploadedFile);
                }
            }
        }
    }

    private void sendUploadSuccess(HttpServletResponse response, int userId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", ApiMessages.translate("user.avatar.upload_ok"));
        body.put("avatarUrl", "/api/user/avatar/" + userId);
        body.put("avatarPath", "/api/user/avatar/" + userId);
        writeJson(response, HttpStatus.OK_200, MAPPER.writeValueAsString(body));
    }

    @FunctionalInterface
    interface AvatarUploader {
        void upload(int userId, Path file) throws Exception;
    }
}
