package com.writablebookwiki.local;

import com.writablebookwiki.core.document.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnnotationEditHistoryTest {
    @Test void insertBeforeAnchorAndSplitParagraphKeepTheOriginalTextMarked() {
        var editor = editor("abcDEFghi");
        var history = new AnnotationEditHistory();
        var notes = List.of(note(0, 3, 0, 6, "comment"));
        var before = editor.document();
        editor.replaceTextSelection(new TextSelection(0, 0, 0, 0), "x\ny");
        notes = history.rebase(before, editor.document(), notes, editor.retainedDocuments());
        assertEquals(note(1, 4, 1, 7, "comment"), notes.getFirst());
    }

    @Test void undoRestoresTheExactAnchorEvenAfterItsTextWasDeleted() {
        var editor = editor("abcDEFghi");
        var history = new AnnotationEditHistory();
        var original = List.of(note(0, 3, 0, 6, "comment"));
        var before = editor.document();
        editor.replaceTextSelection(new TextSelection(0, 2, 0, 8), "");
        var changed = editor.document();
        var mapped = history.rebase(before, changed, original, editor.retainedDocuments());
        assertTrue(editor.undo());
        assertEquals(original, history.rebase(changed, editor.document(), mapped, editor.retainedDocuments()));
    }

    @Test void sortingMovesCommentWithTheParagraphNotItsOldLineNumber() {
        var editor = editor("z");
        editor.replaceTextSelection(new TextSelection(0, 1, 0, 1), "\na");
        var before = editor.document();
        var history = new AnnotationEditHistory();
        editor.sortTextBlocks(new TextSelection(0, 0, 1, 1), true);
        var notes = history.rebase(before, editor.document(), List.of(note(0, 0, 0, 1, "z note")), editor.retainedDocuments());
        assertEquals(note(1, 0, 1, 1, "z note"), notes.getFirst());
    }

    @Test void bodyUndoNeverRestoresOldCommentTextAfterAnIndependentCommentEdit() {
        var editor = editor("abc");
        var history = new AnnotationEditHistory();
        var before = editor.document();
        editor.replaceTextSelection(new TextSelection(0, 0, 0, 0), "x");
        var changed = editor.document();
        history.rebase(before, changed, List.of(note(0, 0, 0, 3, "old")), editor.retainedDocuments());
        history.commentsChanged();
        editor.undo();
        var result = history.rebase(changed, editor.document(), List.of(note(0, 1, 0, 4, "new")), editor.retainedDocuments());
        assertEquals("new", result.getFirst().comment());
    }

    @Test void formattingOnlyDoesNotMoveAnchor() {
        var editor = editor("abcDEF");
        var before = editor.document();
        editor.setFontSize(new TextSelection(0, 0, 0, 6), 24);
        var note = note(0, 3, 0, 6, "comment");
        var history = new AnnotationEditHistory();
        assertEquals(List.of(note), history.rebase(before, editor.document(), List.of(note), editor.retainedDocuments()));
    }

    private static LocalTextAnnotation note(int b, int c, int e, int f, String text) {
        return new LocalTextAnnotation(b, c, e, f, "", text);
    }
    private static WikiDocumentEditor editor(String text) {
        var author = new UUID(0, 1);
        return new WikiDocumentEditor(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "title",
                List.of(new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)))),
                new DocumentMetadata(author, "a", Instant.EPOCH, author, "a", Instant.EPOCH)));
    }
}
