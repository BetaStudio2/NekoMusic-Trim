package com.neko.music.handlers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.model.Playlist;
import com.neko.music.service.ExternalImportService;
import com.neko.music.service.LxmcPlaylistParser;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.SensitiveWordUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 落雪音乐 {@code .lxmc} 歌单上传、预览与后台导入。
 *
 * <ul>
 *   <li>{@code POST /loser/lxmc/inspect}：解析上传文件，返回其中可导入的歌单列表。</li>
 *   <li>{@code POST /loser/lxmc/pull}：重新解析并校验文件，随后异步匹配曲目并写入目标歌单，
 *       进度与其它外部歌单导入一样通过 SSE 返回。</li>
 * </ul>
 */
public final class LxmcImportHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(LxmcImportHandler.class);
    private static final long MULTIPART_OVERHEAD_ALLOWANCE = 1024L * 1024L;
    private static final int MAX_PLAYLIST_ID_LENGTH = 128;
    private static final String ALL_PLAYLISTS = "all";
    private static final LxmcPlaylistParser PARSER = new LxmcPlaylistParser();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        Integer userId = RequestAuthUtil.authenticate(request);
        if (userId == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }

        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            writeError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "lxmc.multipart_required");
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > LxmcPlaylistParser.MAX_COMPRESSED_BYTES + MULTIPART_OVERHEAD_ALLOWANCE) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, fileTooLargeMessage());
            return;
        }

        Part filePart;
        try {
            filePart = request.getPart("file");
        } catch (IllegalStateException e) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, fileTooLargeMessage());
            return;
        } catch (ServletException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "lxmc.invalid_file");
            return;
        }
        if (filePart == null || filePart.getSize() <= 0) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "lxmc.file_required");
            return;
        }
        if (filePart.getSize() > LxmcPlaylistParser.MAX_COMPRESSED_BYTES) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, fileTooLargeMessage());
            return;
        }

        LxmcPlaylistParser.Document document;
        try {
            document = PARSER.parse(filePart.getInputStream());
        } catch (LxmcPlaylistParser.LxmcTooLargeException e) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, fileTooLargeMessage());
            return;
        } catch (LxmcPlaylistParser.LxmcFormatException e) {
            logger.info("落雪歌单文件解析失败: userId={}, reason={}", userId, e.getMessage());
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "lxmc.invalid_file");
            return;
        }

        if (isInspectRequest(request)) {
            writeInspectResponse(response, document);
            return;
        }

        String requestedPlaylistId = trimToNull(request.getParameter("playlistId"));
        if (requestedPlaylistId != null && requestedPlaylistId.length() > MAX_PLAYLIST_ID_LENGTH) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "lxmc.playlist_id_invalid");
            return;
        }
        if (requestedPlaylistId == null && document.playlists().size() != 1) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "lxmc.playlist_required");
            return;
        }
        List<LxmcPlaylistParser.Playlist> selectedPlaylists = selectPlaylists(document, requestedPlaylistId);
        if (selectedPlaylists.isEmpty()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "lxmc.playlist_not_found");
            return;
        }

        TargetResolution target = resolveTarget(request, response, userId);
        if (target == null) {
            return;
        }

        AsyncContext asyncContext;
        try {
            asyncContext = request.startAsync();
        } catch (IllegalStateException e) {
            logger.warn("落雪歌单导入 SSE 异步上下文创建失败", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "qr.sse_unsupported");
            return;
        }
        asyncContext.setTimeout(0);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = response.getWriter();
            writer.print(": connected\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            logger.warn("落雪歌单导入 SSE 响应初始化失败: {}", e.getMessage());
            completeQuietly(asyncContext);
            return;
        }

        ExternalImportService.ImportTarget importTarget = new ExternalImportService.ImportTarget(
                userId, target.playlistId(), target.playlistName(), target.created());
        ExternalImportService.Listener listener = new ExternalImportSseListener(asyncContext, writer);
        List<ExternalImportService.Track> tracks = selectedPlaylists.stream()
                .flatMap(playlist -> playlist.tracks().stream())
                .map(track -> new ExternalImportService.Track(
                        track.sourceId(), track.title(), track.artist()))
                .toList();
        Main.getExternalImportService().startLxmcImport(tracks, importTarget, listener);
    }

    private static boolean isInspectRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        return path != null && path.endsWith("/inspect");
    }

    static List<LxmcPlaylistParser.Playlist> selectPlaylists(
            LxmcPlaylistParser.Document document, String requestedPlaylistId) {
        if (ALL_PLAYLISTS.equals(requestedPlaylistId)) {
            return document.playlists();
        }
        if (requestedPlaylistId != null) {
            return document.playlists().stream()
                    .filter(playlist -> requestedPlaylistId.equals(playlist.id()))
                    .toList();
        }
        if (document.playlists().size() == 1) {
            return List.of(document.playlists().get(0));
        }
        return List.of();
    }

    private static void writeInspectResponse(HttpServletResponse response, LxmcPlaylistParser.Document document)
            throws IOException {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("totalTrackCount", document.totalTrackCount());
        ArrayNode playlists = data.putArray("playlists");
        for (LxmcPlaylistParser.Playlist playlist : document.playlists()) {
            ObjectNode item = playlists.addObject();
            item.put("id", playlist.id());
            item.put("name", playlist.name());
            item.put("trackCount", playlist.tracks().size());
            ObjectNode sources = item.putObject("sources");
            for (Map.Entry<String, Integer> source : playlist.sourceCounts().entrySet()) {
                sources.put(source.getKey(), source.getValue());
            }
        }
        root.put("success", true);
        root.put("message", ApiMessages.translate("lxmc.inspect_ok"));
        writeJson(response, HttpServletResponse.SC_OK, root);
    }

    private static TargetResolution resolveTarget(
            HttpServletRequest request, HttpServletResponse response, int userId) throws IOException {
        String targetPlaylistIdParam = request.getParameter("targetPlaylistId");
        Integer targetPlaylistId = parseIntParam(targetPlaylistIdParam);
        if (targetPlaylistId == null && targetPlaylistIdParam != null && !targetPlaylistIdParam.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiMessages.translate("external.target_playlist_id_not_number"));
            return null;
        }
        if (targetPlaylistId != null) {
            if (!Main.getPlaylistService().isPlaylistOwner(targetPlaylistId, userId)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "playlist.no_permission_update");
                return null;
            }
            return new TargetResolution(targetPlaylistId, null, false);
        }

        String targetPlaylistName = trimToNull(request.getParameter("targetPlaylistName"));
        if (targetPlaylistName == null) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiMessages.translate("external.target_required"));
            return null;
        }
        if (targetPlaylistName.length() > 255) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "playlist.name_too_long");
            return null;
        }
        if (SensitiveWordUtil.contains(targetPlaylistName)) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "playlist.name_contains_banned");
            return null;
        }
        Optional<Playlist> created = Main.getPlaylistService()
                .createPlaylist(userId, targetPlaylistName, "由落雪歌单导入创建");
        if (created.isEmpty()) {
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiMessages.translate("external.playlist_create_failed"));
            return null;
        }
        return new TargetResolution(created.get().id(), created.get().name(), true);
    }

    private static Integer parseIntParam(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,20}")) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fileTooLargeMessage() {
        return ApiMessages.translate("lxmc.file_too_large",
                LxmcPlaylistParser.MAX_COMPRESSED_BYTES / (1024 * 1024) + " MiB");
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", false);
        root.put("message", ApiMessages.translate(message));
        writeJson(response, status, root);
    }

    private static void writeJson(HttpServletResponse response, int status, ObjectNode body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        Main.getObjectMapper().writeValue(response.getWriter(), body);
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 已结束
        }
    }

    private record TargetResolution(int playlistId, String playlistName, boolean created) {
    }
}
