package com.neko.music.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端滑块拼图：签发挑战、松手校验换取短时 passToken、发送邮箱验证码时消费 passToken。
 */
public class SliderCaptchaService {

    private static final Logger logger = LoggerFactory.getLogger(SliderCaptchaService.class);

    private static final int BG_WIDTH = 300;
    private static final int BG_HEIGHT = 180;
    private static final int PUZZLE_W = 52;
    private static final int PUZZLE_H = 52;
    private static final int PUZZLE_ARC = 10;
    private static final int TOLERANCE_PX = 5;
    private static final int MIN_TRACE_SAMPLES = 8;
    private static final int MAX_TRACE_SAMPLES = 160;
    private static final long MIN_TRACE_DURATION_MS = 250L;
    private static final long MAX_TRACE_DURATION_MS = 20_000L;
    private static final int MAX_TRACE_STEP_PX = 80;
    private static final long CHALLENGE_TTL_MS = 3 * 60 * 1000L;
    public static final long PASS_TTL_MS = 2 * 60 * 1000L;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, StoredChallenge> challenges = new ConcurrentHashMap<>();
    /** passToken -> 绑定来源和过期时间 */
    private final ConcurrentHashMap<String, StoredPass> passTokens = new ConcurrentHashMap<>();

    private static final class StoredChallenge {
        final int secretX;
        final long expiresAtMs;
        final String sourceHash;

        StoredChallenge(int secretX, long expiresAtMs, String sourceHash) {
            this.secretX = secretX;
            this.expiresAtMs = expiresAtMs;
            this.sourceHash = sourceHash;
        }
    }

    private record StoredPass(long expiresAtMs, String sourceHash) {
    }

    public record DragSample(long elapsedMs, int offsetX) {
    }

    public Map<String, Object> createChallengePayload(String userAgent) {
        pruneExpired();
        int puzzleY = 12 + random.nextInt(Math.max(1, BG_HEIGHT - PUZZLE_H - 24));
        int margin = 64;
        int minX = margin;
        int maxX = BG_WIDTH - PUZZLE_W - margin;
        int secretX = minX + random.nextInt(Math.max(1, maxX - minX));

        BufferedImage bg = new BufferedImage(BG_WIDTH, BG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawNoiseBackground(g, BG_WIDTH, BG_HEIGHT, random);

        RoundRectangle2D puzzleShapeWorld = new RoundRectangle2D.Double(
                secretX, puzzleY, PUZZLE_W, PUZZLE_H, PUZZLE_ARC, PUZZLE_ARC);

        BufferedImage slider = extractPuzzlePiece(bg, puzzleShapeWorld, secretX, puzzleY);

        drawCutout(g, puzzleShapeWorld);
        g.dispose();

        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        challenges.put(token, new StoredChallenge(
                secretX,
                now + CHALLENGE_TTL_MS,
                sourceHash(userAgent)));

        return Map.of(
                "captchaToken", token,
                "bgImage", toPngDataUrl(bg),
                "sliderImage", toPngDataUrl(slider),
                "puzzleY", puzzleY,
                "bgWidth", BG_WIDTH,
                "bgHeight", BG_HEIGHT,
                "sliderWidth", PUZZLE_W,
                "sliderHeight", PUZZLE_H
        );
    }

    /**
     * 校验拼图位移；无论对错都会消耗当前 captchaToken。
     *
     * @return 成功时返回短时 captchaPassToken，失败返回 empty
     */
    public Optional<String> verifySlideAndIssuePass(String token, int clientOffsetX,
                                                     List<DragSample> trace,
                                                     String userAgent) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        pruneExpired();
        StoredChallenge ch = challenges.remove(token.trim());
        if (ch == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > ch.expiresAtMs) {
            return Optional.empty();
        }
        if (!ch.sourceHash.equals(sourceHash(userAgent))
                || !isHumanLikeTrace(trace, clientOffsetX)) {
            return Optional.empty();
        }
        if (Math.abs(clientOffsetX - ch.secretX) > TOLERANCE_PX) {
            logger.debug("slider offset mismatch clientX={} expected={}", clientOffsetX, ch.secretX);
            return Optional.empty();
        }
        String pass = UUID.randomUUID().toString().replace("-", "");
        passTokens.put(pass, new StoredPass(
                System.currentTimeMillis() + PASS_TTL_MS,
                ch.sourceHash));
        return Optional.of(pass);
    }

    /** 发送验证码前消费 passToken；重复或过期返回 false */
    public boolean consumePassToken(String passToken, String userAgent) {
        if (passToken == null || passToken.isBlank()) {
            return false;
        }
        pruneExpired();
        StoredPass pass = passTokens.remove(passToken.trim());
        return pass != null
                && System.currentTimeMillis() <= pass.expiresAtMs()
                && pass.sourceHash().equals(sourceHash(userAgent));
    }

    /** 把滑块挑战绑定到稳定的客户端形态，避免挑战被换端复用。 */
    private static String sourceHash(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.trim();
        String value = "ua:" + ua.length() + ":" + ua;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean isHumanLikeTrace(List<DragSample> trace, int finalOffsetX) {
        if (trace == null || trace.size() < MIN_TRACE_SAMPLES || trace.size() > MAX_TRACE_SAMPLES) {
            return false;
        }
        DragSample first = trace.get(0);
        DragSample last = trace.get(trace.size() - 1);
        if (first == null || last == null || first.elapsedMs() < 0 || first.elapsedMs() > 100
                || Math.abs(first.offsetX()) > TOLERANCE_PX
                || Math.abs(last.offsetX() - finalOffsetX) > TOLERANCE_PX) {
            return false;
        }
        long previousTime = first.elapsedMs();
        int previousX = first.offsetX();
        int distinctPositions = 1;
        long totalMovement = 0;
        for (int i = 1; i < trace.size(); i++) {
            DragSample sample = trace.get(i);
            if (sample == null || sample.elapsedMs() <= previousTime
                    || sample.elapsedMs() > MAX_TRACE_DURATION_MS
                    || sample.offsetX() < 0 || sample.offsetX() > BG_WIDTH - PUZZLE_W) {
                return false;
            }
            long step = Math.abs((long) sample.offsetX() - previousX);
            if (step > MAX_TRACE_STEP_PX) {
                return false;
            }
            if (sample.offsetX() != previousX) {
                distinctPositions++;
            }
            totalMovement += step;
            previousTime = sample.elapsedMs();
            previousX = sample.offsetX();
        }
        long duration = last.elapsedMs() - first.elapsedMs();
        return duration >= MIN_TRACE_DURATION_MS
                && duration <= MAX_TRACE_DURATION_MS
                && distinctPositions >= MIN_TRACE_SAMPLES - 2
                && totalMovement >= Math.max(20, Math.abs(finalOffsetX) - TOLERANCE_PX);
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        if (challenges.size() + passTokens.size() < 512) {
            return;
        }
        Iterator<Map.Entry<String, StoredChallenge>> it = challenges.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs < now) {
                it.remove();
            }
        }
        Iterator<Map.Entry<String, StoredPass>> pit = passTokens.entrySet().iterator();
        while (pit.hasNext()) {
            if (pit.next().getValue().expiresAtMs() < now) {
                pit.remove();
            }
        }
    }

    /**
     * 渲染缺口。
     *
     * <p>不再使用固定的 {@code (0,0,0,115)} 纯色填充 + 固定亮白描边：那组常量让「缺口 = 图像里
     * 最暗的等宽矩形」成为稳定、可编程的判读信号，脚本一次阈值即可定位。这里每张图随机化填充
     * 灰度 / 透明度、在缺口内叠加噪点、并改用随机色低对比描边，使「拿列均值找深谷」这类朴素
     * 图像分析不再稳定命中；对正常用户而言仍然是一块带亮边的深色取景框，观感不变。</p>
     *
     * <p>需要说明的是：缺口对用户必须可见，因此它**依然可被更强的图像分析定位**——这里的目的是
     * 抬高自动化成本，而不是把它变成人机边界；真正的门槛在发码与滑块的来源限流。</p>
     */
    private void drawCutout(Graphics2D g, RoundRectangle2D shape) {
        // 外层柔化：先在缺口外沿铺一圈更大、透明度随机的暗色，削弱「边缘是一刀切亮度跳变」的特征
        g.setColor(new Color(0, 0, 0, 30 + random.nextInt(35)));
        g.fill(new RoundRectangle2D.Double(
                shape.getX() - 2, shape.getY() - 2,
                shape.getWidth() + 4, shape.getHeight() + 4,
                PUZZLE_ARC + 2, PUZZLE_ARC + 2));

        // 主体：随机灰度的暗色 + 随机透明度，替代固定的黑色 115
        int base = random.nextInt(70);
        g.setColor(new Color(base, base, Math.min(255, base + random.nextInt(45)),
                95 + random.nextInt(70)));
        g.fill(shape);

        // 缺口内噪点：让该区间的「列均值」不再是一段平坦深谷，朴素阈值判读会抖动
        int noiseCount = 160 + random.nextInt(160);
        for (int i = 0; i < noiseCount; i++) {
            int nx = (int) shape.getX() + random.nextInt((int) shape.getWidth());
            int ny = (int) shape.getY() + random.nextInt((int) shape.getHeight());
            if (!shape.contains(nx, ny)) {
                continue; // 圆角外不落点，避免噪点溢出到背景
            }
            int tone = random.nextInt(255);
            g.setColor(new Color(tone, tone, tone, 40 + random.nextInt(70)));
            g.fillOval(nx, ny, 1 + random.nextInt(2), 1 + random.nextInt(2));
        }

        // 描边：随机色、低对比，替代固定 (255,255,255,230)
        g.setColor(new Color(
                170 + random.nextInt(86),
                170 + random.nextInt(86),
                170 + random.nextInt(86),
                120 + random.nextInt(90)));
        g.setStroke(new BasicStroke(1.2f + random.nextFloat() * 1.2f));
        g.draw(shape);
    }

    private static void drawNoiseBackground(Graphics2D g, int w, int h, Random rnd) {
        g.setColor(new Color(240, 244, 250));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 520; i++) {
            int x = rnd.nextInt(w);
            int yy = rnd.nextInt(h);
            int rr = 1 + rnd.nextInt(3);
            g.setColor(new Color(rnd.nextInt(90) + 80, rnd.nextInt(90) + 90, rnd.nextInt(80) + 120, 180));
            g.fillOval(x, yy, rr, rr);
        }
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 8; i++) {
            g.setColor(new Color(120 + rnd.nextInt(80), 120 + rnd.nextInt(80), 160 + rnd.nextInt(60), 90));
            g.drawLine(rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(w), rnd.nextInt(h));
        }
    }

    private static BufferedImage extractPuzzlePiece(BufferedImage bg, RoundRectangle2D puzzleShapeWorld,
                                                    int secretX, int puzzleY) {
        BufferedImage piece = new BufferedImage(PUZZLE_W, PUZZLE_H, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < PUZZLE_H; py++) {
            for (int px = 0; px < PUZZLE_W; px++) {
                int wx = secretX + px;
                int wy = puzzleY + py;
                if (puzzleShapeWorld.contains(wx, wy)) {
                    piece.setRGB(px, py, bg.getRGB(wx, wy) | 0xFF000000);
                }
            }
        }
        Graphics2D pg = piece.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        RoundRectangle2D local = new RoundRectangle2D.Double(0, 0, PUZZLE_W, PUZZLE_H, PUZZLE_ARC, PUZZLE_ARC);
        pg.setColor(new Color(255, 255, 255, 230));
        pg.setStroke(new BasicStroke(2f));
        pg.draw(local);
        pg.dispose();
        return piece;
    }

    private static String toPngDataUrl(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("png encode", e);
        }
    }
}
