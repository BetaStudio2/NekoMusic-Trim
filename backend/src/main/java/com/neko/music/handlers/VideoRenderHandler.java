package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.VideoRenderJob;
import com.neko.music.service.VideoRenderQuotaService;
import com.neko.music.service.VideoRenderService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.VideoRenderPaths;
import com.neko.music.util.VipUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 横屏短视频渲染（异步队列，立即返回 jobId）：
 * <ul>
 *   <li>POST /api/video/render/create — 创建任务</li>
 *   <li>GET /api/video/render/{jobId} — 查询状态</li>
 *   <li>GET /api/video/render/{jobId}/download — 下载成片（无需登录）</li>
 * </ul>
 */
public class VideoRenderHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(VideoRenderHandler.class);
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("^[0-9a-fA-F-]{36}$");

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        if (!isCreatePath(req)) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "admin.lyrics.endpoint_not_found");
            return;
        }
        handleCreate(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        String pathInfo = normalizePathInfo(req.getPathInfo());
        if (pathInfo == null || pathInfo.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "admin.lyrics.endpoint_not_found");
            return;
        }
        if (pathInfo.endsWith("/download")) {
            String jobId = pathInfo.substring(0, pathInfo.length() - "/download".length());
            if (jobId.startsWith("/")) {
                jobId = jobId.substring(1);
            }
            handleDownload(req, resp, jobId);
            return;
        }
        String jobId = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        handleStatus(req, resp, jobId);
    }

    private void handleCreate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ConfigManager cfg = Main.getConfigManager();
        if (!cfg.isVideoRenderEnabled()) {
            sendJson(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, false, "video.disabled");
            return;
        }

        Optional<Integer> userIdOpt = requireUser(req);
        if (userIdOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "auth.login_required");
            return;
        }
        int userId = userIdOpt.get();

        String body = readBody(req);
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "vip.body_not_json");
            return;
        }
        // 数值字段必须先确认类型再转换：Gson 的 getAsInt()/getAsDouble() 对字符串等非数字原语会抛
        // NumberFormatException，一旦冒到容器层就会连异常类名与用户原始输入一起回显（CWE-209）。
        JsonElement musicIdEl = root == null ? null : root.get("musicId");
        if (musicIdEl == null || !musicIdEl.isJsonPrimitive()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.music_id_required");
            return;
        }
        if (!musicIdEl.getAsJsonPrimitive().isNumber()) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.music_id_not_number");
            return;
        }
        int musicId;
        try {
            musicId = musicIdEl.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.music_id_not_number");
            return;
        }
        if (musicId <= 0) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.music_id_invalid");
            return;
        }

        double startSec = 0;
        if (root.has("startSec") && !root.get("startSec").isJsonNull()) {
            JsonElement startSecEl = root.get("startSec");
            if (!startSecEl.isJsonPrimitive() || !startSecEl.getAsJsonPrimitive().isNumber()) {
                sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.start_sec_not_number");
                return;
            }
            try {
                startSec = startSecEl.getAsDouble();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.start_sec_not_number");
                return;
            }
            if (!Double.isFinite(startSec)) {
                sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.start_sec_invalid");
                return;
            }
            if (startSec < 0) {
                sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.start_sec_negative");
                return;
            }
        }

        Optional<MusicMeta> metaOpt = loadMusicMeta(musicId);
        if (metaOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "music.not_found");
            return;
        }
        MusicMeta meta = metaOpt.get();

        Optional<Path> audioOpt = VideoRenderService.resolveAudio(musicId);
        if (audioOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "video.audio_missing");
            return;
        }

        Timestamp vipExpires = Main.getUserAuthService().findVipExpiresAtByUserId(userId).orElse(null);
        boolean isVip = VipUtil.isVipActiveNow(vipExpires);

        int trackDuration = meta.durationSec > 0 ? meta.durationSec : 300;
        if (startSec >= trackDuration) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.start_sec_too_late");
            return;
        }

        Boolean watermarkedParam = null;
        if (root.has("watermarked") && root.get("watermarked").isJsonPrimitive()) {
            watermarkedParam = root.get("watermarked").getAsBoolean();
        }

        double clipDuration;
        boolean watermarked;
        Integer remainingToday = null;

        if (isVip) {
            clipDuration = trackDuration - startSec;
            watermarked = watermarkedParam != null ? watermarkedParam : false;
        } else {
            if (watermarkedParam != null && !watermarkedParam) {
                sendJson(resp, HttpServletResponse.SC_FORBIDDEN, false, "video.nonvip.watermark_required");
                return;
            }
            int limit = cfg.getVideoRenderNonVipDailyLimit();
            int maxSec = cfg.getVideoRenderNonVipMaxDurationSec();
            double maxStart = Math.max(0, trackDuration - maxSec);
            if (startSec > maxStart + 0.001) {
                sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                        ApiMessages.translate("video.nonvip.range_too_long", maxSec));
                return;
            }
            VideoRenderQuotaService quota = Main.getVideoRenderQuotaService();
            int remaining = quota.reserveDailySlot(userId);
            if (remaining < 0) {
                sendJson(resp, 429, false,
                        ApiMessages.translate("video.limit.daily_exhausted", limit));
                return;
            }
            remainingToday = remaining;
            clipDuration = Math.min(maxSec, trackDuration - startSec);
            watermarked = true;
        }

        if (clipDuration <= 0.5) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.clip_too_short");
            return;
        }

        String jobId = UUID.randomUUID().toString();
        VideoRenderJob job = new VideoRenderJob();
        job.setId(jobId);
        job.setUserId(userId);
        job.setMusicId(musicId);
        job.setStartSec(startSec);
        job.setDurationSec(clipDuration);
        job.setWatermarked(watermarked);

        try {
            Main.getVideoRenderJobStore().insertPending(job);
        } catch (Exception e) {
            logger.error("创建视频任务失败 userId={} musicId={}", userId, musicId, e);
            sendJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "video.task_create_failed");
            return;
        }

        Optional<Path> coverOpt;
        try {
            coverOpt = VideoRenderService.resolveCover(musicId, jobId);
        } catch (IOException e) {
            logger.warn("读取视频渲染封面失败 jobId={} musicId={}: {}", jobId, musicId, e.getMessage());
            coverOpt = Optional.empty();
        }
        try {
            Main.getVideoRenderService().submit(job, meta.title, meta.artist, audioOpt.get(), coverOpt);
        } catch (RejectedExecutionException e) {
            coverOpt.ifPresent(VideoRenderHandler::deleteTempCoverQuietly);
            jobDbMarkFailed(jobId, "video.queue_full");
            sendJson(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, false, "video.queue_full");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("jobId", jobId);
        data.addProperty("status", "pending");
        data.addProperty("isVip", isVip);
        data.addProperty("durationSec", clipDuration);
        data.addProperty("watermarked", watermarked);
        data.addProperty("musicId", musicId);
        if (remainingToday != null) {
            data.addProperty("remainingToday", remainingToday);
        }

        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("message", ApiMessages.translate("video.task_created"));
        out.add("data", data);
        sendRawJson(resp, HttpServletResponse.SC_ACCEPTED, out);
    }

    private static void deleteTempCoverQuietly(Path coverPath) {
        try {
            Files.deleteIfExists(coverPath);
        } catch (IOException e) {
            logger.warn("删除排队失败的视频临时封面失败: {}", coverPath, e);
        }
    }

    private static void jobDbMarkFailed(String jobId, String message) {
        try {
            Main.getVideoRenderJobStore().markFailed(jobId, message);
        } catch (Exception ignored) {
        }
    }

    private void handleStatus(HttpServletRequest req, HttpServletResponse resp, String jobId) throws IOException {
        if (!isValidJobId(jobId)) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.task_id_invalid");
            return;
        }
        Optional<Integer> userIdOpt = requireUser(req);
        if (userIdOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "auth.login_required");
            return;
        }

        Optional<VideoRenderJob> jobOpt = Main.getVideoRenderJobStore()
                .findByIdAndUserId(jobId, userIdOpt.get());
        if (jobOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "video.task_not_found");
            return;
        }
        VideoRenderJob job = jobOpt.get();

        JsonObject data = new JsonObject();
        data.addProperty("jobId", job.getId());
        data.addProperty("status", job.getStatus());
        data.addProperty("musicId", job.getMusicId());
        data.addProperty("durationSec", job.getDurationSec());
        data.addProperty("watermarked", job.isWatermarked());
        if (job.getErrorMessage() != null) {
            data.addProperty("error", job.getErrorMessage());
        }
        if ("done".equals(job.getStatus())) {
            data.addProperty("downloadUrl", "/api/video/render/" + job.getId() + "/download");
        }

        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.add("data", data);
        sendRawJson(resp, HttpServletResponse.SC_OK, out);
    }

    /** 仅凭 jobId 下载成片，无需登录或 token（jobId 为 UUID，难以猜测） */
    private void handleDownload(HttpServletRequest req, HttpServletResponse resp, String jobId) throws IOException {
        if (!isValidJobId(jobId)) {
            sendJson(resp, HttpServletResponse.SC_BAD_REQUEST, false, "video.task_id_invalid");
            return;
        }

        Optional<VideoRenderJob> jobOpt = Main.getVideoRenderJobStore().findById(jobId);
        if (jobOpt.isEmpty()) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "video.task_not_found");
            return;
        }
        streamDoneJobVideo(req, resp, jobOpt.get());
    }

    private void streamDoneJobVideo(HttpServletRequest req, HttpServletResponse resp, VideoRenderJob job)
            throws IOException {
        if (!"done".equals(job.getStatus())) {
            sendJson(resp, HttpServletResponse.SC_CONFLICT, false, "video.not_ready");
            return;
        }

        Path file = VideoRenderPaths.outputFile(job.getId());
        if (!Files.isRegularFile(file) || !VideoRenderPaths.isAllowedOutput(file)) {
            sendJson(resp, HttpServletResponse.SC_NOT_FOUND, false, "video.file_missing");
            return;
        }

        String etag = HttpResourceCache.strongEtagForFile(file);
        if (HttpResourceCache.sendNotModifiedIfFresh(req, resp, etag)) {
            return;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("video/mp4");
        resp.setHeader("Content-Disposition", "attachment; filename=\"neko-clip-" + job.getMusicId() + ".mp4\"");
        HttpResourceCache.applyFileCachingHeaders(file, resp);
        resp.setContentLengthLong(Files.size(file));

        try (OutputStream os = resp.getOutputStream()) {
            Files.copy(file, os);
        }
    }

    private static Optional<Integer> requireUser(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        return Main.getUserAuthService().validateToken(auth == null ? "" : auth);
    }

    private static Optional<MusicMeta> loadMusicMeta(int musicId) {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT title, artist, duration FROM music WHERE id = ? LIMIT 1")) {
            ps.setInt(1, musicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    MusicMeta m = new MusicMeta();
                    m.title = rs.getString("title");
                    m.artist = rs.getString("artist");
                    m.durationSec = rs.getInt("duration");
                    return Optional.of(m);
                }
            }
        } catch (Exception e) {
            logger.error("读取音乐信息失败 musicId={}", musicId, e);
        }
        return Optional.empty();
    }

    private static boolean isCreatePath(HttpServletRequest req) {
        return "/create".equals(normalizePathInfo(req.getPathInfo()));
    }

    private static String normalizePathInfo(String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty()) {
            return "";
        }
        return pathInfo.endsWith("/") && pathInfo.length() > 1
                ? pathInfo.substring(0, pathInfo.length() - 1)
                : pathInfo;
    }

    private static boolean isValidJobId(String jobId) {
        return jobId != null && JOB_ID_PATTERN.matcher(jobId).matches();
    }




    private static final class MusicMeta {
        String title;
        String artist;
        int durationSec;
    }
}
