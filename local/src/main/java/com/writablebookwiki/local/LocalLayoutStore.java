package com.writablebookwiki.local;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Local UI layout only. Never stores document content, book IDs or server state. */
public final class LocalLayoutStore {
    public static final int MIN_WIDTH = 64, MAX_WIDTH = 160;
    public record Settings(boolean directoryVisible, boolean annotationVisible, int directoryWidth, int annotationWidth) {
        public Settings {
            if (directoryWidth < MIN_WIDTH || directoryWidth > MAX_WIDTH
                    || annotationWidth < MIN_WIDTH || annotationWidth > MAX_WIDTH)
                throw new IllegalArgumentException("Sidebar width out of range");
        }
    }
    public static final Settings DEFAULT = new Settings(true, true, 96, 110);
    private final Path file;

    public LocalLayoutStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

    public Settings load() throws IOException {
        if (!Files.isRegularFile(file)) return DEFAULT;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
        catch (IllegalArgumentException invalid) { return DEFAULT; }
        return new Settings(flag(values,"directory_visible",true),flag(values,"annotation_visible",true),
                width(values,"directory_width",96),width(values,"annotation_width",110));
    }

    private static boolean flag(Properties values, String key, boolean fallback) {
        String text=values.getProperty(key,"").trim();
        return text.equalsIgnoreCase("true") || !text.equalsIgnoreCase("false") && fallback;
    }
    private static int width(Properties values, String key, int fallback) {
        try {
            int value=Integer.parseInt(values.getProperty(key,"").trim());
            return value>=MIN_WIDTH && value<=MAX_WIDTH ? value : fallback;
        } catch (NumberFormatException invalid) { return fallback; }
    }

    public void save(Settings settings) throws IOException {
        java.util.Objects.requireNonNull(settings);
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"layout-",".tmp");
        try {
            Files.writeString(temporary,"# Local UI only; body paper width is fixed\ndirectory_visible=%s\nannotation_visible=%s\ndirectory_width=%d\nannotation_width=%d\n"
                    .formatted(settings.directoryVisible(),settings.annotationVisible(),settings.directoryWidth(),settings.annotationWidth()),StandardCharsets.UTF_8);
            try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
