package com.editbookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WordEditingRegressionTest {
    private static final TextStyle RED_BOLD = new TextStyle(true, false, false, 0xff0000, 22);

    @Test void typingStyleIsAppliedToAllPastedParagraphs() {
        var editor = editor("abc");
        editor.replaceTextSelection(new TextSelection(0, 1, 0, 2), "X\nY\nZ", RED_BOLD);
        assertEquals(List.of("aX", "Y", "Zc"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
        assertEquals(RED_BOLD, editor.document().blocks().get(1).runs().getFirst().style());
        assertTrue(editor.undo());
        assertEquals("abc", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
    }

    @Test void enterOverSelectionReplacesItAndPreservesSurroundingFormatting() {
        var editor = editor("abcdef");
        editor.replaceTextSelection(new TextSelection(0, 2, 0, 5), "\n", RED_BOLD);
        assertEquals(List.of("ab", "f"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
    }

    @Test void repeatedCharactersAreEditedAtActualCaretNotAtGuessedDiffPosition() {
        var editor = editor("aaa");
        editor.setColor(new TextSelection(0, 0, 0, 1), 0xff0000);
        editor.replaceTextInBlock(0, 0, 1, "");
        assertEquals("aa", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
        assertEquals(0, editor.document().blocks().getFirst().runs().getFirst().style().rgbColor());
    }

    @Test void tableSupportsMultilineTypingStyleAndUndoableNewRow() {
        var editor = editor("");
        assertTrue(editor.insertTableAtSelection(new TextSelection(0, 0, 0, 0), 1, 2));
        int table = 0;
        while (!(editor.document().blocks().get(table) instanceof TableBlock)) table++;
        assertTrue(editor.replaceTextInTableCell(table, 0, 0, 0, 0, "first\nsecond", RED_BOLD));
        var cell = ((TableBlock) editor.document().blocks().get(table)).rows().getFirst().cells().getFirst();
        assertEquals("first\nsecond", cell.runs().getFirst().text());
        assertEquals(RED_BOLD, cell.runs().getFirst().style());
        assertTrue(editor.appendTableRow(table));
        assertEquals(2, ((TableBlock) editor.document().blocks().get(table)).rows().size());
        assertTrue(editor.undo());
        assertEquals(1, ((TableBlock) editor.document().blocks().get(table)).rows().size());
    }

    @Test void tableSelectedStyleLeavesOtherCharactersAlone() {
        var editor = editor("");
        editor.insertTableAtSelection(new TextSelection(0, 0, 0, 0), 1, 1);
        int table = editor.document().blocks().getFirst() instanceof TableBlock ? 0 : 1;
        editor.replaceTextInTableCell(table, 0, 0, 0, 0, "abcd");
        editor.applyTableCellStyle(table, 0, 0, 1, 3, ignored -> RED_BOLD);
        var runs = ((TableBlock) editor.document().blocks().get(table)).rows().getFirst().cells().getFirst().runs();
        assertEquals(List.of(new TextRun("a", TextStyle.DEFAULT), new TextRun("bc", RED_BOLD), new TextRun("d", TextStyle.DEFAULT)), runs);
    }

    @Test void characterNavigationTreatsCombiningTextAndEmojiAsOneCharacter() {
        String text = "e\u0301👩‍💻z";
        assertEquals(2, TextNavigation.character(text, 0, 1));
        assertEquals(7, TextNavigation.character(text, 2, 1));
        assertEquals(2, TextNavigation.character(text, 7, -1));
        assertEquals(0, TextNavigation.character(text, 2, -1));
    }

    @Test void wordNavigationSkipsSpacesAndPunctuation() {
        assertEquals(6, TextNavigation.word("alpha beta", 0, 1));
        assertEquals(0, TextNavigation.word("alpha beta", 6, -1));
        assertEquals(10, TextNavigation.word("alpha beta", 6, 1));
    }

    private static WikiDocumentEditor editor(String text) {
        var author = new UUID(0, 1);
        return new WikiDocumentEditor(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "title",
                List.of(new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)))),
                new DocumentMetadata(author, "a", Instant.EPOCH, author, "a", Instant.EPOCH)));
    }
}
