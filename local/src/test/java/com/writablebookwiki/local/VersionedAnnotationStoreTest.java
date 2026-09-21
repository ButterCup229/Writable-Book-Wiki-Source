package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionedAnnotationStoreTest {
    @TempDir Path directory;
    private static final String ORIGINAL = "a".repeat(64);
    private static final String DRAFT = "b".repeat(64);

    @Test void draftAndOriginalVersionDoNotOverwriteEachOther() throws Exception {
        var store = new LocalAnnotationStore(directory);
        var original = List.of(new LocalTextAnnotation(0, 1, 0, 3, "", "old"));
        var draft = List.of(new LocalTextAnnotation(0, 4, 0, 6, "", "new"));
        store.saveTextAnnotations(LocalAnnotationStore.versionKey("book", ORIGINAL), original);
        store.saveTextAnnotations(LocalAnnotationStore.versionKey("book", DRAFT), draft);
        assertEquals(original, store.loadVersionedTextAnnotations("book", ORIGINAL));
        assertEquals(draft, store.loadVersionedTextAnnotations("book", DRAFT));
    }

    @Test void forkCopiesOnlyAcknowledgedVersionWithoutChangingOriginal() throws Exception {
        var store = new LocalAnnotationStore(directory);
        var old = List.of(new LocalTextAnnotation(0, 1, 0, 3, "", "old"));
        var next = List.of(new LocalTextAnnotation(1, 1, 1, 3, "", "new"));
        store.saveTextAnnotations(LocalAnnotationStore.versionKey("old-book", ORIGINAL), old);
        store.saveTextAnnotations(LocalAnnotationStore.versionKey("old-book", DRAFT), next);
        store.copyVersionedTextAnnotations("old-book", "new-book", DRAFT);
        assertEquals(next, store.loadVersionedTextAnnotations("new-book", DRAFT));
        assertEquals(old, store.loadVersionedTextAnnotations("old-book", ORIGINAL));
    }

    @Test void migratedLegacyCommentsStayDeletedAfterReopening() throws Exception {
        var store = new LocalAnnotationStore(directory);
        store.saveTextAnnotations("legacy", List.of(new LocalTextAnnotation(0, 0, 0, 1, "", "legacy comment")));
        assertEquals(1, store.loadVersionedTextAnnotations("legacy", ORIGINAL).size());
        store.saveTextAnnotations(LocalAnnotationStore.versionKey("legacy", ORIGINAL), List.of());
        assertTrue(store.loadVersionedTextAnnotations("legacy", ORIGINAL).isEmpty());
        store.copyVersionedTextAnnotations("legacy", "fork", ORIGINAL);
        assertTrue(store.loadVersionedTextAnnotations("fork", ORIGINAL).isEmpty());
    }
}
