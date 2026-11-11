package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.service.MusicRecognitionService;
import com.neko.music.service.RecognitionRateLimiter;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.util.MusicLookup;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Accepts a short recording and matches it against the locally hosted music library. */
public final class MusicRecognitionHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicRecognitionHandler.class);
    private static final long MULTIPART_OVERHEAD_ALLOWANCE = 1024L * 1024L;

    static final RecognitionRateLimiter RATE_LIMITER = new RecognitionRateLimiter();

    public static RecognitionRateLimiter.Decision acquireRecognitionSlot(String address, int limit) {
        return RATE_LIMITER.tryAcquire(address, limit);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!Main.getConfigManager().isMusicRecognitionEnabled()) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE_503, "recognition.disabled");
            return;
        }

        RecognitionRateLimiter.Decision rateDecision = RATE_LIMITER.tryAcquire(
                clientAddress(request),
                Main.getConfigManager().getMusicRecognitionRateLimitPerMinute());
        if (!rateDecision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(rateDecision.retryAfterSeconds()));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS_429, "recognition.too_frequent");
            return;
        }

        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            writeError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE_415,
                    "recognition.multipart_required");
            return;
        }
        long maxUploadBytes = Main.getConfigManager().getMusicRecognitionMaxUploadBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxUploadBytes + MULTIPART_OVERHEAD_ALLOWANCE) {
            writeError(response, HttpStatus.PAYLOAD_TOO_LARGE_413, maxUploadMessage(maxUploadBytes));
            return;
        }

        Part audioPart;
        try {
            audioPart = request.getPart("audio");
        } catch (IllegalStateException e) {
            writeError(response, HttpStatus.PAYLOAD_TOO_LARGE_413, maxUploadMessage(maxUploadBytes));
            return;
        } catch (ServletException e) {
            writeError(response, HttpStatus.BAD_REQUEST_400, "recognition.unparsable");
            return;
        }
        if (audioPart == null || audioPart.getSize() <= 0) {
            writeError(response, HttpStatus.BAD_REQUEST_400, "recognition.audio_field_required");
            return;
        }
        if (audioPart.getSize() > maxUploadBytes) {
            writeError(response, HttpStatus.PAYLOAD_TOO_LARGE_413, maxUploadMessage(maxUploadBytes));
            return;
        }

        Path uploaded = Files.createTempFile("music-recognize-", safeSuffix(audioPart.getSubmittedFileName()));
        try {
            try (InputStream input = audioPart.getInputStream()) {
                copyWithLimit(input, uploaded, maxUploadBytes);
            }
            Optional<MusicRecognitionService.RecognitionResult> result =
                    Main.getMusicRecognitionService().recognize(uploaded);
            if (result.isEmpty()) {
                writeNoMatch(response);
            } else {
                writeMatch(response, result.get());
            }
        } catch (UploadTooLargeException e) {
            writeError(response, HttpStatus.PAYLOAD_TOO_LARGE_413, maxUploadMessage(maxUploadBytes));
        } catch (MusicRecognitionService.AudioTooLongException
                 | MusicRecognitionService.InvalidAudioException e) {
            writeError(response, HttpStatus.BAD_REQUEST_400, e.getMessage());
        } catch (MusicRecognitionService.BusyException e) {
            response.setHeader("Retry-After", "2");
            writeError(response, HttpStatus.TOO_MANY_REQUESTS_429, e.getMessage());
        } catch (MusicRecognitionService.IndexUnavailableException e) {
            logger.warn("声纹索引暂不可用: {}", e.getMessage());
            response.setHeader("Retry-After", "5");
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE_503, e.getMessage());
        } catch (IOException e) {
            logger.error("识曲音频处理失败", e);
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE_503, "recognition.service_unavailable");
        } finally {
            Files.deleteIfExists(uploaded);
        }
    }

    private static void writeMatch(
            HttpServletResponse response,
            MusicRecognitionService.RecognitionResult result) throws IOException {
        writeJson(response, HttpStatus.OK_200, matchResponse(result));
    }

    static ObjectNode matchResponse(MusicRecognitionService.RecognitionResult result) {
        MusicRecognitionService.Track track = result.track();
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("id", track.id());
        data.put("title", track.title());
        data.put("artist", track.artist());
        data.put("album", track.album());
        data.put("duration", track.duration());
        data.put("language", track.language());
        data.put("tags", track.tags());
        data.put("filePath", MusicAssetLocator.fileApiUrl(track.id()));
        data.put("coverFilePath", MusicAssetLocator.coverApiUrl(track.id()));
        data.put("coverUrl", MusicAssetLocator.coverApiUrl(track.id()));
        data.put("maxQuality", MusicLookup.findMaxQuality(track.id()));
        data.put("confidence", round(result.confidence(), 4));
        data.put("matchedLandmarks", result.matchedLandmarks());
        data.put("offsetSeconds", round(result.offsetSeconds(), 2));
        data.put("sampleDurationSeconds", round(result.sampleDurationSeconds(), 2));

        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", true);
        root.put("matched", true);
        root.put("message", ApiMessages.translate("recognition.ok"));
        root.set("data", data);
        return root;
    }

    private static void writeNoMatch(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.OK_200, noMatchResponse());
    }

    static ObjectNode noMatchResponse() {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", true);
        root.put("matched", false);
        root.put("message", ApiMessages.translate("recognition.not_in_library"));
        root.putNull("data");
        return root;
    }

    public static ObjectNode errorResponse(String message) {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", false);
        root.put("matched", false);
        root.put("message", message == null || message.isBlank()
                ? ApiMessages.translate("recognition.request_failed") : message);
        root.putNull("data");
        return root;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        ObjectNode root = Main.getObjectMapper().createObjectNode();
        root.put("success", false);
        root.put("matched", false);
        root.put("message", message == null || message.isBlank() ? ApiMessages.translate("recognition.request_failed") : message);
        root.putNull("data");
        writeJson(response, status, root);
    }

    private static void writeJson(HttpServletResponse response, int status, ObjectNode body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=utf-8");
        Main.getObjectMapper().writeValue(response.getWriter(), body);
    }

    private static void copyWithLimit(InputStream input, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new UploadTooLargeException();
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static String safeSuffix(String submittedName) {
        if (submittedName == null) {
            return ".upload";
        }
        String name = Path.of(submittedName).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 12) {
            return ".upload";
        }
        String suffix = name.substring(dot).toLowerCase(Locale.ROOT);
        return suffix.matches("\\.[a-z0-9]{1,10}") ? suffix : ".upload";
    }

    private static String clientAddress(HttpServletRequest request) {
        return com.neko.music.util.ClientIpResolver.clientIp(request);
    }

    private static String maxUploadMessage(long maxBytes) {
        return ApiMessages.translate("recognition.file_too_large", Math.max(1, maxBytes / (1024 * 1024)) + " MiB");
    }

    private static double round(double value, int decimalPlaces) {
        double scale = Math.pow(10d, decimalPlaces);
        return Math.round(value * scale) / scale;
    }

    private static final class UploadTooLargeException extends IOException {
    }
}
