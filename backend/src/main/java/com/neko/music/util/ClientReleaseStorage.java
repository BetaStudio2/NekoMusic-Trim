package com.neko.music.util;

import com.neko.music.service.AppReleaseService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import com.neko.music.i18n.ApiMessages;

/** 客户端安装包目录（直链 {@code GET /update/{文件名}}，固定 {@code {user.dir}/releases}） */
public final class ClientReleaseStorage {

    private static final String RELEASES_DIR_NAME = "releases";
    private static final List<String> PLATFORMS = List.of("android", "windows", "linux", "mac");

    /** 50 MiB（二进制），非 50 MB（十进制） */
    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".apk", ".exe", ".deb", ".pkg"
    );

    private ClientReleaseStorage() {
    }

    public static Path storageDir() {
        return Path.of(System.getProperty("user.dir")).resolve(RELEASES_DIR_NAME).toAbsolutePath().normalize();
    }

    public static void ensureStorageDir() throws IOException {
        Files.createDirectories(storageDir());
    }

    public static String androidApkFileName(String androidVer) {
        return androidVer + ".apk";
    }

    public static String windowsExeFileName(String pcVer) {
        return "Neko歌姬计划 Setup " + pcVer + ".exe";
    }

    public static String linuxDebFileName(String pcVer) {
        return "NekoMusic_" + pcVer + "_amd64.deb";
    }

    public static String macPkgFileName(String pcVer) {
        return "Neko歌姬计划" + pcVer + ".pkg";
    }

    public static String expectedFileNameForPlatform(String platform, AppReleaseService.AppRelease release) {
        if (release == null || platform == null) {
            return null;
        }
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> androidApkFileName(release.androidVer());
            case "windows" -> windowsExeFileName(release.pcVer());
            case "linux" -> linuxDebFileName(release.pcVer());
            case "mac" -> macPkgFileName(release.pcVer());
            default -> null;
        };
    }

    public static String requiredExtensionForPlatform(String platform) {
        if (platform == null) {
            return null;
        }
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> ".apk";
            case "windows" -> ".exe";
            case "linux" -> ".deb";
            case "mac" -> ".pkg";
            default -> null;
        };
    }

    /** 仅校验上传源文件的类型与体积，不要求文件名与发布名一致 */
    public static void validateSourceFileForPlatform(String platform, String sourceFileName, long sizeBytes) {
        String requiredExt = requiredExtensionForPlatform(platform);
        if (requiredExt == null) {
            throw new IllegalArgumentException("无效的平台: " + platform);
        }
        if (sourceFileName == null || sourceFileName.isBlank()) {
            throw new IllegalArgumentException("无法识别文件名");
        }
        String baseName = Path.of(sourceFileName).getFileName().toString();
        if (!baseName.toLowerCase(Locale.ROOT).endsWith(requiredExt)) {
            throw new IllegalArgumentException("该平台仅允许上传 " + requiredExt.substring(1) + " 文件");
        }
        if (sizeBytes < 0 || sizeBytes > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(ApiMessages.translate("admin.release.upload.too_large"));
        }
    }

    public static String publicDownloadUrl(String siteBase, String fileName) {
        String base = trimTrailingSlash(siteBase);
        return base + "/update/" + encodePathSegment(fileName);
    }

    /** 安装包体积（字节）；文件不存在或不可读时返回 0，供 /version 与页面展示 */
    public static long fileSizeOrZero(String fileName) {
        return resolveReadableFile(fileName).map(path -> {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    public static Optional<Path> resolveReadableFile(String fileName) {
        try {
            if (!isSafeFileName(fileName) || !hasAllowedExtension(fileName)) {
                return Optional.empty();
            }
            Path base = storageDir().toAbsolutePath().normalize();
            Path resolved = base.resolve(fileName).normalize();
            if (!resolved.startsWith(base) || !Files.isRegularFile(resolved)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (InvalidPathException | SecurityException e) {
            // A filename that cannot be represented by the host filesystem encoding
            // is equivalent to a missing release for read-only metadata endpoints.
            return Optional.empty();
        }
    }

    public static void validateFileName(String fileName) {
        if (!isSafeFileName(fileName)) {
            throw new IllegalArgumentException("非法文件名");
        }
        if (!hasAllowedExtension(fileName)) {
            throw new IllegalArgumentException("仅允许上传 apk、exe、deb、pkg 安装包");
        }
    }

    public static void validateUpload(String fileName, long sizeBytes) {
        validateFileName(fileName);
        if (sizeBytes < 0 || sizeBytes > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(ApiMessages.translate("admin.release.upload.too_large"));
        }
    }

    public static Path resolveTargetForUpload(String fileName) throws IOException {
        validateFileName(fileName);
        ensureStorageDir();
        Path base = storageDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法文件名");
        }
        return resolved;
    }

    /** 删除同平台下不再需要的安装包，保留传入的文件名（通常是新包与当前对外版本包）。 */
    public static int deleteObsoletePlatformPackages(
            String platform, Collection<String> keepFileNames) throws IOException {
        return deleteObsoletePlatformPackages(storageDir(), platform, keepFileNames);
    }

    static int deleteObsoletePlatformPackages(
            Path directory, String platform, Collection<String> keepFileNames) throws IOException {
        String normalizedPlatform = normalizePlatform(platform);
        if (normalizedPlatform == null) {
            throw new IllegalArgumentException("无效的平台: " + platform);
        }
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        Set<String> keep = new HashSet<>();
        if (keepFileNames != null) {
            for (String fileName : keepFileNames) {
                if (fileName != null && !fileName.isBlank()) {
                    keep.add(fileName);
                    keep.add(fileName.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<Path> obsolete;
        try (var files = Files.list(directory)) {
            obsolete = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> belongsToPlatform(path.getFileName().toString(), normalizedPlatform))
                    .filter(path -> !keep.contains(path.getFileName().toString())
                            && !keep.contains(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        int deleted = 0;
        IOException failure = null;
        for (Path path : obsolete) {
            try {
                if (Files.deleteIfExists(path)) {
                    deleted++;
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return deleted;
    }

    /** 当前发布版本生效后清理所有平台的历史安装包。 */
    public static int deleteObsoleteReleasePackages(
            String androidVer, String pcVer) throws IOException {
        return deleteObsoleteReleasePackages(storageDir(), androidVer, pcVer);
    }

    static int deleteObsoleteReleasePackages(
            Path directory, String androidVer, String pcVer) throws IOException {
        Set<String> keep = new HashSet<>();
        addIfPresent(keep, androidVer == null || androidVer.isBlank() ? null : androidApkFileName(androidVer));
        addIfPresent(keep, pcVer == null || pcVer.isBlank() ? null : windowsExeFileName(pcVer));
        addIfPresent(keep, pcVer == null || pcVer.isBlank() ? null : linuxDebFileName(pcVer));
        addIfPresent(keep, pcVer == null || pcVer.isBlank() ? null : macPkgFileName(pcVer));

        int deleted = 0;
        for (String platform : PLATFORMS) {
            deleted += deleteObsoletePlatformPackages(directory, platform, keep);
        }
        return deleted;
    }

    public static String contentTypeForFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (lower.endsWith(".exe")) {
            return "application/vnd.microsoft.portable-executable";
        }
        if (lower.endsWith(".deb")) {
            return "application/vnd.debian.binary-package";
        }
        if (lower.endsWith(".pkg")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }

    private static boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return false;
        }
        return !fileName.startsWith(".");
    }

    private static boolean hasAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePlatform(String platform) {
        if (platform == null) {
            return null;
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        return PLATFORMS.contains(normalized) ? normalized : null;
    }

    private static boolean belongsToPlatform(String fileName, String normalizedPlatform) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return switch (normalizedPlatform) {
            case "android" -> lower.endsWith(".apk");
            case "windows" -> lower.endsWith(".exe");
            case "linux" -> lower.endsWith(".deb");
            case "mac" -> lower.endsWith(".pkg");
            default -> false;
        };
    }

    private static void addIfPresent(Set<String> target, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            target.add(fileName);
        }
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
