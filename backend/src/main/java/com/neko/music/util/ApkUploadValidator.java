package com.neko.music.util;

import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import net.dongliu.apk.parser.bean.ApkSigner;
import net.dongliu.apk.parser.bean.ApkV2Signer;
import net.dongliu.apk.parser.bean.CertificateMeta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** 管理员上传 Android 手机 APK：校验包名、版本号与签名。 */
public final class ApkUploadValidator {

    /** 与 Android/app/build.gradle.kts applicationId 一致 */
    private static final String EXPECTED_PACKAGE_NAME = "com.neko.music";

    /**
     * release keystore（neko_key.jks）证书 SHA-256，不会轮换。
     * 查看：keytool -list -v -keystore neko_key.jks -alias neko
     */
    public static final String EXPECTED_CERT_SHA256 =
            "53a64cdb1491a2b28a3d309e41e92addbabcc691b8fb5bdc1ba39ec86e817fa2";

    private ApkUploadValidator() {
    }

    public static void validate(Path apkPath, String expectedVersionToken) throws IOException {
        validateInternal(apkPath, expectedVersionToken, EXPECTED_PACKAGE_NAME, EXPECTED_CERT_SHA256);
    }

    private static void validateInternal(Path apkPath, String expectedVersionToken,
                                         String expectedPackageName, String expectedCertSha256) throws IOException {
        VersionToken expectedVersion = parseVersionToken(expectedVersionToken);
        validateApkStructure(apkPath);

        try (ApkFile apkFile = new ApkFile(apkPath.toFile())) {
            ApkMeta meta = apkFile.getApkMeta();
            if (meta == null) {
                throw new IllegalArgumentException("无法解析 APK 元数据");
            }

            String packageName = trimOrNull(meta.getPackageName());
            if (packageName == null) {
                throw new IllegalArgumentException("APK 缺少包名");
            }
            if (expectedPackageName != null && !expectedPackageName.equals(packageName)) {
                throw new IllegalArgumentException(
                        "APK 包名不匹配，期望 " + expectedPackageName + "，实际 " + packageName);
            }

            String versionName = trimOrNull(meta.getVersionName());
            if (versionName == null) {
                throw new IllegalArgumentException("APK 缺少 versionName");
            }
            if (!expectedVersion.versionName().equals(versionName)) {
                throw new IllegalArgumentException(
                        "APK versionName 不匹配，期望 " + expectedVersion.versionName() + "，实际 " + versionName);
            }

            Long versionCode = meta.getVersionCode();
            if (versionCode == null) {
                throw new IllegalArgumentException("APK 缺少 versionCode");
            }
            if (expectedVersion.versionCode() != versionCode.longValue()) {
                throw new IllegalArgumentException(
                        "APK versionCode 不匹配，期望 " + expectedVersion.versionCode() + "，实际 " + versionCode);
            }

            LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
            collectFingerprints(apkFile.getApkSingers(), fingerprints);
            if (fingerprints.isEmpty()
                    || (expectedCertSha256 != null && !fingerprints.contains(expectedCertSha256))) {
                collectTrustedV2Fingerprints(apkFile, fingerprints);
            }
            if (fingerprints.isEmpty()) {
                throw new IllegalArgumentException("APK 未检测到有效签名");
            }
            if (expectedCertSha256 != null && !fingerprints.contains(expectedCertSha256)) {
                throw new IllegalArgumentException("APK 签名证书不受信任");
            }
        } catch (IllegalArgumentException e) {
            if (isBusinessValidationError(e.getMessage())) {
                throw e;
            }
            throw invalidApk(e);
        } catch (Exception e) {
            throw invalidApk(e);
        }
    }

    private static void validateApkStructure(Path apkPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(apkPath.toFile())) {
            if (zipFile.getEntry("AndroidManifest.xml") == null) {
                throw new IllegalArgumentException("无效的 APK 文件：缺少 AndroidManifest.xml");
            }
            if (zipFile.getEntry("classes.dex") == null && zipFile.getEntry("classes2.dex") == null) {
                throw new IllegalArgumentException("无效的 APK 文件：缺少 classes.dex");
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("无效的 APK 文件：请确认上传的是完整 APK", e);
        }
    }

    private static boolean isBusinessValidationError(String message) {
        return message != null && (
                message.startsWith("未配置目标版本号")
                        || message.startsWith("版本号需包含")
                        || message.startsWith("versionCode")
                        || message.startsWith("无法解析 APK 元数据")
                        || message.startsWith("APK 缺少")
                        || message.startsWith("APK 包名不匹配")
                        || message.startsWith("APK versionName 不匹配")
                        || message.startsWith("APK versionCode 不匹配")
                        || message.startsWith("APK 未检测到有效签名")
                        || message.startsWith("APK 签名证书不受信任")
                        || message.startsWith("无效的 APK 文件："));
    }

    private static IllegalArgumentException invalidApk(Exception e) {
        return new IllegalArgumentException("无效的 APK 文件：请确认上传的是完整 release APK", e);
    }

    private static void collectFingerprints(List<ApkSigner> signers, Set<String> out) {
        if (signers == null) {
            return;
        }
        for (ApkSigner signer : signers) {
            addCertificateMetas(signer.getCertificateMetas(), out);
        }
    }

    private static void collectTrustedV2Fingerprints(ApkFile apkFile, Set<String> out) {
        try {
            collectV2Fingerprints(apkFile.getApkV2Singers(), out);
        } catch (Exception e) {
            throw new IllegalArgumentException("APK 签名解析失败，请确认上传的是 release APK", e);
        }
    }

    private static void collectV2Fingerprints(List<ApkV2Signer> signers, Set<String> out) {
        if (signers == null) {
            return;
        }
        for (ApkV2Signer signer : signers) {
            addCertificateMetas(signer.getCertificateMetas(), out);
        }
    }

    private static void addCertificateMetas(List<CertificateMeta> metas, Set<String> out) {
        if (metas == null) {
            return;
        }
        for (CertificateMeta meta : metas) {
            String fp = sha256Fingerprint(meta.getData());
            if (!fp.isEmpty()) {
                out.add(fp);
            }
        }
    }

    private static String sha256Fingerprint(byte[] certBytes) {
        if (certBytes == null || certBytes.length == 0) {
            return "";
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static VersionToken parseVersionToken(String value) {
        String token = trimOrNull(value);
        if (token == null) {
            throw new IllegalArgumentException("未配置目标版本号");
        }

        int separator = token.lastIndexOf('-');
        if (separator <= 0 || separator == token.length() - 1) {
            throw new IllegalArgumentException("版本号需包含 versionName-versionCode");
        }

        String versionName = trimOrNull(token.substring(0, separator));
        String versionCodeText = trimOrNull(token.substring(separator + 1));
        if (versionName == null || versionCodeText == null) {
            throw new IllegalArgumentException("版本号需包含 versionName-versionCode");
        }

        long versionCode;
        try {
            versionCode = Long.parseLong(versionCodeText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("versionCode 必须是数字");
        }
        if (versionCode < 0) {
            throw new IllegalArgumentException("versionCode 不能为负数");
        }

        return new VersionToken(versionName, versionCode);
    }

    private record VersionToken(String versionName, long versionCode) {
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
