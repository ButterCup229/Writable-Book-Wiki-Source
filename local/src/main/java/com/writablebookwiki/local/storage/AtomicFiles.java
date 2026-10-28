package com.writablebookwiki.local.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Complete a sibling file before publishing it. Callers retain format validation and naming policy. */
public final class AtomicFiles {
    private AtomicFiles() { }

    @FunctionalInterface
    public interface Writer<T> {
        /** Close every stream before returning; the path is private until this method succeeds. */
        T write(Path temporary) throws IOException;
    }

    public static <T> T replace(Path destination, Writer<T> writer) throws IOException {
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Objects.requireNonNull(writer, "writer");
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".wbw-", ".part");
        try {
            T result = writer.write(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Filesystems without atomic rename still receive only the completed file.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public static void writeBytes(Path destination, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        replace(destination, temporary -> Files.write(temporary, bytes));
    }

    public static void writeUtf8(Path destination, String text) throws IOException {
        Objects.requireNonNull(text, "text");
        replace(destination, temporary -> Files.writeString(temporary, text, StandardCharsets.UTF_8));
    }
}
