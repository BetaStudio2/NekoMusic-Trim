package com.neko.music.handlers;

import com.neko.music.service.AppReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 上传安装包时 Android APK 必须通过校验。 */
class AdminClientReleaseUploadHandlerTest {

    private static final AppReleaseService.AppRelease RELEASE =
            new AppReleaseService.AppRelease("20260207-36", "2026.207.6");

    @Test
    void androidUploadStillValidatesApk(@TempDir Path dir) throws Exception {
        Path androidApk = Files.writeString(dir.resolve("android.apk"), "not a real apk");

        assertThrows(IllegalArgumentException.class,
                () -> AdminClientReleaseUploadHandler.validateUploadedArtifact("android", androidApk, RELEASE));
    }
}
