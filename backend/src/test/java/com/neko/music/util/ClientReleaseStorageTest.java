package com.neko.music.util;

import com.neko.music.service.AppReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientReleaseStorageTest {

    @Test
    void watchPlatformIsNoLongerSupported() {
        AppReleaseService.AppRelease release =
                new AppReleaseService.AppRelease("20260207-36", "2026.207.6");
        assertNull(ClientReleaseStorage.expectedFileNameForPlatform("watch", release));
        assertNull(ClientReleaseStorage.requiredExtensionForPlatform("watch"));
        assertThrows(IllegalArgumentException.class,
                () -> ClientReleaseStorage.validateSourceFileForPlatform("watch", "watch-release.apk", 1024));
    }

    @Test
    void platformCleanupKeepsNewAndPublishedPackagesOnly(@TempDir Path dir) throws Exception {
        Path oldAndroid = Files.writeString(dir.resolve("20260101-1.apk"), "old");
        Path publishedAndroid = Files.writeString(dir.resolve("20260201-2.apk"), "published");
        Path newAndroid = Files.writeString(dir.resolve("20260207-3.apk"), "new");
        int removed = ClientReleaseStorage.deleteObsoletePlatformPackages(
                dir, "android", Set.of(publishedAndroid.getFileName().toString(), newAndroid.getFileName().toString()));

        assertEquals(1, removed);
        assertFalse(Files.exists(oldAndroid));
        assertTrue(Files.exists(publishedAndroid));
        assertTrue(Files.exists(newAndroid));
    }

    @Test
    void releaseCleanupRemovesEveryOlderPlatformPackage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("20260101-1.apk"), "old android");
        Files.writeString(dir.resolve("Neko歌姬计划 Setup 2026.101.1.exe"), "old windows");
        Files.writeString(dir.resolve("NekoMusic_2026.101.1_amd64.deb"), "old linux");
        Files.writeString(dir.resolve("Neko歌姬计划2026.101.1.pkg"), "old mac");

        Files.writeString(dir.resolve("20260207-3.apk"), "new android");
        Files.writeString(dir.resolve("Neko歌姬计划 Setup 2026.207.6.exe"), "new windows");
        Files.writeString(dir.resolve("NekoMusic_2026.207.6_amd64.deb"), "new linux");
        Files.writeString(dir.resolve("Neko歌姬计划2026.207.6.pkg"), "new mac");
        Path unrelated = Files.writeString(dir.resolve("keep.txt"), "unrelated");

        int removed = ClientReleaseStorage.deleteObsoleteReleasePackages(
                dir, "20260207-3", "2026.207.6");

        assertEquals(4, removed);
        assertTrue(Files.exists(dir.resolve("20260207-3.apk")));
        assertTrue(Files.exists(dir.resolve("Neko歌姬计划 Setup 2026.207.6.exe")));
        assertTrue(Files.exists(dir.resolve("NekoMusic_2026.207.6_amd64.deb")));
        assertTrue(Files.exists(dir.resolve("Neko歌姬计划2026.207.6.pkg")));
        assertTrue(Files.exists(unrelated));
    }
}
