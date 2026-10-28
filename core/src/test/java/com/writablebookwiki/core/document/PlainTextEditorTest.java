package com.writablebookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlainTextEditorTest {
    @Test void selectAllDoesNotDeleteAndTypingReplacesSelection() {
        var text = new PlainTextEditor();
        text.reset("comment");
        text.selectAll();
        assertEquals("comment", text.text());
        assertEquals("comment", text.selectedText());
        text.replaceSelection("replacement");
        assertEquals("replacement", text.text());
        text.undo();
        assertEquals("comment", text.text());
        assertEquals("comment", text.selectedText());
    }
    @Test void clickStartsAnotherUndoUnitAndHistoryIsBounded() {
        var text = new PlainTextEditor();
        for (int i = 0; i < 15; i++) {
            text.moveTo(text.text().length(), false);
            text.replaceSelection("x");
        }
        int undos = 0;
        while (text.undo()) undos++;
        assertEquals(10, undos);
        assertEquals("xxxxx", text.text());
    }
    @Test void dragSelectionCutAndUndoUseExactRange() {
        var text = new PlainTextEditor();
        text.reset("abcdef");
        text.moveTo(1, false);
        text.moveTo(4, true);
        assertEquals("bcd", text.selectedText());
        text.replaceSelection("");
        assertEquals("aef", text.text());
        text.undo();
        assertEquals("abcdef", text.text());
        text.redo();
        assertEquals("aef", text.text());
    }
    @Test void hitTestAndVerticalMovementCannotPlaceCaretInsideGrapheme() {
        var text = new PlainTextEditor();
        text.reset("Ae\u0301👩‍💻B");
        text.moveTo(2, false);
        assertEquals(1, text.caret());
        text.delete(1, false);
        assertEquals("A👩‍💻B", text.text());
        text.undo();
        for (int offset = 4; offset < 8; offset++) {
            text.moveTo(offset, false);
            assertEquals(3, text.caret());
        }
        text.moveTo(8, true);
        assertEquals("👩‍💻", text.selectedText());
        text.replaceSelection("new");
        assertEquals("Ae\u0301newB", text.text());
    }

    @Test void deleteNeverSplitsEmojiOrCombiningCharacter() {
        var text = new PlainTextEditor();
        text.reset("e\u0301👩‍💻");
        text.delete(-1, false);
        assertEquals("e\u0301", text.text());
        text.delete(-1, false);
        assertEquals("", text.text());
    }
}
