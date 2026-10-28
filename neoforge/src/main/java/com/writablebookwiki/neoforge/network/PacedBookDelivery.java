package com.writablebookwiki.neoforge.network;

import static com.writablebookwiki.neoforge.network.BookProtocol.*;
import com.writablebookwiki.neoforge.network.BookPayloads.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-thread delivery queue. Owns every queued image stream until drained, replaced or closed. */
public final class PacedBookDelivery implements AutoCloseable {
    private final Deque<OutboundDelivery> queue = new ArrayDeque<>();
    private double availableBytes;
    private boolean closed;

    private void enqueue(OutboundDelivery delivery) {
        if (closed) {
            delivery.close();
            throw new IllegalStateException("Delivery queue is closed");
        }
        queue.addLast(delivery);
    }

    public void deliver(Consumer<CustomPacketPayload> send, int bytesPerSecond) {
        if (closed) return;
        if (bytesPerSecond < 0) throw new IllegalArgumentException("Negative transfer rate");
        try {
            deliverTick(send, bytesPerSecond);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void deliverTick(Consumer<CustomPacketPayload> send, int bytesPerSecond) {
        boolean unlimited = bytesPerSecond == 0;
        if (!unlimited) {
            // Keep at most one second or one full packet. A rate below the
            // packet size must accumulate enough credit to eventually send.
            availableBytes = Math.min(Math.max(bytesPerSecond, DOCUMENT_CHUNK_BYTES), availableBytes + bytesPerSecond / 20.0D);
        }
        while (!queue.isEmpty()) {
            OutboundDelivery current = queue.peekFirst();
            int nextBytes = current.nextChunkBytes();
            if (nextBytes < 0) {
                current.close();
                queue.removeFirst();
                continue;
            }
            if (nextBytes == 0) {
                // Image headers are emitted only when that image reaches
                // the front of the queue. This prevents client receive
                // timeouts while an earlier body is intentionally paced.
                current.sendNext(send);
                if (current.isComplete()) {
                    current.close();
                    queue.removeFirst();
                }
                continue;
            }
            if (!unlimited && availableBytes < nextBytes) {
                return;
            }
            int sent = current.sendNext(send);
            if (sent <= 0) {
                current.close();
                queue.removeFirst();
                continue;
            }
            if (!unlimited) {
                availableBytes -= sent;
            }
            if (current.isComplete()) {
                current.close();
                queue.removeFirst();
            }
        }
    }

    public boolean isComplete() {
        return queue.isEmpty();
    }

    @Override
    public void close() {
        closed = true;
        while (!queue.isEmpty()) {
            queue.removeFirst().close();
        }
    }
    public void enqueueDocument(long requestId, byte[] bytes) {
        enqueue(new DocumentDelivery(requestId, bytes));
    }

    public void enqueueImage(long requestId, String hash, Path source, int totalBytes) {
        enqueue(new ImageDelivery(requestId, hash, source, totalBytes));
    }


    private interface OutboundDelivery {
        /** Returns zero while a header remains to send, or -1 when unavailable. */
        int nextChunkBytes();

        /** Sends one header or one body chunk, returning body bytes sent. */
        int sendNext(Consumer<CustomPacketPayload> send);

        boolean isComplete();

        void close();
    }

    /** Queued document body; its corresponding header has already been sent. */
    private static final class DocumentDelivery implements OutboundDelivery {
        private final long requestId;
        private final byte[] bytes;
        private int nextChunkIndex;

        private DocumentDelivery(long requestId, byte[] bytes) {
            this.requestId = requestId;
            this.bytes = bytes;
        }

        @Override
        public int nextChunkBytes() {
            if (isComplete()) {
                return -1;
            }
            int offset = nextChunkIndex * DOCUMENT_CHUNK_BYTES;
            return Math.min(DOCUMENT_CHUNK_BYTES, bytes.length - offset);
        }

        @Override
        public int sendNext(Consumer<CustomPacketPayload> send) {
            int expected = nextChunkBytes();
            if (expected <= 0) {
                return 0;
            }
            int offset = nextChunkIndex * DOCUMENT_CHUNK_BYTES;
            send.accept(new BookOpenChunkPayload(requestId, nextChunkIndex,
                    Arrays.copyOfRange(bytes, offset, offset + expected)));
            nextChunkIndex++;
            return expected;
        }

        @Override
        public boolean isComplete() {
            return nextChunkIndex >= chunkCountFor(bytes.length);
        }

        @Override
        public void close() {
            // The document body is an owned byte array; dropping this queue
            // entry is sufficient for it to be reclaimed.
        }
    }

    /** Queued image body. It keeps a small reusable packet buffer, not the full asset in memory. */
    private static final class ImageDelivery implements OutboundDelivery {
        private final long requestId;
        private final String assetHash;
        private final Path source;
        private final int totalBytes;
        private final int chunkCount;
        private final byte[] buffer = new byte[IMAGE_CHUNK_BYTES];
        private int nextChunkIndex;
        private boolean headerSent;
        private boolean failed;
        private InputStream input;

        private ImageDelivery(long requestId, String assetHash, Path source, int totalBytes) {
            this.requestId = requestId;
            this.assetHash = assetHash;
            this.source = source;
            this.totalBytes = totalBytes;
            this.chunkCount = imageChunkCountFor(totalBytes);
        }

        @Override
        public int nextChunkBytes() {
            if (failed || isComplete()) {
                return -1;
            }
            if (!headerSent) {
                return 0;
            }
            int offset = nextChunkIndex * IMAGE_CHUNK_BYTES;
            return Math.min(IMAGE_CHUNK_BYTES, totalBytes - offset);
        }

        @Override
        public int sendNext(Consumer<CustomPacketPayload> send) {
            if (!headerSent) {
                send.accept(new ImageOpenHeaderPayload(requestId, assetHash, totalBytes,
                        chunkCount));
                headerSent = true;
                return 0;
            }
            int expected = nextChunkBytes();
            if (expected <= 0) {
                return 0;
            }
            try {
                if (input == null) {
                    input = Files.newInputStream(source);
                }
                int offset = 0;
                while (offset < expected) {
                    int read = input.read(buffer, offset, expected - offset);
                    if (read < 0) {
                        failed = true;
                        return 0;
                    }
                    offset += read;
                }
                send.accept(new ImageOpenChunkPayload(requestId, assetHash, nextChunkIndex,
                        Arrays.copyOf(buffer, expected)));
                nextChunkIndex++;
                return expected;
            } catch (IOException exception) {
                failed = true;
                return 0;
            }
        }

        @Override
        public boolean isComplete() {
            return failed || nextChunkIndex >= chunkCount;
        }

        @Override
        public void close() {
            if (input == null) {
                return;
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // Closing an optional image stream must not affect the book.
            }
            input = null;
        }
    }


}
