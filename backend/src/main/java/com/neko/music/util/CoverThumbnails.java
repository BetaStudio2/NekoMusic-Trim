package com.neko.music.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

/**
 * 封面缩略图：把原始封面按需缩放成方形 JPEG 并缓存，供 /api/music/cover/{id}?size= 使用。
 *
 * <p>缓存放在 tmpfs（{@code ${java.io.tmpdir}/.neko/cover-cache}）。文件名包含源版本键：
 * 文件来源用「文件名 + mtime」，内嵌封面用封面内容哈希，源被替换后自动生成新缩略图。</p>
 */
public final class CoverThumbnails {
    private static final Logger logger = LoggerFactory.getLogger(CoverThumbnails.class);

    static {
        // 双保险：无图形环境时必须 headless，否则 createGraphics 会在 X11 初始化时阻塞
        System.setProperty("java.awt.headless", "true");
    }

    /** 允许的尺寸白名单，避免被构造任意大图拖垮内存 */
    private static final Set<Integer> ALLOWED_SIZES = Set.of(96, 160, 240, 320, 480, 640);
    private static final float JPEG_QUALITY = 0.82f;

    private CoverThumbnails() {
    }

    public static boolean isAllowedSize(int size) {
        return ALLOWED_SIZES.contains(size);
    }

    /** 由本地封面文件生成/获取缩略图；失败返回 empty，由调用方回退到原图。 */
    public static Optional<Path> fromFile(Path source, int size) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(source).toMillis();
        } catch (IOException e) {
            mtime = 0L;
        }
        String key = stripExtension(source.getFileName().toString()) + "-" + size + "-" + mtime + ".jpg";
        return fromBytes(() -> Files.newInputStream(source), key, size);
    }

    /**
     * 由内存中的封面字节生成/获取缩略图。
     *
     * <p>{@code versionKey} 必须是封面内容的稳定摘要（如 SHA-256 前缀），否则换封面后仍会命中旧缩略图。</p>
     */
    public static Optional<Path> fromBytes(byte[] data, String versionKey, int size) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        String key = sanitize(versionKey) + "-" + size + ".jpg";
        return fromBytes(() -> new java.io.ByteArrayInputStream(data), key, size);
    }

    /** 打开输入流的工厂；缩略图已存在时不会被调用。 */
    private interface InputStreamSupplier {
        InputStream open() throws IOException;
    }

    private static Optional<Path> fromBytes(InputStreamSupplier source, String key, int size) {
        Path target = cacheDir().resolve(key);
        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }
        try (InputStream in = source.open()) {
            return writeResized(in, target, size);
        } catch (IOException e) {
            logger.warn("生成封面缩略图失败 key={} size={}: {}", key, size, e.toString());
            return Optional.empty();
        }
    }

    /** 版本键只保留安全字符，避免路径穿越与非法文件名。 */
    private static String sanitize(String key) {
        if (key == null || key.isBlank()) {
            return "cover";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length() && sb.length() < 64; i++) {
            char c = key.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "cover" : sb.toString();
    }

    /** 由 classpath 资源（如默认封面 DefaultIcon.png）生成/获取缩略图。 */
    public static Optional<Path> fromResource(String resourceName, int size) {
        String key = stripExtension(Path.of(resourceName).getFileName().toString()) + "-" + size + "-res.jpg";
        Path target = cacheDir().resolve(key);
        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }
        InputStream in = CoverThumbnails.class.getClassLoader().getResourceAsStream(resourceName);
        if (in == null) {
            return Optional.empty();
        }
        try (in) {
            return writeResized(in, target, size);
        } catch (IOException e) {
            logger.warn("生成默认封面缩略图失败 resource={} size={}: {}", resourceName, size, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<Path> writeResized(InputStream in, Path target, int size) {
        try {
            BufferedImage source = ImageIO.read(in);
            if (source == null) {
                return Optional.empty();
            }
            BufferedImage scaled = scaleSquare(source, size);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            if (!writeJpeg(scaled, tmp)) {
                Files.deleteIfExists(tmp);
                return Optional.empty();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(target);
        } catch (Exception e) {
            logger.warn("写入封面缩略图失败 target={}: {}", target, e.toString());
            return Optional.empty();
        }
    }

    /** 居中裁剪成方形并缩放到 size×size，alpha 合成到白底。 */
    private static BufferedImage scaleSquare(BufferedImage source, int size) {
        int w = source.getWidth();
        int h = source.getHeight();
        int side = Math.min(w, h);
        int sx = (w - side) / 2;
        int sy = (h - side) / 2;
        BufferedImage square = source.getSubimage(sx, sy, side, side);

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.drawImage(square, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean writeJpeg(BufferedImage image, Path target) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
            return true;
        } finally {
            writer.dispose();
        }
    }

    private static Path cacheDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), ".neko", "cover-cache");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
