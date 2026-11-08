package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.neko.music.Main;
import com.neko.music.model.Playlist;
import com.neko.music.service.ExternalImportService;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.SensitiveWordUtil;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 外部歌单导入（QQ / 网易云 / 酷狗 / 汽水音乐），SSE 推送进度，并把命中曲目加入用户指定的歌单。
 *
 * <ul>
 *   <li>{@code /loser/netease/pull?playlistId=|ids=&targetPlaylistId=}</li>
 *   <li>{@code /loser/qq/pull?disstid=&targetPlaylistId=}</li>
 *   <li>{@code /loser/kugou/pull?listid=&targetPlaylistId=}</li>
 *   <li>{@code /loser/qishui/pull?playlist_id=&targetPlaylistId=}（ID 或分享链接）</li>
 * </ul>
 *
 * <p>均需要用户令牌。曲目入库后可用返回的 {@code musicId} 通过
 * {@code /api/music/file/{id}} 获取音频。</p>
 *
 * <p>导入结束（成功或失败）后会向发起人写入一条站内消息，在线时经
 * {@code /api/user/notifications/stream} 实时推送。</p>
 */
public class ExternalImportHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(ExternalImportHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response);
    }

    private void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Integer userId = RequestAuthUtil.authenticate(request);
        if (userId == null) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "source.netease.token_missing");
            return;
        }

        boolean qq = request.getServletPath() != null && request.getServletPath().endsWith("/qq/pull");
        boolean kugou = request.getServletPath() != null && request.getServletPath().endsWith("/kugou/pull");
        boolean qishui = request.getServletPath() != null && request.getServletPath().endsWith("/qishui/pull");

        String targetPlaylistIdParam = request.getParameter("targetPlaylistId");
        Integer targetPlaylistId = parseIntParam(targetPlaylistIdParam);
        if (targetPlaylistId == null && targetPlaylistIdParam != null && !targetPlaylistIdParam.isBlank()) {
            writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, ApiMessages.translate("external.target_playlist_id_not_number"));
            return;
        }

        int resolvedPlaylistId;
        String resolvedPlaylistName = null;
        boolean playlistCreated = false;
        if (targetPlaylistId != null) {
            if (!Main.getPlaylistService().isPlaylistOwner(targetPlaylistId, userId)) {
                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "playlist.no_permission_update");
                return;
            }
            resolvedPlaylistId = targetPlaylistId;
        } else {
            String targetPlaylistName = request.getParameter("targetPlaylistName");
            targetPlaylistName = targetPlaylistName == null ? null : targetPlaylistName.trim();
            if (targetPlaylistName == null || targetPlaylistName.isEmpty()) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        ApiMessages.translate("external.target_required"));
                return;
            }
            if (targetPlaylistName.length() > 255) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "playlist.name_too_long");
                return;
            }
            if (SensitiveWordUtil.contains(targetPlaylistName)) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "playlist.name_contains_banned");
                return;
            }
            Optional<Playlist> created = Main.getPlaylistService()
                    .createPlaylist(userId, targetPlaylistName, "由外部歌单导入创建");
            if (created.isEmpty()) {
                writeJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiMessages.translate("external.playlist_create_failed"));
                return;
            }
            resolvedPlaylistId = created.get().id();
            resolvedPlaylistName = created.get().name();
            playlistCreated = true;
        }

        String disstid = null;
        String kugouListId = null;
        String qishuiPlaylist = null;
        Long neteasePlaylistId = null;
        List<Long> songIds = List.of();
        if (qq) {
            disstid = request.getParameter("disstid");
            if (disstid == null || !disstid.matches("[0-9]{1,19}")) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        ApiMessages.translate("external.disstid_required"));
                return;
            }
        } else if (qishui) {
            qishuiPlaylist = firstNonBlank(request.getParameter("playlist_id"),
                    request.getParameter("url"), request.getParameter("id"));
            if (qishuiPlaylist == null || qishuiPlaylist.isBlank() || qishuiPlaylist.length() > 2048) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "source.qishui.playlist_id_required");
                return;
            }
            qishuiPlaylist = qishuiPlaylist.trim();
        } else if (kugou) {
            kugouListId = request.getParameter("listid");
            if (kugouListId == null || kugouListId.isBlank() || kugouListId.length() > 255) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "source.kugou.listid_required");
                return;
            }
            kugouListId = kugouListId.trim();
        } else {
            String playlistParam = request.getParameter("playlistId");
            neteasePlaylistId = parseLongParam(playlistParam);
            if (neteasePlaylistId == null && playlistParam != null && !playlistParam.isBlank()) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, ApiMessages.translate("external.playlist_id_not_number"));
                return;
            }
            try {
                songIds = parseIds(request.getParameter("ids"));
            } catch (IllegalArgumentException e) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                return;
            }
            if (neteasePlaylistId == null && songIds.isEmpty()) {
                writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, ApiMessages.translate("external.playlist_required"));
                return;
            }
        }

        ExternalImportService importService = Main.getExternalImportService();

        AsyncContext asyncContext;
        try {
            asyncContext = request.startAsync();
        } catch (IllegalStateException e) {
            logger.warn("SSE 异步上下文创建失败", e);
            writeJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "qr.sse_unsupported");
            return;
        }
        asyncContext.setTimeout(0);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer;
        try {
            writer = response.getWriter();
            writer.print(": connected\n\n");
            writer.flush();
        } catch (IOException | IllegalStateException e) {
            logger.warn("SSE 响应初始化失败: {}", e.getMessage());
            completeQuietly(asyncContext);
            return;
        }

        ExternalImportService.Listener listener = new ExternalImportSseListener(asyncContext, writer);
        ExternalImportService.ImportTarget importTarget = new ExternalImportService.ImportTarget(
                userId, resolvedPlaylistId, resolvedPlaylistName, playlistCreated);
        if (qq) {
            importService.startQqImport(disstid, importTarget, listener);
        } else if (qishui) {
            importService.startQishuiImport(qishuiPlaylist, importTarget, listener);
        } else if (kugou) {
            importService.startKugouImport(kugouListId, importTarget, listener);
        } else {
            importService.startNeteaseImport(neteasePlaylistId, songIds, importTarget, listener);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void completeQuietly(AsyncContext asyncContext) {
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 已结束
        }
    }

    private static Integer parseIntParam(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,20}")) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongParam(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,20}")) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("[0-9]{1,20}")) {
                throw new IllegalArgumentException("ids 必须是逗号分隔的数字");
            }
            ids.add(Long.parseLong(value));
        }
        return ids;
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Main.getObjectMapper().createObjectNode()
                .put("code", status).put("msg", message).toString());
    }

}
