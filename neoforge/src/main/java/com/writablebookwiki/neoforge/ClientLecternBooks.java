package com.writablebookwiki.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.network.chat.Component;

/** Waits for vanilla menu slot sync, then replaces only Wiki-book lecterns with our reader. */
final class ClientLecternBooks {
    private static LoadingScreen pending;
    private static long pendingRequest;

    private ClientLecternBooks() { }

    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LecternScreen vanilla
                && LecternBookSupport.isWikiBook(vanilla.getMenu().getBook())) {
            BookStackData.read(vanilla.getMenu().getBook()).ifPresent(pointer -> {
                pending = new LoadingScreen(new LecternDocumentSession(vanilla.getMenu(), pointer));
                minecraft.setScreen(pending);
                pendingRequest = BookNetwork.requestLecternOpen(vanilla.getMenu().containerId, pointer);
            });
        }
        if (minecraft.screen instanceof LoadingScreen loading && !loading.session.isValid())
            minecraft.setScreen(null);
    }

    static boolean receive(BookNetwork.ReceivedBook book) {
        if (pending == null || pendingRequest != book.requestId()) return false;
        LoadingScreen opening = pending;
        pending = null;
        // A delayed reply must never reopen a lectern after Escape, removal or another menu.
        if (Minecraft.getInstance().screen == opening && opening.session.isValid()) {
            opening.handedOff = true;
            WikiWindow.openDocument(book.document(), opening.session);
        }
        return true;
    }

    static void failed(long requestId, String reason) {
        if (pending != null && pendingRequest == requestId) pending.message = reason;
    }

    static void disconnect() { pending = null; pendingRequest = 0; }

    private static final class LoadingScreen extends Screen {
        private final LecternDocumentSession session;
        private String message = "正在读取讲台中的书…";
        private boolean handedOff;

        private LoadingScreen(LecternDocumentSession session) {
            super(Component.literal("讲台"));
            this.session = session;
        }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                    .bounds(width / 2 - 85, height / 2 + 20, 80, 20).build());
            if (session.canTakeBook()) addRenderableWidget(Button.builder(Component.translatable("lectern.take_book"),
                    button -> session.takeBook()).bounds(width / 2 + 5, height / 2 + 20, 80, 20).build());
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(width / 2 - 170, height / 2 - 50, width / 2 + 170, height / 2 + 55, 0xF0EFE3C5);
            graphics.drawCenteredString(font, message, width / 2, height / 2 - 10, 0x50412F);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override public void removed() {
            if (!handedOff) session.close();
            super.removed();
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}
