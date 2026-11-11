package com.neko.music.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadStorageRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void restoreLegacyTempFileMovesItBackWhenSourceIsMissing() throws Exception {
        Path source = tempDir.resolve("user_upload").resolve("music_1.mp3");
        Path legacyTemp = tempDir.resolve("Music").resolve("temp_7.mp3");
        byte[] content = new byte[]{4, 5, 6};
        Files.createDirectories(legacyTemp.getParent());
        Files.write(legacyTemp, content);

        UploadStorageRecovery.restoreLegacyTempFile(source, legacyTemp);

        assertArrayEquals(content, Files.readAllBytes(source));
        assertFalse(Files.exists(legacyTemp));
    }

    @Test
    void restoreLegacyTempFileKeepsSourceAndRemovesDuplicate() throws Exception {
        Path source = tempDir.resolve("user_upload").resolve("music_1.mp3");
        Path legacyTemp = tempDir.resolve("Music").resolve("temp_7.mp3");
        byte[] content = new byte[]{7, 8, 9};
        Files.createDirectories(source.getParent());
        Files.createDirectories(legacyTemp.getParent());
        Files.write(source, content);
        Files.write(legacyTemp, new byte[]{1});

        UploadStorageRecovery.restoreLegacyTempFile(source, legacyTemp);

        assertArrayEquals(content, Files.readAllBytes(source));
        assertFalse(Files.exists(legacyTemp));
    }

    @Test
    void deleteUnreferencedUploadFilesOnlyKeepsPendingFiles() throws Exception {
        Path uploadDir = tempDir.resolve("user_upload");
        Path kept = uploadDir.resolve("music_pending.mp3");
        Path orphan = uploadDir.resolve("music_aborted.mp3");
        Path approvedCopy = uploadDir.resolve("cover_approved.jpg");
        Files.createDirectories(uploadDir);
        Files.write(kept, new byte[]{1});
        Files.write(orphan, new byte[]{2});
        Files.write(approvedCopy, new byte[]{3});
        makeOld(orphan, approvedCopy);

        UploadStorageRecovery.deleteUnreferencedUploadFiles(uploadDir,
                Set.of(kept.toAbsolutePath().normalize()), System.currentTimeMillis());

        assertTrue(Files.exists(kept));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(approvedCopy));
    }

    @Test
    void cleanupMediaDirDeletesStagingAndUnknownMusicIdFiles() throws Exception {
        Path mediaDir = tempDir.resolve("Music");
        Path known = mediaDir.resolve("7.mp3");
        Path orphan = mediaDir.resolve("8.mp3");
        Path staging = mediaDir.resolve(".neko-staging-123.part");
        Path legacyTemp = mediaDir.resolve("temp_9.mp3");
        Files.createDirectories(mediaDir);
        Files.write(known, new byte[]{1});
        Files.write(orphan, new byte[]{2});
        Files.write(staging, new byte[]{3});
        Files.write(legacyTemp, new byte[]{4});
        makeOld(orphan, staging);

        UploadStorageRecovery.cleanupMediaDir(mediaDir, Set.of(7), System.currentTimeMillis());

        assertTrue(Files.exists(known));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(staging));
        assertTrue(Files.exists(legacyTemp));
    }

    @Test
    void cleanupMediaDirWithoutDatabaseIdsOnlyDeletesStaging() throws Exception {
        Path mediaDir = tempDir.resolve("Music");
        Path orphan = mediaDir.resolve("8.mp3");
        Path staging = mediaDir.resolve(".neko-staging-123.part");
        Files.createDirectories(mediaDir);
        Files.write(orphan, new byte[]{2});
        Files.write(staging, new byte[]{3});
        makeOld(orphan, staging);

        UploadStorageRecovery.cleanupMediaDir(mediaDir, null, System.currentTimeMillis());

        assertTrue(Files.exists(orphan));
        assertFalse(Files.exists(staging));
    }

    @Test
    void cleanupMediaDirSkipsRecentFilesDuringRollingRestart() throws Exception {
        Path mediaDir = tempDir.resolve("Music");
        Path recentOrphan = mediaDir.resolve("8.mp3");
        Path recentStaging = mediaDir.resolve(".neko-staging-123.part");
        Files.createDirectories(mediaDir);
        Files.write(recentOrphan, new byte[]{2});
        Files.write(recentStaging, new byte[]{3});

        UploadStorageRecovery.cleanupMediaDir(mediaDir, Set.of(), System.currentTimeMillis() - 60_000);

        assertTrue(Files.exists(recentOrphan));
        assertTrue(Files.exists(recentStaging));
    }

    private static void makeOld(Path... paths) throws Exception {
        FileTime old = FileTime.fromMillis(0);
        for (Path path : paths) {
            Files.setLastModifiedTime(path, old);
        }
    }
}
