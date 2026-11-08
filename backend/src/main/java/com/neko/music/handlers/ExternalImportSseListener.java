package com.neko.music.handlers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.ExternalImportService;
import jakarta.servlet.AsyncContext;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把 {@link ExternalImportService} 的事件翻译为统一的 SSE 帧。 */
final class ExternalImportSseListener implements ExternalImportService.Listener {
    private final AsyncContext asyncContext;
    private final PrintWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ExternalImportSseListener(AsyncContext asyncContext, PrintWriter writer) {
        this.asyncContext = asyncContext;
        this.writer = writer;
    }

    @Override
    public void onStart(String source, int total, int targetPlaylistId, boolean targetPlaylistCreated) {
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("source", source);
        data.put("total", total);
        data.put("targetPlaylistId", targetPlaylistId);
        data.put("targetPlaylistCreated", targetPlaylistCreated);
        send("start", data);
    }

    @Override
    public void onTrackStarted(ExternalImportService.TrackResult result) {
        send("track", trackNode(result));
    }

    @Override
    public void onTrackProgress(int index, int total, String sourceId, long bytesRead, long totalBytes) {
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("index", index);
        data.put("total", total);
        data.put("sourceId", sourceId);
        data.put("bytes", bytesRead);
        data.put("totalBytes", totalBytes);
        data.put("percent", totalBytes > 0 ? Math.min(100, bytesRead * 100 / totalBytes) : -1);
        send("progress", data);
    }

    @Override
    public void onTrackFinished(ExternalImportService.TrackResult result) {
        send("track", trackNode(result));
    }

    @Override
    public void onComplete(ExternalImportService.Summary summary) {
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("total", summary.total());
        data.put("imported", summary.imported());
        data.put("existed", summary.existed());
        data.put("failed", summary.failed());
        send("done", data);
        finish();
    }

    @Override
    public void onError(String message) {
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("message", message == null
                ? ApiMessages.translate("source.external.import_failed")
                : message);
        send("error", data);
        finish();
    }

    private ObjectNode trackNode(ExternalImportService.TrackResult result) {
        ObjectNode data = Main.getObjectMapper().createObjectNode();
        data.put("index", result.index());
        data.put("total", result.total());
        data.put("source", result.source());
        data.put("sourceId", result.sourceId());
        data.put("title", result.title() == null ? "" : result.title());
        data.put("artist", result.artist() == null ? "" : result.artist());
        data.put("status", result.status());
        data.put("playlistAdded", result.playlistAdded());
        if (result.musicId() != null) {
            data.put("musicId", result.musicId());
            data.put("fileUrl", "/api/music/file/" + result.musicId());
        } else {
            data.putNull("musicId");
            data.putNull("fileUrl");
        }
        if (result.message() == null) {
            data.putNull("message");
        } else {
            data.put("message", result.message());
        }
        return data;
    }

    private void send(String event, ObjectNode data) {
        if (closed.get()) {
            return;
        }
        String json;
        try {
            json = Main.getObjectMapper().writeValueAsString(data);
        } catch (IOException e) {
            closed.set(true);
            return;
        }
        writer.print("event: " + event + "\n");
        writer.print("data: " + json + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            closed.set(true);
        }
    }

    private void finish() {
        closed.set(true);
        try {
            writer.flush();
        } catch (RuntimeException ignored) {
            // 连接已断开，忽略
        }
        try {
            asyncContext.complete();
        } catch (IllegalStateException ignored) {
            // 已结束
        }
    }
}
