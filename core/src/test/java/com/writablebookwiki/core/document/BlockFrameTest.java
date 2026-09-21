package com.writablebookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BlockFrameTest {
    private WikiDocument document(Block... blocks) {
        UUID id = new UUID(0, 1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "frames", List.of(blocks),
                new DocumentMetadata(id, "a", Instant.EPOCH, id, "a", Instant.EPOCH));
    }
    private RichTextBlock text(BlockFrame.Kind kind, String value) {
        return new RichTextBlock(RichTextBlock.Kind.valueOf(kind.name()), RichTextBlock.Alignment.LEFT,
                0, false, List.of(new TextRun(value, TextStyle.DEFAULT)));
    }
    private ImageBlock image() { return new ImageBlock("a".repeat(64), "", ImageAlignment.LEFT, 64); }
    private void assertOneFrame(WikiDocument doc, BlockFrame.Kind kind) {
        BlockFrame frame = doc.blocks().getFirst().frame();
        assertNotNull(frame); assertEquals(kind, frame.kind());
        assertTrue(doc.blocks().stream().allMatch(b -> frame.equals(b.frame())));
        var codec = new WikiDocumentCodec();
        assertEquals(doc, codec.decode(codec.encode(doc)));
    }

    @Test void taskLinesRetainOneContainerForAllThreeKindsAndUndoTogether() {
        for (var kind : BlockFrame.Kind.values()) {
            var editor = new WikiDocumentEditor(document(text(kind, "before\ntask\nafter")));
            var before = editor.document();
            assertEquals(new WikiDocumentEditor.PasteResult(1, 4), editor.addTasksInsideQuote(new TextSelection(0, 8, 0, 8)));
            assertOneFrame(editor.document(), kind);
            assertEquals(List.of("before", "task", "after"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
            assertTrue(editor.undo()); assertEquals(before, editor.document());
            assertTrue(editor.redo()); assertTrue(editor.toggleTask(1));
            assertTrue(((RichTextBlock) editor.document().blocks().get(1)).checked());
            editor.splitTextBlock(1, 4);
            assertFalse(((RichTextBlock) editor.document().blocks().get(2)).checked());
            assertOneFrame(editor.document(), kind);
        }
    }

    @Test void imagesAndTablesInsertedAtMiddleOrEndRetainFrameAndEditableTrailingText() {
        for (var kind : BlockFrame.Kind.values()) for (int caret : List.of(0, 2, 4)) {
            var editor = new WikiDocumentEditor(document(text(kind, "abcd")));
            WikiDocument before = editor.document();
            assertTrue(editor.insertImageAtSelection(new TextSelection(0, caret, 0, caret), image()));
            assertOneFrame(editor.document(), kind);
            assertTrue(editor.document().blocks().getLast() instanceof TextualBlock);
            assertTrue(editor.undo()); assertEquals(before, editor.document());
            assertTrue(editor.insertTableAtSelection(new TextSelection(0, caret, 0, caret), 2, 2));
            assertOneFrame(editor.document(), kind);
            int index = caret == 0 ? 0 : 1;
            editor.replaceTextInTableCell(index, 0, 0, 0, 0, "wrapped\ntext");
            editor.applyTableCellStyle(index, 0, 0, 0, 4, s -> s.withBold(true));
            editor.appendTableRow(index); editor.deleteTableColumn(index, 1); editor.deleteTableRow(index, 2);
            assertOneFrame(editor.document(), kind);
            int tail = editor.document().blocks().size() - 1;
            editor.replaceTextSelection(new TextSelection(tail, 0, tail, 0), "typed");
            assertOneFrame(editor.document(), kind);
        }
    }

    @Test void pictureChangesAndAdjacentParagraphsCannotDetachContainer() {
        var editor = new WikiDocumentEditor(document(text(BlockFrame.Kind.INFO, "abcd")));
        editor.insertImageAtSelection(new TextSelection(0, 2, 0, 2), image());
        editor.updateImageBlock(1, new ImageBlock("a".repeat(64), "", ImageAlignment.LEFT, 100, ImageLayoutMode.COVER, 20, 40));
        editor.insertParagraphAdjacentToImage(1, true);
        assertOneFrame(editor.document(), BlockFrame.Kind.INFO);
        editor.deleteImageBlock(2);
        assertOneFrame(editor.document(), BlockFrame.Kind.INFO);
    }

    @Test void wholeLineCopyGetsIndependentGroupWhilePartialTextUsesDestinationFrame() {
        var editor = new WikiDocumentEditor(document(text(BlockFrame.Kind.WARNING, "one\ntask")));
        editor.addTasksInsideQuote(new TextSelection(0, 0, 0, 0));
        var full = editor.copyTextFragment(new TextSelection(0, 0, 0, 3));
        var partial = editor.copyTextFragment(new TextSelection(0, 1, 0, 2));
        BlockFrame original = editor.document().blocks().getFirst().frame();
        editor.pasteTextFragment(new TextSelection(1, 4, 1, 4), partial);
        assertEquals(original, editor.document().blocks().getLast().frame());
        editor.pasteTextFragment(new TextSelection(1, 0, 1, 0), full);
        assertNotEquals(original.group(), editor.document().blocks().get(1).frame().group());
        assertEquals(original, editor.document().blocks().getLast().frame());
    }

    @Test void changingFrameStyleChangesAllMembersButKeepsTaskAndObjectStructure() {
        var editor = new WikiDocumentEditor(document(text(BlockFrame.Kind.INFO, "before\ntask\nafter")));
        editor.addTasksInsideQuote(new TextSelection(0, 8, 0, 8));
        editor.insertImageAtSelection(new TextSelection(2, 0, 2, 0), image());
        editor.formatParagraphs(new TextSelection(0, 0, 0, 0), r -> r.withPresentation(RichTextBlock.Kind.WARNING, true));
        assertOneFrame(editor.document(), BlockFrame.Kind.WARNING);
        assertTrue(((RichTextBlock)editor.document().blocks().get(1)).isTask());
    }

    @Test void emptyTrailingParagraphExitsFrameWithoutUnwrappingOtherMembers() {
        var editor = new WikiDocumentEditor(document(text(BlockFrame.Kind.INFO, "intro")));
        editor.insertImageAtSelection(new TextSelection(0, 5, 0, 5), image());
        WikiDocument before = editor.document();
        assertTrue(editor.exitFrame(2));
        assertNull(editor.document().blocks().getLast().frame());
        assertNotNull(editor.document().blocks().get(0).frame());
        assertNotNull(editor.document().blocks().get(1).frame());
        assertTrue(editor.undo()); assertEquals(before, editor.document());
    }

    @Test void malformedFrameTrailerIsRejectedRatherThanLosingContainer() {
        var codec = new WikiDocumentCodec();
        byte[] valid = codec.encode(document(image().withFrame(new BlockFrame(BlockFrame.Kind.WARNING, 1))));
        byte[] unknownKind = valid.clone(); unknownKind[unknownKind.length - 5] = 99;
        assertThrows(DocumentFormatException.class, () -> codec.decode(unknownKind));
        byte[] zeroGroup = valid.clone(); Arrays.fill(zeroGroup, zeroGroup.length - 4, zeroGroup.length, (byte)0);
        assertThrows(DocumentFormatException.class, () -> codec.decode(zeroGroup));
        assertThrows(DocumentFormatException.class, () -> codec.decode(Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test void unframedBooksKeepVersionSixBytesAndHashWhileFramesUseVersionSeven() {
        var codec = new WikiDocumentCodec();
        var old = document(text(BlockFrame.Kind.INFO, "unchanged"));
        byte[] original = codec.encode(old);
        assertEquals(6, original[7]);
        assertArrayEquals(original, codec.encode(codec.decode(original)));
        assertEquals(codec.storedContentHash(original), codec.contentHash(codec.decode(original)));
        var framed = document(text(BlockFrame.Kind.INFO, "unchanged").withFrame(new BlockFrame(BlockFrame.Kind.INFO, 1)));
        assertEquals(7, codec.encode(framed)[7]);
        assertNotEquals(codec.contentHash(old), codec.contentHash(framed));
        assertThrows(IllegalArgumentException.class, () -> new BlockFrame(BlockFrame.Kind.INFO, 0));
    }
}
