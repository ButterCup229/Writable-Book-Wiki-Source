package com.writablebookwiki.local.transfer;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One ordered document transfer, owned by its caller's game thread.
 * Memory grows with accepted data, not the untrusted declared size.
 * Rejected chunks never advance the cursor or keep an abandoned transfer alive.
 */
public final class DocumentTransferBuffer {
    private final int totalBytes;
    private final int chunkCount;
    private final int chunkBytes;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final ByteArrayOutputStream output;
    private int nextChunk;
    private long lastActivity;

    public DocumentTransferBuffer(int totalBytes, int chunkCount, int chunkBytes, int maximumBytes,
            long timeoutNanos, LongSupplier clock) {
        if (totalBytes <= 0 || totalBytes > maximumBytes || chunkBytes <= 0 || chunkCount <= 0
                || chunkCount != ((long) totalBytes + chunkBytes - 1) / chunkBytes || timeoutNanos <= 0) {
            throw new IllegalArgumentException("Invalid document transfer header");
        }
        this.totalBytes = totalBytes;
        this.chunkCount = chunkCount;
        this.chunkBytes = chunkBytes;
        this.timeoutNanos = timeoutNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.output = new ByteArrayOutputStream(Math.min(totalBytes, chunkBytes));
        this.lastActivity = clock.getAsLong();
    }

    public boolean accept(int chunkIndex, byte[] bytes) {
        if (nextChunk >= chunkCount || chunkIndex != nextChunk || bytes == null
                || bytes.length != Math.min(chunkBytes, totalBytes - output.size())) {
            return false;
        }
        output.writeBytes(bytes);
        nextChunk++;
        lastActivity = clock.getAsLong();
        return true;
    }

    public boolean isComplete() {
        return nextChunk == chunkCount && output.size() == totalBytes;
    }

    /** Returns an isolated snapshot only after all bytes have arrived. */
    public byte[] bytes() {
        if (!isComplete()) throw new IllegalStateException("Document transfer is incomplete");
        return output.toByteArray();
    }

    public boolean isExpired(long nowNanos) {
        return nowNanos - lastActivity > timeoutNanos;
    }
}
