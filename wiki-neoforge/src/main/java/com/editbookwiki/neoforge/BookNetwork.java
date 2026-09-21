package com.editbookwiki.neoforge;

import com.editbookwiki.core.book.BookPointer;
import com.editbookwiki.core.book.BookRevision;
import com.editbookwiki.core.book.BookRevisionPolicy;
import com.editbookwiki.core.book.BookSaveResult;
import com.editbookwiki.core.document.WikiDocument;
import com.editbookwiki.core.document.WikiDocumentCodec;
import com.editbookwiki.core.document.DocumentMetadata;
import com.editbookwiki.core.document.ImageBlock;
import com.editbookwiki.local.ChunkedAssetReceiver;
import com.editbookwiki.local.FileDocumentCache;
import com.editbookwiki.local.ImportedImageAsset;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Versioned play-network transport for opening a world-owned editable book.
 *
 * <p>The protocol deliberately sends only a compact header followed by fixed
 * document chunks. Image files are stored separately by their content hash,
 * so opening a document never puts an image-sized blob into one packet.</p>
 *
 * <p>All stateful receive work is scheduled through NeoForge's payload
 * context. The common transport has no dependency on a client screen: a
 * client bootstrap supplies a {@link ClientReceiver} later.</p>
 */
@EventBusSubscriber(modid = EditBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BookNetwork {
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
    /** Technical in-memory cap while this first asset transport is buffered. 25 MiB images fit comfortably. */
    public static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    /** A missing chunk must not reserve a document/image buffer forever. */
    private static final long TRANSFER_TIMEOUT_NANOS = 45_000_000_000L;
    private static final int MAX_DOCUMENT_CHUNKS =
            (MAX_DOCUMENT_BYTES + DOCUMENT_CHUNK_BYTES - 1) / DOCUMENT_CHUNK_BYTES;
    private static final int MAX_IMAGE_CHUNKS =
            (MAX_IMAGE_BYTES + IMAGE_CHUNK_BYTES - 1) / IMAGE_CHUNK_BYTES;
    private static final int MAX_FAILURE_MESSAGE_CHARS = 160;
    private static final ResourceLocation EDITABLE_BOOK_ITEM =
            ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "editable_book");
    private static final WikiDocumentCodec DOCUMENT_CODEC = new WikiDocumentCodec();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong(System.nanoTime() & Long.MAX_VALUE);
    /* Only accessed by server-main-thread payload work; one upload per player. */
    private static final Map<UUID, PendingSaveDocument> PENDING_SERVER_SAVES = new HashMap<>();
    private static final Map<UUID, PendingImageAsset> PENDING_SERVER_ASSETS = new HashMap<>();
    /* Server-main-thread delivery queues, paced separately for each recipient. */
    private static final Map<UUID, PendingServerDelivery> PENDING_SERVER_DELIVERIES = new HashMap<>();

    /* Accessed only from client main-thread payload work after initialisation. */
    private static long latestRequestId;
    private static PendingDocument pendingDocument;
    private static final Map<String, PendingImageAsset> PENDING_CLIENT_ASSETS = new HashMap<>();
    private static volatile ClientReceiver clientReceiver = ClientReceiver.IGNORING;

    private BookNetwork() {
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(BookOpenRequestPayload.TYPE, BookOpenRequestPayload.STREAM_CODEC,
                BookNetwork::handleOpenRequest);
        registrar.playToClient(BookOpenHeaderPayload.TYPE, BookOpenHeaderPayload.STREAM_CODEC,
                BookNetwork::handleOpenHeader);
        registrar.playToClient(BookOpenChunkPayload.TYPE, BookOpenChunkPayload.STREAM_CODEC,
                BookNetwork::handleOpenChunk);
        registrar.playToClient(BookOpenFailurePayload.TYPE, BookOpenFailurePayload.STREAM_CODEC,
                BookNetwork::handleOpenFailure);
        registrar.playToServer(BookSaveHeaderPayload.TYPE, BookSaveHeaderPayload.STREAM_CODEC,
                BookNetwork::handleSaveHeader);
        registrar.playToServer(BookSaveChunkPayload.TYPE, BookSaveChunkPayload.STREAM_CODEC,
                BookNetwork::handleSaveChunk);
        registrar.playToServer(BookLockRequestPayload.TYPE, BookLockRequestPayload.STREAM_CODEC,
                BookNetwork::handleLockRequest);
        registrar.playToClient(BookSaveSuccessPayload.TYPE, BookSaveSuccessPayload.STREAM_CODEC,
                BookNetwork::handleSaveSuccess);
        registrar.playToClient(BookSaveFailurePayload.TYPE, BookSaveFailurePayload.STREAM_CODEC,
                BookNetwork::handleSaveFailure);
        registrar.playToServer(ImageUploadHeaderPayload.TYPE, ImageUploadHeaderPayload.STREAM_CODEC,
                BookNetwork::handleImageUploadHeader);
        registrar.playToServer(ImageUploadChunkPayload.TYPE, ImageUploadChunkPayload.STREAM_CODEC,
                BookNetwork::handleImageUploadChunk);
        registrar.playToClient(ImageOpenHeaderPayload.TYPE, ImageOpenHeaderPayload.STREAM_CODEC,
                BookNetwork::handleImageOpenHeader);
        registrar.playToClient(ImageOpenChunkPayload.TYPE, ImageOpenChunkPayload.STREAM_CODEC,
                BookNetwork::handleImageOpenChunk);
        registrar.playToClient(ImageUploadResultPayload.TYPE, ImageUploadResultPayload.STREAM_CODEC,
                BookNetwork::handleImageUploadResult);
    }

    /**
     * Begins opening the editable book currently held in {@code hand}.
     *
     * <p>This helper is intentionally client-side-only in usage, but contains
     * no client class reference so the dedicated-server classpath remains
     * safe. The returned id lets a later save protocol bind to this session.</p>
     */
    public static long requestOpen(InteractionHand hand) {
        return requestOpen(hand, null, false, false);
    }

    /**
     * Opens a verified local cache immediately when possible, then asks the
     * server for the same pointer. A matching server pointer omits duplicate
     * body bytes but still streams any document images the client is missing.
     */
    public static long requestOpen(InteractionHand hand, BookPointer candidatePointer,
            boolean canEdit, boolean canChangeLock) {
        if (hand == null) {
            throw new IllegalArgumentException("hand cannot be null");
        }
        long requestId = nextRequestId();
        latestRequestId = requestId;
        pendingDocument = null;
        BookPointer cachedPointer = null;
        if (candidatePointer != null) {
            try {
                Optional<WikiDocument> cached = clientDocumentCache().load(candidatePointer);
                if (cached.isPresent()) {
                    cachedPointer = candidatePointer;
                    clientReceiver.onBookReceived(new ReceivedBook(requestId, hand, candidatePointer,
                            canEdit, canChangeLock, cached.get()));
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // A cache is only an acceleration path. Failure falls back to
                // the ordinary server-authoritative chunk request below.
            }
        }
        PacketDistributor.sendToServer(new BookOpenRequestPayload(requestId, hand, cachedPointer));
        return requestId;
    }

    /**
     * Sends a proposed document revision for the still-held physical book.
     * The server rechecks {@code originalPointer} both before allocation and
     * immediately before committing; a client can never save an edit onto a
     * different stack by moving items while the chunks are in flight.
     */
    public static long requestSave(InteractionHand hand, BookPointer originalPointer, WikiDocument proposed) {
        if (hand == null || originalPointer == null || proposed == null) {
            throw new IllegalArgumentException("hand, originalPointer, and proposed document are required");
        }
        byte[] bytes = DOCUMENT_CODEC.encode(proposed);
        if (bytes.length <= 0 || bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Document byte length is outside transport bounds");
        }

        long requestId = nextRequestId();
        int chunkCount = chunkCountFor(bytes.length);
        PacketDistributor.sendToServer(new BookSaveHeaderPayload(requestId, hand, originalPointer,
                bytes.length, chunkCount));
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int offset = chunkIndex * DOCUMENT_CHUNK_BYTES;
            int end = Math.min(offset + DOCUMENT_CHUNK_BYTES, bytes.length);
            PacketDistributor.sendToServer(new BookSaveChunkPayload(requestId, chunkIndex,
                    Arrays.copyOfRange(bytes, offset, end)));
        }
        return requestId;
    }

    /**
     * Requests a lock-only update for the exact held physical book. The
     * server verifies the stack pointer and creative mode again; the client
     * supplied state is merely the requested result, never an authority.
     */
    public static long requestLockState(InteractionHand hand, BookPointer originalPointer, boolean locked) {
        if (hand == null || originalPointer == null) {
            throw new IllegalArgumentException("hand and originalPointer are required");
        }
        long requestId = nextRequestId();
        PacketDistributor.sendToServer(new BookLockRequestPayload(requestId, hand, originalPointer, locked));
        return requestId;
    }

    /**
     * Streams an already locally validated image into the authoritative world
     * asset directory. The bytes are sent in normal bounded game packets;
     * saving a document only references the resulting content hash.
     */
    public static long requestImageUpload(InteractionHand hand, BookPointer originalPointer, ImportedImageAsset asset)
            throws IOException {
        if (hand == null || originalPointer == null || asset == null) {
            throw new IllegalArgumentException("hand, originalPointer and image asset are required");
        }
        Path source = asset.storedFile();
        long fileSize = Files.size(source);
        if (fileSize <= 0 || fileSize > MAX_IMAGE_BYTES) {
            throw new IOException("图片文件超过当前传输可承受大小");
        }
        int size = Math.toIntExact(fileSize);
        long requestId = nextRequestId();
        int chunkCount = imageChunkCountFor(size);
        PacketDistributor.sendToServer(new ImageUploadHeaderPayload(requestId, hand, originalPointer,
                asset.assetHash(), size, chunkCount));
        byte[] buffer = new byte[IMAGE_CHUNK_BYTES];
        try (InputStream input = Files.newInputStream(source)) {
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int expected = Math.min(IMAGE_CHUNK_BYTES, size - chunkIndex * IMAGE_CHUNK_BYTES);
                int offset = 0;
                while (offset < expected) {
                    int read = input.read(buffer, offset, expected - offset);
                    if (read < 0) {
                        throw new IOException("图片文件在传输过程中被截断");
                    }
                    offset += read;
                }
                PacketDistributor.sendToServer(new ImageUploadChunkPayload(requestId, asset.assetHash(), chunkIndex,
                        Arrays.copyOf(buffer, expected)));
            }
        }
        return requestId;
    }

    /**
     * Installs the thin client handoff. It receives a fully decoded,
     * hash-verified document and may decide how to open a UI session.
     */
    public static void setClientReceiver(ClientReceiver receiver) {
        clientReceiver = receiver == null ? ClientReceiver.IGNORING : receiver;
    }

    /**
     * Called from the client tick. No partial document reaches disk before
     * completion, so abandoning it is safe; a future open simply requests it
     * from the world again.
     */
    static void expireClientTransfers() {
        long now = System.nanoTime();
        PendingDocument pending = pendingDocument;
        if (pending != null && pending.isExpired(now)) {
            pendingDocument = null;
            reportClientFailure(pending.header().requestId(), "书籍正文传输超时，未使用不完整内容。");
        }
        Iterator<Map.Entry<String, PendingImageAsset>> assets = PENDING_CLIENT_ASSETS.entrySet().iterator();
        while (assets.hasNext()) {
            PendingImageAsset assetPending = assets.next().getValue();
            if (assetPending.isExpired(now)) {
                assetPending.discard();
                assets.remove();
            }
        }
    }

    /**
     * Called from the server tick. The player is notified when an upload was
     * abandoned, while the bounded temporary buffer is released immediately.
     */
    static void expireServerTransfers(MinecraftServer server) {
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, PendingSaveDocument>> saves = PENDING_SERVER_SAVES.entrySet().iterator();
        while (saves.hasNext()) {
            Map.Entry<UUID, PendingSaveDocument> entry = saves.next();
            PendingSaveDocument pending = entry.getValue();
            if (!pending.isExpired(now)) {
                continue;
            }
            saves.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                sendSaveFailure(player, pending.header().requestId(), "保存传输超时，未写入不完整内容。");
            }
        }
        Iterator<Map.Entry<UUID, PendingImageAsset>> assets = PENDING_SERVER_ASSETS.entrySet().iterator();
        while (assets.hasNext()) {
            Map.Entry<UUID, PendingImageAsset> entry = assets.next();
            PendingImageAsset pending = entry.getValue();
            if (!pending.isExpired(now)) {
                continue;
            }
            assets.remove();
            pending.discard();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                sendImageUploadResult(player, pending.requestId(), pending.assetHash(), false,
                        "图片传输超时，未写入不完整内容");
            }
        }
    }

    /**
     * Sends queued world-book bodies and image assets. A configured rate is
     * per receiving player, so one slow reader does not pause another reader.
     * A zero configuration value retains the previous immediate behaviour.
     */
    static void deliverQueuedTransfers(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingServerDelivery>> deliveries = PENDING_SERVER_DELIVERIES.entrySet().iterator();
        while (deliveries.hasNext()) {
            Map.Entry<UUID, PendingServerDelivery> entry = deliveries.next();
            PendingServerDelivery delivery = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                delivery.close();
                deliveries.remove();
                continue;
            }
            delivery.deliver(player, EditBookWikiServerConfig.downloadBytesPerSecond());
            if (delivery.isComplete()) {
                delivery.close();
                deliveries.remove();
            }
        }
    }

    private static long nextRequestId() {
        return REQUEST_SEQUENCE.updateAndGet(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }

    private static void handleOpenRequest(BookOpenRequestPayload request,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!isRequestIdValid(request.requestId())) {
                return;
            }
            ItemStack stack = player.getItemInHand(request.hand());
            if (!isEditableBook(stack)) {
                sendFailure(player, request.requestId(), "请手持编辑书 Wiki 后再打开。");
                return;
            }

            WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
            BookRevision revision;
            try {
                Optional<BookPointer> pointer = BookStackData.read(stack);
                if (pointer.isPresent()) {
                    Optional<BookRevision> loaded = repository.load(pointer.get());
                    if (loaded.isEmpty()) {
                        // Never silently replace an existing pointer: that
                        // would make a damaged world document look like a new
                        // blank book and lose the only recovery reference.
                        sendFailure(player, request.requestId(), "这本书的世界存档内容不存在或已损坏。");
                        return;
                    }
                    revision = loaded.get();
                    // A verified legacy document can be migrated by the
                    // repository without changing visible content. Persist
                    // its replacement pointer on precisely this held copy;
                    // other physical copies remain untouched until opened.
                    if (!revision.pointer().equals(pointer.get())) {
                        BookStackData.write(stack, revision.pointer());
                    }
                } else {
                    revision = repository.create(player);
                    BookStackData.write(stack, revision.pointer());
                }
            } catch (IOException exception) {
                sendFailure(player, request.requestId(), "无法读取或创建这本书的世界存档内容。");
                return;
            }

            if (!revision.pointer().contentHash().equals(DOCUMENT_CODEC.contentHash(revision.document()))) {
                sendFailure(player, request.requestId(), "这本书的内容校验失败，未发送。");
                return;
            }
            boolean bodyOmitted = revision.pointer().equals(request.cachedPointer());
            byte[] bytes = null;
            int chunkCount = 0;
            if (!bodyOmitted) {
                try {
                    bytes = DOCUMENT_CODEC.encode(revision.document());
                } catch (IllegalArgumentException exception) {
                    sendFailure(player, request.requestId(), "这本书的内容格式无效，无法打开。");
                    return;
                }
                if (bytes.length <= 0 || bytes.length > MAX_DOCUMENT_BYTES) {
                    sendFailure(player, request.requestId(), "这本书的文本内容超过当前安全传输大小。");
                    return;
                }
                if (!EditBookWikiServerConfig.allowsDistribution(bytes.length)) {
                    sendFailure(player, request.requestId(), "这本书的正文超过服务器设置的单次分发大小。");
                    return;
                }
                chunkCount = chunkCountFor(bytes.length);
            }
            replaceServerDelivery(player.getUUID());
            boolean canEdit = !revision.pointer().locked() || player.isCreative();
            PacketDistributor.sendToPlayer(player, new BookOpenHeaderPayload(request.requestId(), request.hand(),
                    revision.pointer(), canEdit, player.isCreative(), bodyOmitted,
                    bodyOmitted ? 0 : bytes.length, chunkCount));
            if (!bodyOmitted) {
                serverDelivery(player.getUUID()).enqueue(new DocumentDelivery(request.requestId(), bytes));
            }
            for (String assetHash : imageAssetHashes(revision.document())) {
                repository.assetPathIfPresent(assetHash).ifPresent(asset ->
                        enqueueImageForClient(player, request.requestId(), assetHash, asset));
            }
        });
    }

    private static void handleSaveHeader(BookSaveHeaderPayload header,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(header.hand());
            if (!isEditableBook(stack)) {
                sendSaveFailure(player, header.requestId(), "请继续手持原来的编辑书 Wiki 后再保存。");
                return;
            }
            Optional<BookPointer> heldPointer = BookStackData.read(stack);
            if (heldPointer.isEmpty() || !heldPointer.get().equals(header.originalPointer())) {
                sendSaveFailure(player, header.requestId(), "手持的书已更换或版本已变化，未保存本次修改。");
                return;
            }
            if (!EditBookWikiServerConfig.allowsDistribution(header.totalBytes())) {
                sendSaveFailure(player, header.requestId(), "正文超过服务器设置的单次分发大小，未保存本次修改。");
                return;
            }

            // Replacing a prior unfinished upload from this player releases
            // its buffer and keeps storage bounded to one document per player.
            PENDING_SERVER_SAVES.put(player.getUUID(), new PendingSaveDocument(header));
        });
    }

    private static void handleSaveChunk(BookSaveChunkPayload chunk,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PendingSaveDocument pending = PENDING_SERVER_SAVES.get(player.getUUID());
            if (pending == null || pending.header().requestId() != chunk.requestId()) {
                return;
            }
            if (!pending.accept(chunk)) {
                PENDING_SERVER_SAVES.remove(player.getUUID());
                sendSaveFailure(player, chunk.requestId(), "保存数据分块顺序或大小无效，已取消保存。");
                return;
            }
            if (!pending.isComplete()) {
                return;
            }
            PENDING_SERVER_SAVES.remove(player.getUUID());
            commitSave(player, pending);
        });
    }

    private static void commitSave(ServerPlayer player, PendingSaveDocument pending) {
        BookSaveHeaderPayload header = pending.header();
        ItemStack stack = player.getItemInHand(header.hand());
        if (!isEditableBook(stack)) {
            sendSaveFailure(player, header.requestId(), "保存期间手持的书已更换，未保存本次修改。");
            return;
        }
        Optional<BookPointer> heldPointer = BookStackData.read(stack);
        if (heldPointer.isEmpty() || !heldPointer.get().equals(header.originalPointer())) {
            sendSaveFailure(player, header.requestId(), "保存期间书籍版本已变化，未保存本次修改。");
            return;
        }

        WikiDocument proposed;
        try {
            proposed = DOCUMENT_CODEC.decode(pending.bytes());
        } catch (IllegalArgumentException exception) {
            sendSaveFailure(player, header.requestId(), "提交的书籍内容无法读取，未保存本次修改。");
            return;
        }

        WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
        Optional<BookRevision> current = repository.load(header.originalPointer());
        if (current.isEmpty()) {
            sendSaveFailure(player, header.requestId(), "这本书的世界存档内容不存在或已损坏。");
            return;
        }

        // Audit fields are authoritative server data.  A client may propose
        // visible content, but cannot forge the creator or last editor.
        proposed = withServerAudit(current.get().document(), proposed, player);
        BookSaveResult result = new BookRevisionPolicy(UUID::randomUUID)
                .save(current.get(), proposed, player.isCreative(), false);
        try {
            if (result instanceof BookSaveResult.Denied denied) {
                sendSaveFailure(player, header.requestId(), denied.reason());
                return;
            }
            if (result instanceof BookSaveResult.Forked forked) {
                repository.saveNew(forked.revision());
                // Exactly the item currently held in the requested hand is
                // updated. Other physical copies retain their old pointer.
                BookStackData.write(stack, forked.revision().pointer());
                // The item tooltip/name is part of the physical copy, rather
                // than a cached rendering of its old pointer.  Updating it in
                // the authoritative save path makes a title edit visible both
                // in the hotbar and after the stack is moved elsewhere.
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(forked.revision().document().title()));
                sendSaveSuccess(player, header.requestId(), header.hand(), forked.revision().pointer(),
                        SaveOutcome.FORKED);
                return;
            }
            if (result instanceof BookSaveResult.LockStateChanged changed) {
                BookStackData.write(stack, changed.pointer());
                sendSaveSuccess(player, header.requestId(), header.hand(), changed.pointer(),
                        SaveOutcome.LOCK_STATE_CHANGED);
                return;
            }
            if (result instanceof BookSaveResult.Unchanged unchanged) {
                sendSaveSuccess(player, header.requestId(), header.hand(), unchanged.pointer(),
                        SaveOutcome.UNCHANGED);
                return;
            }
            sendSaveFailure(player, header.requestId(), "保存结果无法识别，未保存本次修改。");
        } catch (IOException exception) {
            sendSaveFailure(player, header.requestId(), "无法写入这本书的世界存档内容。");
        }
    }

    /** Applies the creative-only lock action without touching document bytes or revision history. */
    private static void handleLockRequest(BookLockRequestPayload request,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(request.hand());
            if (!isEditableBook(stack)) {
                sendSaveFailure(player, request.requestId(), "请继续手持原来的编辑书 Wiki 后再修改锁定状态。");
                return;
            }
            Optional<BookPointer> heldPointer = BookStackData.read(stack);
            if (heldPointer.isEmpty() || !heldPointer.get().equals(request.originalPointer())) {
                sendSaveFailure(player, request.requestId(), "手持的书已更换或版本已变化，未修改锁定状态。");
                return;
            }
            WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
            Optional<BookRevision> current = repository.load(request.originalPointer());
            if (current.isEmpty()) {
                sendSaveFailure(player, request.requestId(), "这本书的世界存档内容不存在或已损坏。");
                return;
            }
            BookSaveResult result = new BookRevisionPolicy(UUID::randomUUID)
                    .changeLock(current.get(), player.isCreative(), request.locked());
            if (result instanceof BookSaveResult.Denied denied) {
                sendSaveFailure(player, request.requestId(), denied.reason());
                return;
            }
            if (result instanceof BookSaveResult.LockStateChanged changed) {
                BookStackData.write(stack, changed.pointer());
                sendSaveSuccess(player, request.requestId(), request.hand(), changed.pointer(),
                        SaveOutcome.LOCK_STATE_CHANGED);
                return;
            }
            if (result instanceof BookSaveResult.Unchanged unchanged) {
                sendSaveSuccess(player, request.requestId(), request.hand(), unchanged.pointer(),
                        SaveOutcome.UNCHANGED);
                return;
            }
            sendSaveFailure(player, request.requestId(), "锁定状态结果无法识别，未修改。");
        });
    }

    private static WikiDocument withServerAudit(WikiDocument current, WikiDocument proposed, ServerPlayer player) {
        DocumentMetadata prior = current.metadata();
        DocumentMetadata audited = new DocumentMetadata(prior.creatorId(), prior.creatorName(), prior.createdAt(),
                player.getUUID(), player.getGameProfile().getName(), Instant.now());
        return new WikiDocument(proposed.formatVersion(), proposed.title(), proposed.blocks(), audited);
    }

    private static void handleImageUploadHeader(ImageUploadHeaderPayload header,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(header.hand());
            if (!isEditableBook(stack)) {
                sendImageUploadResult(player, header.requestId(), header.assetHash(), false, "请手持可编辑书后再上传图片");
                return;
            }
            Optional<BookPointer> pointer = BookStackData.read(stack);
            if (pointer.isEmpty() || !pointer.get().equals(header.originalPointer())) {
                sendImageUploadResult(player, header.requestId(), header.assetHash(), false, "书籍尚未创建世界存档");
                return;
            }
            Optional<BookRevision> revision = WorldBookRepository.forLevel(player.serverLevel()).load(pointer.get());
            if (revision.isEmpty() || (revision.get().pointer().locked() && !player.isCreative())) {
                sendImageUploadResult(player, header.requestId(), header.assetHash(), false, "这本书当前没有上传图片的权限");
                return;
            }
            if (!EditBookWikiServerConfig.allowsDistribution(header.totalBytes())) {
                sendImageUploadResult(player, header.requestId(), header.assetHash(), false,
                        "图片超过服务器设置的单次分发大小");
                return;
            }
            try {
                WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
                PendingImageAsset replacement = new PendingImageAsset(header.requestId(), header.assetHash(),
                        repository.beginAssetTransfer(header.assetHash(), header.totalBytes(), header.chunkCount(),
                                IMAGE_CHUNK_BYTES));
                PendingImageAsset prior = PENDING_SERVER_ASSETS.put(player.getUUID(), replacement);
                if (prior != null) {
                    prior.discard();
                }
            } catch (IOException | IllegalArgumentException exception) {
                sendImageUploadResult(player, header.requestId(), header.assetHash(), false,
                        "服务器无法准备图片临时文件");
            }
        });
    }

    private static void handleImageUploadChunk(ImageUploadChunkPayload chunk,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PendingImageAsset pending = PENDING_SERVER_ASSETS.get(player.getUUID());
            if (pending == null || pending.requestId() != chunk.requestId() || !pending.assetHash().equals(chunk.assetHash())) {
                return;
            }
            try {
                if (!pending.accept(chunk.chunkIndex(), chunk.bytes())) {
                    PENDING_SERVER_ASSETS.remove(player.getUUID());
                    pending.discard();
                    sendImageUploadResult(player, chunk.requestId(), chunk.assetHash(), false, "图片分块顺序或大小无效");
                    return;
                }
            } catch (IOException exception) {
                PENDING_SERVER_ASSETS.remove(player.getUUID());
                pending.discard();
                sendImageUploadResult(player, chunk.requestId(), chunk.assetHash(), false, "服务器无法写入图片临时文件");
                return;
            }
            if (!pending.isComplete()) {
                return;
            }
            PENDING_SERVER_ASSETS.remove(player.getUUID());
            try {
                WorldBookRepository.forLevel(player.serverLevel()).saveReceivedAsset(pending.assetHash(), pending.receiver());
                sendImageUploadResult(player, pending.requestId(), pending.assetHash(), true, "");
            } catch (IOException | IllegalArgumentException exception) {
                pending.discard();
                sendImageUploadResult(player, pending.requestId(), pending.assetHash(), false, "服务器无法保存该图片");
            }
        });
    }

    private static Set<String> imageAssetHashes(WikiDocument document) {
        Set<String> hashes = new HashSet<>();
        document.blocks().forEach(block -> {
            if (block instanceof ImageBlock image) {
                hashes.add(image.assetHash());
            }
        });
        return hashes;
    }

    private static void enqueueImageForClient(ServerPlayer player, long requestId, String assetHash, Path asset) {
        try {
            long sizeLong = Files.size(asset);
            if (sizeLong <= 0 || sizeLong > MAX_IMAGE_BYTES || !EditBookWikiServerConfig.allowsDistribution(
                    Math.toIntExact(sizeLong))) {
                return;
            }
            int size = Math.toIntExact(sizeLong);
            serverDelivery(player.getUUID()).enqueue(new ImageDelivery(requestId, assetHash, asset, size));
        } catch (IOException ignored) {
            // A missing/corrupt optional picture never prevents its text document opening.
        }
    }

    private static void handleOpenHeader(BookOpenHeaderPayload header,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            // A response from an earlier right click must not open over the
            // user's most recent book. One active receive session also keeps
            // a server from inducing unbounded client buffering.
            if (header.requestId() != latestRequestId) {
                return;
            }
            if (header.documentBodyOmitted()) {
                // The matching cache was decoded and displayed before this
                // request left the client. Image chunks can still arrive
                // independently after this compact acknowledgement.
                pendingDocument = null;
                return;
            }
            pendingDocument = new PendingDocument(header);
        });
    }

    private static void handleOpenChunk(BookOpenChunkPayload chunk,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            PendingDocument pending = pendingDocument;
            if (pending == null || chunk.requestId() != pending.header().requestId()) {
                return;
            }
            if (!pending.accept(chunk)) {
                pendingDocument = null;
                reportClientFailure(chunk.requestId(), "书籍数据分块顺序或大小无效，已取消打开。");
                return;
            }
            if (!pending.isComplete()) {
                return;
            }

            pendingDocument = null;
            try {
                WikiDocument document = DOCUMENT_CODEC.decode(pending.bytes());
                if (!pending.header().pointer().contentHash().equals(DOCUMENT_CODEC.contentHash(document))) {
                    reportClientFailure(chunk.requestId(), "书籍内容校验失败，已取消打开。");
                    return;
                }
                cacheClientDocument(pending.header().pointer(), document);
                clientReceiver.onBookReceived(new ReceivedBook(pending.header().requestId(),
                        pending.header().hand(), pending.header().pointer(), pending.header().canEdit(),
                        pending.header().canChangeLock(), document));
            } catch (IllegalArgumentException exception) {
                reportClientFailure(chunk.requestId(), "书籍内容无法读取，已取消打开。");
            }
        });
    }

    private static void handleOpenFailure(BookOpenFailurePayload failure,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (failure.requestId() != latestRequestId) {
                return;
            }
            pendingDocument = null;
            reportClientFailure(failure.requestId(), failure.reason());
        });
    }

    private static void handleImageOpenHeader(ImageOpenHeaderPayload header,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (header.requestId() != latestRequestId) {
                return;
            }
            try {
                PendingImageAsset replacement = new PendingImageAsset(header.requestId(), header.assetHash(),
                        WikiImageTextures.beginReceivedAsset(header.assetHash(), header.totalBytes(), header.chunkCount(),
                                IMAGE_CHUNK_BYTES));
                PendingImageAsset prior = PENDING_CLIENT_ASSETS.put(assetTransferKey(header.requestId(), header.assetHash()),
                        replacement);
                if (prior != null) {
                    prior.discard();
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // The image is optional. Text stays readable and the canvas
                // shows its ordinary missing-image placeholder.
            }
        });
    }

    private static void handleImageOpenChunk(ImageOpenChunkPayload chunk,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            String key = assetTransferKey(chunk.requestId(), chunk.assetHash());
            PendingImageAsset pending = PENDING_CLIENT_ASSETS.get(key);
            if (pending == null) {
                return;
            }
            try {
                if (pending.accept(chunk.chunkIndex(), chunk.bytes())) {
                    if (!pending.isComplete()) {
                        return;
                    }
                    PENDING_CLIENT_ASSETS.remove(key);
                    pending.finish().ifPresent(temporary ->
                            WikiImageTextures.cacheReceivedAsset(pending.assetHash(), temporary));
                    return;
                }
            } catch (IOException ignored) {
                // Fall through to remove the failed temporary file.
            }
            PENDING_CLIENT_ASSETS.remove(key);
            pending.discard();
        });
    }

    private static void handleImageUploadResult(ImageUploadResultPayload result,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> clientReceiver.onImageUploadResult(result.requestId(), result.assetHash(),
                result.success(), result.reason()));
    }

    private static void handleSaveSuccess(BookSaveSuccessPayload success,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            clientReceiver.onBookSaved(new SavedBook(success.requestId(), success.hand(), success.pointer(),
                    success.outcome()));
        });
    }

    private static void handleSaveFailure(BookSaveFailurePayload failure,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            clientReceiver.onBookSaveFailed(failure.requestId(), failure.reason());
        });
    }

    private static boolean isEditableBook(ItemStack stack) {
        return !stack.isEmpty() && EDITABLE_BOOK_ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static void sendFailure(ServerPlayer player, long requestId, String reason) {
        PacketDistributor.sendToPlayer(player, new BookOpenFailurePayload(requestId, reason));
    }

    private static void sendSaveSuccess(ServerPlayer player, long requestId, InteractionHand hand,
            BookPointer pointer, SaveOutcome outcome) {
        PacketDistributor.sendToPlayer(player, new BookSaveSuccessPayload(requestId, hand, pointer, outcome));
    }

    private static void sendSaveFailure(ServerPlayer player, long requestId, String reason) {
        PacketDistributor.sendToPlayer(player, new BookSaveFailurePayload(requestId, reason));
    }

    private static void sendImageUploadResult(ServerPlayer player, long requestId, String assetHash, boolean success,
            String reason) {
        PacketDistributor.sendToPlayer(player, new ImageUploadResultPayload(requestId, assetHash, success, reason));
    }

    private static FileDocumentCache clientDocumentCache() {
        return new FileDocumentCache(FMLPaths.CONFIGDIR.get()
                .resolve(EditBookWikiMod.MOD_ID).resolve("book-cache"));
    }

    private static void cacheClientDocument(BookPointer pointer, WikiDocument document) {
        try {
            clientDocumentCache().save(pointer, document);
        } catch (IOException | IllegalArgumentException ignored) {
            // Cache persistence is optional. A full, verified document has
            // already reached the client and must remain readable even if a
            // local disk is temporarily unavailable.
        }
    }

    private static void reportClientFailure(long requestId, String reason) {
        clientReceiver.onBookOpenFailed(requestId, reason);
    }

    private static boolean isRequestIdValid(long requestId) {
        return requestId > 0;
    }

    private static int chunkCountFor(int byteLength) {
        if (byteLength <= 0 || byteLength > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Document byte length is outside transport bounds");
        }
        return (int) (((long) byteLength + DOCUMENT_CHUNK_BYTES - 1L) / DOCUMENT_CHUNK_BYTES);
    }

    private static int imageChunkCountFor(int byteLength) {
        if (byteLength <= 0 || byteLength > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image byte length is outside transport bounds");
        }
        return (int) (((long) byteLength + IMAGE_CHUNK_BYTES - 1L) / IMAGE_CHUNK_BYTES);
    }

    private static String assetTransferKey(long requestId, String assetHash) {
        return requestId + ":" + assetHash;
    }

    private static void replaceServerDelivery(UUID playerId) {
        PendingServerDelivery prior = PENDING_SERVER_DELIVERIES.remove(playerId);
        if (prior != null) {
            prior.close();
        }
        PENDING_SERVER_DELIVERIES.put(playerId, new PendingServerDelivery());
    }

    private static PendingServerDelivery serverDelivery(UUID playerId) {
        return PENDING_SERVER_DELIVERIES.computeIfAbsent(playerId, ignored -> new PendingServerDelivery());
    }

    private static boolean isAssetHashValid(String assetHash) {
        return assetHash != null && assetHash.matches("[0-9a-f]{64}");
    }

    private static InteractionHand readHand(RegistryFriendlyByteBuf buffer) {
        int handOrdinal = buffer.readUnsignedByte();
        InteractionHand[] hands = InteractionHand.values();
        if (handOrdinal >= hands.length) {
            throw new IllegalArgumentException("Invalid interaction hand");
        }
        return hands[handOrdinal];
    }

    private static void writeHand(RegistryFriendlyByteBuf buffer, InteractionHand hand) {
        buffer.writeByte(hand.ordinal());
    }

    private static BookPointer readPointer(RegistryFriendlyByteBuf buffer) {
        return new BookPointer(buffer.readUUID(), buffer.readUtf(64), buffer.readBoolean());
    }

    private static void writePointer(RegistryFriendlyByteBuf buffer, BookPointer pointer) {
        buffer.writeUUID(pointer.bookId());
        buffer.writeUtf(pointer.contentHash(), 64);
        buffer.writeBoolean(pointer.locked());
    }

    /** Complete, verified data exposed to a client UI/session adapter. */
    public record ReceivedBook(long requestId, InteractionHand hand, BookPointer pointer, boolean canEdit,
            boolean canChangeLock, WikiDocument document) {
    }

    /** Server-authoritative result exposed to the client session adapter. */
    public record SavedBook(long requestId, InteractionHand hand, BookPointer pointer, SaveOutcome outcome) {
    }

    public enum SaveOutcome {
        UNCHANGED,
        LOCK_STATE_CHANGED,
        FORKED
    }

    /**
     * Client-only consumers should install this from a client bootstrap. The
     * common transport remains usable in dedicated server processes.
     */
    public interface ClientReceiver {
        ClientReceiver IGNORING = new ClientReceiver() {
            @Override
            public void onBookReceived(ReceivedBook book) {
            }

            @Override
            public void onBookOpenFailed(long requestId, String reason) {
            }
        };

        void onBookReceived(ReceivedBook book);

        void onBookOpenFailed(long requestId, String reason);

        default void onBookSaved(SavedBook book) {
        }

        default void onBookSaveFailed(long requestId, String reason) {
        }

        default void onImageUploadResult(long requestId, String assetHash, boolean success, String reason) {
        }
    }

    /**
     * A small per-player token bucket. We deliberately keep transport chunks
     * intact: the packet codec can verify their exact position and length,
     * while the bucket determines when the next chunk is sent.
     */
    private static final class PendingServerDelivery {
        private final Deque<OutboundDelivery> queue = new ArrayDeque<>();
        private double availableBytes;

        private void enqueue(OutboundDelivery delivery) {
            queue.addLast(delivery);
        }

        private void deliver(ServerPlayer player, int bytesPerSecond) {
            boolean unlimited = bytesPerSecond == 0;
            if (!unlimited) {
                // Do not accumulate an arbitrary idle burst: one second is
                // enough to keep normal transfers smooth after a short lag.
                availableBytes = Math.min(bytesPerSecond, availableBytes + bytesPerSecond / 20.0D);
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
                    current.sendNext(player);
                    if (current.isComplete()) {
                        current.close();
                        queue.removeFirst();
                    }
                    continue;
                }
                if (!unlimited && availableBytes < nextBytes) {
                    return;
                }
                int sent = current.sendNext(player);
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

        private boolean isComplete() {
            return queue.isEmpty();
        }

        private void close() {
            while (!queue.isEmpty()) {
                queue.removeFirst().close();
            }
        }
    }

    private interface OutboundDelivery {
        /** Returns zero while a header remains to send, or -1 when unavailable. */
        int nextChunkBytes();

        /** Sends one header or one body chunk, returning body bytes sent. */
        int sendNext(ServerPlayer player);

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
        public int sendNext(ServerPlayer player) {
            int expected = nextChunkBytes();
            if (expected <= 0) {
                return 0;
            }
            int offset = nextChunkIndex * DOCUMENT_CHUNK_BYTES;
            PacketDistributor.sendToPlayer(player, new BookOpenChunkPayload(requestId, nextChunkIndex,
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
        public int sendNext(ServerPlayer player) {
            if (!headerSent) {
                PacketDistributor.sendToPlayer(player, new ImageOpenHeaderPayload(requestId, assetHash, totalBytes,
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
                PacketDistributor.sendToPlayer(player, new ImageOpenChunkPayload(requestId, assetHash, nextChunkIndex,
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

    private static final class PendingDocument {
        private final BookOpenHeaderPayload header;
        private final ByteArrayOutputStream bytes;
        private int nextChunkIndex;
        private long lastActivityNanos = System.nanoTime();

        private PendingDocument(BookOpenHeaderPayload header) {
            this.header = header;
            this.bytes = new ByteArrayOutputStream(header.totalBytes());
        }

        private BookOpenHeaderPayload header() {
            return header;
        }

        private boolean accept(BookOpenChunkPayload chunk) {
            if (chunk.chunkIndex() != nextChunkIndex || nextChunkIndex >= header.chunkCount()) {
                return false;
            }
            int remaining = header.totalBytes() - bytes.size();
            int expectedLength = Math.min(DOCUMENT_CHUNK_BYTES, remaining);
            if (chunk.bytes().length != expectedLength) {
                return false;
            }
            bytes.writeBytes(chunk.bytes());
            nextChunkIndex++;
            lastActivityNanos = System.nanoTime();
            return true;
        }

        private boolean isComplete() {
            return nextChunkIndex == header.chunkCount() && bytes.size() == header.totalBytes();
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos - lastActivityNanos > TRANSFER_TIMEOUT_NANOS;
        }
    }

    /** Server-side ordered upload buffer. It grows only as validated chunks arrive. */
    private static final class PendingSaveDocument {
        private final BookSaveHeaderPayload header;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int nextChunkIndex;
        private long lastActivityNanos = System.nanoTime();

        private PendingSaveDocument(BookSaveHeaderPayload header) {
            this.header = header;
        }

        private BookSaveHeaderPayload header() {
            return header;
        }

        private boolean accept(BookSaveChunkPayload chunk) {
            if (chunk.chunkIndex() != nextChunkIndex || nextChunkIndex >= header.chunkCount()) {
                return false;
            }
            int remaining = header.totalBytes() - bytes.size();
            int expectedLength = Math.min(DOCUMENT_CHUNK_BYTES, remaining);
            if (chunk.bytes().length != expectedLength) {
                return false;
            }
            bytes.writeBytes(chunk.bytes());
            nextChunkIndex++;
            lastActivityNanos = System.nanoTime();
            return true;
        }

        private boolean isComplete() {
            return nextChunkIndex == header.chunkCount() && bytes.size() == header.totalBytes();
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos - lastActivityNanos > TRANSFER_TIMEOUT_NANOS;
        }
    }

    /**
     * Ordered image receiver backed by a temporary file. The same class is
     * used for client downloads and server uploads; neither side keeps a full
     * 25 MiB image in a Java byte array while it is in flight.
     */
    private static final class PendingImageAsset {
        private final long requestId;
        private final String assetHash;
        private final ChunkedAssetReceiver receiver;
        private long lastActivityNanos = System.nanoTime();

        private PendingImageAsset(long requestId, String assetHash, ChunkedAssetReceiver receiver) {
            this.requestId = requestId;
            this.assetHash = assetHash;
            this.receiver = receiver;
        }

        private long requestId() {
            return requestId;
        }

        private String assetHash() {
            return assetHash;
        }

        private ChunkedAssetReceiver receiver() {
            return receiver;
        }

        private boolean accept(int chunkIndex, byte[] chunkBytes) throws IOException {
            boolean accepted = receiver.append(chunkIndex, chunkBytes);
            if (accepted) {
                lastActivityNanos = System.nanoTime();
            }
            return accepted;
        }

        private boolean isComplete() {
            return receiver.isComplete();
        }

        private Optional<Path> finish() throws IOException {
            return receiver.finish();
        }

        private void discard() {
            receiver.discard();
        }

        private boolean isExpired(long nowNanos) {
            return nowNanos - lastActivityNanos > TRANSFER_TIMEOUT_NANOS;
        }
    }

    record BookOpenRequestPayload(long requestId, InteractionHand hand, BookPointer cachedPointer)
            implements CustomPacketPayload {
        static final Type<BookOpenRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_open_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookOpenRequestPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenRequestPayload::write, BookOpenRequestPayload::read);

        BookOpenRequestPayload {
            if (!isRequestIdValid(requestId) || hand == null) {
                throw new IllegalArgumentException("Invalid book-open request");
            }
        }

        private static BookOpenRequestPayload read(RegistryFriendlyByteBuf buffer) {
            long requestId = buffer.readLong();
            int handOrdinal = buffer.readUnsignedByte();
            InteractionHand[] hands = InteractionHand.values();
            if (handOrdinal >= hands.length) {
                throw new IllegalArgumentException("Invalid interaction hand");
            }
            boolean hasCachedPointer = buffer.readBoolean();
            BookPointer cachedPointer = hasCachedPointer ? readPointer(buffer) : null;
            return new BookOpenRequestPayload(requestId, hands[handOrdinal], cachedPointer);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeByte(hand.ordinal());
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

    record BookOpenHeaderPayload(long requestId, InteractionHand hand, BookPointer pointer, boolean canEdit,
            boolean canChangeLock, boolean documentBodyOmitted, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        static final Type<BookOpenHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_open_header"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookOpenHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenHeaderPayload::write, BookOpenHeaderPayload::read);

        BookOpenHeaderPayload {
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
            int handOrdinal = buffer.readUnsignedByte();
            InteractionHand[] hands = InteractionHand.values();
            if (handOrdinal >= hands.length) {
                throw new IllegalArgumentException("Invalid interaction hand");
            }
            BookPointer pointer = new BookPointer(buffer.readUUID(), buffer.readUtf(64), buffer.readBoolean());
            boolean canEdit = buffer.readBoolean();
            boolean canChangeLock = buffer.readBoolean();
            boolean documentBodyOmitted = buffer.readBoolean();
            int totalBytes = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            return new BookOpenHeaderPayload(requestId, hands[handOrdinal], pointer, canEdit, canChangeLock,
                    documentBodyOmitted, totalBytes, chunkCount);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeLong(requestId);
            buffer.writeByte(hand.ordinal());
            buffer.writeUUID(pointer.bookId());
            buffer.writeUtf(pointer.contentHash(), 64);
            buffer.writeBoolean(pointer.locked());
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

    record BookOpenChunkPayload(long requestId, int chunkIndex, byte[] bytes) implements CustomPacketPayload {
        static final Type<BookOpenChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_open_chunk"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookOpenChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenChunkPayload::write, BookOpenChunkPayload::read);

        BookOpenChunkPayload {
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

    record BookOpenFailurePayload(long requestId, String reason) implements CustomPacketPayload {
        static final Type<BookOpenFailurePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_open_failure"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookOpenFailurePayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookOpenFailurePayload::write, BookOpenFailurePayload::read);

        BookOpenFailurePayload {
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

    record ImageUploadHeaderPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            String assetHash, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        static final Type<ImageUploadHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "image_upload_header"));
        static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadHeaderPayload::write, ImageUploadHeaderPayload::read);

        ImageUploadHeaderPayload {
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

    record ImageUploadChunkPayload(long requestId, String assetHash, int chunkIndex, byte[] bytes)
            implements CustomPacketPayload {
        static final Type<ImageUploadChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "image_upload_chunk"));
        static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadChunkPayload::write, ImageUploadChunkPayload::read);

        ImageUploadChunkPayload {
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

    record ImageOpenHeaderPayload(long requestId, String assetHash, int totalBytes, int chunkCount)
            implements CustomPacketPayload {
        static final Type<ImageOpenHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "image_open_header"));
        static final StreamCodec<RegistryFriendlyByteBuf, ImageOpenHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageOpenHeaderPayload::write, ImageOpenHeaderPayload::read);

        ImageOpenHeaderPayload {
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

    record ImageOpenChunkPayload(long requestId, String assetHash, int chunkIndex, byte[] bytes)
            implements CustomPacketPayload {
        static final Type<ImageOpenChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "image_open_chunk"));
        static final StreamCodec<RegistryFriendlyByteBuf, ImageOpenChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageOpenChunkPayload::write, ImageOpenChunkPayload::read);

        ImageOpenChunkPayload {
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

    record ImageUploadResultPayload(long requestId, String assetHash, boolean success, String reason)
            implements CustomPacketPayload {
        static final Type<ImageUploadResultPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "image_upload_result"));
        static final StreamCodec<RegistryFriendlyByteBuf, ImageUploadResultPayload> STREAM_CODEC =
                CustomPacketPayload.codec(ImageUploadResultPayload::write, ImageUploadResultPayload::read);

        ImageUploadResultPayload {
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

    record BookSaveHeaderPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            int totalBytes, int chunkCount) implements CustomPacketPayload {
        static final Type<BookSaveHeaderPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_save_header"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookSaveHeaderPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveHeaderPayload::write, BookSaveHeaderPayload::read);

        BookSaveHeaderPayload {
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
    record BookLockRequestPayload(long requestId, InteractionHand hand, BookPointer originalPointer,
            boolean locked) implements CustomPacketPayload {
        static final Type<BookLockRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_lock_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookLockRequestPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookLockRequestPayload::write, BookLockRequestPayload::read);

        BookLockRequestPayload {
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

    record BookSaveChunkPayload(long requestId, int chunkIndex, byte[] bytes) implements CustomPacketPayload {
        static final Type<BookSaveChunkPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_save_chunk"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookSaveChunkPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveChunkPayload::write, BookSaveChunkPayload::read);

        BookSaveChunkPayload {
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

    record BookSaveSuccessPayload(long requestId, InteractionHand hand, BookPointer pointer,
            SaveOutcome outcome) implements CustomPacketPayload {
        static final Type<BookSaveSuccessPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_save_success"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookSaveSuccessPayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveSuccessPayload::write, BookSaveSuccessPayload::read);

        BookSaveSuccessPayload {
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

    record BookSaveFailurePayload(long requestId, String reason) implements CustomPacketPayload {
        static final Type<BookSaveFailurePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EditBookWikiMod.MOD_ID, "book_save_failure"));
        static final StreamCodec<RegistryFriendlyByteBuf, BookSaveFailurePayload> STREAM_CODEC =
                CustomPacketPayload.codec(BookSaveFailurePayload::write, BookSaveFailurePayload::read);

        BookSaveFailurePayload {
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
