package com.writablebookwiki.verification;

import com.mojang.authlib.GameProfile;
import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.book.BookRevision;
import com.writablebookwiki.core.document.*;
import com.writablebookwiki.local.ChunkedAssetReceiver;
import com.writablebookwiki.neoforge.BookNetwork;
import com.writablebookwiki.neoforge.network.LecternOpenRequest;
import com.writablebookwiki.neoforge.network.PacedBookDelivery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.item.ItemEntity;
import com.writablebookwiki.neoforge.WritableBookWikiMod;
import com.writablebookwiki.neoforge.network.BookPayloads.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Exercises an unmodified release JAR in an installed dedicated server.
 * Payload handlers use an in-process context and FakePlayer: this is not a TCP/multiplayer test.
 */
@Mod("writablebookwiki_verification")
public final class ServerReleaseSmoke {
    private static final List<String> checks = new ArrayList<>();
    private static final Path OUTPUT = Path.of("verification-results");
    private static final WikiDocumentCodec CODEC = new WikiDocumentCodec();
    private static long request = 100;
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;

    public ServerReleaseSmoke() {
        if (System.getProperty("writablebookwiki.serverSmokePhase") != null)
            NeoForge.EVENT_BUS.addListener(this::started);
        if ("client".equals(System.getProperty("writablebookwiki.serverSmokePhase"))) {
            NeoForge.EVENT_BUS.addListener(this::liveLogin);
            NeoForge.EVENT_BUS.addListener(this::liveCommands);
        }
    }

    private void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        String phase = System.getProperty("writablebookwiki.serverSmokePhase");
        try {
            Files.createDirectories(OUTPUT);
            String origin = WritableBookWikiMod.class.getProtectionDomain().getCodeSource().getLocation().toString();
            check(origin.contains(".jar") && !origin.contains("build/classes"), "loaded production classes from JAR: " + origin);
            check(server.isDedicatedServer(), "running dedicated server with real world and registries");
            if ("client".equals(phase)) prepareLive(server);
            else if ("write".equals(phase)) writePhase(server);
            else if ("reload".equals(phase)) reloadPhase(server);
            else throw new IllegalArgumentException("Unknown test phase " + phase);
            server.saveEverything(false, true, true);
            Files.writeString(OUTPUT.resolve(phase + ".txt"), "PASS\n" + String.join("\n", checks));
            System.out.println("SERVER_RELEASE_SMOKE_PASS " + phase + " " + checks.size() + " checks");
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.writeString(OUTPUT.resolve(phase + ".txt"), "FAIL\n" + String.join("\n", checks) + "\n" + failure); }
            catch (Exception ignored) { }
            System.out.println("SERVER_RELEASE_SMOKE_FAIL " + phase + " " + failure);
        } finally {
            if (!"client".equals(phase)) server.halt(false);
        }
    }

    private static ItemStack liveBook;

    private static void prepareLive(MinecraftServer server) throws Exception {
        var state = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("writablebookwiki-verification.nbt"),
                NbtAccounter.unlimitedHeap());
        liveBook = ItemStack.parse(server.registryAccess(), state.getCompound("current")).orElseThrow();
        ServerLevel level = server.overworld();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++)
            level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(LECTERN_POS, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(LECTERN_POS, Blocks.LECTERN.defaultBlockState());
        server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
        check(liveBook != null, "isolated live TCP fixture ready");
    }

    private void liveLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        player.setItemInHand(HAND, liveBook.copy());
        player.teleportTo(4.5, 80, 6.5);
        player.setYRot(180);
        player.setXRot(20);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    private void liveCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("wbwverify")
            .then(net.minecraft.commands.Commands.literal("first").executes(context -> {
                ServerPlayer other = reader(context.getSource().getLevel(), 99, "OtherReader");
                useLectern(other);
                ((LecternMenu) other.containerMenu).clickMenuButton(other, 100);
                other.closeContainer();
                return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("vanilla").executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                player.closeContainer();
                player.serverLevel().setBlockAndUpdate(LECTERN_POS, Blocks.AIR.defaultBlockState());
                player.serverLevel().setBlockAndUpdate(LECTERN_POS, Blocks.LECTERN.defaultBlockState());
                player.setItemInHand(HAND, new ItemStack(Items.WRITABLE_BOOK));
                player.inventoryMenu.broadcastChanges();
                return 1;
            }))
            .then(net.minecraft.commands.Commands.literal("stop").executes(context -> {
                context.getSource().getServer().halt(false);
                return 1;
            })));
    }

    private static void writePhase(MinecraftServer server) throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path stateFile = root.resolve("writablebookwiki-verification.nbt");
        check(!Files.exists(stateFile), "fresh isolated test world, no prior fixture overwritten");
        ServerLevel level = server.overworld();
        Object repository = repository(level);
        FakePlayer player = new FakePlayer(level, new GameProfile(new UUID(11, 12), "BookVerifier"));
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("writablebookwiki", "editable_book"));
        check(item != Items.AIR, "editable book item registered on dedicated server");

        List<ItemStack> grid = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) grid.add(new ItemStack(slot == 4 ? Items.WRITABLE_BOOK : Items.PAPER));
        CraftingInput input = CraftingInput.of(3, 3, grid);
        var recipe = server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        ItemStack crafted = recipe.value().assemble(input, server.registryAccess());
        check(crafted.is(item) && crafted.getCount() == 1, "book and quill surrounded by eight paper crafts one Wiki book");

        for (GameType mode : List.of(GameType.CREATIVE, GameType.SURVIVAL)) {
            player.setGameMode(mode);
            for (String origin : List.of("inventory", "crafted")) {
                ItemStack book = origin.equals("crafted") ? crafted.copy() : new ItemStack(item);
                player.setItemInHand(HAND, book);
                check(pointer(book).isEmpty(), mode + " " + origin + " starts without a partial pointer");
                item.use(level, player, HAND);
                BookPointer initial = pointer(book).orElseThrow();
                check(!initial.locked() && load(repository, initial).isPresent(), mode + " " + origin + " first use creates readable unlocked book");
                WikiDocument empty = load(repository, initial).orElseThrow().document();
                WikiDocument changed = new WikiDocument(empty.formatVersion(), mode + "-" + origin,
                        List.of(paragraph("可编辑 ABC")), empty.metadata());
                save(player, initial, changed);
                check(!pointer(book).orElseThrow().bookId().equals(initial.bookId()),
                        mode + " " + origin + " may edit its unlocked book");
            }
        }

        player.setGameMode(GameType.SURVIVAL);
        ItemStack book = new ItemStack(item);
        player.setItemInHand(HAND, book);
        item.use(level, player, HAND);
        ItemStack untouchedCopy = book.copy();
        BookPointer initial = pointer(book).orElseThrow();
        BookRevision original = load(repository, initial).orElseThrow();
        byte[] originalBytes = Files.readAllBytes(documentFile(root, initial));
        String imageHash = uploadImage(repository);
        WikiDocument rich = new WikiDocument(original.document().formatVersion(), "重启验收：图文表格",
                List.of(new Heading(1, List.of(new TextRun("第一章", TextStyle.DEFAULT.withBold(true)))),
                        paragraph("中文 English 👩‍💻 e\u0301"),
                        new ImageBlock(imageHash, "", ImageAlignment.LEFT, 64),
                        new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(
                                new TextRun("表格\n第二行", TextStyle.DEFAULT.withColor(0x9A2030)))))))),
                        PageBreak.INSTANCE, paragraph("第二页")), original.document().metadata());
        save(player, initial, rich);
        BookPointer changed = pointer(book).orElseThrow();
        check(!changed.bookId().equals(initial.bookId()), "changed content forks only held physical copy");
        check(pointer(untouchedCopy).orElseThrow().equals(initial), "other physical copy retains original pointer");
        check(Arrays.equals(originalBytes, Files.readAllBytes(documentFile(root, initial))), "fork does not rewrite original content file");
        BookRevision saved = load(repository, changed).orElseThrow();
        check(saved.document().blocks().equals(rich.blocks()), "Unicode images tables and authored pages persist together");
        check(book.get(DataComponents.CUSTOM_NAME).getString().equals(rich.title()), "title save updates held item display name");
        save(player, changed, saved.document());
        check(pointer(book).orElseThrow().equals(changed), "unchanged save does not fork or change hash");

        lock(player, changed, true);
        check(pointer(book).orElseThrow().equals(changed), "survival cannot lock a book");
        player.setGameMode(GameType.CREATIVE);
        lock(player, changed, true);
        BookPointer locked = pointer(book).orElseThrow();
        check(locked.locked() && locked.bookId().equals(changed.bookId())
                && locked.contentHash().equals(changed.contentHash()), "creative lock changes only lock flag");
        byte[] lockedBytes = Files.readAllBytes(documentFile(root, locked));
        WikiDocument forced = new WikiDocument(saved.document().formatVersion(), "创造强制编辑后的书名",
                saved.document().blocks(), saved.document().metadata());
        player.setGameMode(GameType.SURVIVAL);
        save(player, locked, forced);
        check(pointer(book).orElseThrow().equals(locked), "survival save of locked book is rejected");
        check(Arrays.equals(lockedBytes, Files.readAllBytes(documentFile(root, locked))), "denied locked save leaves file unchanged");
        lock(player, locked, false);
        check(pointer(book).orElseThrow().equals(locked), "survival unlock is rejected");
        player.setGameMode(GameType.CREATIVE);
        save(player, locked, forced);
        BookPointer forcedPointer = pointer(book).orElseThrow();
        check(forcedPointer.locked() && !forcedPointer.bookId().equals(locked.bookId()), "creative force edit forks and preserves lock");
        lock(player, forcedPointer, false);
        BookPointer finalPointer = pointer(book).orElseThrow();
        check(!finalPointer.locked() && finalPointer.bookId().equals(forcedPointer.bookId())
                && finalPointer.contentHash().equals(forcedPointer.contentHash()), "creative unlock preserves ID and hash");

        ItemStack richSnapshot = book.copy();
        long storedCount = documentCount(root);
        WikiDocument rejected = new WikiDocument(forced.formatVersion(), "must-not-save", forced.blocks(), forced.metadata());
        byte[] proposed = CODEC.encode(rejected);
        long id = ++request;
        payload("handleSaveHeader", new BookSaveHeaderPayload(id, HAND, finalPointer, proposed.length, chunks(proposed)), player);
        player.setItemInHand(HAND, new ItemStack(Items.STONE));
        sendChunks(id, proposed, player);
        check(load(repository, finalPointer).orElseThrow().document().title().equals(forced.title()),
                "changing held item mid-upload does not rewrite stored book");
        check(documentCount(root) == storedCount, "rejected hand-swap save creates no orphan revision");
        player.setItemInHand(HAND, book);
        WikiDocument large = new WikiDocument(forced.formatVersion(), "multi-chunk",
                List.of(paragraph("正文 block ".repeat(6000))), forced.metadata());
        byte[] multiple = CODEC.encode(large);
        check(chunks(multiple) > 1, "large body actually spans multiple transport chunks");
        long interruptedId = ++request;
        payload("handleSaveHeader", new BookSaveHeaderPayload(interruptedId, HAND, finalPointer, multiple.length, chunks(multiple)), player);
        payload("handleSaveChunk", new BookSaveChunkPayload(interruptedId, 0,
                Arrays.copyOfRange(multiple, 0, BookNetwork.DOCUMENT_CHUNK_BYTES)), player);
        check(pointer(book).orElseThrow().equals(finalPointer) && documentCount(root) == storedCount,
                "partial upload commits neither pointer nor document");
        callStatic("BookNetwork", "disconnectPlayer", new Class<?>[]{UUID.class}, player.getUUID());
        for (int i = 1; i < chunks(multiple); i++) {
            int start = i * BookNetwork.DOCUMENT_CHUNK_BYTES;
            payload("handleSaveChunk", new BookSaveChunkPayload(interruptedId, i,
                    Arrays.copyOfRange(multiple, start, Math.min(multiple.length, start + BookNetwork.DOCUMENT_CHUNK_BYTES))), player);
        }
        check(pointer(book).orElseThrow().equals(finalPointer) && documentCount(root) == storedCount,
                "late chunks after disconnect cannot resurrect discarded upload");
        save(player, finalPointer, large);
        check(load(repository, pointer(book).orElseThrow()).orElseThrow().document().blocks().equals(large.blocks()),
                "complete multi-chunk upload persists exact large body");
        // Retain the rich book for process-restart verification and keep the large revision as additional evidence.
        book = richSnapshot;
        player.setItemInHand(HAND, book);
        Object otherDimension = repository(Objects.requireNonNull(server.getLevel(Level.NETHER)));
        check(load(otherDimension, finalPointer).orElseThrow().document().equals(load(repository, finalPointer).orElseThrow().document()),
                "book repository and index remain world-scoped across dimensions");

        CompoundTag state = new CompoundTag();
        state.put("original", untouchedCopy.save(server.registryAccess()));
        state.put("current", book.save(server.registryAccess()));
        state.putString("image_hash", imageHash);
        NbtIo.writeCompressed(state, stateFile);
        Files.write(OUTPUT.resolve("expected-current.ewd"), CODEC.encode(load(repository, finalPointer).orElseThrow().document()));
        Files.write(OUTPUT.resolve("expected-original.ewd"), originalBytes);
        check(Files.isRegularFile(root.resolve("writablebookwiki/assets/" + imageHash + ".asset")), "image stored in world-owned assets");
        lecternPhase(server, book.copy());
    }

    private static void reloadPhase(MinecraftServer server) throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT);
        CompoundTag state = NbtIo.readCompressed(root.resolve("writablebookwiki-verification.nbt"), NbtAccounter.unlimitedHeap());
        ItemStack original = ItemStack.parse(server.registryAccess(), state.getCompound("original")).orElseThrow();
        ItemStack current = ItemStack.parse(server.registryAccess(), state.getCompound("current")).orElseThrow();
        BookPointer before = pointer(original).orElseThrow(), after = pointer(current).orElseThrow();
        Object index = callStatic("WorldBookIndexData", "get", new Class<?>[]{ServerLevel.class}, server.overworld());
        check(call(index, "contentHash", new Class<?>[]{UUID.class}, before.bookId()).equals(Optional.of(before.contentHash())),
                "original index entry persisted before repository auto-repair can run");
        check(call(index, "contentHash", new Class<?>[]{UUID.class}, after.bookId()).equals(Optional.of(after.contentHash())),
                "changed index entry persisted before repository auto-repair can run");
        Object repository = repository(server.overworld());
        WikiDocument oldDocument = load(repository, before).orElseThrow().document();
        WikiDocument newDocument = load(repository, after).orElseThrow().document();
        check(Arrays.equals(CODEC.encode(oldDocument), Files.readAllBytes(OUTPUT.resolve("expected-original.ewd"))),
                "original copy reads identically after full process restart");
        check(Arrays.equals(CODEC.encode(newDocument), Files.readAllBytes(OUTPUT.resolve("expected-current.ewd"))),
                "edited rich document reads identically after full process restart");
        check(!before.bookId().equals(after.bookId()) && !after.locked(), "fork identity and creative unlock survive restart");
        check(current.get(DataComponents.CUSTOM_NAME).getString().equals(newDocument.title()), "item display name survives NBT reload");
        String hash = state.getString("image_hash");
        byte[] asset = Files.readAllBytes(root.resolve("writablebookwiki/assets/" + hash + ".asset"));
        check(hash.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(asset))),
                "image bytes retain content hash after restart");
        check(ImageIO.read(new java.io.ByteArrayInputStream(asset)).getWidth() == 32, "persisted image decodes at original resolution");
        LecternBlockEntity lectern = (LecternBlockEntity) server.overworld().getBlockEntity(LECTERN_POS);
        check(lectern != null && lectern.hasBook(), "placed Wiki book survives real server process restart");
        check(pointer(lectern.getBook()).orElseThrow().equals(after), "lectern NBT preserves exact book pointer");
        check(lectern.getPage() == 1 && lectern.getRedstoneSignal() == 15, "lectern page count shared page and comparator survive reload");
        check(newDocument.blocks().stream().anyMatch(b -> b instanceof TableBlock)
                && newDocument.blocks().stream().anyMatch(b -> b instanceof PageBreak), "table and authored page survive restart");
    }

    private static final BlockPos LECTERN_POS = new BlockPos(4, 80, 4);

    private static void lecternPhase(MinecraftServer server, ItemStack richBook) throws Exception {
        ServerLevel level = server.overworld();
        ServerPlayer owner = reader(level, 21, "LecternOwner"), visitor = reader(level, 22, "LecternVisitor");
        CaptureConnection ownerConnection = (CaptureConnection) owner.connection;
        CaptureConnection visitorConnection = (CaptureConnection) visitor.connection;
        level.setBlockAndUpdate(LECTERN_POS, Blocks.LECTERN.defaultBlockState());
        owner.setItemInHand(HAND, richBook.copy());
        check(useLectern(owner).consumesAction(), "real vanilla block interaction accepts Wiki book tag");
        LecternBlockEntity lectern = (LecternBlockEntity) level.getBlockEntity(LECTERN_POS);
        check(lectern.hasBook() && level.getBlockState(LECTERN_POS).getValue(LecternBlock.HAS_BOOK),
                "lectern mixin recognises Wiki book and vanilla displayed-book state is true");
        check(owner.getMainHandItem().isEmpty(), "survival placement consumes exactly one book");
        BookPointer placed = pointer(lectern.getBook()).orElseThrow();
        check(placed.equals(pointer(richBook).orElseThrow()), "placement preserves book ID hash and lock");
        check(lectern.getRedstoneSignal() == 1, "two-page Wiki book begins at comparator signal one");
        useLectern(owner);
        useLectern(visitor);
        check(owner.containerMenu instanceof LecternMenu && visitor.containerMenu instanceof LecternMenu,
                "two independent players open vanilla lectern menus");
        LecternMenu first = (LecternMenu) owner.containerMenu, second = (LecternMenu) visitor.containerMenu;
        check(first.stillValid(owner) && second.stillValid(visitor), "vanilla menus stay valid with Wiki book");
        ownerConnection.payloads.clear();
        visitorConnection.payloads.clear();
        payload("handleLecternOpen", new LecternOpenRequest(++request, first.containerId, placed), owner);
        payload("handleLecternOpen", new LecternOpenRequest(++request, second.containerId, placed), visitor);
        var firstHeader = ownerConnection.payloads.stream().filter(BookOpenHeaderPayload.class::isInstance)
                .map(BookOpenHeaderPayload.class::cast).findFirst().orElseThrow();
        var secondHeader = visitorConnection.payloads.stream().filter(BookOpenHeaderPayload.class::isInstance)
                .map(BookOpenHeaderPayload.class::cast).findFirst().orElseThrow();
        check(firstHeader.pointer().equals(secondHeader.pointer()) && !firstHeader.canEdit() && !firstHeader.canChangeLock(),
                "both readers receive the same source with no edit or lock authority");
        List<CustomPacketPayload> delivered = drainDelivery(owner);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var packet : delivered) if (packet instanceof BookOpenChunkPayload chunk) body.writeBytes(chunk.bytes());
        check(CODEC.decode(body.toByteArray()).equals(load(repository(level), placed).orElseThrow().document()),
                "lectern document chunks reconstruct complete rich body");
        check(delivered.stream().anyMatch(ImageOpenChunkPayload.class::isInstance), "lectern delivery includes world image bytes");
        drainDelivery(visitor);
        first.clickMenuButton(owner, 101);
        check(second.getPage() == 1 && lectern.getPage() == 1 && lectern.getRedstoneSignal() == 15,
                "vanilla shared page and comparator follow one reader turning to page two");
        second.clickMenuButton(visitor, 100);
        check(first.getPage() == 0 && level.getBlockState(LECTERN_POS).getValue(LecternBlock.POWERED),
                "other reader turns back and vanilla redstone page pulse fires");

        ownerConnection.payloads.clear();
        payload("handleLecternOpen", new LecternOpenRequest(++request, first.containerId + 1, placed), owner);
        check(ownerConnection.payloads.stream().anyMatch(BookOpenFailurePayload.class::isInstance), "wrong menu ID cannot request a lectern body");
        owner.setPos(100, 80, 100);
        ownerConnection.payloads.clear();
        payload("handleLecternOpen", new LecternOpenRequest(++request, first.containerId, placed), owner);
        check(ownerConnection.payloads.stream().anyMatch(BookOpenFailurePayload.class::isInstance), "distant reader is rejected");
        owner.setPos(4.5, 80, 6);
        BookPointer mismatch = new BookPointer(UUID.randomUUID(), placed.contentHash(), placed.locked());
        ownerConnection.payloads.clear();
        payload("handleLecternOpen", new LecternOpenRequest(++request, first.containerId, mismatch), owner);
        check(ownerConnection.payloads.stream().anyMatch(BookOpenFailurePayload.class::isInstance), "changed book identity rejects stale open");
        visitor.setGameMode(GameType.ADVENTURE);
        check(!second.clickMenuButton(visitor, 3) && lectern.hasBook(), "adventure reader cannot take book");
        visitor.setGameMode(GameType.SURVIVAL);
        check(second.clickMenuButton(visitor, 3), "survival reader can take book with vanilla button");
        check(!lectern.hasBook() && !level.getBlockState(LECTERN_POS).getValue(LecternBlock.HAS_BOOK)
                && !first.stillValid(owner) && !second.stillValid(visitor), "taking book invalidates every reader and clears lectern");
        check(visitor.getInventory().items.stream().anyMatch(stack -> {
            try { return pointer(stack).filter(placed::equals).isPresent(); } catch (Exception failure) { throw new RuntimeException(failure); }
        }), "taken book retains pointer in player inventory");
        ownerConnection.payloads.clear();
        payload("handleLecternOpen", new LecternOpenRequest(++request, first.containerId, placed), owner);
        check(ownerConnection.payloads.stream().anyMatch(BookOpenFailurePayload.class::isInstance), "removed book cannot be opened by stale request");
        owner.closeContainer();
        visitor.closeContainer();

        for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE)) {
            owner.setGameMode(mode);
            ItemStack fresh = new ItemStack(richBook.getItem());
            owner.setItemInHand(HAND, fresh);
            useLectern(owner);
            check(lectern.hasBook() && pointer(lectern.getBook()).isPresent(), mode + " fresh unopened book initialises on placement");
            check(mode == GameType.CREATIVE ? owner.getMainHandItem().getCount() == 1 : owner.getMainHandItem().isEmpty(),
                    mode + " placement retains vanilla consumption rule");
            level.destroyBlock(LECTERN_POS, false);
            check(level.getEntitiesOfClass(ItemEntity.class, new AABB(LECTERN_POS).inflate(2)).stream()
                    .anyMatch(drop -> drop.getItem().is(richBook.getItem())), mode + " breaking lectern drops Wiki book");
            level.getEntitiesOfClass(ItemEntity.class, new AABB(LECTERN_POS).inflate(2)).forEach(ItemEntity::discard);
            level.setBlockAndUpdate(LECTERN_POS, Blocks.LECTERN.defaultBlockState());
            lectern = (LecternBlockEntity) level.getBlockEntity(LECTERN_POS);
        }
        owner.setGameMode(GameType.SURVIVAL);
        owner.setItemInHand(HAND, new ItemStack(Items.WRITABLE_BOOK));
        useLectern(owner);
        check(lectern.hasBook() && lectern.getBook().is(Items.WRITABLE_BOOK), "vanilla book-and-quill placement remains unchanged");
        level.destroyBlock(LECTERN_POS, false);
        level.setBlockAndUpdate(LECTERN_POS, Blocks.LECTERN.defaultBlockState());
        owner.setItemInHand(HAND, richBook.copy());
        useLectern(owner);
        useLectern(owner);
        ((LecternMenu) owner.containerMenu).clickMenuButton(owner, 101);
        owner.closeContainer();
        check(((LecternBlockEntity) level.getBlockEntity(LECTERN_POS)).getPage() == 1, "shared second page staged for restart");
    }

    private static net.minecraft.world.InteractionResult useLectern(ServerPlayer player) {
        return player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), HAND,
                new BlockHitResult(Vec3.atCenterOf(LECTERN_POS), Direction.UP, LECTERN_POS, false));
    }

    private static ServerPlayer reader(ServerLevel level, long id, String name) {
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(new UUID(0, id), name),
                ClientInformation.createDefault());
        player.connection = new CaptureConnection(level.getServer(), player);
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(4.5, 80, 6);
        return player;
    }

    private static final class CaptureConnection extends ServerGamePacketListenerImpl {
        private final List<CustomPacketPayload> payloads = new ArrayList<>();
        CaptureConnection(MinecraftServer server, ServerPlayer player) {
            super(server, new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public boolean hasChannel(ResourceLocation id) { return false; }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) payloads.add(custom.payload());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CustomPacketPayload> drainDelivery(ServerPlayer player) throws Exception {
        Field field = BookNetwork.class.getDeclaredField("PENDING_SERVER_DELIVERIES");
        field.setAccessible(true);
        var deliveries = (Map<UUID, PacedBookDelivery>) field.get(null);
        PacedBookDelivery delivery = deliveries.remove(player.getUUID());
        List<CustomPacketPayload> packets = new ArrayList<>();
        while (!delivery.isComplete()) delivery.deliver(packets::add, 0);
        delivery.close();
        return packets;
    }

    private static String uploadImage(Object repository) throws Exception {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, (x * 8 << 16) | y * 16);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bytes);
        byte[] data = bytes.toByteArray();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        try (ChunkedAssetReceiver transfer = (ChunkedAssetReceiver) call(repository, "beginAssetTransfer",
                new Class<?>[]{String.class, int.class, int.class, int.class}, hash, data.length, 1, BookNetwork.IMAGE_CHUNK_BYTES)) {
            check(transfer.append(0, data), "world image receiver accepts ordered bytes");
            call(repository, "saveReceivedAsset", new Class<?>[]{String.class, ChunkedAssetReceiver.class}, hash, transfer);
        }
        return hash;
    }

    private static void save(ServerPlayer player, BookPointer original, WikiDocument document) throws Exception {
        byte[] bytes = CODEC.encode(document);
        long id = ++request;
        payload("handleSaveHeader", new BookSaveHeaderPayload(id, HAND, original, bytes.length, chunks(bytes)), player);
        sendChunks(id, bytes, player);
    }
    private static int chunks(byte[] bytes) { return (bytes.length + BookNetwork.DOCUMENT_CHUNK_BYTES - 1) / BookNetwork.DOCUMENT_CHUNK_BYTES; }
    private static void sendChunks(long id, byte[] bytes, ServerPlayer player) throws Exception {
        for (int i = 0; i < chunks(bytes); i++) {
            int start = i * BookNetwork.DOCUMENT_CHUNK_BYTES;
            payload("handleSaveChunk", new BookSaveChunkPayload(id, i,
                    Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + BookNetwork.DOCUMENT_CHUNK_BYTES))), player);
        }
    }
    private static void lock(ServerPlayer player, BookPointer pointer, boolean locked) throws Exception {
        payload("handleLockRequest", new BookLockRequestPayload(++request, HAND, pointer, locked), player);
    }
    private static void payload(String method, Object payload, ServerPlayer player) throws Exception {
        IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[]{IPayloadContext.class}, (proxy, called, args) -> {
                    if (called.getName().equals("player")) return player;
                    if (called.getName().equals("enqueueWork")) {
                        ((Runnable) args[0]).run();
                        return CompletableFuture.completedFuture(null);
                    }
                    throw new UnsupportedOperationException(called.toString());
                });
        Method handler = BookNetwork.class.getDeclaredMethod(method, payload.getClass(), IPayloadContext.class);
        handler.setAccessible(true);
        handler.invoke(null, payload, context);
    }
    private static Object repository(ServerLevel level) throws Exception {
        return callStatic("WorldBookRepository", "forLevel", new Class<?>[]{ServerLevel.class}, level);
    }
    @SuppressWarnings("unchecked")
    private static Optional<BookRevision> load(Object repository, BookPointer pointer) throws Exception {
        return (Optional<BookRevision>) call(repository, "load", new Class<?>[]{BookPointer.class}, pointer);
    }
    @SuppressWarnings("unchecked")
    private static Optional<BookPointer> pointer(ItemStack stack) throws Exception {
        return (Optional<BookPointer>) callStatic("BookStackData", "read", new Class<?>[]{ItemStack.class}, stack);
    }
    private static Object callStatic(String owner, String method, Class<?>[] types, Object... args) throws Exception {
        Method target = Class.forName("com.writablebookwiki.neoforge." + owner).getDeclaredMethod(method, types);
        target.setAccessible(true);
        return target.invoke(null, args);
    }
    private static Object call(Object receiver, String method, Class<?>[] types, Object... args) throws Exception {
        Method target = receiver.getClass().getDeclaredMethod(method, types);
        target.setAccessible(true);
        return target.invoke(receiver, args);
    }
    private static Paragraph paragraph(String text) { return new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT))); }
    private static long documentCount(Path root) throws Exception {
        try (var files = Files.list(root.resolve("writablebookwiki/documents"))) { return files.count(); }
    }
    private static Path documentFile(Path root, BookPointer pointer) { return root.resolve("writablebookwiki/documents/" + pointer.bookId() + ".ewd"); }
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks.add(label);
    }
}
