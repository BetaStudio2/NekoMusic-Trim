package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.MusicRecognitionService;
import com.neko.music.util.HttpResourceCache;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

/** Streams raw PCM frames and checks the growing sample against the local music index. */
public final class MusicRecognitionWebSocketEndpoint {
    public static final String PATH = "/api/music/recognize/ws";
    private static final Logger logger = LoggerFactory.getLogger(MusicRecognitionWebSocketEndpoint.class);
    private static final int INPUT_SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAX_BINARY_MESSAGE_BYTES = 64 * 1024;
    private byte[] pcm = new byte[0];
    private int pcmSize;
    private long totalAudioBytes;
    private boolean started;
    private boolean finished;
    private boolean streamSlotAcquired;
    private int nextCheckSeconds;
    private int maxDurationSeconds;

    public static ServerEndpointConfig endpointConfig() {
        return ServerEndpointConfig.Builder.create(MusicRecognitionWebSocketEndpoint.class, PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public void modifyHandshake(
                            ServerEndpointConfig config,
                            jakarta.websocket.server.HandshakeRequest request,
                            jakarta.websocket.HandshakeResponse response) {
                        response.getHeaders().put(
                                "Cache-Control", java.util.List.of(HttpResourceCache.CACHE_CONTROL_NO_STORE));
                    }
                })
                .build();
    }

    @OnOpen
    public void onOpen(Session session) {
        session.setMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BYTES);
        session.setMaxTextMessageBufferSize(4096);
        session.setMaxIdleTimeout(45_000L);
        ConfigManager config = Main.getConfigManager();
        if (config == null || !config.isMusicRecognitionEnabled()) {
            sendErrorAndClose(session, ApiMessages.translate("recognition.disabled"),
                    CloseReason.CloseCodes.TRY_AGAIN_LATER);
            return;
        }
        MusicRecognitionService service = Main.getMusicRecognitionService();
        if (service == null || !service.tryAcquireLiveStreamSlot()) {
            sendErrorAndClose(session, ApiMessages.translate("recognition.busy"),
                    CloseReason.CloseCodes.TRY_AGAIN_LATER);
            return;
        }
        streamSlotAcquired = true;
        send(session, "{\"type\":\"ready\"}");
    }

    @OnMessage
    public synchronized void onText(Session session, String text) {
        if (!session.isOpen() || finished) {
            return;
        }
        try {
            JsonNode command = Main.getObjectMapper().readTree(text);
            String type = command.path("type").asText("");
            if ("start".equals(type)) {
                start(session, command);
            } else if ("finish".equals(type)) {
                finish(session);
            } else {
                fail(session, "recognition.ws_invalid_command", CloseReason.CloseCodes.PROTOCOL_ERROR);
            }
        } catch (Exception e) {
            fail(session, "recognition.ws_invalid_command", CloseReason.CloseCodes.PROTOCOL_ERROR);
        }
    }

    @OnMessage
    public synchronized void onBinary(Session session, ByteBuffer message) {
        if (!session.isOpen() || finished) {
            return;
        }
        if (!started) {
            fail(session, "recognition.ws_start_required", CloseReason.CloseCodes.PROTOCOL_ERROR);
            return;
        }
        int length = message.remaining();
        if (length == 0 || (length & 1) != 0 || length > MAX_BINARY_MESSAGE_BYTES) {
            fail(session, "recognition.ws_invalid_audio", CloseReason.CloseCodes.VIOLATED_POLICY);
            return;
        }
        long maxBytes = (long) maxDurationSeconds * INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE;
        if (totalAudioBytes + length > maxBytes) {
            fail(session, "recognition.ws_duration_exceeded", CloseReason.CloseCodes.TOO_BIG);
            return;
        }
        totalAudioBytes += length;
        int requiredSize = pcmSize + length;
        if (requiredSize > pcm.length) {
            int maxCapacity = (int) maxBytes;
            int nextCapacity = Math.max(requiredSize, Math.max(4096, pcm.length * 2));
            pcm = Arrays.copyOf(pcm, Math.min(maxCapacity, nextCapacity));
        }
        message.get(pcm, pcmSize, length);
        pcmSize = requiredSize;

        int durationSeconds = (int) (totalAudioBytes / (INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE));
        int minDuration = Main.getConfigManager().getMusicRecognitionMinSampleDurationSeconds();
        if (durationSeconds >= minDuration && durationSeconds >= nextCheckSeconds) {
            nextCheckSeconds = durationSeconds + 1;
            tryRecognize(session, false);
        }
    }

    @OnClose
    public synchronized void onClose(Session session, CloseReason reason) {
        finished = true;
        pcm = new byte[0];
        pcmSize = 0;
        totalAudioBytes = 0;
        releaseStreamSlot();
    }

    @OnError
    public synchronized void onError(Session session, Throwable error) {
        if (error != null) {
            logger.debug("听歌识曲 WebSocket 异常: {}", error.getMessage());
        }
        finished = true;
        pcm = new byte[0];
        pcmSize = 0;
        totalAudioBytes = 0;
        releaseStreamSlot();
    }

    private void start(Session session, JsonNode command) {
        if (started || !command.path("format").asText("").equals("pcm_s16le")
                || command.path("sampleRate").asInt() != INPUT_SAMPLE_RATE
                || command.path("channels").asInt() != 1) {
            fail(session, "recognition.ws_invalid_format", CloseReason.CloseCodes.CANNOT_ACCEPT);
            return;
        }
        started = true;
        maxDurationSeconds = Main.getConfigManager().getMusicRecognitionMaxSampleDurationSeconds();
        int initialCapacity = Math.min(
                maxDurationSeconds * INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE,
                INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE * 4);
        pcm = new byte[initialCapacity];
        pcmSize = 0;
        totalAudioBytes = 0;
        nextCheckSeconds = Main.getConfigManager().getMusicRecognitionMinSampleDurationSeconds();
        ObjectNode ready = Main.getObjectMapper().createObjectNode();
        ready.put("type", "started");
        ready.put("sampleRate", INPUT_SAMPLE_RATE);
        ready.put("channels", 1);
        ready.put("maxDurationSeconds", maxDurationSeconds);
        send(session, ready.toString());
    }

    private void finish(Session session) {
        if (!started) {
            fail(session, "recognition.ws_start_required", CloseReason.CloseCodes.PROTOCOL_ERROR);
            return;
        }
        finished = true;
        int minimumBytes = Main.getConfigManager().getMusicRecognitionMinSampleDurationSeconds()
                * INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE;
        if (totalAudioBytes < minimumBytes) {
            sendErrorAndClose(session, "录音过短，请至少录制 "
                    + Main.getConfigManager().getMusicRecognitionMinSampleDurationSeconds() + " 秒",
                    CloseReason.CloseCodes.NORMAL_CLOSURE);
            return;
        }
        tryRecognize(session, true);
        pcm = new byte[0];
        close(session, CloseReason.CloseCodes.NORMAL_CLOSURE);
    }

    private void tryRecognize(Session session, boolean finalAttempt) {
        try {
            byte[] sample = Arrays.copyOf(pcm, pcmSize);
            Optional<MusicRecognitionService.RecognitionResult> result =
                    Main.getMusicRecognitionService().recognizePcm16k(sample);
            if (result.isPresent()) {
                ObjectNode response = MusicRecognitionHandler.matchResponse(result.get());
                response.put("type", "result");
                send(session, response.toString());
                finished = true;
                pcm = new byte[0];
                close(session, CloseReason.CloseCodes.NORMAL_CLOSURE);
            } else if (finalAttempt) {
                ObjectNode response = MusicRecognitionHandler.noMatchResponse();
                response.put("type", "result");
                send(session, response.toString());
            }
        } catch (MusicRecognitionService.BusyException e) {
            sendErrorAndClose(session, e.getMessage(), CloseReason.CloseCodes.TRY_AGAIN_LATER);
        } catch (MusicRecognitionService.IndexUnavailableException e) {
            sendErrorAndClose(session, e.getMessage(), CloseReason.CloseCodes.TRY_AGAIN_LATER);
        } catch (MusicRecognitionService.AudioTooLongException
                 | MusicRecognitionService.InvalidAudioException e) {
            sendErrorAndClose(session, e.getMessage(), CloseReason.CloseCodes.VIOLATED_POLICY);
        } catch (IOException e) {
            logger.warn("流式识曲处理失败", e);
            sendErrorAndClose(session, ApiMessages.translate("recognition.service_unavailable"),
                    CloseReason.CloseCodes.TRY_AGAIN_LATER);
        }
    }

    private void fail(Session session, String messageKey, CloseReason.CloseCode code) {
        sendErrorAndClose(session, ApiMessages.translate(messageKey), code);
    }

    private void sendErrorAndClose(Session session, String message, CloseReason.CloseCode code) {
        finished = true;
        ObjectNode error = MusicRecognitionHandler.errorResponse(message);
        error.put("type", "error");
        send(session, error.toString());
        close(session, code);
    }

    private void releaseStreamSlot() {
        if (streamSlotAcquired) {
            streamSlotAcquired = false;
            MusicRecognitionService service = Main.getMusicRecognitionService();
            if (service != null) {
                service.releaseLiveStreamSlot();
            }
        }
    }

    private static void send(Session session, String message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException e) {
                logger.debug("写入流式识曲 WebSocket 响应失败: {}", e.getMessage());
            }
        }
    }

    private static void close(Session session, CloseReason.CloseCode code) {
        if (session.isOpen()) {
            try {
                session.close(new CloseReason(code, ""));
            } catch (IOException ignored) {
                // The peer may have closed concurrently.
            }
        }
    }
}
