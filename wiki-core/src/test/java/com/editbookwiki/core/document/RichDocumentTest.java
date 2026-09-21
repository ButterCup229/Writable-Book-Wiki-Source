package com.editbookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RichDocumentTest {
    private WikiDocument doc(List<Block> blocks) {
        UUID id=new UUID(0,1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION,"示例",blocks,new DocumentMetadata(id,"a",Instant.EPOCH,id,"a",Instant.EPOCH));
    }
    @Test void codecRetainsEveryParagraphAndInlineAttributeAndDetectsChanges() {
        List<Block> blocks=new ArrayList<>();
        TextStyle style=TextStyle.DEFAULT.withBold(true).withUnderline(true).withScript(-1).withHidden(true).withCode(true);
        for(var kind:RichTextBlock.Kind.values())for(var align:RichTextBlock.Alignment.values())blocks.add(new RichTextBlock(kind,align,2,true,List.of(new TextRun("中文 abc",style,"page:2"))));
        blocks.add(HorizontalRule.INSTANCE);
        WikiDocument document=doc(blocks);var codec=new WikiDocumentCodec();
        assertEquals(document,codec.decode(codec.encode(document)));
        var editor=new WikiDocumentEditor(document);
        editor.applyStyle(new TextSelection(0,0,0,2),s->s.withScript(1));
        assertNotEquals(codec.contentHash(document),codec.contentHash(editor.document()));
    }
    @Test void existingStyleOperationsPreserveNewAttributesAndParagraphLayout() {
        var style=TextStyle.DEFAULT.withCode(true).withScript(1).withHidden(true).withUnderline(true);
        var editor=new WikiDocumentEditor(doc(List.of(new RichTextBlock(RichTextBlock.Kind.INFO,RichTextBlock.Alignment.CENTER,0,false,List.of(new TextRun("abc",style))))));
        var selection=new TextSelection(0,0,0,3);
        editor.toggleBold(selection);editor.toggleItalic(selection);editor.toggleStrikethrough(selection);editor.setColor(selection,0x123456);editor.setFontSize(selection,16);
        TextStyle actual=editor.document().blocks().getFirst().runs().getFirst().style();
        assertEquals(style.withBold(true).withItalic(true).withStrikethrough(true).withColor(0x123456).withFontSize(16),actual);
        editor.replaceTextInBlock(0,1,2,"X");
        assertEquals(RichTextBlock.Alignment.CENTER,((RichTextBlock)editor.document().blocks().getFirst()).alignment());
    }
    @Test void listEnterContinuesUncheckedAndTaskToggleIsUndoable() {
        var task=new RichTextBlock(RichTextBlock.Kind.TASK,RichTextBlock.Alignment.LEFT,0,true,List.of(new TextRun("abcdef",TextStyle.DEFAULT)));
        var editor=new WikiDocumentEditor(doc(List.of(task)));
        assertTrue(editor.splitTextBlock(0,3));
        assertEquals("def",WikiDocumentEditor.blockText(editor.document().blocks().get(1)));
        assertFalse(((RichTextBlock)editor.document().blocks().get(1)).checked());
        assertTrue(editor.toggleTask(1));assertTrue(editor.undo());assertFalse(((RichTextBlock)editor.document().blocks().get(1)).checked());
    }
    @Test void horizontalRuleSplitsAtCaretAndImportsAreSingleUndoableTransactions() {
        WikiDocument original=doc(List.of(new Paragraph(List.of(new TextRun("beforeafter",TextStyle.DEFAULT)))));
        var editor=new WikiDocumentEditor(original);
        assertTrue(editor.insertHorizontalRule(new TextSelection(0,6,0,6)));
        assertEquals("before",WikiDocumentEditor.blockText(editor.document().blocks().get(0)));
        assertInstanceOf(HorizontalRule.class,editor.document().blocks().get(1));
        assertEquals("after",WikiDocumentEditor.blockText(editor.document().blocks().get(2)));
        assertTrue(editor.deleteHorizontalRule(1));assertTrue(editor.undo());assertTrue(editor.undo());assertEquals(original,editor.document());
        editor.importDocument(doc(List.of(new Heading(1,List.of(new TextRun("new",TextStyle.DEFAULT))))));
        assertTrue(editor.undo());assertEquals(original,editor.document());
    }
    @Test void paragraphsDoNotIncludeFollowingUnselectedLineAndLinksRejectUnsafeSchemes() {
        var editor=new WikiDocumentEditor(doc(List.of(new Paragraph(List.of(new TextRun("a",TextStyle.DEFAULT))),new Paragraph(List.of(new TextRun("b",TextStyle.DEFAULT))))));
        editor.formatParagraphs(new TextSelection(0,0,1,0),r->new RichTextBlock(RichTextBlock.Kind.BULLET,r.alignment(),0,false,r.runs()));
        assertInstanceOf(RichTextBlock.class,editor.document().blocks().getFirst());assertInstanceOf(Paragraph.class,editor.document().blocks().get(1));
        assertEquals("say hi",DocumentLink.parse("command:/say hi").value());
        assertEquals(DocumentLink.Kind.PAGE,DocumentLink.parse("page:2").kind());
        assertEquals(DocumentLink.Kind.WEB,DocumentLink.parse("https://example.com/a?q=b").kind());
        for(String target:List.of("command:/say a\nstop","file:///C:/a","javascript:alert(1)","page:0","https://user:pass@example.com"))assertThrows(IllegalArgumentException.class,()->DocumentLink.parse(target));
    }
}
