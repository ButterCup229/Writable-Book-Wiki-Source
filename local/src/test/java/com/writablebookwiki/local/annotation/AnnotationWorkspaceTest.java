package com.writablebookwiki.local.annotation;

import com.writablebookwiki.core.document.*;
import com.writablebookwiki.local.LocalAnnotationStore;
import com.writablebookwiki.local.LocalTextAnnotation;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationWorkspaceTest {
    private static final String NAMESPACE = "personal-book";
    private static final class MemoryStorage implements AnnotationWorkspace.Storage {
        List<LocalTextAnnotation> initial = List.of();
        final Map<String, List<LocalTextAnnotation>> saved = new HashMap<>();
        boolean failLoad, failSave;
        int writes;
        public List<LocalTextAnnotation> load(String namespace, String hash) throws IOException {
            if (failLoad) throw new IOException("Unreadable");
            return initial;
        }
        public void save(String key, List<LocalTextAnnotation> entries) throws IOException {
            if (failSave) throw new IOException("Disk unavailable");
            saved.put(key, List.copyOf(entries));
            writes++;
        }
    }

    @Test void failedCloseKeepsDraftUndoAndAcceptedEntriesUntilRetry() throws Exception {
        var storage = new MemoryStorage();
        storage.initial = List.of(note(1, 4, "original"));
        var body = editor();
        var workspace = new AnnotationWorkspace(body.document(), NAMESPACE, storage);
        workspace.edit(0);
        workspace.input().selectAll();
        workspace.input().replaceSelection("replacement");
        storage.failSave = true;
        assertThrows(IOException.class, () -> workspace.close(NAMESPACE));
        assertTrue(workspace.editing());
        assertTrue(workspace.draftChanged());
        assertEquals("replacement", workspace.input().text());
        assertEquals(storage.initial, workspace.entries());
        assertTrue(workspace.input().undo());
        assertEquals("original", workspace.input().text());
        assertTrue(workspace.input().redo());
        storage.failSave = false;
        workspace.close(NAMESPACE);
        assertFalse(workspace.editing());
        assertEquals(List.of(note(1, 4, "replacement")), storage.saved.get(key(body.document())));
    }

    @Test void failedDeletionNeverRemovesAnAcceptedComment() throws Exception {
        var storage = new MemoryStorage();
        storage.initial = List.of(note(1, 4, "original"));
        var workspace = new AnnotationWorkspace(editor().document(), NAMESPACE, storage);
        workspace.edit(0);
        workspace.input().selectAll();
        workspace.input().replaceSelection("");
        storage.failSave = true;
        assertThrows(IOException.class, () -> workspace.persistDraft(NAMESPACE));
        assertEquals(storage.initial, workspace.entries());
        storage.failSave = false;
        workspace.close(NAMESPACE);
        assertTrue(workspace.entries().isEmpty());
    }

    @Test void unreadableStoreIsNeverOverwrittenByEmptyOrNewComments() {
        var storage = new MemoryStorage();
        storage.failLoad = true;
        var workspace = new AnnotationWorkspace(editor().document(), NAMESPACE, storage);
        assertTrue(workspace.loadFailed());
        assertThrows(IOException.class, () -> workspace.saveVersion(NAMESPACE));
        workspace.begin(new AnnotationWorkspace.Anchor(0, 1, 0, 4, ""));
        workspace.input().replaceSelection("draft");
        assertThrows(IOException.class, () -> workspace.close(NAMESPACE));
        assertEquals("draft", workspace.input().text());
        assertEquals(0, storage.writes);
    }

    @Test void unchangedFocusSwitchesDoNotWriteOrChangeSharedContent() throws Exception {
        var body = editor();
        var original = body.document();
        var storage = new MemoryStorage();
        storage.initial = List.of(note(1, 4, "existing"));
        var workspace = new AnnotationWorkspace(original, NAMESPACE, storage);
        workspace.edit(0);
        workspace.close(NAMESPACE);
        workspace.begin(new AnnotationWorkspace.Anchor(0, 0, 0, 1, "source text must not appear"));
        assertEquals("", workspace.input().text());
        assertEquals("", workspace.anchor().quotedText());
        workspace.close(NAMESPACE);
        assertEquals(0, storage.writes);
        assertSame(original, body.document());
        assertEquals(key(original), key(body.document()));
    }

    @Test void sortedDraftStillEditsTheCorrectCommentAfterEveryKeystroke() throws Exception {
        var storage = new MemoryStorage();
        storage.initial = List.of(note(5, 7, "later"));
        var workspace = new AnnotationWorkspace(editor().document(), NAMESPACE, storage);
        workspace.begin(new AnnotationWorkspace.Anchor(0, 1, 0, 2, ""));
        workspace.input().replaceSelection("first");
        workspace.persistDraft(NAMESPACE);
        assertEquals(0, workspace.editingIndex());
        workspace.input().replaceSelection("!");
        workspace.close(NAMESPACE);
        assertEquals(List.of(note(1, 2, "first!"), note(5, 7, "later")), workspace.entries());
    }

    @Test void anchorsRestoreOnUndoAndOnlyCurrentVersionIsSaved() throws Exception {
        var body = editor();
        var original = body.document();
        var storage = new MemoryStorage();
        storage.initial = List.of(note(3, 6, "comment"));
        var workspace = new AnnotationWorkspace(original, NAMESPACE, storage);
        workspace.saveVersion(NAMESPACE);
        body.replaceTextSelection(new TextSelection(0, 2, 0, 8), "");
        var shortened = body.document();
        workspace.rebase(shortened, body.retainedDocuments());
        workspace.saveVersion(NAMESPACE);
        assertEquals(storage.initial, storage.saved.get(key(original)));
        assertNotEquals(storage.initial, storage.saved.get(key(shortened)));
        body.undo();
        workspace.rebase(body.document(), body.retainedDocuments());
        assertEquals(storage.initial, workspace.entries());
        assertEquals(2, storage.writes);
    }

    @Test void activeExistingDraftTracksExactHistoricalAnchorAfterDeletionAndUndo() throws Exception {
        var body = editor();
        var storage = new MemoryStorage();
        storage.initial = List.of(note(3, 6, "comment"));
        var workspace = new AnnotationWorkspace(body.document(), NAMESPACE, storage);
        body.replaceTextSelection(new TextSelection(0, 2, 0, 8), "");
        workspace.rebase(body.document(), body.retainedDocuments());
        workspace.edit(0);
        workspace.input().selectAll();
        workspace.input().replaceSelection("revised");
        body.undo();
        workspace.rebase(body.document(), body.retainedDocuments());
        assertEquals(0, workspace.editingIndex());
        assertEquals(new AnnotationWorkspace.Anchor(0, 3, 0, 6, ""), workspace.anchor());
        workspace.close(NAMESPACE);
        assertEquals(List.of(note(3, 6, "revised")), workspace.entries());
    }

    @Test void activeNewDraftSurvivesRebaseUndoWithoutDuplicatingAcceptedEntries() throws Exception {
        var body = editor();
        var workspace = new AnnotationWorkspace(body.document(), NAMESPACE, new MemoryStorage());
        workspace.begin(new AnnotationWorkspace.Anchor(0, 3, 0, 6, ""));
        workspace.input().replaceSelection("draft");
        body.replaceTextSelection(new TextSelection(0, 2, 0, 8), "");
        workspace.rebase(body.document(), body.retainedDocuments());
        body.undo();
        workspace.rebase(body.document(), body.retainedDocuments());
        workspace.close(NAMESPACE);
        assertEquals(List.of(note(3, 6, "draft")), workspace.entries());
    }

    @Test void activeCommentFollowsItsParagraphWhenVisibleSortOrderChangesAndUndoRestoresIt() throws Exception {
        var body = editor();
        body.replaceTextSelection(new TextSelection(0, 0, 0, 9), "zeta\nalpha");
        body.finishTextEdit();
        var storage = new MemoryStorage();
        storage.initial = List.of(note(0, 4, "z comment"),
                new LocalTextAnnotation(1, 0, 1, 5, "", "a comment"));
        var workspace = new AnnotationWorkspace(body.document(), NAMESPACE, storage);
        workspace.edit(0);
        body.sortTextBlocks(new TextSelection(0, 0, 1, 5), true);
        workspace.rebase(body.document(), body.retainedDocuments());
        assertEquals(1, workspace.editingIndex());
        assertEquals(1, workspace.anchor().startBlock());
        body.undo();
        workspace.rebase(body.document(), body.retainedDocuments());
        assertEquals(0, workspace.editingIndex());
        workspace.input().selectAll();
        workspace.input().replaceSelection("updated z");
        workspace.close(NAMESPACE);
        assertEquals(List.of(note(0, 4, "updated z"),
                new LocalTextAnnotation(1, 0, 1, 5, "", "a comment")), workspace.entries());
    }

    @Test void confirmedNamespaceChangeUsesNewKeyWithoutChangingOldVersion() throws Exception {
        var body = editor();
        var storage = new MemoryStorage();
        storage.initial = List.of(note(1, 4, "comment"));
        var workspace = new AnnotationWorkspace(body.document(), NAMESPACE, storage);
        workspace.saveVersion(NAMESPACE);
        workspace.edit(0);
        workspace.input().selectAll();
        workspace.input().replaceSelection("fork comment");
        workspace.close("fork-book");
        assertEquals(storage.initial, storage.saved.get(key(body.document())));
        String forkKey = LocalAnnotationStore.versionKey("fork-book", new WikiDocumentCodec().contentHash(body.document()));
        assertEquals(List.of(note(1, 4, "fork comment")), storage.saved.get(forkKey));
    }

    private static LocalTextAnnotation note(int start, int end, String text) {
        return new LocalTextAnnotation(0, start, 0, end, "", text);
    }
    private static String key(WikiDocument document) {
        return LocalAnnotationStore.versionKey(NAMESPACE, new WikiDocumentCodec().contentHash(document));
    }
    private static WikiDocumentEditor editor() {
        var author = new UUID(0, 1);
        return new WikiDocumentEditor(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "title",
                List.of(new Paragraph(List.of(new TextRun("abcDEFghi", TextStyle.DEFAULT)))),
                new DocumentMetadata(author, "a", Instant.EPOCH, author, "a", Instant.EPOCH)));
    }
}
