package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.*;
import com.writablebookwiki.local.ChunkedAssetReceiver;
import com.writablebookwiki.neoforge.network.*;
import com.writablebookwiki.neoforge.network.BookPayloads.*;
import io.netty.buffer.Unpooled;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

/** Opt-in in-engine protocol/lifecycle checks; excluded from the distributable JAR. */
final class NetworkSmokeChecks {
    private NetworkSmokeChecks() { }

    static void run(BiConsumer<Boolean, String> check, Path output) throws Exception {
        verifyProtocol(check, output);
        verifyDelivery(check, output);
        verifyDisconnect(check, output);
    }

    private static List<CustomPacketPayload> samples() {
        BookPointer pointer = new BookPointer(new UUID(1, 2), "a".repeat(64), true);
        InteractionHand hand = InteractionHand.OFF_HAND;
        byte[] chunk = {1, 2, 3};
        List<CustomPacketPayload> samples = new ArrayList<>(List.of(
                new BookOpenRequestPayload(17, hand, pointer),
                new BookOpenHeaderPayload(17, hand, pointer, true, false, false, 3, 1),
                new BookOpenChunkPayload(17, 0, chunk),
                new BookOpenFailurePayload(17, "失败"),
                new ImageUploadHeaderPayload(17, hand, pointer, "b".repeat(64), 3, 1),
                new ImageUploadChunkPayload(17, "b".repeat(64), 0, chunk),
                new ImageOpenHeaderPayload(17, "b".repeat(64), 3, 1),
                new ImageOpenChunkPayload(17, "b".repeat(64), 0, chunk),
                new ImageUploadResultPayload(17, "b".repeat(64), true, ""),
                new BookSaveHeaderPayload(17, hand, pointer, 3, 1),
                new BookLockRequestPayload(17, hand, pointer, false),
                new BookSaveChunkPayload(17, 0, chunk),
                new BookSaveSuccessPayload(17, hand, pointer, SaveOutcome.FORKED),
                new BookSaveFailurePayload(17, "失败"),
                new BookOpenRequestPayload(17, InteractionHand.MAIN_HAND, null),
                new BookOpenHeaderPayload(17, hand, pointer, false, true, true, 0, 0),
                new BookSaveSuccessPayload(17, hand, pointer, SaveOutcome.UNCHANGED),
                new BookSaveSuccessPayload(17, hand, pointer, SaveOutcome.LOCK_STATE_CHANGED)));
        return samples;
    }

    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf, Object> codec(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("STREAM_CODEC");
        field.setAccessible(true);
        return (StreamCodec<RegistryFriendlyByteBuf, Object>) field.get(null);
    }

    private static byte[] encode(Object payload) throws Exception {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec(payload.getClass()).encode(buffer, payload);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static void verifyProtocol(BiConsumer<Boolean, String> check, Path output) throws Exception {
        Properties expected = new Properties();
        String baseline = System.getProperty("writablebookwiki.protocolBaselineJar", "");
        if (baseline.isEmpty()) {
            try (var input = NetworkSmokeChecks.class.getResourceAsStream("/protocol-v1.properties")) {
                if (input == null) throw new AssertionError("Missing frozen protocol-v1 fixture");
                expected.load(input);
            }
        }
        List<String> captured = new ArrayList<>();
        try (URLClassLoader legacy = baseline.isEmpty() ? null : legacyLoader(Path.of(baseline))) {
            int index = 0;
            for (CustomPacketPayload sample : samples()) {
                byte[] bytes = encode(sample);
                String current = sample.type().id() + "|" + HexFormat.of().formatHex(bytes);
                String frozen;
                if (legacy != null) {
                    Object old = legacySample(legacy, sample);
                    Method type = old.getClass().getDeclaredMethod("type");
                    type.setAccessible(true);
                    var oldType = (CustomPacketPayload.Type<?>) type.invoke(old);
                    frozen = oldType.id() + "|" + HexFormat.of().formatHex(encode(old));
                    captured.add(index + "=" + frozen);
                } else {
                    frozen = expected.getProperty(Integer.toString(index));
                }
                check.accept(current.equals(frozen), "protocol v1 frozen bytes: " + index + " " + sample.type().id());
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
                try {
                    Object decoded = codec(sample.getClass()).decode(buffer);
                    check.accept(!buffer.isReadable() && Arrays.equals(bytes, encode(decoded)),
                            "protocol round trip consumes exact payload: " + index);
                } finally {
                    buffer.release();
                }
                index++;
            }
        }
        if (!baseline.isEmpty()) Files.write(output.resolve("protocol-v1.properties"), captured);
        byte[] original = {1, 2, 3};
        var chunk = new BookOpenChunkPayload(1, 0, original);
        original[0] = 9;
        byte[] read = chunk.bytes();
        read[1] = 9;
        check.accept(Arrays.equals(chunk.bytes(), new byte[] {1, 2, 3}), "packet bytes are immutable snapshots");
        boolean rejected = false;
        try { new BookSaveHeaderPayload(1, InteractionHand.MAIN_HAND, new BookPointer(new UUID(0, 1), "a".repeat(64), false), 3, 2); }
        catch (IllegalArgumentException expectedFailure) { rejected = true; }
        check.accept(rejected, "wire header rejects inconsistent length/count");
    }

    private static URLClassLoader legacyLoader(Path jar) throws Exception {
        return new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, NetworkSmokeChecks.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Isolate only the old coordinator and its nested messages; share Minecraft/core types.
                if (!name.equals(BookNetwork.class.getName()) && !name.startsWith(BookNetwork.class.getName() + "$"))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) found = findClass(name);
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object legacySample(ClassLoader loader, CustomPacketPayload sample) throws Exception {
        Class<?> oldType = loader.loadClass(BookNetwork.class.getName() + "$" + sample.getClass().getSimpleName());
        RecordComponent[] parts = oldType.getRecordComponents();
        Class<?>[] types = Arrays.stream(parts).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Object value = sample.getClass().getMethod(parts[i].getName()).invoke(sample);
            if (types[i].isEnum() && !types[i].isInstance(value))
                value = Enum.valueOf((Class) types[i], ((Enum<?>) value).name());
            values[i] = value;
        }
        Constructor<?> constructor = oldType.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static void verifyDelivery(BiConsumer<Boolean, String> check, Path output) throws Exception {
        List<CustomPacketPayload> sent = new ArrayList<>();
        var paced = new PacedBookDelivery();
        paced.enqueueDocument(3, new byte[BookProtocol.DOCUMENT_CHUNK_BYTES]);
        for (int tick = 0; tick < 479; tick++) paced.deliver(sent::add, 1024);
        check.accept(sent.isEmpty(), "1 KiB/s does not spend credit early");
        paced.deliver(sent::add, 1024);
        // Floating point accumulation may require one extra tick at the boundary.
        if (sent.isEmpty()) paced.deliver(sent::add, 1024);
        check.accept(sent.size() == 1 && paced.isComplete(), "rate below packet size still makes progress");
        Path image = output.resolve("queued-image.bin");
        Files.write(image, new byte[BookProtocol.IMAGE_CHUNK_BYTES * 2]);
        try {
            paced = new PacedBookDelivery();
            paced.enqueueImage(4, "b".repeat(64), image, (int) Files.size(image));
            sent.clear();
            for (int tick = 0; tick < 21; tick++) paced.deliver(sent::add, BookProtocol.IMAGE_CHUNK_BYTES);
            check.accept(sent.getFirst() instanceof ImageOpenHeaderPayload
                            && sent.stream().filter(ImageOpenChunkPayload.class::isInstance).count() == 1,
                    "image header precedes paced streaming chunks");
            paced.close();
            paced.close();
            check.accept(paced.isComplete(), "closing queued delivery drains and is idempotent");
            Files.delete(image); // On Windows this also verifies the live file handle was closed.
            check.accept(!Files.exists(image), "queue close releases its opened image file");
            Files.write(image, new byte[BookProtocol.IMAGE_CHUNK_BYTES * 2]);
            var failing = new PacedBookDelivery();
            failing.enqueueImage(4, "b".repeat(64), image, (int) Files.size(image));
            boolean failed = false;
            try {
                failing.deliver(payload -> {
                    if (payload instanceof ImageOpenChunkPayload) throw new IllegalStateException("disconnected");
                }, 0);
            } catch (IllegalStateException expected) { failed = true; }
            Files.delete(image);
            check.accept(failed && failing.isComplete(), "send failure drains queue and closes active stream");
        } finally {
            Files.deleteIfExists(image);
        }
    }

    private static void verifyDisconnect(BiConsumer<Boolean, String> check, Path output) throws Exception {
        UUID player = new UUID(3, 4), other = new UUID(5, 6);
        BookPointer pointer = new BookPointer(new UUID(0, 1), "a".repeat(64), false);
        Object pending = construct("PendingSaveDocument",
                new Class<?>[] {BookSaveHeaderPayload.class},
                new BookSaveHeaderPayload(1, InteractionHand.MAIN_HAND, pointer, 3, 1));
        Map<Object, Object> saves = map("PENDING_SERVER_SAVES");
        saves.put(player, pending);
        saves.put(other, pending);
        Path incoming = Files.createDirectories(output.resolve("incoming"));
        var receiver = ChunkedAssetReceiver.create(incoming, "asset-", "b".repeat(64), 3, 1, 3);
        Object asset = construct("PendingImageAsset",
                new Class<?>[] {long.class, String.class, ChunkedAssetReceiver.class}, 1L, "b".repeat(64), receiver);
        map("PENDING_SERVER_ASSETS").put(player, asset);
        map("PENDING_SERVER_DELIVERIES").put(player, new PacedBookDelivery());
        BookNetwork.disconnectPlayer(player);
        check.accept(!saves.containsKey(player) && saves.containsKey(other), "logout clears only the departed player's upload");
        try (var files = Files.list(incoming)) {
            check.accept(files.findAny().isEmpty(), "logout discards partial asset file");
        }
        BookNetwork.stopServer();
        BookNetwork.stopServer();
        check.accept(saves.isEmpty() && map("PENDING_SERVER_DELIVERIES").isEmpty(), "server stop drains all transfer state");
        Object download = construct("PendingDocument", new Class<?>[] {BookOpenHeaderPayload.class},
                new BookOpenHeaderPayload(2, InteractionHand.MAIN_HAND, pointer, true, true, false, 3, 1));
        set(BookNetwork.class, null, "pendingDocument", download);
        set(BookNetwork.class, null, "latestRequestId", 2L);
        BookNetwork.disconnectClient();
        check.accept(get(BookNetwork.class, null, "pendingDocument") == null
                && (long) get(BookNetwork.class, null, "latestRequestId") == 0, "client disconnect invalidates open reply and buffer");
        var session = new WorldBookSession(InteractionHand.MAIN_HAND, pointer, true, true);
        var future = new CompletableFuture<DocumentSession.SaveConfirmation>();
        set(WorldBookSession.class, session, "pendingSave", future);
        ClientBookSessions.track(5, session);
        ClientBookSessions.disconnect();
        check.accept(future.isDone() && !future.join().success() && !session.canEdit() && !session.canChangeLock(),
                "disconnect terminates pending save and revokes stale session actions");
        check.accept(session.changeLock(true).contains("断开"), "stale session cannot send lock updates");
        var connected = new WorldBookSession(InteractionHand.MAIN_HAND, pointer, true, true);
        var acknowledged = new CompletableFuture<DocumentSession.SaveConfirmation>();
        set(WorldBookSession.class, connected, "pendingSave", acknowledged);
        set(WorldBookSession.class, connected, "saveRequest", 9L);
        BookPointer revision = new BookPointer(new UUID(9, 9), "c".repeat(64), false);
        connected.acceptSave(new BookNetwork.SavedBook(8, InteractionHand.MAIN_HAND, revision, SaveOutcome.FORKED));
        check.accept(!acknowledged.isDone() && connected.pointer().equals(pointer), "unmatched save reply cannot advance pointer");
        connected.acceptSave(new BookNetwork.SavedBook(9, InteractionHand.MAIN_HAND, revision, SaveOutcome.FORKED));
        check.accept(acknowledged.join().success() && connected.pointer().equals(revision), "matching acknowledgement advances session pointer");
        connected.disconnect();
        connected.acceptSave(new BookNetwork.SavedBook(9, InteractionHand.MAIN_HAND, pointer, SaveOutcome.UNCHANGED));
        check.accept(connected.pointer().equals(revision), "late reply cannot mutate a disconnected session");
    }

    private static Object construct(String nested, Class<?>[] types, Object... values) throws Exception {
        Constructor<?> constructor = Class.forName(BookNetwork.class.getName() + "$" + nested).getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(String name) throws Exception {
        return (Map<Object, Object>) get(BookNetwork.class, null, name);
    }

    private static Object get(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
