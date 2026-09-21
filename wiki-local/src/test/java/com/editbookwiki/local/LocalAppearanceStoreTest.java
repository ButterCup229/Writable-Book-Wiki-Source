package com.editbookwiki.local;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAppearanceStoreTest {
    @TempDir Path directory;

    @Test void missingPreferenceUsesPaperWithoutWritingAFile() throws Exception {
        Path file = directory.resolve("appearance.properties");
        assertEquals(0xFFF8E8, new LocalAppearanceStore(file).loadPaperColor());
        assertFalse(Files.exists(file));
    }

    @Test void savesAndReloadsExactCustomColor() throws Exception {
        Path file = directory.resolve("nested/appearance.properties");
        new LocalAppearanceStore(file).savePaperColor(0x0A31FF);
        assertEquals(0x0A31FF, new LocalAppearanceStore(file).loadPaperColor());
        try (var entries = Files.list(file.getParent())) { assertEquals(1, entries.count()); }
    }

    @Test void malformedColorFallsBackWithoutOverwritingThePreference() throws Exception {
        Path file = directory.resolve("appearance.properties");
        Files.writeString(file, "paper_color=#nope\n");
        assertEquals(LocalAppearanceStore.DEFAULT_PAPER_RGB, new LocalAppearanceStore(file).loadPaperColor());
        assertEquals("paper_color=#nope\n", Files.readString(file));
    }

    @Test void invalidValueCannotOverwriteSavedAppearance() throws Exception {
        var store = new LocalAppearanceStore(directory.resolve("appearance.properties"));
        store.savePaperColor(0xFFFFFF);
        assertThrows(IllegalArgumentException.class, () -> store.savePaperColor(-1));
        assertThrows(IllegalArgumentException.class, () -> store.savePaperColor(0x1000000));
        assertEquals(0xFFFFFF, store.loadPaperColor());
    }
}
