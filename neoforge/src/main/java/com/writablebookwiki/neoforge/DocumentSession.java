package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.local.ImportedImageAsset;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The ownership and save destination of the one document currently shown by
 * {@link WikiWindow}.  It is deliberately independent from the canvas: read
 * and edit still use the same {@link WikiDocument} and the same renderer.
 */
interface DocumentSession {
    /** Optional live-container lifecycle. Ordinary held/local documents have no container. */
    default boolean isValid() { return true; }
    default int sharedPage() { return -1; }
    default void changePage(int page) { }
    default boolean canTakeBook() { return false; }
    default void takeBook() { }
    default void close() { }

    /** Whether the current holder may enter edit mode. */
    boolean canEdit();

    /** Persists a finished editor snapshot and returns a player-facing status. */
    String save(WikiDocument document);

    /** Completes only when the destination has accepted the snapshot, not when packets were queued. */
    default java.util.concurrent.CompletableFuture<SaveConfirmation> saveConfirmed(WikiDocument document) {
        return java.util.concurrent.CompletableFuture.completedFuture(new SaveConfirmation(true, save(document)));
    }

    record SaveConfirmation(boolean success, String message) { }

    /** Whether this client may request the server-authoritative lock action. */
    default boolean canChangeLock() {
        return false;
    }

    /** The current physical copy's lock state, for status text and the toggle label. */
    default boolean isLocked() {
        return false;
    }

    /** Requests a lock-only update. It must never rewrite the document body. */
    default String changeLock(boolean locked) {
        return "当前文档不支持锁定操作";
    }

    /**
     * Copies a chosen local image into this session's formal asset store.
     * World-backed sessions must override this only together with their
     * server-authoritative resource upload path; silently using a player's
     * private config folder would make a shared book lose its pictures.
     */
    default ImportedImageAsset importLocalImage(Path source) throws IOException {
        throw new IOException("当前文档尚未启用图片资源导入");
    }

    /**
     * Stable local-only namespace for personal reader annotations.  It is
     * never sent through {@link #save(WikiDocument)} and deliberately has no
     * relation to the shared document payload.
     */
    default String personalAnnotationKey() {
        return "preview";
    }
}
