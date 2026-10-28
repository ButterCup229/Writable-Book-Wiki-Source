package com.writablebookwiki.local.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
    @TempDir Path directory;

    @Test void replacesCompletedUnicodeTextWithShortDestinationName() throws IOException {
        Path file = directory.resolve("子目录").resolve("书");
        AtomicFiles.writeUtf8(file, "原文");
        AtomicFiles.writeUtf8(file, "批注😀\n新文");
        assertEquals("批注😀\n新文", Files.readString(file));
        assertNoParts(file.getParent());
    }

    @Test void failedWriteKeepsOriginalAndCleansTemporary() throws IOException {
        Path file = directory.resolve("document.ewd");
        Files.writeString(file, "original");
        IOException failure = assertThrows(IOException.class, () -> AtomicFiles.replace(file, temporary -> {
            Files.writeString(temporary, "partial");
            throw new IOException("disk full");
        }));
        assertEquals("disk full", failure.getMessage());
        assertEquals("original", Files.readString(file));
        assertNoParts(directory);
    }

    @Test void failedValidationNeverPublishesNewFile() throws IOException {
        Path file = directory.resolve("export.docx");
        assertThrows(IllegalArgumentException.class, () -> AtomicFiles.replace(file, temporary -> {
            Files.writeString(temporary, "unfinished archive");
            throw new IllegalArgumentException("invalid source");
        }));
        assertFalse(Files.exists(file));
        assertNoParts(directory);
    }

    @Test void returnsResultOnlyAfterFileIsPublished() throws IOException {
        Path file = directory.resolve("result");
        String result = AtomicFiles.replace(file, temporary -> {
            assertEquals(file.getParent(), temporary.getParent());
            assertFalse(Files.exists(file));
            Files.writeString(temporary, "complete");
            return "warnings";
        });
        assertEquals("warnings", result);
        assertEquals("complete", Files.readString(file));
        assertNoParts(directory);
    }

    @Test void failedMoveCleansTemporaryWithoutReplacingDirectory() throws IOException {
        Path target = Files.createDirectory(directory.resolve("occupied"));
        Files.writeString(target.resolve("keep"), "safe");
        assertThrows(IOException.class, () -> AtomicFiles.writeUtf8(target, "data"));
        assertEquals("safe", Files.readString(target.resolve("keep")));
        assertNoParts(directory);
    }

    private void assertNoParts(Path parent) throws IOException {
        try (Stream<Path> entries = Files.list(parent)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }
}
