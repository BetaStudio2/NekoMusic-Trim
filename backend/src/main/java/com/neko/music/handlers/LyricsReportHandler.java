package com.neko.music.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.Main;
import com.neko.music.database.LyricsDatabaseManager;
import com.neko.music.database.LyricsReportDatabaseManager;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.NekoLyrics;
import com.neko.music.util.RequestAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 用户提交歌词报错：{@code POST /api/lyrics/reports}。 */
public final class LyricsReportHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(LyricsReportHandler.class);
    private static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_LINES = 5000;
    private static final ObjectMapper MAPPER = Main.getObjectMapper();

    private final LyricsReportDatabaseManager reportDatabaseManager;
    private final LyricsDatabaseManager lyricsDatabaseManager;

    public LyricsReportHandler() {
        this(new LyricsReportDatabaseManager(Main.getDatabaseManager()),
                Main.getLyricsDatabaseManager());
    }

    LyricsReportHandler(
            LyricsReportDatabaseManager reportDatabaseManager,
            LyricsDatabaseManager lyricsDatabaseManager
    ) {
        this.reportDatabaseManager = reportDatabaseManager;
        this.lyricsDatabaseManager = lyricsDatabaseManager;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);
        Integer userId = RequestAuthUtil.authenticate(request);
        if (userId == null) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
            return;
        }

        SubmitRequest body;
        try {
            body = MAPPER.readValue(readBody(request), SubmitRequest.class);
        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.bad_request");
            return;
        }
        if (body == null || body.musicId() == null || body.musicId() <= 0
                || body.content() == null || body.content().isBlank()) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.invalid");
            return;
        }
        if (body.content().getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.too_large");
            return;
        }
        String note = cleanNote(body.note());
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.note_too_long");
            return;
        }

        NekoLyrics.Document document;
        try {
            document = NekoLyrics.deserialize(body.content())
                    .orElseThrow(() -> new IllegalArgumentException("invalid neko lyrics"));
        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.invalid_lyrics");
            return;
        }
        if (document.isEmpty()) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "lyrics.report.empty_lyrics");
            return;
        }
        if (document.lines().size() > MAX_LINES) {
            sendErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiMessages.translate("lyrics.report.too_many_lines", MAX_LINES));
            return;
        }

        try {
            if (!musicExists(body.musicId())) {
                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "music.not_found");
                return;
            }
            if (reportDatabaseManager.findPendingId(userId, body.musicId()).isPresent()) {
                sendErrorResponse(response, HttpServletResponse.SC_CONFLICT, "lyrics.report.already_pending");
                return;
            }
            Optional<LyricsDatabaseManager.StoredLyrics> original =
                    lyricsDatabaseManager.findByMusicId(body.musicId());
            long reportId = reportDatabaseManager.create(
                    body.musicId(),
                    userId,
                    NekoLyrics.serialize(document),
                    original.map(LyricsDatabaseManager.StoredLyrics::content).orElse(null),
                    original.map(LyricsDatabaseManager.StoredLyrics::format).orElse(null),
                    note
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", reportId);
            data.put("status", "pending");
            sendResponse(response, true, "lyrics.report.submitted", data);
        } catch (Exception e) {
            logger.warn("提交歌词报错失败 musicId={} userId={}", body.musicId(), userId, e);
            sendErrorResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "lyrics.report.submit_failed");
        }
    }

    private static boolean musicExists(int musicId) throws Exception {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM music WHERE id = ?")) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }

    private record SubmitRequest(Integer musicId, String content, String note) {
    }
}
