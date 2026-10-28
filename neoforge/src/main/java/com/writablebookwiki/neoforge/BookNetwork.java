package com.writablebookwiki.neoforge;

import com.writablebookwiki.neoforge.network.BookProtocol;
import com.writablebookwiki.neoforge.network.LecternOpenRequest;
import net.minecraft.world.inventory.LecternMenu;
import com.writablebookwiki.neoforge.network.PacedBookDelivery;
import static com.writablebookwiki.neoforge.network.BookProtocol.*;
import com.writablebookwiki.neoforge.network.BookPayloads.*;
import com.writablebookwiki.local.transfer.DocumentTransferBuffer;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.book.SaveOutcome;
import com.writablebookwiki.core.book.BookRevision;
import com.writablebookwiki.core.book.BookRevisionPolicy;
import com.writablebookwiki.core.book.BookSaveResult;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import com.writablebookwiki.core.document.DocumentMetadata;
import com.writablebookwiki.core.document.ImageBlock;
import com.writablebookwiki.local.ChunkedAssetReceiver;
import com.writablebookwiki.local.FileDocumentCache;
import com.writablebookwiki.local.ImportedImageAsset;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
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
@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BookNetwork {
    // Public aliases retained for callers; protocol bounds live with the wire contract.
    public static final int DOCUMENT_CHUNK_BYTES = BookProtocol.DOCUMENT_CHUNK_BYTES;
    public static final int IMAGE_CHUNK_BYTES = BookProtocol.IMAGE_CHUNK_BYTES;
    public static final int MAX_DOCUMENT_BYTES = BookProtocol.MAX_DOCUMENT_BYTES;
    public static final int MAX_IMAGE_BYTES = BookProtocol.MAX_IMAGE_BYTES;
    private static final ResourceLocation EDITABLE_BOOK_ITEM =
            ResourceLocation.fromNamespaceAndPath(WritableBookWikiMod.MOD_ID, "editable_book");
    private static final WikiDocumentCodec DOCUMENT_CODEC = new WikiDocumentCodec();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong(System.nanoTime() & Long.MAX_VALUE);
    /* Only accessed by server-main-thread payload work; one upload per player. */
    private static final Map<UUID, PendingSaveDocument> PENDING_SERVER_SAVES = new HashMap<>();
    private static final Map<UUID, PendingImageAsset> PENDING_SERVER_ASSETS = new HashMap<>();
    /* Server-main-thread delivery queues, paced separately for each recipient. */
    private static final Map<UUID, PacedBookDelivery> PENDING_SERVER_DELIVERIES = new HashMap<>();

    /* Accessed only from client main-thread payload work after initialisation. */
    private static long latestRequestId;
    private static PendingDocument pendingDocument;
    private static final Map<String, PendingImageAsset> PENDING_CLIENT_ASSETS = new HashMap<>();
    private static volatile ClientReceiver clientReceiver = ClientReceiver.IGNORING;

    private BookNetwork() {
    }

    /** Client main thread: invalidate open replies and release partial image files. */
    static void disconnectClient() {
        latestRequestId = 0;
        pendingDocument = null;
        PENDING_CLIENT_ASSETS.values().forEach(PendingImageAsset::discard);
        PENDING_CLIENT_ASSETS.clear();
    }

    /** Server main thread: logout cannot leave streams or uploads attached to this UUID. */
    static void disconnectPlayer(UUID playerId) {
        PENDING_SERVER_SAVES.remove(playerId);
        PendingImageAsset asset = PENDING_SERVER_ASSETS.remove(playerId);
        if (asset != null) asset.discard();
        PacedBookDelivery delivery = PENDING_SERVER_DELIVERIES.remove(playerId);
        if (delivery != null) delivery.close();
    }

    /** Server main thread: also covers integrated servers reopened in the same process. */
    static void stopServer() {
        PENDING_SERVER_SAVES.clear();
        PENDING_SERVER_ASSETS.values().forEach(PendingImageAsset::discard);
        PENDING_SERVER_ASSETS.clear();
        PENDING_SERVER_DELIVERIES.values().forEach(PacedBookDelivery::close);
        PENDING_SERVER_DELIVERIES.clear();
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(LecternOpenRequest.TYPE, LecternOpenRequest.STREAM_CODEC,
                BookNetwork::handleLecternOpen);
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
        Iterator<Map.Entry<UUID, PacedBookDelivery>> deliveries = PENDING_SERVER_DELIVERIES.entrySet().iterator();
        while (deliveries.hasNext()) {
            Map.Entry<UUID, PacedBookDelivery> entry = deliveries.next();
            PacedBookDelivery delivery = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                delivery.close();
                deliveries.remove();
                continue;
            }
            delivery.deliver(payload -> PacketDistributor.sendToPlayer(player, payload),
                    WritableBookWikiServerConfig.downloadBytesPerSecond());
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
            if (!(context.player() instanceof ServerPlayer player) || !isRequestIdValid(request.requestId())) return;
            ItemStack stack = player.getItemInHand(request.hand());
            if (!isEditableBook(stack)) {
                sendFailure(player, request.requestId(), "请手持Writable Book Wiki 后再打开。");
                return;
            }
            try {
                BookRevision revision = loadOrCreateBook(player, stack);
                deliverBook(player, request.requestId(), request.hand(), revision, request.cachedPointer(),
                        !revision.pointer().locked() || player.isCreative(), player.isCreative());
            } catch (IOException | IllegalArgumentException exception) {
                sendFailure(player, request.requestId(), "这本书的世界存档内容不存在、已损坏或无法读取。");
            }
        });
    }

    /** Shared verified read/initialisation for held books and placement on a lectern. */
    static BookRevision loadOrCreateBook(ServerPlayer player, ItemStack stack) throws IOException {
        WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
        Optional<BookPointer> pointer = BookStackData.read(stack);
        BookRevision revision = pointer.isPresent()
                ? repository.load(pointer.get()).orElseThrow(() -> new IOException("Missing or damaged book"))
                : repository.create(player);
        if (pointer.isEmpty() || !revision.pointer().equals(pointer.get())) BookStackData.write(stack, revision.pointer());
        return revision;
    }

    public static long requestLecternOpen(int containerId, BookPointer pointer) {
        long requestId = nextRequestId();
        latestRequestId = requestId;
        pendingDocument = null;
        PacketDistributor.sendToServer(new LecternOpenRequest(requestId, containerId, pointer));
        return requestId;
    }

    private static void handleLecternOpen(LecternOpenRequest request,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof LecternMenu menu) || menu.containerId != request.containerId()
                    || !menu.stillValid(player) || !isEditableBook(menu.getBook())
                    || !BookStackData.read(menu.getBook()).filter(request.pointer()::equals).isPresent()) {
                sendFailure(player, request.requestId(), "讲台已关闭、距离过远或书已被取走。");
                return;
            }
            try {
                BookRevision revision = loadOrCreateBook(player, menu.getBook());
                // The hand field belongs to the existing delivery envelope only.
                // This source always creates a read-only client session, never a held-book edit session.
                deliverBook(player, request.requestId(), InteractionHand.MAIN_HAND, revision, null, false, false);
            } catch (IOException | IllegalArgumentException exception) {
                sendFailure(player, request.requestId(), "讲台中的书籍内容无法读取。");
            }
        });
    }

    private static void deliverBook(ServerPlayer player, long requestId, InteractionHand hand,
            BookRevision revision, BookPointer cachedPointer, boolean canEdit, boolean canChangeLock) {
        WorldBookRepository repository = WorldBookRepository.forLevel(player.serverLevel());
        if (!revision.pointer().contentHash().equals(DOCUMENT_CODEC.contentHash(revision.document()))) {
            sendFailure(player, requestId, "这本书的内容校验失败，未发送。");
            return;
        }
        boolean bodyOmitted = revision.pointer().equals(cachedPointer);
        byte[] bytes = null;
        int chunkCount = 0;
        if (!bodyOmitted) {
            try {
                bytes = DOCUMENT_CODEC.encode(revision.document());
            } catch (IllegalArgumentException exception) {
                sendFailure(player, requestId, "这本书的内容格式无效，无法打开。");
                return;
            }
            if (bytes.length <= 0 || bytes.length > MAX_DOCUMENT_BYTES) {
                sendFailure(player, requestId, "这本书的文本内容超过当前安全传输大小。");
                return;
            }
            if (!WritableBookWikiServerConfig.allowsDistribution(bytes.length)) {
                sendFailure(player, requestId, "这本书的正文超过服务器设置的单次分发大小。");
                return;
            }
            chunkCount = chunkCountFor(bytes.length);
        }
        replaceServerDelivery(player.getUUID());
        PacketDistributor.sendToPlayer(player, new BookOpenHeaderPayload(requestId, hand,
                revision.pointer(), canEdit, canChangeLock, bodyOmitted,
                bodyOmitted ? 0 : bytes.length, chunkCount));
        if (!bodyOmitted) {
            serverDelivery(player.getUUID()).enqueueDocument(requestId, bytes);
        }
        for (String assetHash : imageAssetHashes(revision.document())) {
            repository.assetPathIfPresent(assetHash).ifPresent(asset ->
                    enqueueImageForClient(player, requestId, assetHash, asset));
        }
    }

    private static void handleSaveHeader(BookSaveHeaderPayload header,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(header.hand());
            if (!isEditableBook(stack)) {
                sendSaveFailure(player, header.requestId(), "请继续手持原来的Writable Book Wiki 后再保存。");
                return;
            }
            Optional<BookPointer> heldPointer = BookStackData.read(stack);
            if (heldPointer.isEmpty() || !heldPointer.get().equals(header.originalPointer())) {
                sendSaveFailure(player, header.requestId(), "手持的书已更换或版本已变化，未保存本次修改。");
                return;
            }
            if (!WritableBookWikiServerConfig.allowsDistribution(header.totalBytes())) {
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
                sendSaveFailure(player, request.requestId(), "请继续手持原来的Writable Book Wiki 后再修改锁定状态。");
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
            if (!WritableBookWikiServerConfig.allowsDistribution(header.totalBytes())) {
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
            if (sizeLong <= 0 || sizeLong > MAX_IMAGE_BYTES || !WritableBookWikiServerConfig.allowsDistribution(
                    Math.toIntExact(sizeLong))) {
                return;
            }
            int size = Math.toIntExact(sizeLong);
            serverDelivery(player.getUUID()).enqueueImage(requestId, assetHash, asset, size);
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
        return new FileDocumentCache(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("book-cache"));
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




    private static String assetTransferKey(long requestId, String assetHash) {
        return requestId + ":" + assetHash;
    }

    private static void replaceServerDelivery(UUID playerId) {
        PacedBookDelivery prior = PENDING_SERVER_DELIVERIES.remove(playerId);
        if (prior != null) {
            prior.close();
        }
        PENDING_SERVER_DELIVERIES.put(playerId, new PacedBookDelivery());
    }

    private static PacedBookDelivery serverDelivery(UUID playerId) {
        return PENDING_SERVER_DELIVERIES.computeIfAbsent(playerId, ignored -> new PacedBookDelivery());
    }






    /** Complete, verified data exposed to a client UI/session adapter. */
    public record ReceivedBook(long requestId, InteractionHand hand, BookPointer pointer, boolean canEdit,
            boolean canChangeLock, WikiDocument document) {
    }

    /** Server-authoritative result exposed to the client session adapter. */
    public record SavedBook(long requestId, InteractionHand hand, BookPointer pointer, SaveOutcome outcome) {
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
    private static final class PendingDocument {
        private final BookOpenHeaderPayload header;
        private final DocumentTransferBuffer buffer;

        private PendingDocument(BookOpenHeaderPayload header) {
            this.header = header;
            this.buffer = new DocumentTransferBuffer(header.totalBytes(), header.chunkCount(),
                    DOCUMENT_CHUNK_BYTES, MAX_DOCUMENT_BYTES, TRANSFER_TIMEOUT_NANOS, System::nanoTime);
        }

        private BookOpenHeaderPayload header() { return header; }
        private boolean accept(BookOpenChunkPayload chunk) { return buffer.accept(chunk.chunkIndex(), chunk.bytes()); }
        private boolean isComplete() { return buffer.isComplete(); }
        private byte[] bytes() { return buffer.bytes(); }
        private boolean isExpired(long nowNanos) { return buffer.isExpired(nowNanos); }
    }

    /** Server-side ordered upload buffer. It grows only as validated chunks arrive. */
    private static final class PendingSaveDocument {
        private final BookSaveHeaderPayload header;
        private final DocumentTransferBuffer buffer;

        private PendingSaveDocument(BookSaveHeaderPayload header) {
            this.header = header;
            this.buffer = new DocumentTransferBuffer(header.totalBytes(), header.chunkCount(),
                    DOCUMENT_CHUNK_BYTES, MAX_DOCUMENT_BYTES, TRANSFER_TIMEOUT_NANOS, System::nanoTime);
        }

        private BookSaveHeaderPayload header() { return header; }
        private boolean accept(BookSaveChunkPayload chunk) { return buffer.accept(chunk.chunkIndex(), chunk.bytes()); }
        private boolean isComplete() { return buffer.isComplete(); }
        private byte[] bytes() { return buffer.bytes(); }
        private boolean isExpired(long nowNanos) { return buffer.isExpired(nowNanos); }
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


}
