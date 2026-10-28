package com.writablebookwiki.neoforge.network;

import com.writablebookwiki.core.book.BookPointer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;

/** Version 1 wire bounds and shared primitive codecs. Changing these requires protocol review. */
public final class BookProtocol {
    private BookProtocol() { }
    /** Raw document bytes per packet. Packet framing is additional. */
    public static final int DOCUMENT_CHUNK_BYTES = 24 * 1024;
    /** Image chunks use the same bounded packet size as document chunks. */
    public static final int IMAGE_CHUNK_BYTES = DOCUMENT_CHUNK_BYTES;

    /**
     * A technical in-memory ceiling, not a gameplay quota. It prevents an
     * invalid header from allocating arbitrary memory before the configured
     * synchronisation policy is available. Images are external assets and do
     * not count toward this document-data transfer.
     */
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    /** Technical asset size cap; chunks are received into temporary files, not full-size byte arrays. */
    public static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    /** A missing chunk must not reserve a document/image buffer forever. */
    public static final long TRANSFER_TIMEOUT_NANOS = 45_000_000_000L;
    public static final int MAX_DOCUMENT_CHUNKS =
            (MAX_DOCUMENT_BYTES + DOCUMENT_CHUNK_BYTES - 1) / DOCUMENT_CHUNK_BYTES;
    public static final int MAX_IMAGE_CHUNKS =
            (MAX_IMAGE_BYTES + IMAGE_CHUNK_BYTES - 1) / IMAGE_CHUNK_BYTES;
    public static final int MAX_FAILURE_MESSAGE_CHARS = 160;

    public static boolean isRequestIdValid(long requestId) {
        return requestId > 0;
    }

    public static int chunkCountFor(int byteLength) {
        if (byteLength <= 0 || byteLength > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Document byte length is outside transport bounds");
        }
        return (int) (((long) byteLength + DOCUMENT_CHUNK_BYTES - 1L) / DOCUMENT_CHUNK_BYTES);
    }

    public static int imageChunkCountFor(int byteLength) {
        if (byteLength <= 0 || byteLength > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image byte length is outside transport bounds");
        }
        return (int) (((long) byteLength + IMAGE_CHUNK_BYTES - 1L) / IMAGE_CHUNK_BYTES);
    }

    public static boolean isAssetHashValid(String assetHash) {
        return assetHash != null && assetHash.matches("[0-9a-f]{64}");
    }

    public static InteractionHand readHand(RegistryFriendlyByteBuf buffer) {
        int handOrdinal = buffer.readUnsignedByte();
        InteractionHand[] hands = InteractionHand.values();
        if (handOrdinal >= hands.length) {
            throw new IllegalArgumentException("Invalid interaction hand");
        }
        return hands[handOrdinal];
    }

    public static void writeHand(RegistryFriendlyByteBuf buffer, InteractionHand hand) {
        buffer.writeByte(hand.ordinal());
    }

    public static BookPointer readPointer(RegistryFriendlyByteBuf buffer) {
        return new BookPointer(buffer.readUUID(), buffer.readUtf(64), buffer.readBoolean());
    }

    public static void writePointer(RegistryFriendlyByteBuf buffer, BookPointer pointer) {
        buffer.writeUUID(pointer.bookId());
        buffer.writeUtf(pointer.contentHash(), 64);
        buffer.writeBoolean(pointer.locked());
    }
}
