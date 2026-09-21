package com.editbookwiki.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.editbookwiki.core.book.BookPointer;
import com.editbookwiki.local.ImportedImageAsset;
import com.editbookwiki.local.LocalImageAssetStore;
import com.editbookwiki.local.LocalAnnotationStore;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = EditBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EditBookWikiClientModEvents {
    private static WorldBookSession activeWorldBookSession;
    private static final java.util.Map<Long, WorldBookSession> pendingOperations = new java.util.concurrent.ConcurrentHashMap<>();
    static final KeyMapping OPEN_READER = new KeyMapping(
            "key.editbookwiki.open_reader", InputConstants.Type.KEYSYM, InputConstants.KEY_F8,
            "key.categories.editbookwiki");
    static final KeyMapping TOGGLE_WINDOW_HOST = new KeyMapping(
            "key.editbookwiki.toggle_window_host", InputConstants.Type.KEYSYM, InputConstants.KEY_F7,
            "key.categories.editbookwiki");

    private EditBookWikiClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_READER);
        event.register(TOGGLE_WINDOW_HOST);
        BookNetwork.setClientReceiver(new BookNetwork.ClientReceiver() {
            @Override
            public void onBookReceived(BookNetwork.ReceivedBook book) {
                activeWorldBookSession = new WorldBookSession(book.hand(), book.pointer(), book.canEdit(),
                        book.canChangeLock());
                WikiWindow.openDocument(book.document(), activeWorldBookSession);
            }

            @Override
            public void onBookOpenFailed(long requestId, String reason) {
                showStatus(reason);
            }

            @Override
            public void onBookSaved(BookNetwork.SavedBook book) {
                WorldBookSession savedSession = pendingOperations.remove(book.requestId());
                if (savedSession != null && savedSession.hand == book.hand()) {
                    BookPointer originalPointer = savedSession.pointer;
                    if (book.outcome() == BookNetwork.SaveOutcome.FORKED
                            && !originalPointer.bookId().equals(book.pointer().bookId())) {
                        try {
                            new LocalAnnotationStore(FMLPaths.CONFIGDIR.get()
                                    .resolve(EditBookWikiMod.MOD_ID).resolve("annotations"))
                                    .copyVersionedTextAnnotations(originalPointer.bookId().toString(),
                                            book.pointer().bookId().toString(), book.pointer().contentHash());
                        } catch (IOException ignored) {
                            // A world save is already authoritative.  A local
                            // annotation migration failure must never turn it
                            // into a failed book save.
                        }
                    }
                    savedSession.pointer = book.pointer();
                    savedSession.confirmSave(book.requestId(), true, "已保存到世界存档");
                }
                showStatus(switch (book.outcome()) {
                    case FORKED -> "已保存：这一本副本已成为新的版本。";
                    case UNCHANGED -> "内容没有变化，无需创建新版本。";
                    case LOCK_STATE_CHANGED -> "锁定状态已更新。";
                });
            }

            @Override
            public void onBookSaveFailed(long requestId, String reason) {
                WorldBookSession failedSession = pendingOperations.remove(requestId);
                if (failedSession != null) failedSession.confirmSave(requestId, false, "保存失败：" + reason);
                showStatus("保存失败：" + reason);
            }

            @Override
            public void onImageUploadResult(long requestId, String assetHash, boolean success, String reason) {
                showStatus(success ? "图片已保存到世界存档" : "图片上传失败：" + reason);
            }
        });
    }

    private static void showStatus(String text) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), true);
        }
    }

    private static final class WorldBookSession implements DocumentSession {
        private final net.minecraft.world.InteractionHand hand;
        private BookPointer pointer;
        private final boolean canEdit;
        private final boolean canChangeLock;
        private long saveRequest;
        private java.util.concurrent.CompletableFuture<SaveConfirmation> pendingSave;

        private WorldBookSession(net.minecraft.world.InteractionHand hand, BookPointer pointer, boolean canEdit,
                boolean canChangeLock) {
            this.hand = hand;
            this.pointer = pointer;
            this.canEdit = canEdit;
            this.canChangeLock = canChangeLock;
        }

        @Override
        public boolean canEdit() {
            return canEdit;
        }

        @Override
        public String save(com.editbookwiki.core.document.WikiDocument document) {
            saveConfirmed(document);
            return "正在向世界存档保存…";
        }

        @Override
        public java.util.concurrent.CompletableFuture<SaveConfirmation> saveConfirmed(com.editbookwiki.core.document.WikiDocument document) {
            if (pendingSave != null && !pendingSave.isDone())
                return java.util.concurrent.CompletableFuture.completedFuture(new SaveConfirmation(false, "前一次保存尚未完成，请稍后重试"));
            long request = BookNetwork.requestSave(hand, pointer, document);
            saveRequest = request;
            pendingSave = new java.util.concurrent.CompletableFuture<>();
            pendingOperations.put(request, this);
            pendingSave.orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((result, error) -> pendingOperations.remove(request, this));
            return pendingSave;
        }

        private void confirmSave(long request, boolean success, String message) {
            if (request == saveRequest && pendingSave != null && !pendingSave.isDone())
                pendingSave.complete(new SaveConfirmation(success, message));
        }

        @Override
        public boolean canChangeLock() {
            return canChangeLock;
        }

        @Override
        public boolean isLocked() {
            return pointer.locked();
        }

        @Override
        public String changeLock(boolean locked) {
            if (pendingSave != null && !pendingSave.isDone()) return "正在保存，请完成后再修改锁定状态";
            long request = BookNetwork.requestLockState(hand, pointer, locked);
            pendingOperations.put(request, this);
            java.util.concurrent.CompletableFuture.delayedExecutor(90, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> pendingOperations.remove(request, this));
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
            ImportedImageAsset imported = new LocalImageAssetStore(FMLPaths.CONFIGDIR.get()
                    .resolve(EditBookWikiMod.MOD_ID)).importImage(source);
            BookNetwork.requestImageUpload(hand, pointer, imported);
            return imported;
        }
    }
}
