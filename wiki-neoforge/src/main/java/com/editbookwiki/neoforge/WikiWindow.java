package com.editbookwiki.neoforge;

import com.editbookwiki.core.document.WikiDocument;
import com.editbookwiki.core.document.WikiDocumentEditor;
import com.editbookwiki.local.LocalDocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Opens the native Minecraft document host.
 *
 * <p>The document is presented by {@link NativeWikiScreen}, which keeps the
 * editor and save session intact for its whole lifetime.</p>
 */
final class WikiWindow {
    private static final Path PREVIEW_DOCUMENT_FILE = FMLPaths.CONFIGDIR.get()
            .resolve(EditBookWikiMod.MOD_ID).resolve("preview-document.ewd");

    private static WikiWindow activeWindow;

    private final WikiDocumentEditor editor;
    private final DocumentSession session;
    private DocumentMode mode;
    private final NativeWikiScreen gameScreen;
    private int pageIndex;

    private WikiWindow(WikiDocumentEditor editor, DocumentSession session, DocumentMode mode, int requestedPage) {
        this.editor = editor;
        this.session = session;
        this.mode = mode;
        this.pageIndex = clampPage(requestedPage);
        this.gameScreen = new NativeWikiScreen(editor, session, mode, pageIndex, this::onGamePageChanged,
                this::onGameScreenClosed);
    }

    static void openReader(WikiDocument fallbackDocument) {
        openDocument(loadPreviewDocument(fallbackDocument), new LocalPreviewSession());
    }

    /** Opens a world-backed document without touching the local F8 preview file. */
    static void openDocument(WikiDocument document, DocumentSession session) {
        WikiWindow candidate = new WikiWindow(new WikiDocumentEditor(document), session, DocumentMode.READ, 0);
        replaceActive(candidate);
    }

    /**
     * F7 is retained as the documented host-switch key, but a standalone
     * renderer is not enabled until its independent GLFW context test is safe.
     */
    static void toggleWindowHost() {
        notifyStatus("独立窗口渲染正在验证；当前使用游戏内窗口");
    }

    private static void replaceActive(WikiWindow candidate) {
        WikiWindow previous = activeWindow;
        // Assign first: a Screen#removed callback from the old host must not
        // clear the native host we are about to show.
        activeWindow = candidate;
        if (previous != null) {
            previous.closeQuietlyForReplacement();
        }
        if (!candidate.open()) {
            if (activeWindow == candidate) {
                activeWindow = null;
            }
            return;
        }
    }

    private boolean open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(gameScreen);
        return minecraft.screen == gameScreen;
    }

    private boolean isOpen() {
        return Minecraft.getInstance().screen == gameScreen;
    }

    /** Reserved for the native edit toolbar: mode changes never make a new document. */
    private void switchMode() {
        if (mode == DocumentMode.READ && !session.canEdit()) {
            notifyStatus("此书当前没有编辑权限");
            return;
        }
        editor.finishTextEdit();
        mode = mode == DocumentMode.READ ? DocumentMode.EDIT : DocumentMode.READ;
        gameScreen.setDocumentMode(mode);
    }

    private void onGamePageChanged(int changedPage) {
        pageIndex = clampPage(changedPage);
    }

    private void onGameScreenClosed() {
        if (activeWindow == this) {
            activeWindow = null;
        }
    }

    private void closeQuietlyForReplacement() {
        if (Minecraft.getInstance().screen == gameScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    private int clampPage(int requestedPage) {
        int pages = NativeDocumentPresentation.pageCount(editor.document());
        return Math.max(0, Math.min(requestedPage, pages - 1));
    }

    @SuppressWarnings("unused") // Connected when the native edit toolbar arrives.
    private String saveDocument() {
        editor.finishTextEdit();
        String status = session.save(editor.document());
        notifyStatus(status);
        return status;
    }

    private static WikiDocument loadPreviewDocument(WikiDocument fallbackDocument) {
        try {
            return new LocalDocumentStore(PREVIEW_DOCUMENT_FILE).load().orElse(fallbackDocument);
        } catch (IOException exception) {
            // A malformed/interrupted local preview cannot prevent a physical
            // world book from being opened later.
            return fallbackDocument;
        }
    }

    private static void notifyStatus(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static final class LocalPreviewSession implements DocumentSession {
        @Override
        public boolean canEdit() {
            return true;
        }

        @Override
        public String save(WikiDocument document) {
            return saveConfirmed(document).join().message();
        }

        @Override
        public java.util.concurrent.CompletableFuture<SaveConfirmation> saveConfirmed(WikiDocument document) {
            SaveConfirmation result;
            try {
                new LocalDocumentStore(PREVIEW_DOCUMENT_FILE).save(document);
                result = new SaveConfirmation(true, "已保存到本地预览");
            } catch (IOException exception) {
                result = new SaveConfirmation(false, "保存失败：本地文件不可写，编辑内容仍保留");
            }
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }

        @Override
        public com.editbookwiki.local.ImportedImageAsset importLocalImage(Path source) throws IOException {
            return new com.editbookwiki.local.LocalImageAssetStore(PREVIEW_DOCUMENT_FILE.getParent()).importImage(source);
        }
    }
}
