package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.book.SaveOutcome;
import com.writablebookwiki.local.LocalAnnotationStore;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Client-main-thread session coordinator. Only timeout removal touches the map off-thread. */
final class ClientBookSessions {
    private static WorldBookSession activeSession;
    private static final Map<Long, WorldBookSession> pendingOperations = new ConcurrentHashMap<>();

    private ClientBookSessions() { }

    static void installReceiver() {
        BookNetwork.setClientReceiver(new BookNetwork.ClientReceiver() {
            @Override
            public void onBookReceived(BookNetwork.ReceivedBook book) {
                if (ClientLecternBooks.receive(book)) return;
                activeSession = new WorldBookSession(book.hand(), book.pointer(), book.canEdit(),
                        book.canChangeLock());
                WikiWindow.openDocument(book.document(), activeSession);
            }

            @Override
            public void onBookOpenFailed(long requestId, String reason) {
                ClientLecternBooks.failed(requestId, reason);
                showStatus(reason);
            }

            @Override
            public void onBookSaved(BookNetwork.SavedBook book) {
                WorldBookSession savedSession = pendingOperations.remove(book.requestId());
                if (savedSession == null || !savedSession.awaits(book.requestId(), book.hand())) return;
                BookPointer originalPointer = savedSession.pointer();
                if (book.outcome() == SaveOutcome.FORKED
                        && !originalPointer.bookId().equals(book.pointer().bookId())) {
                    try {
                        new LocalAnnotationStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("annotations"))
                                .copyVersionedTextAnnotations(originalPointer.bookId().toString(),
                                        book.pointer().bookId().toString(), book.pointer().contentHash());
                    } catch (IOException ignored) {
                        // A world save is already authoritative.  A local
                        // annotation migration failure must never turn it
                        // into a failed book save.
                    }
                }
                savedSession.acceptSave(book);
                showStatus(switch (book.outcome()) {
                    case FORKED -> "已保存：这一本副本已成为新的版本。";
                    case UNCHANGED -> "内容没有变化，无需创建新版本。";
                    case LOCK_STATE_CHANGED -> "锁定状态已更新。";
                });
            }

            @Override
            public void onBookSaveFailed(long requestId, String reason) {
                WorldBookSession failedSession = pendingOperations.remove(requestId);
                if (failedSession == null) return;
                failedSession.confirmSave(requestId, false, "保存失败：" + reason);
                showStatus("保存失败：" + reason);
            }

            @Override
            public void onImageUploadResult(long requestId, String assetHash, boolean success, String reason) {
                showStatus(success ? "图片已保存到世界存档" : "图片上传失败：" + reason);
            }
        });
    }

    static void track(long request, WorldBookSession session) {
        pendingOperations.put(request, session);
    }

    static void forget(long request, WorldBookSession session) {
        pendingOperations.remove(request, session);
    }

    /** Called before clearing transport state so pending callers receive a terminal result. */
    static void disconnect() {
        ClientLecternBooks.disconnect();
        var sessions = new HashSet<>(pendingOperations.values());
        if (activeSession != null) sessions.add(activeSession);
        pendingOperations.clear();
        activeSession = null;
        sessions.forEach(WorldBookSession::disconnect);
    }

    private static void showStatus(String text) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), true);
        }
    }
}
