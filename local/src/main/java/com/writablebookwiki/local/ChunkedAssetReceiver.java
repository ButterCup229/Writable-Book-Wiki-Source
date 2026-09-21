package com.writablebookwiki.local;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered, hash-checked file receiver for an externally transported asset.
 *
 * <p>Chunks are appended directly to a private {@code .part} file while the
 * digest is updated. No full asset byte array is retained. A caller receives
 * a completed temporary path only after length, order and SHA-256 all match;
 * otherwise the temporary file is removed.</p>
 */
public final class ChunkedAssetReceiver implements AutoCloseable {
    private final String expectedHash;
    private final int totalBytes;
    private final int chunkCount;
    private final int chunkBytes;
    private final Path temporaryFile;
    private final OutputStream output;
    private final MessageDigest digest;
    private int nextChunkIndex;
    private int receivedBytes;
    private boolean closed;
    private boolean finalized;

    public static ChunkedAssetReceiver create(Path temporaryDirectory, String filePrefix, String expectedHash,
            int totalBytes, int chunkCount, int chunkBytes) throws IOException {
        Path directory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toAbsolutePath().normalize();
        validateHeader(expectedHash, totalBytes, chunkCount, chunkBytes);
        Files.createDirectories(directory);
        return new ChunkedAssetReceiver(expectedHash, totalBytes, chunkCount, chunkBytes,
                Files.createTempFile(directory, Objects.requireNonNull(filePrefix, "filePrefix"), ".part"));
    }

    private ChunkedAssetReceiver(String expectedHash, int totalBytes, int chunkCount, int chunkBytes, Path temporaryFile)
            throws IOException {
        this.expectedHash = expectedHash;
        this.totalBytes = totalBytes;
        this.chunkCount = chunkCount;
        this.chunkBytes = chunkBytes;
        this.temporaryFile = temporaryFile;
        this.output = Files.newOutputStream(temporaryFile);
        this.digest = sha256();
    }

    /** Appends exactly the next expected chunk. Invalid protocol order returns false without writing it. */
    public boolean append(int chunkIndex, byte[] bytes) throws IOException {
        if (closed || finalized || chunkIndex != nextChunkIndex || nextChunkIndex >= chunkCount || bytes == null) {
            return false;
        }
        int remaining = totalBytes - receivedBytes;
        int expectedLength = Math.min(chunkBytes, remaining);
        if (bytes.length != expectedLength) {
            return false;
        }
        output.write(bytes);
        digest.update(bytes);
        receivedBytes += bytes.length;
        nextChunkIndex++;
        return true;
    }

    public boolean isComplete() {
        return nextChunkIndex == chunkCount && receivedBytes == totalBytes;
    }

    /**
     * Closes and validates the temporary file. The caller owns the returned
     * file and must atomically move it into a permanent content-addressed
     * store or delete it after use.
     */
    public Optional<Path> finish() throws IOException {
        if (finalized) {
            return Optional.empty();
        }
        finalized = true;
        closeOutput();
        if (!isComplete() || !expectedHash.equals(HexFormat.of().formatHex(digest.digest()))) {
            Files.deleteIfExists(temporaryFile);
            return Optional.empty();
        }
        return Optional.of(temporaryFile);
    }

    /** Abandons the transfer and removes its incomplete file. */
    public void discard() {
        if (finalized) {
            return;
        }
        finalized = true;
        try {
            closeOutput();
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // A later client/server startup may clean an orphaned temporary
            // file; the transfer never becomes a visible final asset.
        }
    }

    @Override
    public void close() {
        discard();
    }

    private void closeOutput() throws IOException {
        if (!closed) {
            output.close();
            closed = true;
        }
    }

    private static void validateHeader(String expectedHash, int totalBytes, int chunkCount, int chunkBytes) {
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}") || totalBytes <= 0 || chunkCount <= 0
                || chunkBytes <= 0 || chunkCount != expectedChunkCount(totalBytes, chunkBytes)) {
            throw new IllegalArgumentException("Invalid chunked asset header");
        }
    }

    private static int expectedChunkCount(int totalBytes, int chunkBytes) {
        return (int) (((long) totalBytes + chunkBytes - 1L) / chunkBytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
