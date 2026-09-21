package com.writablebookwiki.local;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Reader appearance is a local preference, never part of a shared book or its hash. */
public final class LocalAppearanceStore {
    public static final int DEFAULT_PAPER_RGB = 0xFFF8E8;
    private final Path file;

    public LocalAppearanceStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public int loadPaperColor() throws IOException {
        if (!Files.isRegularFile(file)) return DEFAULT_PAPER_RGB;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IllegalArgumentException malformed) {
            return DEFAULT_PAPER_RGB;
        }
        String value = values.getProperty("paper_color", "").trim();
        return value.matches("#[0-9a-fA-F]{6}") ? Integer.parseInt(value.substring(1), 16) : DEFAULT_PAPER_RGB;
    }

    public void savePaperColor(int rgb) throws IOException {
        if ((rgb & 0xFF000000) != 0) throw new IllegalArgumentException("Expected a 24-bit RGB colour");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
        try {
            Files.writeString(temporary, "# Local reader appearance; never uploaded with books\npaper_color=#%06X\n".formatted(rgb),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
