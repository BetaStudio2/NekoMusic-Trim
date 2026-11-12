package com.neko.music.handlers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.service.EmbeddedMetadataSyncService;
import com.neko.music.service.SearchIndexService;
import com.neko.music.util.AdminPermissionUtil;
import com.neko.music.util.PermissionHelper;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理后台歌词管理器：API 保持文件树形态，实际数据来自 music_lyrics。
 */
public class AdminLyricsFileHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(AdminLyricsFileHandler.class);
    private static final Pattern MUSIC_ID_FILE = Pattern.compile("(\\d+)\\.lrc", Pattern.CASE_INSENSITIVE);
    private static final long MAX_LYRICS_BYTES = 1024L * 1024L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PermissionHelper.checkPermission(request, response, AdminPermissionUtil.Permission.MUSIC_VIEW)) {
            return;
        }

        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.equals("/tree")) {
            listTree(response);
            return;
        }

        if (pathInfo.startsWith("/file/")) {
            readFile(pathInfo.substring("/file/".length()), response);
            return;
        }

        response.setStatus(HttpStatus.NOT_FOUND_404);
        sendErrorResponse(response, "admin.lyrics.endpoint_not_found");
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PermissionHelper.checkPermission(request, response, AdminPermissionUtil.Permission.MUSIC_EDIT)) {
            return;
        }

        String pathInfo = request.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/file/")) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.path_required");
            return;
        }

        SaveLyricsRequest saveRequest;
        try {
            String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            saveRequest = Main.getObjectMapper().readValue(body, SaveLyricsRequest.class);
        } catch (Exception e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "auth.bad_request");
            return;
        }

        if (saveRequest.content() == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "music.lyrics.content_required");
            return;
        }
        if (saveRequest.content().getBytes(StandardCharsets.UTF_8).length > MAX_LYRICS_BYTES) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.file_too_large");
            return;
        }

        Integer musicId = musicIdFromEncodedPath(pathInfo.substring("/file/".length()));
        if (musicId == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.invalid_path");
            return;
        }
        if (!musicMetaById().containsKey(musicId)) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            sendErrorResponse(response, "music.not_found_no_lyrics");
            return;
        }
        if (!Main.getLyricsDatabaseManager().upsert(musicId, saveRequest.content())) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            sendErrorResponse(response, "admin.lyrics.save_failed");
            return;
        }
        SearchIndexService.notifyMusicChanged(musicId);

        // 歌词变更后同步进音频文件（保留广告元数据与横幅）
        EmbeddedMetadataSyncService.syncOne(musicId);

        ObjectNode data = buildDbFileNode(musicId, saveRequest.content(), musicMetaById());
        sendResponse(response, true, "admin.lyrics.save_ok", data);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PermissionHelper.checkPermission(request, response, AdminPermissionUtil.Permission.MUSIC_EDIT)) {
            return;
        }

        String pathInfo = request.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/file/")) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.path_required");
            return;
        }

        Integer musicId = musicIdFromEncodedPath(pathInfo.substring("/file/".length()));
        if (musicId == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.invalid_path");
            return;
        }
        if (Main.getLyricsDatabaseManager().findByMusicId(musicId).isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            sendErrorResponse(response, "admin.lyrics.not_in_db");
            return;
        }

        Main.getLyricsDatabaseManager().delete(musicId);
        SearchIndexService.notifyMusicChanged(musicId);

        // 歌词删除后同步进音频文件（保留广告元数据与横幅）
        EmbeddedMetadataSyncService.syncOne(musicId);

        sendResponse(response, true, "admin.lyrics.delete_ok", Main.getObjectMapper().createObjectNode());
    }

    private void listTree(HttpServletResponse response) throws IOException {
        Map<Integer, MusicMeta> metaById = musicMetaById();
        List<LyricsDatabaseManager.AdminLyricsMeta> dbLyrics = Main.getLyricsDatabaseManager().findAllForAdmin();

        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("type", "directory");
        root.put("name", "lyrics");
        root.put("path", "");
        root.set("children", Main.getObjectMapper().createArrayNode());

        for (LyricsDatabaseManager.AdminLyricsMeta meta : dbLyrics) {
            appendNode(root, buildDbFileNode(meta, metaById));
        }

        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.set("tree", root);
        data.put("totalFiles", dbLyrics.size());
        data.put("basePath", "database");
        sendResponse(response, true, "admin.lyrics.query_ok", data);
    }

    private void readFile(String encodedPath, HttpServletResponse response) throws IOException {
        Integer musicId = musicIdFromEncodedPath(encodedPath);
        if (musicId == null) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            sendErrorResponse(response, "admin.lyrics.invalid_path");
            return;
        }
        var stored = Main.getLyricsDatabaseManager().findByMusicId(musicId);
        if (stored.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            sendErrorResponse(response, "admin.lyrics.not_in_db");
            return;
        }

        String lrc = stored.get().lrcContent();
        ObjectNode data = buildDbFileNode(musicId, lrc, musicMetaById());
        data.put("content", lrc);
        sendResponse(response, true, "admin.lyrics.read_ok", data);
    }

    private void appendNode(ObjectNode root, ObjectNode fileNode) {
        ArrayNode children = (ArrayNode) root.get("children");
        children.add(fileNode);
    }

    private ObjectNode buildDbFileNode(int musicId, String content, Map<Integer, MusicMeta> metaById) {
        int size = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        LyricsDatabaseManager.AdminLyricsMeta meta = new LyricsDatabaseManager.AdminLyricsMeta(
                musicId,
                null,
                null,
                size,
                Timestamp.from(Instant.now()),
                false,
                metaById.containsKey(musicId)
        );
        return buildDbFileNode(meta, metaById);
    }

    private ObjectNode buildDbFileNode(LyricsDatabaseManager.AdminLyricsMeta lyricsMeta, Map<Integer, MusicMeta> metaById) {
        int musicId = lyricsMeta.musicId();
        MusicMeta meta = metaById.get(musicId);
        String fileName = musicId + ".lrc";

        ObjectNode node = Main.getObjectMapper().createObjectNode();
        node.put("type", "file");
        node.put("name", fileName);
        node.put("path", fileName);
        node.put("size", lyricsMeta.sizeBytes());
        node.put("updatedAt", lyricsMeta.updatedAt() == null
                ? Instant.EPOCH.toString()
                : lyricsMeta.updatedAt().toInstant().toString());
        node.put("musicId", musicId);
        node.put("storage", "database");
        node.put("placeholder", lyricsMeta.placeholder());
        if (meta == null) {
            node.putNull("title");
            node.putNull("artist");
            node.put("existsInDb", false);
            node.put("displayName", fileName);
        } else {
            node.put("title", meta.title);
            node.put("artist", meta.artist);
            node.put("existsInDb", true);
            node.put("displayName", musicId + " - " + meta.title);
        }
        return node;
    }

    private Map<Integer, MusicMeta> musicMetaById() {
        Map<Integer, MusicMeta> metaById = new HashMap<>();
        String sql = "SELECT id, title, artist FROM music";
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                metaById.put(rs.getInt("id"), new MusicMeta(rs.getString("title"), rs.getString("artist")));
            }
        } catch (Exception e) {
            logger.warn("读取音乐元数据失败: {}", e.toString());
        }
        return metaById;
    }

    private Integer musicIdFromFile(String fileName) {
        Matcher matcher = MUSIC_ID_FILE.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer musicIdFromEncodedPath(String encodedPath) {
        try {
            String decoded = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8);
            decoded = decoded.replace('\\', '/');
            while (decoded.startsWith("/")) {
                decoded = decoded.substring(1);
            }
            if (decoded.isBlank() || decoded.contains("\0") || !decoded.toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                return null;
            }
            int slash = decoded.lastIndexOf('/');
            String fileName = slash >= 0 ? decoded.substring(slash + 1) : decoded;
            return musicIdFromFile(fileName);
        } catch (Exception e) {
            return null;
        }
    }



    private record SaveLyricsRequest(String content) {
    }
    private record MusicMeta(String title, String artist) {
    }
}
