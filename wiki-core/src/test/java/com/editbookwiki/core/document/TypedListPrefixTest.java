package com.editbookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TypedListPrefixTest {
    private WikiDocumentEditor editor(Block block) {
        UUID id = new UUID(0, 1);
        return new WikiDocumentEditor(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "auto list", List.of(block),
                new DocumentMetadata(id, "a", Instant.EPOCH, id, "a", Instant.EPOCH)));
    }
    private Paragraph paragraph(String text) { return new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT))); }
    private RichTextBlock rich(RichTextBlock.Kind kind, String text) {
        return new RichTextBlock(kind, RichTextBlock.Alignment.LEFT, 0, false, paragraph(text).runs());
    }
    private TextSelection caret(int column) { return new TextSelection(0, column, 0, column); }

    @Test void exactPrefixesBecomeStructuralItemsAndRoundTrip() {
        for (String prefix : List.of("1. ", "- ", "* ")) {
            var editor = editor(paragraph(prefix));
            assertEquals(new WikiDocumentEditor.PasteResult(0, 0), editor.convertTypedListPrefix(caret(prefix.length())));
            var item = (RichTextBlock)editor.document().blocks().getFirst();
            assertEquals(prefix.equals("1. ") ? RichTextBlock.Kind.NUMBERED : RichTextBlock.Kind.BULLET, item.kind());
            assertEquals("", WikiDocumentEditor.blockText(item));
            assertNull(item.frame());
            var codec = new WikiDocumentCodec();
            assertEquals(editor.document(), codec.decode(codec.encode(editor.document())));
        }
    }

    @Test void undoRestoresLiteralSpaceSeparatelyFromEarlierTyping() {
        var editor = editor(paragraph(""));
        editor.replaceTextInBlock(0, 0, 0, "1");
        editor.replaceTextInBlock(0, 1, 1, ".");
        editor.replaceTextInBlock(0, 2, 2, " ");
        WikiDocument typed = editor.document();
        editor.convertTypedListPrefix(caret(3));
        WikiDocument converted = editor.document();
        assertTrue(editor.undo()); assertEquals(typed, editor.document());
        assertTrue(editor.redo()); assertEquals(converted, editor.document());
        assertTrue(editor.undo()); assertTrue(editor.undo());
        assertEquals("", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
    }

    @Test void proseAndStyledSuffixArePreservedWithinAllContainers() {
        for (var frameKind : BlockFrame.Kind.values()) for (boolean legacy : List.of(false, true)) {
            BlockFrame frame = new BlockFrame(frameKind, 7);
            var original = new RichTextBlock(legacy ? RichTextBlock.Kind.valueOf(frameKind.name()) : RichTextBlock.Kind.NORMAL,
                    RichTextBlock.Alignment.RIGHT, 0, false,
                    List.of(new TextRun("intro\n1. ", TextStyle.DEFAULT),
                            new TextRun("内容😀\noutro", TextStyle.DEFAULT.withBold(true).withColor(0x56789A))), legacy ? null : frame);
            var editor = editor(original);
            assertEquals(new WikiDocumentEditor.PasteResult(1, 0), editor.convertTypedListPrefix(caret(9)));
            assertEquals(List.of("intro", "内容😀", "outro"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
            var item = (RichTextBlock)editor.document().blocks().get(1);
            assertEquals(RichTextBlock.Kind.NUMBERED, item.kind());
            assertEquals(RichTextBlock.Alignment.RIGHT, item.alignment());
            assertTrue(item.runs().getFirst().style().bold());
            assertEquals(frameKind, item.frame().kind());
            assertTrue(editor.document().blocks().stream().allMatch(b -> item.frame().equals(b.frame())));
            if (!legacy) assertEquals(frame, item.frame());
            assertTrue(editor.undo()); assertEquals(original, editor.document().blocks().getFirst());
        }
    }

    @Test void softLineInOrdinaryParagraphSplitsOnlyCurrentLine() {
        var editor = editor(paragraph("before\n1. \nafter"));
        editor.convertTypedListPrefix(caret(10));
        assertEquals(List.of("before", "", "after"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
        assertEquals(RichTextBlock.Kind.NUMBERED, ((RichTextBlock)editor.document().blocks().get(1)).kind());
        assertTrue(editor.document().blocks().stream().allMatch(b -> b.frame() == null));
    }

    @Test void ordinaryTextDecimalsAndIncompletePrefixesStayUntouched() {
        for (String text : List.of("1.", "1.2 ", "version 1. ", " 1. ", "11. ", "2. ", "1.  ", "x- ", "** ")) {
            var editor = editor(paragraph(text));
            WikiDocument before = editor.document();
            assertNull(editor.convertTypedListPrefix(caret(text.length())), text);
            assertSame(before, editor.document()); assertFalse(editor.canUndo());
        }
    }

    @Test void selectionsHeadingsExistingListsTasksAndCodeAreNotConverted() {
        var selected = editor(paragraph("1. "));
        assertNull(selected.convertTypedListPrefix(new TextSelection(0, 0, 0, 3)));
        assertNull(selected.convertTypedListPrefix(caret(50)));
        for (var kind : List.of(RichTextBlock.Kind.NUMBERED, RichTextBlock.Kind.BULLET, RichTextBlock.Kind.TASK,
                RichTextBlock.Kind.QUOTE_TASK, RichTextBlock.Kind.CODE, RichTextBlock.Kind.STEP)) {
            var editor = editor(rich(kind, "1. "));
            assertNull(editor.convertTypedListPrefix(caret(3))); assertFalse(editor.canUndo());
        }
        assertNull(editor(new Heading(1, paragraph("1. ").runs())).convertTypedListPrefix(caret(3)));
        assertNull(editor(new RichTextBlock(RichTextBlock.Kind.NORMAL, RichTextBlock.Alignment.LEFT, 1, false,
                paragraph("1. ").runs())).convertTypedListPrefix(caret(3)));
        assertNull(editor(HorizontalRule.INSTANCE).convertTypedListPrefix(caret(3)));
    }
}
