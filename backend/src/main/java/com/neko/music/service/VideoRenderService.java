package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.VideoRenderJob;
import com.neko.music.util.BundledFfmpegSupport;
import com.neko.music.util.BundledRenderFontSupport;
import com.neko.music.util.BundledWatermarkSupport;
import com.neko.music.util.LrcParser;
import com.neko.music.util.MusicAssetLocator;
import com.neko.music.util.VideoRenderPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 横屏短视频 FFmpeg 渲染：独立线程池异步执行，HTTP 请求仅入队后立即返回。
 * 文字叠加使用 ASS + subtitles 滤镜；内嵌 Noto Sans SC 支持中日韩等非拉丁字符。
 * <p>渲染管线 {@code cuda_native} 与 {@code cpu_legacy} 共用同一套 CPU 滤镜图（圆形封面、封面外径向频谱条等），
 * 区别仅在于成片编码：前者固定 {@code h264_nvenc}，后者可选 libx264 / h264_nvenc。</p>
 */
public class VideoRenderService {
    private static final Logger logger = LoggerFactory.getLogger(VideoRenderService.class);

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int COVER_SIZE = 600;
    private static final int COVER_X = 72;
    private static final int COVER_Y = (HEIGHT - COVER_SIZE) / 2;
    /** 封面圆形蒙版中心（与左侧封面块几何中心一致） */
    private static final int COVER_CENTER_X = COVER_X + COVER_SIZE / 2;
    private static final int COVER_CENTER_Y = COVER_Y + COVER_SIZE / 2;
    /** 径向频谱叠层画布（正方形，中心与封面圆一致） */
    private static final int RING_VIS_SIZE = 820;
    private static final int RING_HALF = RING_VIS_SIZE / 2;
    /** showfreqs 条形图：宽越大角向越细，越接近「一根根白射线」 */
    private static final int RADIAL_SPEC_W = 2048;
    private static final int RADIAL_SPEC_H = 288;
    /** showfreqs 纵向：跳过底部基线附近与顶端几行，避免径向内侧整圈发黑 */
    private static final int RADIAL_SPEC_YMARGIN = 12;
    /** 径向叠层：亮度低于此值视为透明（略低更易露出细条） */
    private static final int RADIAL_ALPHA_LUM_THRESH = 12;
    /** 径向条内缘：尽量贴封面圆，减少「一圈死黑空隙」 */
    private static final int RADIAL_R0 = COVER_SIZE / 2 + 4;
    /** 外伸长度：越大条「拉」得越远 */
    private static final int RADIAL_R1 = RADIAL_R0 + 118;
    /** 右侧内容区中心（封面右缘至屏幕右缘的中点） */
    private static final int RIGHT_CENTER_X = COVER_X + COVER_SIZE + (WIDTH - COVER_X - COVER_SIZE) / 2;
    private static final int LYRIC_CENTER_Y = 620;
    /** 含翻译行（\\N）的单行歌词块垂直间距 */
    private static final int LINE_SPACING = 102;
    private static final int FADE_MS = 220;
    private static final int FFMPEG_TIMEOUT_MINUTES = 15;
    private static final String FONT = BundledRenderFontSupport.FONT_FAMILY;
    private static final String LYRIC_CLIP = "\\clip(" + (COVER_X + COVER_SIZE + 24) + ",340,1880,920)";
    private static final int LYRIC_VISIBLE_BEFORE = 2;
    private static final int LYRIC_VISIBLE_AFTER = 2;
    private final ConfigManager configManager;
    private final VideoRenderJobStore jobStore;
    private final VideoRenderArtifactCleanup artifactCleanup;
    private final ThreadPoolExecutor executor;

    public VideoRenderService(ConfigManager configManager, VideoRenderJobStore jobStore,
                              VideoRenderArtifactCleanup artifactCleanup) {
        this.configManager = configManager;
        this.jobStore = jobStore;
        this.artifactCleanup = artifactCleanup;
        int threads = Math.max(1, configManager.getVideoRenderWorkerThreads());
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "video-render-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(threads * 4),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        logger.info("视频渲染线程池已启动 threads={}, queueCapacity={}", threads, threads * 4);
        artifactCleanup.start();
        warmupBundledAssets();
    }

    /** 启动时释放 JAR 内嵌字体与水印 PNG，避免首次渲染才解压。 */
    private void warmupBundledAssets() {
        if (!configManager.isVideoRenderEnabled()) {
            return;
        }
        try {
            Path fontsDir = BundledRenderFontSupport.ensureFontsDir();
            Path watermark = BundledWatermarkSupport.ensureWatermarkFile();
            logger.info("视频渲染内嵌资源已释放 fontsDir={} watermark={}",
                    fontsDir.toAbsolutePath(), BundledWatermarkSupport.describeForLog(watermark));
        } catch (IOException e) {
            logger.warn("视频渲染内嵌资源释放失败（渲染时将重试）: {}", e.getMessage());
        }
    }

    public void submit(VideoRenderJob job, String title, String artist, Path audioFile, Optional<Path> coverFile) {
        executor.execute(() -> runJob(job, title, artist, audioFile, coverFile));
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    private void runJob(VideoRenderJob job, String title, String artist, Path audioFile, Optional<Path> coverFile) {
        Path assFile = null;
        boolean retainArtifacts = false;
        try {
            jobStore.markProcessing(job.getId());
            VideoRenderPaths.ensureVideoDir();
            Path output = VideoRenderPaths.outputFile(job.getId());
            assFile = VideoRenderPaths.assFile(job.getId());
            Path fontsDir = BundledRenderFontSupport.ensureFontsDir();
            List<LrcParser.Line> lyrics = loadLyrics(job.getMusicId());
            writeAssFile(assFile, job, title, artist, lyrics);
            runFfmpeg(job, audioFile, coverFile, assFile, fontsDir, output);
            if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
                throw new IOException("渲染输出文件无效");
            }
            jobStore.markDone(job.getId(), VideoRenderPaths.outputRelPath(job.getId()));
            artifactCleanup.scheduleJobArtifacts(job.getId());
            retainArtifacts = true;
            logger.info("视频渲染完成 jobId={} userId={} musicId={} lyricsLines={}",
                    job.getId(), job.getUserId(), job.getMusicId(), lyrics.size());
            notifyRenderCompleteByEmail(job, title, artist);
        } catch (Exception e) {
            logger.error("视频渲染失败 jobId={}: {}", job.getId(), e.getMessage(), e);
            jobStore.markFailed(job.getId(), shortenError(e.getMessage()));
            artifactCleanup.deleteJobArtifacts(job.getId());
        } finally {
            if (assFile != null && !retainArtifacts) {
                try {
                    Files.deleteIfExists(assFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private List<LrcParser.Line> loadLyrics(int musicId) {
        return Main.getLyricsDatabaseManager().findByMusicId(musicId)
                .flatMap(lyrics -> LrcParser.parseString(lyrics.lrcContent()))
                .orElse(List.of());
    }

    private void notifyRenderCompleteByEmail(VideoRenderJob job, String title, String artist) {
        try {
            String base = configManager.getVideoRenderNotifyFrontendBaseUrl();
            if (base == null || base.isEmpty()) {
                logger.warn("未配置 video_render.notify_frontend_base_url，跳过渲染完成邮件 jobId={}", job.getId());
                return;
            }
            String downloadUrl = base + "/api/video/render/" + job.getId() + "/download";
            Main.getUserAuthService().findEmailByUserId(job.getUserId()).ifPresent(email -> {
                boolean sent = Main.getEmailService().sendVideoRenderCompleteEmail(
                        email,
                        title == null ? "未知歌曲" : title,
                        artist == null ? "未知艺术家" : artist,
                        job.getDurationSec(),
                        downloadUrl,
                        job.isWatermarked());
                if (!sent) {
                    logger.warn("渲染完成邮件发送失败 jobId={} email={}", job.getId(), email);
                }
            });
        } catch (Exception e) {
            logger.warn("发送渲染完成邮件异常 jobId={}: {}", job.getId(), e.getMessage());
        }
    }

    private void writeAssFile(Path assFile, VideoRenderJob job, String title, String artist,
                              List<LrcParser.Line> lyrics) throws IOException {
        double duration = job.getDurationSec();
        double clipStart = job.getStartSec();
        String end = formatAssTime(duration);
        String safeTitle = VideoRenderPaths.escapeAssText(
                VideoRenderPaths.truncateText(title == null ? "未知歌曲" : title, 48));
        String safeArtist = VideoRenderPaths.escapeAssText(
                VideoRenderPaths.truncateText(artist == null ? "未知艺术家" : artist, 48));
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n");
        ass.append("ScriptType: v4.00+\n");
        ass.append("PlayResX: ").append(WIDTH).append('\n');
        ass.append("PlayResY: ").append(HEIGHT).append('\n');
        ass.append("WrapStyle: 0\n\n");
        ass.append("[V4+ Styles]\n");
        ass.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                .append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        ass.append("Style: Title,").append(FONT).append(",64,&H00FFFFFF,&H000000FF,&H80C4B5FD,&H00000000,1,0,0,0,100,100,0,0,1,0,0,8,40,40,80,1\n");
        ass.append("Style: Artist,").append(FONT).append(",42,&H00F0F0F0,&H000000FF,&H60000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,40,40,140,1\n");
        ass.append("Style: LyricBase,").append(FONT).append(",44,&H00FFFFFF,&H000000FF,&H60000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,40,40,0,1\n");
        ass.append("Style: LyricTrans,").append(FONT).append(",32,&H00E8E8E8,&H000000FF,&H60000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,40,40,0,1\n\n");
        ass.append("[Events]\n");
        ass.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        ass.append("Dialogue: 0,0:00:00.00,").append(end).append(",Title,,0,0,0,,{\\an8\\pos(")
                .append(RIGHT_CENTER_X).append(",115)\\blur2\\bord3\\3c&H00C4B5FD&\\shad2\\4c&H50000000&}")
                .append(safeTitle).append('\n');
        ass.append("Dialogue: 0,0:00:00.00,").append(end).append(",Artist,,0,0,0,,{\\an8\\pos(")
                .append(RIGHT_CENTER_X).append(",195)\\1c&H00FAFAFA&}").append(safeArtist).append('\n');
        ass.append("Dialogue: 0,0:00:00.00,").append(end).append(",Artist,,0,0,0,,{\\an8\\pos(")
                .append(RIGHT_CENTER_X).append(",248)\\1c&H00A78BFA&\\fs18\\blur1}").append("━━━━━━━━━━━━").append('\n');
        appendLyricEvents(ass, lyrics, clipStart, duration);
        String assBody = ass.toString();
        Files.writeString(assFile, assBody, StandardCharsets.UTF_8);
        if (job.isWatermarked()) {
            logger.info("ASS 水印检查 jobId={} mode=png-overlay assTextWatermark={}",
                    job.getId(), hasAssTextWatermark(assBody));
        }
    }

    /** 是否仍写入 ASS 文字水印（Mark 样式 Dialogue）；正常应为 false，水印走 PNG overlay。 */
    private static boolean hasAssTextWatermark(String assBody) {
        for (String line : assBody.split("\n")) {
            if (line.startsWith("Dialogue:") && line.contains(",Mark,,")) {
                return true;
            }
        }
        return false;
    }

    private void appendLyricEvents(StringBuilder ass, List<LrcParser.Line> lyrics, double clipStart, double duration) {
        if (lyrics.isEmpty()) {
            return;
        }
        List<ScrollSegment> segments = buildScrollSegments(lyrics, clipStart, duration);
        if (segments.isEmpty()) {
            return;
        }
        int firstActive = segments.get(0).activeIndex();
        int lastActive = segments.get(segments.size() - 1).activeIndex();
        int jFrom = Math.max(0, firstActive - LYRIC_VISIBLE_BEFORE);
        int jTo = Math.min(lyrics.size() - 1, lastActive + LYRIC_VISIBLE_AFTER);

        for (ScrollSegment seg : segments) {
            int nextActive = seg.scrollAtEnd() ? seg.activeIndex() + 1 : seg.activeIndex();
            for (int j = jFrom; j <= jTo; j++) {
                if (!isLyricLineVisible(j, seg.activeIndex())) {
                    continue;
                }
                LrcParser.Line line = lyrics.get(j);
                boolean active = j == seg.activeIndex();
                boolean fadeIn = j == seg.activeIndex() + LYRIC_VISIBLE_AFTER;
                boolean fadeOut = seg.scrollAtEnd() && !isLyricLineVisible(j, nextActive);
                appendLyricLineSegment(ass, line, j, seg, active, fadeIn, fadeOut);
            }
        }
    }

    private record ScrollSegment(double relStart, double relEnd, int activeIndex, boolean scrollAtEnd) {
    }

    private List<ScrollSegment> buildScrollSegments(List<LrcParser.Line> lyrics, double clipStart, double duration) {
        List<ScrollSegment> segments = new ArrayList<>();
        int active = activeIndexAt(lyrics, clipStart);
        double relStart = Math.max(0, lyrics.get(active).getTimeSec() - clipStart);
        while (active < lyrics.size() && relStart < duration - 1e-6) {
            double relEnd = duration;
            boolean scroll = false;
            if (active + 1 < lyrics.size()) {
                double nextRel = lyrics.get(active + 1).getTimeSec() - clipStart;
                if (nextRel > relStart + 1e-6 && nextRel <= duration) {
                    relEnd = nextRel;
                    scroll = true;
                }
            }
            if (relEnd <= relStart + 1e-6) {
                break;
            }
            segments.add(new ScrollSegment(relStart, relEnd, active, scroll));
            if (!scroll) {
                break;
            }
            relStart = relEnd;
            active++;
        }
        return segments;
    }

    private void appendLyricLineSegment(StringBuilder ass, LrcParser.Line line, int lineIndex, ScrollSegment seg,
                                        boolean active, boolean fadeIn, boolean fadeOut) {
        String text = formatLyricBlock(line, active);
        int yHold = LYRIC_CENTER_Y + (lineIndex - seg.activeIndex()) * LINE_SPACING;
        int yNext = yHold - LINE_SPACING;
        long segMs = Math.max(1, Math.round((seg.relEnd() - seg.relStart()) * 1000));

        String startTs = formatAssTime(seg.relStart());
        String endTs = formatAssTime(seg.relEnd());
        ass.append("Dialogue: 0,").append(startTs).append(',').append(endTs).append(",LyricBase,,0,0,0,,")
                .append(buildSegmentMotionTags(yHold, yNext, segMs, seg.scrollAtEnd(), active, fadeIn, fadeOut, line.hasTranslation()))
                .append(text).append('\n');
    }

    /** 主歌词 + 翻译合并为同一块（\\N），随主行一起滚动，避免翻译单独消失。 */
    private static String formatLyricBlock(LrcParser.Line line, boolean active) {
        String main = VideoRenderPaths.escapeAssText(VideoRenderPaths.truncateText(line.getText(), 64));
        if (!line.hasTranslation()) {
            return main;
        }
        String trans = VideoRenderPaths.escapeAssText(VideoRenderPaths.truncateText(line.getTranslation(), 80));
        if (active) {
            return main + "{\\r\\fs36\\1c&H00FFFFFF&\\b0\\bord0\\blur1\\3c&H60C4B5FD&}\\N" + trans;
        }
        return main + "{\\r\\fs30\\1c&H00CCCCCC&\\b0\\bord0}\\N" + trans;
    }

    /**
     * 整段匀速 {@code \\move}（速度随 LRC 句间隔自动变化），比段末短促跳动更自然。
     */
    private String buildSegmentMotionTags(int yHold, int yNext, long segMs, boolean scroll, boolean active,
                                          boolean fadeIn, boolean fadeOut, boolean hasTranslation) {
        StringBuilder tags = new StringBuilder("{\\an5").append(LYRIC_CLIP);
        if (fadeIn) {
            tags.append("\\fad(").append(FADE_MS).append(",0)");
        } else if (fadeOut) {
            tags.append("\\fad(0,").append(FADE_MS).append(')');
        }
        appendLyricVisualState(tags, active, hasTranslation);
        appendActivePulse(tags, active, segMs);
        if (scroll) {
            tags.append(String.format(Locale.US, "\\move(%d,%d,%d,%d,0,%d)",
                    RIGHT_CENTER_X, yHold, RIGHT_CENTER_X, yNext, segMs));
        } else {
            tags.append(String.format(Locale.US, "\\pos(%d,%d)", RIGHT_CENTER_X, yHold));
        }
        tags.append('}');
        return tags.toString();
    }

    /** 当前句微缩放脉冲，增强节奏感 */
    private static void appendActivePulse(StringBuilder tags, boolean active, long segMs) {
        if (!active || segMs < 300) {
            return;
        }
        long mid = Math.min(350, segMs / 3);
        long relax = Math.min(segMs, mid * 2);
        tags.append(String.format(Locale.US, "\\t(0,%d,\\fscx112\\fscy112\\blur5)", mid));
        tags.append(String.format(Locale.US, "\\t(%d,%d,\\fscx106\\fscy106\\blur4)", mid, relax));
    }

    private static int activeIndexAt(List<LrcParser.Line> lyrics, double clipStart) {
        int active = 0;
        boolean matched = false;
        for (int k = 0; k < lyrics.size(); k++) {
            if (lyrics.get(k).getTimeSec() <= clipStart + 1e-3) {
                active = k;
                matched = true;
            } else {
                break;
            }
        }
        return matched ? active : 0;
    }

    private static boolean isLyricLineVisible(int lineIndex, int activeIndex) {
        return lineIndex >= activeIndex - LYRIC_VISIBLE_BEFORE
                && lineIndex <= activeIndex + LYRIC_VISIBLE_AFTER;
    }

    private static void appendLyricVisualState(StringBuilder tags, boolean active, boolean hasTranslation) {
        if (active) {
            tags.append("\\fs64\\1c&H00FFFFFF&\\1a&H00&\\b1\\bord4\\3c&H00E879F9&\\4c&H40C4B5FD&\\blur3\\shad0");
            tags.append("\\fscx106\\fscy106");
        } else {
            tags.append("\\fs44\\1c&H00F0F0F0&\\1a&H00&\\b0\\bord0\\fscx100\\fscy100");
            if (hasTranslation) {
                tags.append("\\1a&H15&");
            }
        }
    }

    /** 封面正方形内切圆：alpha 在圆内 255、圆外 0（W=H=COVER_SIZE） */
    private static String circleCoverAlphaGeq() {
        int r = COVER_SIZE / 2 - 1;
        int r2 = r * r;
        return "if(lte(pow(X-W/2,2)+pow(Y-H/2,2)," + r2 + "),255,0)";
    }

    /** 径向环带守卫：内缘 RADIAL_R0 以内、外缘 RADIAL_R1 以外均为 0，否则套用 inner 表达式。 */
    private static String radialBand(String inner) {
        int c = RING_HALF;
        String hypot = "hypot(X-" + c + ",Y-" + c + ")";
        return "if(lt(" + hypot + "," + RADIAL_R0 + "),0,"
                + "if(gt(" + hypot + "," + RADIAL_R1 + "),0,"
                + inner + "))";
    }

    /** remap 用 x 坐标图（8bit）：角向对应 showfreqs 的列 */
    private static String radialXmapLumGeq() {
        int c = RING_HALF;
        int smax = RADIAL_SPEC_W - 1;
        return radialBand("min(255,max(0,(255*min(" + smax + ",max(0,(" + smax + ")*(atan2(Y-" + c + ",X-" + c + ")+PI)/(2*PI))))/(" + smax + ")))");
    }

    /** remap 用 y 坐标图：半径越大越靠近条形顶端（能量越强） */
    private static String radialYmapLumGeq() {
        int c = RING_HALF;
        int sh = RADIAL_SPEC_H - 1;
        int span = RADIAL_R1 - RADIAL_R0;
        int m = RADIAL_SPEC_YMARGIN;
        int yr = Math.max(1, sh - 2 * m);
        return radialBand("min(255,max(0,(255*min(" + sh + ",max(0," + m + "+(" + yr + ")*(1-((hypot(X-" + c + ",Y-" + c + ")-" + RADIAL_R0 + ")/(" + span + ")))))/" + sh + ")))");
    }

    /**
     * 叠层 RGB：环带内、频谱够亮则输出纯白（对齐参考 demo 的白色径向细条）。
     */
    private static String radialRingWhiteRgbGeq() {
        String lum = "0.299*r(X,Y)+0.587*g(X,Y)+0.114*b(X,Y)";
        return radialBand("if(gt(" + lum + "," + RADIAL_ALPHA_LUM_THRESH + "),255,0)");
    }

    /** 叠层 alpha：同径向带；略抬 alpha 让白条在毛玻璃上更站得住 */
    private static String radialRingWhiteAlphaGeq() {
        String lum = "0.299*r(X,Y)+0.587*g(X,Y)+0.114*b(X,Y)";
        return radialBand("if(gt(" + lum + "," + RADIAL_ALPHA_LUM_THRESH + "),min(255,58+1.4*(" + lum + ")),0)");
    }

    /**
     * 视频编码：NVENC 走 GPU（需系统 FFmpeg 编译含 h264_nvenc 与 NVIDIA 驱动）；libx264 走 CPU。
     */
    private static void appendVideoEncodeArgs(List<String> cmd, String codec) {
        cmd.add("-c:v");
        if ("h264_nvenc".equals(codec)) {
            cmd.add("h264_nvenc");
            cmd.add("-preset");
            cmd.add("p4");
            cmd.add("-rc");
            cmd.add("vbr");
            cmd.add("-cq");
            cmd.add("23");
            cmd.add("-b:v");
            cmd.add("0");
        } else {
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-threads");
            cmd.add("2");
            cmd.add("-crf");
            cmd.add("23");
        }
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
    }

    private void runFfmpeg(VideoRenderJob job, Path audioFile, Optional<Path> coverFile, Path assFile,
                           Path fontsDir, Path output) throws IOException, InterruptedException {
        String ffmpeg = BundledFfmpegSupport.resolve(
                configManager.getVideoRenderFfmpegPath(),
                configManager.isVideoRenderPreferBundledFfmpeg());
        double duration = job.getDurationSec();
        double start = job.getStartSec();
        int fps = 30;
        int durFrames = Math.max(1, (int) Math.ceil(duration * fps));

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        boolean cudaNative = "cuda_native".equals(configManager.getVideoRenderPipeline());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(formatSec(start));
        cmd.add("-t");
        cmd.add(formatSec(duration));
        cmd.add("-i");
        cmd.add(audioFile.toAbsolutePath().toString());

        boolean hasCover = coverFile.isPresent() && Files.isRegularFile(coverFile.get());
        int coverInputIdx = -1;
        if (hasCover) {
            coverInputIdx = 1;
            appendLoopImageInput(cmd, fps, duration, coverFile.get());
        }

        boolean watermarked = job.isWatermarked();
        int watermarkInputIdx = -1;
        Path watermarkFile = null;
        if (watermarked) {
            watermarkFile = BundledWatermarkSupport.ensureWatermarkFile();
            watermarkInputIdx = hasCover ? 2 : 1;
            logger.info("视频水印 PNG 输入 jobId={} inputIndex={} hasCover={} {}",
                    job.getId(), watermarkInputIdx, hasCover,
                    BundledWatermarkSupport.describeForLog(watermarkFile));
            appendLoopImageInput(cmd, fps, duration, watermarkFile);
        }

        String subtitles = VideoRenderPaths.subtitlesFilterArg(assFile, fontsDir);
        String filter = buildLandscapeFilter(hasCover, coverInputIdx, watermarked, watermarkInputIdx,
                durFrames, fps, subtitles);
        if (watermarked) {
            logger.info("视频水印 FFmpeg overlay jobId={} wmInput=[{}:v] filterTail={}",
                    job.getId(), watermarkInputIdx, tailWatermarkFilter(filter));
        }
        cmd.add("-filter_complex");
        cmd.add(filter);
        cmd.add("-map");
        cmd.add("[vout]");
        cmd.add("-map");
        cmd.add("0:a");
        appendVideoEncodeArgs(cmd, cudaNative ? "h264_nvenc" : configManager.getVideoRenderVideoCodec());
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-shortest");
        cmd.add(output.toAbsolutePath().toString());

        logger.info("异步 ffmpeg 开始 jobId={} pipeline={} videoCodec={} durationSec={} watermarked={} watermarkMode={}",
                job.getId(),
                configManager.getVideoRenderPipeline(),
                cudaNative ? "h264_nvenc" : configManager.getVideoRenderVideoCodec(),
                duration, job.isWatermarked(),
                watermarked ? "png-overlay" : "none");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder logOut = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logOut.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException("ffmpeg 超时（>" + FFMPEG_TIMEOUT_MINUTES + " 分钟）");
        }
        int code = process.exitValue();
        if (code != 0) {
            String detail = tail(logOut.toString(), 1200);
            throw new IOException("ffmpeg 退出码 " + code + (detail.isBlank() ? "" : ": " + detail.trim()));
        }
    }

    /**
     * 横屏 1920×1080：背景毛玻璃 + 圆形封面 + 封面外径向频谱条（showfreqs + remap）+ ASS 字幕。
     */
    private static String buildLandscapeFilter(boolean hasCover, int coverInputIdx, boolean watermarked,
                                               int watermarkInputIdx, int durFrames, int fps, String subtitles) {
        String sizeWxH = WIDTH + "x" + HEIGHT;
        String sizeColon = WIDTH + ":" + HEIGHT;
        String coverColon = COVER_SIZE + ":" + COVER_SIZE;
        String coverIn = "[" + coverInputIdx + ":v]";
        StringBuilder fc = new StringBuilder();
        if (hasCover) {
            fc.append(coverIn).append("scale=").append(sizeColon)
                    .append(":force_original_aspect_ratio=increase,crop=").append(sizeColon)
                    .append(",setsar=1[bg_src];");
            fc.append("[bg_src]gblur=sigma=50,eq=brightness='0.02+0.015*sin(n/30)':saturation=1.12:contrast=1.06[blur_bg];");
            fc.append(colorSource("white@0.22", sizeWxH, durFrames, fps)).append("[frost_base];");
            fc.append("[frost_base]noise=alls=8:allf=t[frost_noise];");
            fc.append("[blur_bg][frost_noise]overlay=0:0:format=auto[glass];");
            fc.append("[glass]hue=h='4*sin(2*PI*t/14)':s=1.18[glass_hue];");
            fc.append(colorSource("0x7C3AED@0.10", sizeWxH, durFrames, fps)).append("[tint_p];");
            fc.append(colorSource("0x22D3EE@0.05", sizeWxH, durFrames, fps)).append("[tint_c];");
            fc.append("[glass_hue][tint_p]overlay=0:0:format=auto[glass_p];");
            fc.append("[glass_p][tint_c]overlay=0:0:format=auto[glass_tint];");
            fc.append(colorSource("black@0.15", sizeWxH, durFrames, fps)).append("[vign];");
            fc.append("[glass_tint][vign]overlay=0:0:format=auto[bg];");
            fc.append(coverIn).append("scale=").append(coverColon).append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(coverColon).append(":(ow-iw)/2:(oh-ih)/2:color=black@0,")
                    .append("format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='")
                    .append(circleCoverAlphaGeq()).append("'[cover_raw];");
            fc.append("[cover_raw]split=3[cover_main][cover_shadow_src][cover_glow_src];");
            fc.append("[cover_shadow_src]gblur=sigma=18,colorchannelmixer=aa=0.72[cover_shadow];");
            fc.append("[cover_glow_src]gblur=sigma=24,eq=saturation=1.7:brightness=0.06,")
                    .append("colorchannelmixer=rr=0.55:gg=0.38:bb=1.0:aa=0.68[cover_glow];");
            fc.append("[bg][cover_glow]overlay=").append(COVER_X - 10).append(':').append(COVER_Y - 10)
                    .append(":format=auto[bg_glow];");
            fc.append("[bg_glow][cover_shadow]overlay=").append(COVER_X + 12).append(':').append(COVER_Y + 16)
                    .append(":format=auto[bg_shadow];");
            fc.append("[bg_shadow][cover_main]overlay=").append(COVER_X).append(':').append(COVER_Y)
                    .append(":format=auto[composed];");
        } else {
            fc.append(colorSource("0x0f172a", sizeWxH, durFrames, fps)).append(",eq=brightness='0.02+0.01*sin(n/30)'[dark];");
            fc.append(colorSource("white@0.14", sizeWxH, durFrames, fps)).append(",noise=alls=6:allf=t[frost];");
            fc.append("[dark][frost]overlay=0:0:format=auto[composed];");
            fc.append("[composed]hue=h='4*sin(2*PI*t/14)':s=1.12[composed];");
        }
        String mapDurSec = String.format(Locale.US, "%.5f", Math.max(0.1, durFrames / (double) fps));
        String ringSize = RING_VIS_SIZE + "x" + RING_VIS_SIZE;
        fc.append("[0:a]aformat=sample_rates=44100:channel_layouts=stereo,showfreqs=s=").append(RADIAL_SPEC_W).append('x')
                .append(RADIAL_SPEC_H).append(":mode=bar:ascale=sqrt:fscale=log:overlap=0.30:averaging=1:win_size=4096:rate=")
                .append(fps).append(":colors=white|white[spec_raw];");
        fc.append("[spec_raw]eq=contrast=1.28:brightness=0.08[spec_eq];");
        fc.append("[spec_eq]gblur=sigma=0.22[spec_strip];");
        fc.append(colorSource("black", ringSize, mapDurSec, fps)).append(",format=gray,geq=lum='").append(radialXmapLumGeq()).append("'[xmap];");
        fc.append(colorSource("black", ringSize, mapDurSec, fps)).append(",format=gray,geq=lum='").append(radialYmapLumGeq()).append("'[ymap];");
        fc.append("[spec_strip][xmap][ymap]remap=fill=black,unsharp=7:7:1.05:5:5:0.0,format=rgba,geq=r='")
                .append(radialRingWhiteRgbGeq()).append("':g='").append(radialRingWhiteRgbGeq()).append("':b='")
                .append(radialRingWhiteRgbGeq()).append("':a='").append(radialRingWhiteAlphaGeq()).append("'[ring_vis];");
        int ringOy = ((hasCover ? COVER_CENTER_Y : HEIGHT / 2) - RING_HALF);
        fc.append("[composed][ring_vis]overlay=").append(COVER_CENTER_X - RING_HALF).append(':').append(ringOy)
                .append(":format=auto[base];");
        fc.append("[base]eq=gamma=1.06:saturation=1.1:brightness=0.012[flash];");
        if (watermarked) {
            fc.append("[flash]").append(subtitles).append("[vsub];");
            fc.append("[").append(watermarkInputIdx).append(":v]scale=220:-1,format=rgba[wm_scaled];");
            fc.append("[vsub][wm_scaled]overlay=W-w-36:36:format=auto[vout]");
        } else {
            fc.append("[flash]").append(subtitles).append("[vout]");
        }
        return fc.toString();
    }

    /** 追加一路循环图片输入（封面/水印共用）。 */
    private static void appendLoopImageInput(List<String> cmd, int fps, double duration, Path file) {
        cmd.add("-loop");
        cmd.add("1");
        cmd.add("-framerate");
        cmd.add(String.valueOf(fps));
        cmd.add("-t");
        cmd.add(formatSec(duration));
        cmd.add("-i");
        cmd.add(file.toAbsolutePath().toString());
    }

    /** color 源滤镜（画布尺寸 + 整数帧数）。 */
    private static String colorSource(String color, String size, int durFrames, int fps) {
        return colorSource(color, size, String.valueOf(durFrames), fps);
    }

    /** color 源滤镜（自定义时长文本，供 remap 映射图层复用）。 */
    private static String colorSource(String color, String size, String duration, int fps) {
        return "color=c=" + color + ":s=" + size + ":d=" + duration + ":r=" + fps;
    }

    private static String formatSec(double sec) {
        return String.format(Locale.US, "%.3f", Math.max(0, sec));
    }

    private static String formatAssTime(double sec) {
        int totalCs = Math.max(0, (int) Math.round(sec * 100));
        int s = totalCs / 100;
        int cs = totalCs % 100;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int ss = s % 60;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, ss, cs);
    }

    private static String tailWatermarkFilter(String filter) {
        int idx = filter.indexOf("[flash]");
        if (idx < 0) {
            return tail(filter, 280);
        }
        return filter.substring(idx);
    }

    private static String tail(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String shortenError(String msg) {
        if (msg == null || msg.isBlank()) {
            return "渲染失败";
        }
        return msg.length() > 500 ? msg.substring(0, 499) + "…" : msg;
    }

    public void shutdown() {
        artifactCleanup.shutdown();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static Optional<Path> resolveCover(int musicId) {
        return MusicAssetLocator.findCoverFile(musicId);
    }

    public static Optional<Path> resolveAudio(int musicId) {
        return MusicAssetLocator.findAudioFile(musicId);
    }
}
