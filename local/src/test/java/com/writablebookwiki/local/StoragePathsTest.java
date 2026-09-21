package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.*;
import com.writablebookwiki.core.ModIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoragePathsTest {
    @TempDir Path root;

    @Test void freshInstallUsesNewNamespaceWithoutCreatingFiles() {
        assertEquals(root.resolve("writablebookwiki"), StoragePaths.dataRoot(root));
        assertFalse(Files.exists(root.resolve("writablebookwiki")));
        assertEquals("writablebookwiki-server.toml", StoragePaths.serverConfigName(root));
        assertEquals("Writable Book Wiki", ModIdentity.DISPLAY_NAME);
    }

    @Test void legacyLocalAndWorldDirectoriesRemainReadableAndUntouched() throws Exception {
        for (String context : new String[]{"config", "world"}) {
            Path parent = root.resolve(context);
            Path legacy = Files.createDirectories(parent.resolve("editbookwiki"));
            Path content = legacy.resolve("retained-data");
            Files.writeString(content, "old image, annotation, document or preference");
            assertEquals(legacy, StoragePaths.dataRoot(parent));
            assertEquals("old image, annotation, document or preference", Files.readString(content));
            assertFalse(Files.exists(parent.resolve("writablebookwiki")));
            assertEquals(legacy, StoragePaths.dataRoot(parent));
        }
    }

    @Test void existingNewDirectoryWinsWithoutMergingOrOverwritingEitherVersion() throws Exception {
        Path legacy = Files.createDirectories(root.resolve("editbookwiki"));
        Path current = Files.createDirectories(root.resolve("writablebookwiki"));
        Files.writeString(legacy.resolve("same-name"), "legacy");
        Files.writeString(current.resolve("same-name"), "current");
        assertEquals(current, StoragePaths.dataRoot(root));
        assertEquals("legacy", Files.readString(legacy.resolve("same-name")));
        assertEquals("current", Files.readString(current.resolve("same-name")));
    }

    @Test void neverTreatsAFileAsAnEmptyStorageDirectory() throws Exception {
        Files.writeString(root.resolve("editbookwiki"), "not a directory");
        assertThrows(IllegalStateException.class, () -> StoragePaths.dataRoot(root));
        Files.writeString(root.resolve("writablebookwiki"), "also not a directory");
        assertThrows(IllegalStateException.class, () -> StoragePaths.dataRoot(root));
    }

    @Test void existingServerLimitsSurviveRenameAndCurrentConfigHasPriority() throws Exception {
        Path legacy = root.resolve("editbookwiki-server.toml");
        Files.writeString(legacy, "download_bytes_per_second=12345");
        assertEquals("editbookwiki-server.toml", StoragePaths.serverConfigName(root));
        Files.writeString(root.resolve("writablebookwiki-server.toml"), "download_bytes_per_second=67890");
        assertEquals("writablebookwiki-server.toml", StoragePaths.serverConfigName(root));
        assertEquals("download_bytes_per_second=12345", Files.readString(legacy));
    }
}
