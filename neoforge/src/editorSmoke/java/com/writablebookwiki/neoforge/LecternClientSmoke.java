package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** A real Minecraft client over localhost TCP to the opt-in installed release server fixture. */
@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, value = Dist.CLIENT)
public final class LecternClientSmoke {
    private static final BlockPos POS = new BlockPos(4, 80, 4);
    private static final Path OUT = Path.of("lectern-smoke-results");
    private static final List<String> checks = new ArrayList<>();
    private static int ticks, stage, since;
    private static String bookId;
    private static NativeWikiScreen reader;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("writablebookwiki.lecternSmoke") || stage < 0) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.player != null) mc.getToasts().clear();
        try {
            if (ticks > 2200) throw new AssertionError("TCP client test timeout at stage " + stage + ", screen=" + mc.screen);
            if (mc.getOverlay() == null && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen onboarding) {
                onboarding.onClose();
                return;
            }
            if (stage == 0) {
                if (mc.player == null || mc.level == null || ticks < 100 || !LecternBookSupport.isWikiBook(mc.player.getMainHandItem())) return;
                Files.createDirectories(OUT);
                mc.options.guiScale().set(2);
                mc.options.pauseOnLostFocus = false;
                mc.resizeDisplay();
                mc.setScreen(null);
                mc.player.setYRot(180);
                mc.player.setXRot(25);
                bookId = BookStackData.read(mc.player.getMainHandItem()).orElseThrow().bookId().toString();
                use(mc); next(1); return;
            }
            if (ticks - since < 20) return;
            switch (stage) {
                case 1 -> {
                    check(mc.level.getBlockState(POS).getValue(LecternBlock.HAS_BOOK), "TCP placement displays book on vanilla lectern");
                    check(mc.player.getMainHandItem().isEmpty(), "server inventory sync consumes survival book");
                    capture(mc, "lectern-world.png");
                    use(mc); next(2);
                }
                case 2 -> {
                    if (!(mc.screen instanceof NativeWikiScreen nativeReader)) return;
                    reader = nativeReader;
                    var session = (DocumentSession) field(reader, "session");
                    WikiDocument document = ((WikiDocumentEditor) field(reader, "editor")).document();
                    check(session instanceof LecternDocumentSession && !session.canEdit() && !session.canChangeLock(),
                            "network reply opens native read-only lectern session");
                    check(session.personalAnnotationKey().equals(bookId), "lectern comments reuse local book annotation namespace");
                    check(document.blocks().stream().anyMatch(ImageBlock.class::isInstance)
                            && document.blocks().stream().anyMatch(TableBlock.class::isInstance), "TCP body retains images and tables");
                    check(session.canTakeBook(), "survival reader has take-book control");
                    reader.charTyped('X', 0);
                    check(((WikiDocumentEditor) field(reader, "editor")).document().equals(document), "read mode ignores body typing");
                    capture(mc, "lectern-reader.png");
                    reader.showPage(1); next(3);
                }
                case 3 -> {
                    check(reader.pageIndex() == 1 && ((LecternMenu) mc.player.containerMenu).getPage() == 1,
                            "native next-page sends vanilla menu packet and remains on page two");
                    check(mc.screen == reader, "page turn reuses the same screen without closing");
                    mc.player.connection.sendCommand("wbwverify first"); next(4);
                }
                case 4 -> {
                    check(reader.pageIndex() == 0, "other server reader's turn reaches real client through menu data sync");
                    reader.showPage(1); next(5);
                }
                case 5 -> { reader.onClose(); next(6); }
                case 6 -> {
                    check(mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu, "Escape closes native reader and server menu");
                    use(mc); next(7);
                }
                case 7 -> {
                    if (!(mc.screen instanceof NativeWikiScreen reopened)) return;
                    reader = reopened;
                    check(reader.pageIndex() == 1, "reopening resumes lectern shared page");
                    reader.showPage(0); next(8);
                }
                case 8 -> {
                    Object bounds = invoke(reader, "bounds");
                    reader.mouseClicked(number(bounds, "lockButtonX") + 5, number(bounds, "headerY") + 8, 0);
                    next(9);
                }
                case 9 -> {
                    check(mc.screen == null && !mc.level.getBlockState(POS).getValue(LecternBlock.HAS_BOOK),
                            "take button removes book and closes reader over TCP");
                    check(mc.player.getInventory().items.stream().anyMatch(stack ->
                            BookStackData.read(stack).filter(pointer -> pointer.bookId().toString().equals(bookId)).isPresent()),
                            "taken inventory book retains ID");
                    mc.player.connection.sendCommand("wbwverify vanilla"); next(10);
                }
                case 10 -> {
                    if (!mc.player.getMainHandItem().is(Items.WRITABLE_BOOK)) return;
                    use(mc); next(11);
                }
                case 11 -> { use(mc); next(12); }
                case 12 -> {
                    check(mc.screen instanceof LecternScreen, "vanilla book still opens vanilla lectern screen");
                    capture(mc, "vanilla-lectern.png");
                    Files.writeString(OUT.resolve("result.txt"), "PASS\n" + String.join("\n", checks));
                    System.out.println("LECTERN_TCP_SMOKE_PASS " + checks.size() + " checks");
                    mc.player.connection.sendCommand("wbwverify stop");
                    stage = -1;
                    mc.stop();
                }
                default -> throw new AssertionError("Unknown stage");
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.createDirectories(OUT); Files.writeString(OUT.resolve("result.txt"), "FAIL\n" + String.join("\n", checks) + "\n" + failure); }
            catch (Exception ignored) { }
            stage = -1;
            if (mc.player != null) mc.player.connection.sendCommand("wbwverify stop");
            mc.stop();
        }
    }

    private static void use(Minecraft mc) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(POS), Direction.UP, POS, false));
    }
    private static void next(int step) { stage = step; since = ticks; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks.add(message); }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static int number(Object target, String name) throws Exception { return ((Number) invoke(target, name)).intValue(); }
    private static void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(OUT.resolve(name)); }
    }
}
