package com.neko.music.service;

import com.neko.music.util.VideoRenderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 清理 {@code /tmp/.neko} 下过期的渲染成片与 ASS；内嵌字体/FFmpeg/水印缓存不参与按任务删除。
 */
public class VideoRenderArtifactCleanup {
    private static final Logger logger = LoggerFactory.getLogger(VideoRenderArtifactCleanup.class);
    private static final Path LEGACY_VIDEO_DIR = Paths.get("/tmp/NekoMusic-video-render");

    private final long retentionMs;
    private final ScheduledExecutorService scheduler;

    public VideoRenderArtifactCleanup(int retentionHours) {
        int hours = Math.max(1, retentionHours);
        this.retentionMs = hours * 3600_000L;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "video-render-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sweepExpiredArtifacts, 15, 30, TimeUnit.MINUTES);
        logger.info("视频渲染临时文件清理已启动：保留 {} 小时，目录 {}", retentionMs / 3600_000L,
                VideoRenderPaths.NEKO_TMP_ROOT.toAbsolutePath());
    }

    /** 任务完成后再保留 retention 时长，然后删除 MP4 与 ASS。 */
    public void scheduleJobArtifacts(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        scheduler.schedule(() -> deleteJobArtifacts(jobId), retentionMs, TimeUnit.MILLISECONDS);
    }

    public void deleteJobArtifacts(String jobId) {
        deleteQuiet(VideoRenderPaths.outputFile(jobId));
        deleteQuiet(VideoRenderPaths.videoRenderDir().resolve("ass").resolve(jobId + ".ass"));
        deleteJobCoverFiles(jobId);
    }

    public void sweepExpiredArtifacts() {
        long cutoff = System.currentTimeMillis() - retentionMs;
        try {
            Path renderDir = VideoRenderPaths.videoRenderDir();
            if (Files.isDirectory(renderDir)) {
                try (Stream<Path> mp4s = Files.list(renderDir)) {
                    mp4s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".mp4"))
                            .filter(p -> isOlderThan(p, cutoff))
                            .forEach(this::deleteQuiet);
                }
                try (Stream<Path> covers = Files.list(renderDir)) {
                    covers.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().matches(
                                    "^[0-9a-fA-F-]{36}\\.cover\\.[a-zA-Z0-9]+$"))
                            .filter(p -> isOlderThan(p, cutoff))
                            .forEach(this::deleteQuiet);
                }
                Path assDir = renderDir.resolve("ass");
                if (Files.isDirectory(assDir)) {
                    try (Stream<Path> assFiles = Files.list(assDir)) {
                        assFiles.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().endsWith(".ass"))
                                .filter(p -> isOlderThan(p, cutoff))
                                .forEach(this::deleteQuiet);
                    }
                }
            }
            sweepMp4InDir(LEGACY_VIDEO_DIR, cutoff);
        } catch (IOException e) {
            logger.warn("扫描过期渲染文件失败: {}", e.getMessage());
        }
    }

    private void deleteJobCoverFiles(String jobId) {
        Path renderDir = VideoRenderPaths.videoRenderDir();
        if (!Files.isDirectory(renderDir)) {
            return;
        }
        String prefix = jobId + ".cover.";
        try (Stream<Path> files = Files.list(renderDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(this::deleteQuiet);
        } catch (IOException e) {
            logger.warn("清理视频任务临时封面失败 jobId={}: {}", jobId, e.getMessage());
        }
    }

    private void sweepMp4InDir(Path dir, long cutoffEpochMs) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mp4"))
                    .filter(p -> isOlderThan(p, cutoffEpochMs))
                    .forEach(this::deleteQuiet);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static boolean isOlderThan(Path file, long cutoffEpochMs) {
        try {
            return Files.getLastModifiedTime(file).toMillis() < cutoffEpochMs;
        } catch (IOException e) {
            return true;
        }
    }

    private void deleteQuiet(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                logger.debug("已删除过期渲染文件: {}", path);
            }
        } catch (IOException e) {
            logger.warn("删除渲染文件失败 {}: {}", path, e.getMessage());
        }
    }
}
