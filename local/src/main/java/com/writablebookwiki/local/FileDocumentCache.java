package com.writablebookwiki.local;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Private, hash-addressed client cache for shared book bodies.
 *
 * <p>This is intentionally not a {@link LocalDocumentStore}: the cache owns
 * neither a book title nor a revision. A cached byte stream is usable only
 * after decoding and recalculating the exact hash carried by a physical
 * {@link BookPointer}. Failed validation is a cache miss; it never damages or
 * replaces the authoritative world copy.</p>
 */
public final class FileDocumentCache {
    private final Path directory;
    private final WikiDocumentCodec codec;

    public FileDocumentCache(Path directory) {
        this(directory, new WikiDocumentCodec());
    }

    FileDocumentCache(Path directory, WikiDocumentCodec codec) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Returns a document only when its freshly calculated content hash matches {@code pointer}. */
    public Optional<WikiDocument> load(BookPointer pointer) throws IOException {
        Objects.requireNonNull(pointer, "pointer");
        Path file = cacheFile(pointer.contentHash());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            WikiDocument document = codec.decode(Files.readAllBytes(file));
            return pointer.contentHash().equals(codec.contentHash(document))
                    ? Optional.of(document) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Atomically records a server-verified document under its content hash.
     * The caller must already have validated {@code pointer}; this method
     * repeats the deterministic check so accidental stale cache writes cannot
     * make a later open look valid.
     */
    public void save(BookPointer pointer, WikiDocument document) throws IOException {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(document, "document");
        if (!pointer.contentHash().equals(codec.contentHash(document))) {
            throw new IllegalArgumentException("Cached document does not match its book pointer hash");
        }
        Files.createDirectories(directory);
        Path destination = cacheFile(pointer.contentHash());
        if (Files.isRegularFile(destination)) {
            // It is safe to retain a prior cache entry only if it is still
            // verifiably the same content. Replacing it otherwise repairs a
            // locally corrupted entry through the normal atomic path.
            if (load(pointer).isPresent()) {
                return;
            }
        }
        Path temporary = Files.createTempFile(directory, "document-", ".part");
        boolean completed = false;
        try {
            Files.write(temporary, codec.encode(document));
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Path cacheFile(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid content hash");
        }
        return directory.resolve(contentHash + ".ewd");
    }
}
