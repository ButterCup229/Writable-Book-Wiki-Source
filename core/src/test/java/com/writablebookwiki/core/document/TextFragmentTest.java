package com.writablebookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextFragmentTest {
    private WikiDocument doc(Block... blocks) {
        UUID id = new UUID(0, 1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "copy", List.of(blocks),
                new DocumentMetadata(id,"a",Instant.EPOCH,id,"a",Instant.EPOCH));
    }
    private Paragraph paragraph(String text) { return new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT))); }
    private RichTextBlock quote(String text) {
        return new RichTextBlock(RichTextBlock.Kind.QUOTE, RichTextBlock.Alignment.LEFT, 0, false,
                List.of(new TextRun(text, TextStyle.DEFAULT.withBold(true).withColor(0x123456), "page:2")));
    }

    @Test void wholeLineKeepsQuoteRunsAndSoftBreaksAndPasteIsOneUndoStep() {
        var source = new WikiDocumentEditor(doc(quote("one\ntwo")));
        TextFragment fragment = source.copyTextFragment(new TextSelection(0,0,0,7));
        assertEquals("one\ntwo", fragment.plainText());
        var target = new WikiDocumentEditor(doc(paragraph("")));
        WikiDocument before = target.document();
        assertEquals(new WikiDocumentEditor.PasteResult(0,7), target.pasteTextFragment(new TextSelection(0,0,0,0),fragment));
        assertEquals(source.document().blocks(), target.document().blocks());
        assertTrue(target.undo()); assertEquals(before,target.document());
        assertTrue(target.redo()); assertEquals(source.document().blocks(),target.document().blocks());
    }

    @Test void partialWordsKeepInlineStylesButNeverAcquireQuoteOrTaskFrame() {
        var source = new WikiDocumentEditor(doc(quote("abcdef")));
        TextFragment fragment = source.copyTextFragment(new TextSelection(0,1,0,3));
        assertFalse(fragment.parts().getFirst().wholeLine());
        var target = new WikiDocumentEditor(doc(paragraph("LR")));
        assertEquals(new WikiDocumentEditor.PasteResult(0,3), target.pasteTextFragment(new TextSelection(0,1,0,1),fragment));
        assertInstanceOf(Paragraph.class,target.document().blocks().getFirst());
        assertEquals("LbcR",WikiDocumentEditor.blockText(target.document().blocks().getFirst()));
        assertTrue(target.document().blocks().getFirst().runs().get(1).style().bold());
        assertEquals("page:2",target.document().blocks().getFirst().runs().get(1).linkTarget());
    }

    @Test void completeLogicalLineWithinMultilineBlockKeepsFrameButPartialLineDoesNot() {
        var source = new WikiDocumentEditor(doc(quote("abc\ndef\nghi")));
        assertTrue(source.copyTextFragment(new TextSelection(0,4,0,7)).parts().getFirst().wholeLine());
        assertTrue(source.copyTextFragment(new TextSelection(0,4,0,8)).parts().getFirst().wholeLine());
        assertFalse(source.copyTextFragment(new TextSelection(0,5,0,7)).parts().getFirst().wholeLine());
        assertFalse(source.copyTextFragment(new TextSelection(0,4,0,6)).parts().getFirst().wholeLine());
    }

    @Test void richPasteInProsePreservesBothSurroundingParagraphs() {
        var source = new WikiDocumentEditor(doc(quote("quoted")));
        var fragment=source.copyTextFragment(new TextSelection(0,0,0,6));
        var target=new WikiDocumentEditor(doc(paragraph("beforeafter")));
        assertEquals(new WikiDocumentEditor.PasteResult(1,6),target.pasteTextFragment(new TextSelection(0,6,0,6),fragment));
        assertEquals(List.of(paragraph("before"),quote("quoted"),paragraph("after")),target.document().blocks());
    }

    @Test void multipleBlocksKeepIndividualStylesAndStructuralBoundariesAreRejected() {
        var source=new WikiDocumentEditor(doc(paragraph("plain"),quote("quoted")));
        var fragment=source.copyTextFragment(new TextSelection(1,6,0,0));
        var target=new WikiDocumentEditor(doc(paragraph("old")));
        target.pasteTextFragment(new TextSelection(0,0,0,3),fragment);
        assertEquals(source.document().blocks(),target.document().blocks());
        var guarded=new WikiDocumentEditor(doc(paragraph("a"),PageBreak.INSTANCE,paragraph("b")));
        WikiDocument before=guarded.document();
        assertNull(guarded.copyTextFragment(new TextSelection(0,0,2,1)));
        assertNull(guarded.pasteTextFragment(new TextSelection(0,0,2,1),fragment));
        assertEquals(before,guarded.document());
    }

    @Test void taskInsideQuoteKeepsProseAndCheckboxAndQuoteTogglesAreIndependent() {
        var editor=new WikiDocumentEditor(doc(quote("before\ntask\nafter")));
        WikiDocument before=editor.document();
        assertEquals(new WikiDocumentEditor.PasteResult(1,4),editor.addTasksInsideQuote(new TextSelection(0,8,0,8)));
        BlockFrame frame = editor.document().blocks().getFirst().frame();
        assertEquals(quote("before").withFrame(frame),editor.document().blocks().getFirst());
        assertEquals(quote("after").withFrame(frame),editor.document().blocks().getLast());
        RichTextBlock task=(RichTextBlock)editor.document().blocks().get(1);
        assertTrue(task.isQuote());assertTrue(task.isTask());assertFalse(task.checked());
        assertTrue(editor.toggleTask(1));
        task=(RichTextBlock)editor.document().blocks().get(1);
        assertTrue(task.checked());
        assertEquals(RichTextBlock.Kind.QUOTE,task.withPresentation(RichTextBlock.Kind.TASK,false).kind());
        assertEquals(RichTextBlock.Kind.TASK,task.withPresentation(RichTextBlock.Kind.QUOTE,false).kind());
        assertTrue(task.withPresentation(RichTextBlock.Kind.QUOTE,false).checked());
        assertEquals(editor.document(),new WikiDocumentCodec().decode(new WikiDocumentCodec().encode(editor.document())));
        assertTrue(editor.undo());assertTrue(editor.undo());assertEquals(before,editor.document());
    }

    @Test void selectedQuoteLinesBecomeSeparateTasksAndNewTaskStartsUnchecked() {
        var editor=new WikiDocumentEditor(doc(quote("intro\none\ntwo\nend")));
        editor.addTasksInsideQuote(new TextSelection(0,6,0,13));
        assertEquals(4,editor.document().blocks().size());
        assertTrue(((RichTextBlock)editor.document().blocks().get(1)).isTask());
        assertTrue(((RichTextBlock)editor.document().blocks().get(2)).isTask());
        assertEquals(quote("end").withFrame(editor.document().blocks().getFirst().frame()),editor.document().blocks().getLast());
        editor.toggleTask(1);editor.finishTextEdit();editor.splitTextBlock(1,3);
        RichTextBlock next=(RichTextBlock)editor.document().blocks().get(2);
        assertTrue(next.isQuote());assertTrue(next.isTask());assertFalse(next.checked());
    }
}
