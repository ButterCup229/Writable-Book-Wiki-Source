package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.*;
import com.writablebookwiki.core.ModIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoragePathsTest {
    @TempDir Path root;

    @Test void freshInstallUsesOwnNamespaceWithoutCreatingFiles() {
        assertEquals(root.resolve("writablebookwiki"), StoragePaths.dataRoot(root));
        assertFalse(Files.exists(root.resolve("writablebookwiki")));
        assertEquals("writablebookwiki-server.toml", StoragePaths.serverConfigName());
        assertEquals("Writable Book Wiki", ModIdentity.DISPLAY_NAME);
    }

    @Test void neverAdoptsOrMovesOldDevelopmentData() throws Exception {
        for (String context : new String[]{"config", "world"}) {
            Path parent = root.resolve(context);
            Path previous = Files.createDirectories(parent.resolve("editbookwiki"));
            Path content = previous.resolve("retained-data");
            Files.writeString(content, "leave untouched");
            assertEquals(parent.resolve("writablebookwiki"), StoragePaths.dataRoot(parent));
            assertEquals("leave untouched", Files.readString(content));
            assertFalse(Files.exists(parent.resolve("writablebookwiki")));
        }
    }

    @Test void existingDataDirectoryIsUsedUnchanged() throws Exception {
        Path current = Files.createDirectories(root.resolve("writablebookwiki"));
        Files.writeString(current.resolve("retained-data"), "current");
        assertEquals(current, StoragePaths.dataRoot(root));
        assertEquals("current", Files.readString(current.resolve("retained-data")));
    }

    @Test void neverTreatsAFileAsAnEmptyStorageDirectory() throws Exception {
        Files.writeString(root.resolve("writablebookwiki"), "not a directory");
        assertThrows(IllegalStateException.class, () -> StoragePaths.dataRoot(root));
    }

    @Test void unrelatedServerConfigIsNeverSelectedOrChanged() throws Exception {
        Path previous = root.resolve("editbookwiki-server.toml");
        Files.writeString(previous, "download_bytes_per_second=12345");
        assertEquals("writablebookwiki-server.toml", StoragePaths.serverConfigName());
        assertEquals("download_bytes_per_second=12345", Files.readString(previous));
    }
}
