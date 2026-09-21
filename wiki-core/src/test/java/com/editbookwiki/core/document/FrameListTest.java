package com.editbookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrameListTest {
    private WikiDocument document(Block... blocks) {
        UUID id = new UUID(0, 1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "lists", List.of(blocks),
                new DocumentMetadata(id, "a", Instant.EPOCH, id, "a", Instant.EPOCH));
    }
    private RichTextBlock text(RichTextBlock.Kind kind, String value) {
        return new RichTextBlock(kind, RichTextBlock.Alignment.LEFT, 0, false,
                List.of(new TextRun(value, TextStyle.DEFAULT.withBold(true).withColor(0x56789A))));
    }
    private List<RichTextBlock.Kind> listKinds() { return List.of(RichTextBlock.Kind.BULLET, RichTextBlock.Kind.NUMBERED); }

    @Test void selectedLogicalLinesBecomeItemsInOneFrameWithoutChangingSurroundingText() {
        for (BlockFrame.Kind frameKind : BlockFrame.Kind.values()) for (var listKind : listKinds()) {
            var editor = new WikiDocumentEditor(document(text(RichTextBlock.Kind.valueOf(frameKind.name()), "intro\none\ntwo\noutro")));
            WikiDocument original = editor.document();
            var caret = editor.addListInsideFrame(new TextSelection(0, 6, 0, 13), listKind);
            assertEquals(new WikiDocumentEditor.PasteResult(2, 3), caret);
            assertEquals(List.of("intro", "one", "two", "outro"), editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList());
            BlockFrame frame = editor.document().blocks().getFirst().frame();
            assertEquals(frameKind, frame.kind());
            assertTrue(editor.document().blocks().stream().allMatch(b -> frame.equals(b.frame())));
            assertEquals(listKind, ((RichTextBlock)editor.document().blocks().get(1)).kind());
            assertEquals(listKind, ((RichTextBlock)editor.document().blocks().get(2)).kind());
            assertTrue(editor.document().blocks().stream().flatMap(b -> b.runs().stream()).allMatch(r -> r.style().bold()));
            assertTrue(editor.undo()); assertEquals(original, editor.document()); assertTrue(editor.redo());
            var codec = new WikiDocumentCodec();
            assertEquals(editor.document(), codec.decode(codec.encode(editor.document())));
        }
    }

    @Test void cancellingOrSwitchingListStyleDoesNotRemoveParentFrame() {
        for (BlockFrame.Kind frameKind : BlockFrame.Kind.values()) for (var listKind : listKinds()) {
            BlockFrame frame = new BlockFrame(frameKind, 1);
            var editor = new WikiDocumentEditor(document(text(RichTextBlock.Kind.NORMAL, "intro\nitem\nafter").withFrame(frame)));
            editor.addListInsideFrame(new TextSelection(0, 8, 0, 8), listKind);
            editor.formatParagraphs(new TextSelection(1, 0, 1, 0), r -> r.withPresentation(listKind, false));
            assertEquals(RichTextBlock.Kind.NORMAL, ((RichTextBlock)editor.document().blocks().get(1)).kind());
            assertTrue(editor.document().blocks().stream().allMatch(b -> frame.equals(b.frame())));
            editor.addListInsideFrame(new TextSelection(1, 0, 1, 0), listKind);
            var other = listKind == RichTextBlock.Kind.NUMBERED ? RichTextBlock.Kind.BULLET : RichTextBlock.Kind.NUMBERED;
            editor.addListInsideFrame(new TextSelection(1, 0, 1, 0), other);
            assertEquals(other, ((RichTextBlock)editor.document().blocks().get(1)).kind());
            assertEquals(frame, editor.document().blocks().get(1).frame());
        }
    }

    @Test void splittingAndRemovingItemsKeepsOneFrameAndEmptyListExitsBeforeContainer() {
        for (var kind : listKinds()) {
            BlockFrame frame = new BlockFrame(BlockFrame.Kind.WARNING, 4);
            var editor = new WikiDocumentEditor(document(text(kind, "abc").withFrame(frame)));
            assertTrue(editor.splitTextBlock(0, 3));
            assertEquals(kind, ((RichTextBlock)editor.document().blocks().get(1)).kind());
            assertFalse(editor.exitFrame(1));
            editor.formatParagraphs(new TextSelection(1, 0, 1, 0), r -> r.withPresentation(kind, false));
            assertEquals(frame, editor.document().blocks().get(1).frame());
            assertTrue(editor.exitFrame(1));
            assertNull(editor.document().blocks().get(1).frame());
            assertEquals(frame, editor.document().blocks().getFirst().frame());
        }
    }

    @Test void changingOrRemovingParentStylePreservesAllItsListItems() {
        BlockFrame frame = new BlockFrame(BlockFrame.Kind.QUOTE, 1);
        var editor = new WikiDocumentEditor(document(text(RichTextBlock.Kind.NUMBERED, "one").withFrame(frame),
                text(RichTextBlock.Kind.BULLET, "two").withFrame(frame)));
        editor.formatParagraphs(new TextSelection(0, 0, 0, 0), r -> r.withPresentation(RichTextBlock.Kind.INFO, true));
        assertEquals(RichTextBlock.Kind.NUMBERED, ((RichTextBlock)editor.document().blocks().getFirst()).kind());
        assertEquals(RichTextBlock.Kind.BULLET, ((RichTextBlock)editor.document().blocks().getLast()).kind());
        assertEquals(BlockFrame.Kind.INFO, editor.document().blocks().getLast().frame().kind());
        editor.formatParagraphs(new TextSelection(0, 0, 0, 0), r -> r.withPresentation(RichTextBlock.Kind.INFO, false));
        assertTrue(editor.document().blocks().stream().allMatch(b -> b.frame() == null));
        assertTrue(editor.document().blocks().stream().allMatch(b -> ((RichTextBlock)b).isList()));
    }

    @Test void wholeLineCopyKeepsListAndIndependentFrameWhilePartialCopyDoesNot() {
        BlockFrame frame = new BlockFrame(BlockFrame.Kind.INFO, 2);
        var editor = new WikiDocumentEditor(document(text(RichTextBlock.Kind.NUMBERED, "item").withFrame(frame),
                new Paragraph(List.of())));
        var full = editor.copyTextFragment(new TextSelection(0, 0, 0, 4));
        var partial = editor.copyTextFragment(new TextSelection(0, 1, 0, 3));
        editor.pasteTextFragment(new TextSelection(1, 0, 1, 0), full);
        var pasted = (RichTextBlock) editor.document().blocks().get(1);
        assertEquals(RichTextBlock.Kind.NUMBERED, pasted.kind());
        assertNotEquals(frame, pasted.frame()); assertEquals(frame.kind(), pasted.frame().kind());
        editor.undo(); editor.pasteTextFragment(new TextSelection(1, 0, 1, 0), partial);
        assertInstanceOf(Paragraph.class, editor.document().blocks().get(1));
        assertEquals("te", WikiDocumentEditor.blockText(editor.document().blocks().get(1)));
    }

    @Test void selectingSeveralTextBlocksKeepsTheirExistingFrame() {
        BlockFrame frame = new BlockFrame(BlockFrame.Kind.WARNING, 1);
        var editor = new WikiDocumentEditor(document(text(RichTextBlock.Kind.NORMAL, "a").withFrame(frame),
                text(RichTextBlock.Kind.NORMAL, "b").withFrame(frame)));
        editor.formatParagraphs(new TextSelection(0, 0, 1, 1), r -> r.withPresentation(RichTextBlock.Kind.NUMBERED, true));
        assertTrue(editor.document().blocks().stream().allMatch(b -> frame.equals(b.frame()) && ((RichTextBlock)b).kind() == RichTextBlock.Kind.NUMBERED));
    }
}
