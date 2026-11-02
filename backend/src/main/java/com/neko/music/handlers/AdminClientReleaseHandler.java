package com.neko.music.handlers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.service.AppReleaseService;
import com.neko.music.util.ClientReleaseStorage;
import com.neko.music.util.SiteUrlResolver;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import com.neko.music.i18n.ApiMessages;

/** 管理员：查询/更新客户端版本号与安装包状态 */
public class AdminClientReleaseHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(AdminClientReleaseHandler.class);
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter EFFECTIVE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!authorize(request, response)) {
            return;
        }

        String siteBase = SiteUrlResolver.resolvePublicSiteBase(request);
        Optional<AppReleaseService.AppReleaseState> state = Main.getAppReleaseService().getReleaseStateForAdmin();

        ObjectNode data = Main.getObjectMapper().createObjectNode();
        if (state.isPresent()) {
            writeStateToJson(data, siteBase, state.get());
        } else {
            data.putNull("androidVer");
            data.putNull("pcVer");
            data.set("packages", Main.getObjectMapper().createArrayNode());
        }

        sendResponse(response, true, "admin.lyrics.query_ok", data);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!authorize(request, response)) {
            return;
        }

        String body = new String(request.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        VersionUpdateRequest req;
        try {
            req = Main.getObjectMapper().readValue(body, VersionUpdateRequest.class);
        } catch (Exception e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "auth.bad_request");
            return;
        }

        if (req.androidVer() == null || req.androidVer().isBlank()
                || req.pcVer() == null || req.pcVer().isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.release.versions_required");
            return;
        }

        String androidVer = req.androidVer().trim();
        String pcVer = req.pcVer().trim();
        if (!isValidVersionToken(androidVer) || !isValidVersionToken(pcVer)) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.release.invalid_version");
            return;
        }

        Optional<AppReleaseService.AppReleaseState> state =
                Main.getAppReleaseService().scheduleReleaseUpdate(androidVer, pcVer);
        if (state.isEmpty()) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            sendErrorResponse(response, "admin.release.save_failed");
            return;
        }

        String siteBase = SiteUrlResolver.resolvePublicSiteBase(request);
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        writeStateToJson(data, siteBase, state.get());

        String message = state.get().hasPending()
                ? ApiMessages.translate("admin.release.scheduled",
                formatEffectiveAt(state.get().pendingEffectiveAt()),
                AppReleaseService.VERSION_JSON_DELAY_MINUTES)
                : ApiMessages.translate("admin.release.updated");
        sendResponse(response, true, message, data);
        logger.info("管理员排期/更新客户端版本 pending={}", state.get().hasPending());
    }

    private void writeStateToJson(ObjectNode data, String siteBase, AppReleaseService.AppReleaseState state) {
        AppReleaseService.AppRelease pub = state.published();
        data.put("androidVer", pub.androidVer());
        data.put("pcVer", pub.pcVer());
        data.put("publishedAndroidVer", pub.androidVer());
        data.put("publishedPcVer", pub.pcVer());

        if (state.hasPending() && state.pending() != null) {
            data.put("pendingAndroidVer", state.pending().androidVer());
            data.put("pendingPcVer", state.pending().pcVer());
            data.put("pendingEffectiveAt", formatEffectiveAt(state.pendingEffectiveAt()));
            data.put("versionJsonDelayMinutes", AppReleaseService.VERSION_JSON_DELAY_MINUTES);
            data.set("packages", buildPackagesArray(siteBase, state.pending()));
        } else {
            data.putNull("pendingAndroidVer");
            data.putNull("pendingPcVer");
            data.putNull("pendingEffectiveAt");
            data.set("packages", buildPackagesArray(siteBase, pub));
        }
    }

    private static String formatEffectiveAt(Instant instant) {
        if (instant == null) {
            return "";
        }
        return ZonedDateTime.ofInstant(instant, CN_ZONE).format(EFFECTIVE_FMT) + " (UTC+8)";
    }

    private ArrayNode buildPackagesArray(String siteBase, AppReleaseService.AppRelease r) {
        ArrayNode arr = Main.getObjectMapper().createArrayNode();
        addPackageEntry(arr, siteBase, "android", ClientReleaseStorage.androidApkFileName(r.androidVer()));
        addPackageEntry(arr, siteBase, "windows", ClientReleaseStorage.windowsExeFileName(r.pcVer()));
        addPackageEntry(arr, siteBase, "linux", ClientReleaseStorage.linuxDebFileName(r.pcVer()));
        addPackageEntry(arr, siteBase, "mac", ClientReleaseStorage.macPkgFileName(r.pcVer()));
        return arr;
    }

    private void addPackageEntry(ArrayNode arr, String siteBase, String platform, String fileName) {
        ObjectNode item = arr.addObject();
        item.put("platform", platform);
        if (fileName == null || fileName.isBlank()) {
            item.putNull("fileName");
            item.putNull("downloadUrl");
            item.put("uploaded", false);
            item.putNull("size");
            return;
        }
        item.put("fileName", fileName);
        item.put("downloadUrl", ClientReleaseStorage.publicDownloadUrl(siteBase, fileName));
        var pathOpt = ClientReleaseStorage.resolveReadableFile(fileName);
        if (pathOpt.isPresent()) {
            try {
                item.put("uploaded", true);
                item.put("size", Files.size(pathOpt.get()));
            } catch (IOException e) {
                item.put("uploaded", true);
                item.putNull("size");
            }
        } else {
            item.put("uploaded", false);
            item.putNull("size");
        }
    }

    private boolean authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED_401);
            sendErrorResponse(response, "auth.unauthorized");
            return false;
        }
        if (!Main.getAdminAuthService().canUploadClientReleaseByToken(token)) {
            response.setStatus(HttpStatus.FORBIDDEN_403);
            sendErrorResponse(response, "admin.release.permission_required");
            return false;
        }
        return true;
    }

    private static boolean isValidVersionToken(String ver) {
        if (ver.length() > 64) {
            return false;
        }
        for (int i = 0; i < ver.length(); i++) {
            char c = ver.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }



    private record VersionUpdateRequest(String androidVer, String pcVer) {
    }
}
