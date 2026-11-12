package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.database.LyricsReportDatabaseManager;
import com.neko.music.model.Admin;
import com.neko.music.service.EmbeddedMetadataSyncService;
import com.neko.music.service.SearchIndexService;
import com.neko.music.util.AdminPermissionUtil;
import com.neko.music.util.DbTimeUtil;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.PermissionHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 后台歌词报错审核接口。管理端接口不写入公开 API 文档。 */
public final class AdminLyricsReportHandler extends ApiServlet {
    private final LyricsReportDatabaseManager reportDatabaseManager;

    public AdminLyricsReportHandler() {
        this(new LyricsReportDatabaseManager(Main.getDatabaseManager()));
    }

    AdminLyricsReportHandler(LyricsReportDatabaseManager reportDatabaseManager) {
        this.reportDatabaseManager = reportDatabaseManager;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Admin admin = requirePermission(request, response, AdminPermissionUtil.Permission.AUDIT_VIEW);
        if (admin == null) {
            return;
        }
        String path = request.getPathInfo();
        if (path == null || path.isBlank() || "/".equals(path) || "/pending".equals(path)) {
            listPending(response);
            return;
        }
        Long id = parseId(path);
        if (id == null) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.id_invalid");
            return;
        }
        try {
            Optional<LyricsReportDatabaseManager.Report> report = reportDatabaseManager.findById(id);
            if (report.isEmpty()) {
                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "lyrics.report.not_found");
                return;
            }
            sendResponse(response, true, "lyrics.report.detail_ok", toDetailMap(report.get()));
        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "lyrics.report.load_failed");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        String path = request.getPathInfo();
        if (path == null || path.isBlank()) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "lyrics.report.not_found");
            return;
        }
        boolean approve = path.endsWith("/approve");
        boolean reject = path.endsWith("/reject");
        if (!approve && !reject) {
            sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "lyrics.report.not_found");
            return;
        }
        AdminPermissionUtil.Permission permission = approve
                ? AdminPermissionUtil.Permission.AUDIT_APPROVE
                : AdminPermissionUtil.Permission.AUDIT_REJECT;
        Admin admin = requirePermission(request, response, permission);
        if (admin == null) {
            return;
        }
        Long id = parseId(path.substring(0, path.lastIndexOf('/')));
        if (id == null) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.id_invalid");
            return;
        }
        String note = readReviewNote(request);
        try {
            if (approve) {
                approve(response, id, admin, note);
            } else {
                reject(response, id, admin, note);
            }
        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    approve ? "lyrics.report.approve_failed" : "lyrics.report.reject_failed");
        }
    }

    private void listPending(HttpServletResponse response) throws IOException {
        try {
            List<LyricsReportDatabaseManager.Report> reports = reportDatabaseManager.listPending();
            List<Integer> userIds = reports.stream().map(LyricsReportDatabaseManager.Report::userId)
                    .distinct().toList();
            Map<Integer, String> nicknames = Main.getNicknameResolver()
                    .resolveByLocalUserIds(userIds, "未知用户");
            List<Map<String, Object>> data = new ArrayList<>(reports.size());
            for (LyricsReportDatabaseManager.Report report : reports) {
                Map<String, Object> row = baseMap(report);
                row.put("userNickname",
                        nicknames.getOrDefault(report.userId(), "未知用户"));
                data.add(row);
            }
            sendResponse(response, true, "lyrics.report.list_ok", Map.of("reports", data));
        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "lyrics.report.load_failed");
        }
    }

    private void approve(
            HttpServletResponse response,
            long id,
            Admin admin,
            String reviewNote
    ) throws Exception {
        LyricsReportDatabaseManager.Report report;
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                report = reportDatabaseManager.findPendingForUpdate(conn, id).orElse(null);
                if (report == null) {
                    conn.rollback();
                    sendErrorResponse(response, HttpServletResponse.SC_CONFLICT,
                            "lyrics.report.already_reviewed");
                    return;
                }
                Main.getLyricsDatabaseManager().upsert(
                        conn, report.musicId(), report.content(), NekoLyrics.FORMAT);
                if (!reportDatabaseManager.markReviewed(conn, id, "approved", admin.id(), reviewNote)) {
                    throw new SQLException("歌词报错状态更新失败");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        rebuildLyricsSideEffects(report.musicId());
        notifyReporter(report,
                "lyrics_report_approved",
                "歌词报错已采纳",
                "你对《" + report.title() + "》的歌词修改已通过审核。");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", "approved");
        sendResponse(response, true, "lyrics.report.approved", data);
    }

    private void reject(
            HttpServletResponse response,
            long id,
            Admin admin,
            String reviewNote
    ) throws Exception {
        LyricsReportDatabaseManager.Report report;
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                report = reportDatabaseManager.findPendingForUpdate(conn, id).orElse(null);
                if (report == null) {
                    conn.rollback();
                    sendErrorResponse(response, HttpServletResponse.SC_CONFLICT,
                            "lyrics.report.already_reviewed");
                    return;
                }
                if (!reportDatabaseManager.markReviewed(conn, id, "rejected", admin.id(), reviewNote)) {
                    throw new SQLException("歌词报错状态更新失败");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        notifyReporter(report,
                "lyrics_report_rejected",
                "歌词报错未通过",
                reviewNote == null || reviewNote.isBlank()
                        ? "你对《" + report.title() + "》的歌词修改未通过审核。"
                        : "审核备注：" + reviewNote);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", "rejected");
        sendResponse(response, true, "lyrics.report.rejected", data);
    }

    private void rebuildLyricsSideEffects(int musicId) {
        try {
            SearchIndexService.notifyMusicChanged(musicId);
        } catch (Exception e) {
            // 索引失败不影响审核结果，下次启动会重建。
        }
        try {
            EmbeddedMetadataSyncService.syncOne(musicId);
        } catch (Exception e) {
            // 标签同步是后置增强，失败不影响歌词已入库。
        }
    }

    private void notifyReporter(
            LyricsReportDatabaseManager.Report report,
            String type,
            String title,
            String body
    ) {
        if (Main.getUserNotificationService() == null) {
            return;
        }
        try {
            Main.getUserNotificationService().notify(
                    report.userId(), type, title, body, "/detail/" + report.musicId(), null);
        } catch (Exception ignored) {
            // 通知失败不改变审核结果。
        }
    }

    private static Admin requirePermission(
            HttpServletRequest request,
            HttpServletResponse response,
            AdminPermissionUtil.Permission permission
    ) {
        if (!PermissionHelper.checkPermission(request, response, permission)) {
            return null;
        }
        return PermissionHelper.getAdminFromRequest(request);
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.replace("/", "").trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String readReviewNote(HttpServletRequest request) throws IOException {
        String body = readBody(request);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            ReviewRequest review = Main.getObjectMapper().readValue(body, ReviewRequest.class);
            if (review == null) {
                return null;
            }
            String note = review.reviewNote() != null ? review.reviewNote() : review.reason();
            if (note == null || note.isBlank()) {
                return null;
            }
            String trimmed = note.trim();
            return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> baseMap(LyricsReportDatabaseManager.Report report) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", report.id());
        row.put("musicId", report.musicId());
        row.put("title", report.title());
        row.put("artist", report.artist());
        row.put("userId", report.userId());
        row.put("note", report.note());
        row.put("status", report.status());
        row.put("createdAt", DbTimeUtil.formatInShanghai(report.createdAt()));
        row.put("stats", contentStats(report.content()));
        return row;
    }

    private static Map<String, Integer> contentStats(String content) {
        NekoLyrics.Document document = NekoLyrics.deserialize(content).orElse(null);
        if (document == null || document.isEmpty()) {
            return Map.of("lines", 0, "translated", 0, "words", 0);
        }
        int lines = 0;
        int translated = 0;
        int words = 0;
        for (NekoLyrics.Line line : document.lines()) {
            if (!line.text().isBlank()) {
                lines++;
            }
            if (line.translation() != null && !line.translation().isBlank()) {
                translated++;
            }
            words += line.words().size();
        }
        return Map.of("lines", lines, "translated", translated, "words", words);
    }

    private static Map<String, Object> toDetailMap(LyricsReportDatabaseManager.Report report) {
        Map<String, Object> row = baseMap(report);
        row.put("content", report.content());
        row.put("originalContent", report.originalContent());
        row.put("originalFormat", report.originalFormat());
        row.put("reviewNote", report.reviewNote());
        row.put("reviewedAt", DbTimeUtil.formatInShanghai(report.reviewedAt()));
        return row;
    }

    private record ReviewRequest(String reviewNote, String reason) {
    }
}
