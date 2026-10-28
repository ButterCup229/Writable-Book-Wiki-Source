package com.writablebookwiki.neoforge.network;

import static com.writablebookwiki.neoforge.network.BookProtocol.*;
import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.book.SaveOutcome;
import com.writablebookwiki.core.ModIdentity;
import java.util.Arrays;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** Immutable wire messages only: no screen, file-system, scheduling or player-session ownership. */
public final class BookPayloads {
    private BookPayloads() { }
    public record BookOpenRequestPayload(long requestId, InteractionHand hand, BookPointer cachedPointer)
            implements CustomPacketPayload {
        public static final Type<BookOpenRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_open_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookOpenRequestPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenRequestPayload::write, BookOpenRequestPayload::read);

        public BookOpenRequestPayload {
            if (!isRequestIdValid(requestId) || hand == null) {
                throw new IllegalArgumentException("Invalid book-open request");
            }
        }

        private static BookOpenRequestPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            InteractionHand hand = readHand(buffer);
            boolean hasCachedPointer = buffer.readBoolean();
            BookPointer cachedPointer = hasCachedPointer ? readPointer(buffer) : null;
            return new BookOpenRequestPayload(requestId, hand, cachedPointer);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            buffer.writeBoolean(cachedPointer != null);
            if (cachedPointer != null) {
                writePointer(buffer, cachedPointer);
            }
        }

        @Override
        public Type<BookOpenRequestPayload> type() {
            return TYPE;
        }
    }

    public record BookOpenHeaderPayload(long requestId, InteractionHand hand, BookPointer pointer, boolean canEdit,
            boolean canChangeLock, boolean documentBodyOmitted, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<BookOpenHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_open_header"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookOpenHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenHeaderPayload::write, BookOpenHeaderPayload::read);

        public BookOpenHeaderPayload {
            boolean validBodyShape = documentBodyOmitted
                    ? totalBytes == 0 && chunkCount == 0
                    : totalBytes > 0 && totalBytes <= MAX_DOCUMENT_BYTES
                    && chunkCount == chunkCountFor(totalBytes);
            if (!isRequestIdValid(requestId) || hand == null || pointer == null || !validBodyShape) {
                throw new IllegalArgumentException("Invalid book-open header");
            }
        }

        private static BookOpenHeaderPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            InteractionHand hand = readHand(buffer);
            BookPointer pointer = readPointer(buffer);
            boolean canEdit = buffer.readBoolean();
            boolean canChangeLock = buffer.readBoolean();
            boolean documentBodyOmitted = buffer.readBoolean();
            int totalBytes = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            return new BookOpenHeaderPayload(requestId, hand, pointer, canEdit, canChangeLock,
                    documentBodyOmitted, totalBytes, chunkCount);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            writePointer(buffer, pointer);
            buffer.writeBoolean(canEdit);
            buffer.writeBoolean(canChangeLock);
            buffer.writeBoolean(documentBodyOmitted);
            buffer.writeVarInt(totalBytes);
            buffer.writeVarInt(chunkCount);
        }

        @Override
        public Type<BookOpenHeaderPayload> type() {
            return TYPE;
        }
    }

    public record BookOpenChunkPayload(long requestId, int chunkIndex, byte[] bytes) implements CustomPacketPayload {
        public static final Type<BookOpenChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_open_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookOpenChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenChunkPayload::write, BookOpenChunkPayload::read);

        public BookOpenChunkPayload {
            if (!isRequestIdValid(requestId) || chunkIndex < 0 || chunkIndex >= MAX_DOCUMENT_CHUNKS
                    || bytes == null || bytes.length <= 0 || bytes.length > DOCUMENT_CHUNK_BYTES) {
                throw new IllegalArgumentException("Invalid book-open chunk");
            }
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        private static BookOpenChunkPayload read(RegistryFriendlyByteBuf buffer) {
            return new BookOpenChunkPayload(buffer.readLong(), buffer.readVarInt(),
                    buffer.readByteArray(DOCUMENT_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeVarInt(chunkIndex);
            buffer.writeByteArray(bytes);
        }

        @Override
        public Type<BookOpenChunkPayload> type() {
            return TYPE;
        }
    }

    public record BookOpenFailurePayload(long requestId, String reason) implements CustomPacketPayload {
        public static final Type<BookOpenFailurePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_open_failure"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookOpenFailurePayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenFailurePayload::write, BookOpenFailurePayload::read);

        public BookOpenFailurePayload {
            if (!isRequestIdValid(requestId) || reason == null || reason.isBlank()
                    || reason.length() > MAX_FAILURE_MESSAGE_CHARS) {
                throw new IllegalArgumentException("Invalid book-open failure");
            }
        }

        private static BookOpenFailurePayload read(RegistryFriendlyByteBuf buffer) {
            return new BookOpenFailurePayload(buffer.readLong(), buffer.readUtf(MAX_FAILURE_MESSAGE_CHARS));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(reason, MAX_FAILURE_MESSAGE_CHARS);
        }

        @Override
        public Type<BookOpenFailurePayload> type() {
            return TYPE;
        }
    }

    public record ImageUploadHeaderPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            String assetHash, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<ImageUploadHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "image_upload_header"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadHeaderPayload::write, ImageUploadHeaderPayload::read);

        public ImageUploadHeaderPayload {
            if (!isRequestIdValid(requestId) || hand == null || originalPointer == null || !isAssetHashValid(assetHash)
                    || totalBytes <= 0 || totalBytes > MAX_IMAGE_BYTES || chunkCount != imageChunkCountFor(totalBytes)) {
                throw new IllegalArgumentException("Invalid image-upload header");
            }
        }

        private static ImageUploadHeaderPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            InteractionHand hand = readHand(buffer);
            BookPointer originalPointer = readPointer(buffer);
            return new ImageUploadHeaderPayload(requestId, hand, originalPointer, buffer.readUtf(64),
                    buffer.readVarInt(), buffer.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            writePointer(buffer, originalPointer);
            buffer.writeUtf(assetHash, 64);
            buffer.writeVarInt(totalBytes);
            buffer.writeVarInt(chunkCount);
        }

        @Override
        public Type<ImageUploadHeaderPayload> type() {
            return TYPE;
        }
    }

    public record ImageUploadChunkPayload(long requestId, String assetHash, int chunkIndex, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<ImageUploadChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "image_upload_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadChunkPayload::write, ImageUploadChunkPayload::read);

        public ImageUploadChunkPayload {
            if (!isRequestIdValid(requestId) || !isAssetHashValid(assetHash)
                    || chunkIndex < 0 || chunkIndex >= MAX_IMAGE_CHUNKS
                    || bytes == null || bytes.length <= 0 || bytes.length > IMAGE_CHUNK_BYTES) {
                throw new IllegalArgumentException("Invalid image-upload chunk");
            }
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        private static ImageUploadChunkPayload read(RegistryFriendlyByteBuf buffer) {
            return new ImageUploadChunkPayload(buffer.readLong(), buffer.readUtf(64), buffer.readVarInt(),
                    buffer.readByteArray(IMAGE_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(assetHash, 64);
            buffer.writeVarInt(chunkIndex);
            buffer.writeByteArray(bytes);
        }

        @Override
        public Type<ImageUploadChunkPayload> type() {
            return TYPE;
        }
    }

    public record ImageOpenHeaderPayload(long requestId, String assetHash, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        public static final Type<ImageOpenHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "image_open_header"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageOpenHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageOpenHeaderPayload::write, ImageOpenHeaderPayload::read);

        public ImageOpenHeaderPayload {
            if (!isRequestIdValid(requestId) || !isAssetHashValid(assetHash)
                    || totalBytes <= 0 || totalBytes > MAX_IMAGE_BYTES || chunkCount != imageChunkCountFor(totalBytes)) {
                throw new IllegalArgumentException("Invalid image-open header");
            }
        }

        private static ImageOpenHeaderPayload read(RegistryFriendlyByteBuf buffer) {
            return new ImageOpenHeaderPayload(buffer.readLong(), buffer.readUtf(64), buffer.readVarInt(),
                    buffer.readVarInt());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(assetHash, 64);
            buffer.writeVarInt(totalBytes);
            buffer.writeVarInt(chunkCount);
        }

        @Override
        public Type<ImageOpenHeaderPayload> type() {
            return TYPE;
        }
    }

    public record ImageOpenChunkPayload(long requestId, String assetHash, int chunkIndex, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<ImageOpenChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "image_open_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageOpenChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageOpenChunkPayload::write, ImageOpenChunkPayload::read);

        public ImageOpenChunkPayload {
            if (!isRequestIdValid(requestId) || !isAssetHashValid(assetHash)
                    || chunkIndex < 0 || chunkIndex >= MAX_IMAGE_CHUNKS
                    || bytes == null || bytes.length <= 0 || bytes.length > IMAGE_CHUNK_BYTES) {
                throw new IllegalArgumentException("Invalid image-open chunk");
            }
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        private static ImageOpenChunkPayload read(RegistryFriendlyByteBuf buffer) {
            return new ImageOpenChunkPayload(buffer.readLong(), buffer.readUtf(64), buffer.readVarInt(),
                    buffer.readByteArray(IMAGE_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(assetHash, 64);
            buffer.writeVarInt(chunkIndex);
            buffer.writeByteArray(bytes);
        }

        @Override
        public Type<ImageOpenChunkPayload> type() {
            return TYPE;
        }
    }

    public record ImageUploadResultPayload(long requestId, String assetHash, boolean success, String reason)
            implements CustomPacketPayload {
        public static final Type<ImageUploadResultPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "image_upload_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadResultPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadResultPayload::write, ImageUploadResultPayload::read);

        public ImageUploadResultPayload {
            if (!isRequestIdValid(requestId) || !isAssetHashValid(assetHash) || reason == null
                    || reason.length() > MAX_FAILURE_MESSAGE_CHARS || (!success && reason.isBlank())) {
                throw new IllegalArgumentException("Invalid image-upload result");
            }
        }

        private static ImageUploadResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new ImageUploadResultPayload(buffer.readLong(), buffer.readUtf(64), buffer.readBoolean(),
                    buffer.readUtf(MAX_FAILURE_MESSAGE_CHARS));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(assetHash, 64);
            buffer.writeBoolean(success);
            buffer.writeUtf(reason, MAX_FAILURE_MESSAGE_CHARS);
        }

        @Override
        public Type<ImageUploadResultPayload> type() {
            return TYPE;
        }
    }

    public record BookSaveHeaderPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            int totalBytes, int chunkCount) implements CustomPacketPayload {
        public static final Type<BookSaveHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_save_header"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookSaveHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveHeaderPayload::write, BookSaveHeaderPayload::read);

        public BookSaveHeaderPayload {
            if (!isRequestIdValid(requestId) || hand == null || originalPointer == null
                    || totalBytes <= 0 || totalBytes > MAX_DOCUMENT_BYTES
                    || chunkCount != chunkCountFor(totalBytes)) {
                throw new IllegalArgumentException("Invalid book-save header");
            }
        }

        private static BookSaveHeaderPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            InteractionHand hand = readHand(buffer);
            BookPointer originalPointer = readPointer(buffer);
            int totalBytes = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            return new BookSaveHeaderPayload(requestId, hand, originalPointer, totalBytes, chunkCount);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            writePointer(buffer, originalPointer);
            buffer.writeVarInt(totalBytes);
            buffer.writeVarInt(chunkCount);
        }

        @Override
        public Type<BookSaveHeaderPayload> type() {
            return TYPE;
        }
    }

    /** Compact request for a pure access-control change on a held physical copy. */
    public record BookLockRequestPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            boolean locked) implements CustomPacketPayload {
        public static final Type<BookLockRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_lock_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookLockRequestPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookLockRequestPayload::write, BookLockRequestPayload::read);

        public BookLockRequestPayload {
            if (!isRequestIdValid(requestId) || hand == null || originalPointer == null) {
                throw new IllegalArgumentException("Invalid book-lock request");
            }
        }

        private static BookLockRequestPayload read(RegistryFriendlyByteBuf buffer) {
            return new BookLockRequestPayload(buffer.readLong(), readHand(buffer), readPointer(buffer),
                    buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            writePointer(buffer, originalPointer);
            buffer.writeBoolean(locked);
        }

        @Override
        public Type<BookLockRequestPayload> type() {
            return TYPE;
        }
    }

    public record BookSaveChunkPayload(long requestId, int chunkIndex, byte[] bytes) implements CustomPacketPayload {
        public static final Type<BookSaveChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_save_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookSaveChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveChunkPayload::write, BookSaveChunkPayload::read);

        public BookSaveChunkPayload {
            if (!isRequestIdValid(requestId) || chunkIndex < 0 || chunkIndex >= MAX_DOCUMENT_CHUNKS
                    || bytes == null || bytes.length <= 0 || bytes.length > DOCUMENT_CHUNK_BYTES) {
                throw new IllegalArgumentException("Invalid book-save chunk");
            }
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        private static BookSaveChunkPayload read(RegistryFriendlyByteBuf buffer) {
            return new BookSaveChunkPayload(buffer.readLong(), buffer.readVarInt(),
                    buffer.readByteArray(DOCUMENT_CHUNK_BYTES));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeVarInt(chunkIndex);
            buffer.writeByteArray(bytes);
        }

        @Override
        public Type<BookSaveChunkPayload> type() {
            return TYPE;
        }
    }

    public record BookSaveSuccessPayload(long requestId, InteractionHand hand, BookPointer pointer,
            SaveOutcome outcome) implements CustomPacketPayload {
        public static final Type<BookSaveSuccessPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_save_success"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookSaveSuccessPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveSuccessPayload::write, BookSaveSuccessPayload::read);

        public BookSaveSuccessPayload {
            if (!isRequestIdValid(requestId) || hand == null || pointer == null || outcome == null) {
                throw new IllegalArgumentException("Invalid book-save success");
            }
        }

        private static BookSaveSuccessPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            InteractionHand hand = readHand(buffer);
            BookPointer pointer = readPointer(buffer);
            int outcomeOrdinal = buffer.readUnsignedByte();
            SaveOutcome[] outcomes = SaveOutcome.values();
            if (outcomeOrdinal >= outcomes.length) {
                throw new IllegalArgumentException("Invalid book-save outcome");
            }
            return new BookSaveSuccessPayload(requestId, hand, pointer, outcomes[outcomeOrdinal]);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            writeHand(buffer, hand);
            writePointer(buffer, pointer);
            buffer.writeByte(outcome.ordinal());
        }

        @Override
        public Type<BookSaveSuccessPayload> type() {
            return TYPE;
        }
    }

    public record BookSaveFailurePayload(long requestId, String reason) implements CustomPacketPayload {
        public static final Type<BookSaveFailurePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "book_save_failure"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BookSaveFailurePayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveFailurePayload::write, BookSaveFailurePayload::read);

        public BookSaveFailurePayload {
            if (!isRequestIdValid(requestId) || reason == null || reason.isBlank()
                    || reason.length() > MAX_FAILURE_MESSAGE_CHARS) {
                throw new IllegalArgumentException("Invalid book-save failure");
            }
        }

        private static BookSaveFailurePayload read(RegistryFriendlyByteBuf buffer) {
            return new BookSaveFailurePayload(buffer.readLong(), buffer.readUtf(MAX_FAILURE_MESSAGE_CHARS));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeUtf(reason, MAX_FAILURE_MESSAGE_CHARS);
        }

        @Override
        public Type<BookSaveFailurePayload> type() {
            return TYPE;
        }
    }
}
