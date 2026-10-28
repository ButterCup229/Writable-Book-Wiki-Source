package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.document.WikiDocument;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.InteractionHand;
import com.writablebookwiki.local.ImportedImageAsset;
import com.writablebookwiki.local.LocalImageAssetStore;
import java.io.IOException;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** One physical-book editing session; server acknowledgement alone advances its pointer. */
final class WorldBookSession implements DocumentSession {
    private final InteractionHand hand;
    private BookPointer pointer;
    private final boolean canEdit;
    private final boolean canChangeLock;
    private boolean connected = true;
    private long saveRequest;
    private CompletableFuture<SaveConfirmation> pendingSave;

    WorldBookSession(InteractionHand hand, BookPointer pointer, boolean canEdit,
            boolean canChangeLock) {
        this.hand = hand;
        this.pointer = pointer;
        this.canEdit = canEdit;
        this.canChangeLock = canChangeLock;
    }

    @Override
    public boolean canEdit() {
        return connected && canEdit;
    }

    @Override
    public String save(WikiDocument document) {
        saveConfirmed(document);
        return "正在向世界存档保存…";
    }

    @Override
    public CompletableFuture<SaveConfirmation> saveConfirmed(WikiDocument document) {
        if (!connected) return CompletableFuture.completedFuture(
                new SaveConfirmation(false, "已断开连接，请重新打开实体书"));
        if (pendingSave != null && !pendingSave.isDone())
            return CompletableFuture.completedFuture(new SaveConfirmation(false, "前一次保存尚未完成，请稍后重试"));
        long request = BookNetwork.requestSave(hand, pointer, document);
        return awaitConfirmation(request);
    }

    BookPointer pointer() { return pointer; }

    boolean awaits(long request, InteractionHand requestedHand) {
        return connected && hand == requestedHand && request == saveRequest
                && pendingSave != null && !pendingSave.isDone();
    }

    void acceptSave(BookNetwork.SavedBook saved) {
        if (!awaits(saved.requestId(), saved.hand())) return;
        pointer = saved.pointer();
        confirmSave(saved.requestId(), true, "已保存到世界存档");
    }

    private CompletableFuture<SaveConfirmation> awaitConfirmation(long request) {
        saveRequest = request;
        pendingSave = new CompletableFuture<>();
        ClientBookSessions.track(request, this);
        pendingSave.orTimeout(90, TimeUnit.SECONDS)
                .whenComplete((result, error) -> ClientBookSessions.forget(request, this));
        return pendingSave;
    }

    void confirmSave(long request, boolean success, String message) {
        if (request == saveRequest && pendingSave != null && !pendingSave.isDone())
            pendingSave.complete(new SaveConfirmation(success, message));
    }

    @Override
    public boolean canChangeLock() {
        return connected && canChangeLock;
    }

    @Override
    public boolean isLocked() {
        return pointer.locked();
    }

    @Override
    public String changeLock(boolean locked) {
        if (!connected) return "已断开连接，请重新打开实体书";
        if (pendingSave != null && !pendingSave.isDone()) return "前一次操作尚未完成，请稍后重试";
        long request = BookNetwork.requestLockState(hand, pointer, locked);
        awaitConfirmation(request);
        return locked ? "正在锁定这一本实体书…" : "正在解除这一本实体书的锁定…";
    }

    @Override
    public String personalAnnotationKey() {
        // A copy-on-write save changes this book ID, so annotations stay
        // with the exact version the reader was commenting on.
        return pointer.bookId().toString();
    }

    @Override
    public ImportedImageAsset importLocalImage(Path source) throws IOException {
        if (!connected) throw new IOException("已断开连接，请重新打开实体书");
        ImportedImageAsset imported = new LocalImageAssetStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get())).importImage(source);
        BookNetwork.requestImageUpload(hand, pointer, imported);
        return imported;
    }

    void disconnect() {
        connected = false;
        if (pendingSave != null && !pendingSave.isDone()) {
            pendingSave.complete(new SaveConfirmation(false, "连接已断开，尚未收到服务器保存确认"));
        }
    }
}
