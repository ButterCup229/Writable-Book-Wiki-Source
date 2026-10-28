package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.document.WikiDocument;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.LecternMenu;

/** Read-only document backed by the real vanilla menu; personal comments remain local. */
final class LecternDocumentSession implements DocumentSession {
    private final LecternMenu menu;
    private final BookPointer pointer;

    LecternDocumentSession(LecternMenu menu, BookPointer pointer) {
        this.menu = menu;
        this.pointer = pointer;
    }

    @Override public boolean canEdit() { return false; }
    @Override public boolean isLocked() { return pointer.locked(); }
    @Override public String personalAnnotationKey() { return pointer.bookId().toString(); }
    @Override public String save(WikiDocument document) { return "讲台上的书仅供阅读，请先取回手中再编辑"; }
    @Override public CompletableFuture<SaveConfirmation> saveConfirmed(WikiDocument document) {
        return CompletableFuture.completedFuture(new SaveConfirmation(false, save(document)));
    }

    @Override public boolean isValid() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isAlive() && player.containerMenu == menu
                && BookStackData.read(menu.getBook()).filter(pointer::equals).isPresent();
    }

    @Override public int sharedPage() { return menu.getPage(); }

    @Override public void changePage(int page) {
        if (!isValid() || page == menu.getPage()) return;
        // Optimistic page data prevents an old local value from undoing this click
        // while the server response is in flight. Other readers still receive server data.
        menu.setData(0, page);
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 100 + page);
    }

    @Override public boolean canTakeBook() {
        var player = Minecraft.getInstance().player;
        return isValid() && player.mayBuild();
    }

    @Override public void takeBook() {
        if (canTakeBook()) Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, 3);
    }

    @Override public void close() {
        var player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu != menu) return;
        // Called by Screen.removed: do not call closeContainer(), which would recursively setScreen(null).
        player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
        menu.removed(player);
        player.containerMenu = player.inventoryMenu;
    }
}
