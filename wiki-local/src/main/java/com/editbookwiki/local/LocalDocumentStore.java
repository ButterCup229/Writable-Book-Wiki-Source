package com.editbookwiki.local;

import com.editbookwiki.core.document.DocumentFormatException;
import com.editbookwiki.core.document.WikiDocument;
import com.editbookwiki.core.document.WikiDocumentCodec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Atomic local storage for a complete {@link WikiDocument}.
 *
 * <p>This is deliberately a local module service. It is suitable for private
 * notes and the development preview, but it does not participate in world
 * book ownership, locking, or copy-on-write rules.</p>
 */
public final class LocalDocumentStore {
    private final Path documentFile;
    private final WikiDocumentCodec codec;

    public LocalDocumentStore(Path documentFile) {
        this(documentFile, new WikiDocumentCodec());
    }

    LocalDocumentStore(Path documentFile, WikiDocumentCodec codec) {
        this.documentFile = Objects.requireNonNull(documentFile, "documentFile").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Returns empty when the local document has not been saved yet. */
    public Optional<WikiDocument> load() throws IOException {
        if (!Files.isRegularFile(documentFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(Files.readAllBytes(documentFile)));
        } catch (DocumentFormatException exception) {
            throw new IOException("The local Wiki document is malformed", exception);
        }
    }

    /**
     * Saves a complete new snapshot. A temporary sibling is atomically moved
     * into place whenever the filesystem supports it, so a crash cannot leave
     * a partly-written document at the final path.
     */
    public void save(WikiDocument document) throws IOException {
        Objects.requireNonNull(document, "document");
        Path directory = documentFile.getParent();
        if (directory == null) {
            throw new IOException("Local document file must have a parent directory");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, documentFile.getFileName().toString(), ".part");
        boolean completed = false;
        try {
            Files.write(temporary, codec.encode(document));
            try {
                Files.move(temporary, documentFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, documentFile, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
