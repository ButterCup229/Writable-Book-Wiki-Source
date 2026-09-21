package com.editbookwiki.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WikiDocumentEditorTest {
    @Test
    void selectedStyleChangeSplitsRunsWithoutChangingUnselectedText() {
        TextStyle blueBold = new TextStyle(true, false, false, 0x336699, 16);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(new Paragraph(List.of(
                new TextRun("ab", blueBold), new TextRun("cd", TextStyle.DEFAULT))))));

        assertTrue(editor.toggleBold(new TextSelection(0, 1, 0, 3)));

        List<TextRun> runs = editor.document().blocks().getFirst().runs();
        assertEquals(List.of(
                new TextRun("ab", blueBold),
                new TextRun("c", new TextStyle(true, false, false, 0x000000, TextStyle.DEFAULT.fontSize())),
                new TextRun("d", TextStyle.DEFAULT)), runs);
        assertEquals("abcd", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
    }

    @Test
    void continuousTypingIsOneUndoStepButFormattingStartsANewStep() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("first"))));

        assertTrue(editor.replaceLines(List.of("first second")));
        assertTrue(editor.replaceLines(List.of("first second third")));
        assertTrue(editor.undo());
        assertEquals("first", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));

        assertTrue(editor.replaceLines(List.of("first formatted")));
        assertTrue(editor.toggleItalic(new TextSelection(0, 0, 0, 5)));
        assertTrue(editor.undo());
        assertFalse(editor.document().blocks().getFirst().runs().getFirst().style().italic());
        assertTrue(editor.undo());
        assertEquals("first", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
    }

    @Test
    void historyKeepsOnlyTheLastTenLogicalCommands() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("body"))));
        for (int index = 1; index <= 12; index++) {
            assertTrue(editor.replaceTitle("title " + index));
            editor.finishTextEdit();
        }

        for (int index = 0; index < 10; index++) {
            assertTrue(editor.undo());
        }
        assertEquals("title 2", editor.document().title());
        assertFalse(editor.undo());
    }

    @Test
    void selectionNeverSplitsASurrogatePair() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("a😀b"))));

        assertTrue(editor.toggleBold(new TextSelection(0, 2, 0, 3)));

        List<TextRun> runs = editor.document().blocks().getFirst().runs();
        assertEquals("a😀b", WikiDocumentEditor.blockText(editor.document().blocks().getFirst()));
        assertEquals("😀", runs.get(1).text());
        assertTrue(runs.get(1).style().bold());
    }

    @Test
    void nativeCharacterReplacementPreservesRunsAndCoalescesUndo() {
        TextStyle blue = new TextStyle(false, false, false, 0x336699, 16);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(new Paragraph(List.of(
                new TextRun("甲", blue), new TextRun("乙", TextStyle.DEFAULT))))));

        assertTrue(editor.replaceTextInBlock(0, 1, 1, "丙"));
        assertTrue(editor.replaceTextInBlock(0, 2, 2, "丁"));
        assertEquals("甲丙丁乙", blockText(editor.document().blocks().getFirst()));
        assertEquals(blue, editor.document().blocks().getFirst().runs().getFirst().style());

        assertTrue(editor.undo());
        assertEquals("甲乙", blockText(editor.document().blocks().getFirst()));
    }

    @Test
    void nativeCharacterReplacementProtectsStructuralBlocksAndSurrogatePairs() {
        ImageBlock image = new ImageBlock("b".repeat(64), "图", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("a😀b"), image)));

        assertTrue(editor.replaceTextInBlock(0, 2, 3, "X"));
        assertEquals("aXb", blockText(editor.document().blocks().getFirst()));
        assertFalse(editor.replaceTextInBlock(1, 0, 0, "不会写入图片"));
        assertEquals(image, editor.document().blocks().get(1));
    }

    @Test
    void enterSplitsEditableTextButNeverStructuralBlocks() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("前半后半"))));

        assertTrue(editor.splitTextBlock(0, 2));
        assertEquals(List.of(paragraph("前半"), paragraph("后半")), editor.document().blocks());
        assertTrue(editor.undo());
        assertEquals(List.of(paragraph("前半后半")), editor.document().blocks());
    }

    @Test
    void nativeMultiParagraphReplacementCreatesParagraphsAndRemainsOneUndoStep() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("alpha"), paragraph("beta"), paragraph("tail"))));

        assertTrue(editor.replaceTextSelection(new TextSelection(0, 2, 1, 2), "甲\n乙"));
        assertEquals(List.of(paragraph("al甲"), paragraph("乙ta"), paragraph("tail")), editor.document().blocks());

        assertTrue(editor.undo());
        assertEquals(List.of(paragraph("alpha"), paragraph("beta"), paragraph("tail")), editor.document().blocks());
    }

    @Test
    void nativeMultiParagraphReplacementRefusesToCrossImageProtectionBoundary() {
        ImageBlock image = new ImageBlock("c".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("before"), image, paragraph("after"))));

        assertFalse(editor.replaceTextSelection(new TextSelection(0, 1, 2, 3), "不会删除图片"));
        assertEquals(List.of(paragraph("before"), image, paragraph("after")), editor.document().blocks());
    }

    @Test
    void explicitFontSizeChangesOnlyTheSelectedTextAndUsesDocumentHistory() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("small large"))));

        assertTrue(editor.setFontSize(new TextSelection(0, 6, 0, 11), 24));
        assertEquals(List.of(
                new TextRun("small ", TextStyle.DEFAULT),
                new TextRun("large", new TextStyle(false, false, false, 0x000000, 24))),
                editor.document().blocks().getFirst().runs());

        assertTrue(editor.undo());
        assertEquals(List.of(new TextRun("small large", TextStyle.DEFAULT)),
                editor.document().blocks().getFirst().runs());
        assertTrue(editor.redo());
        assertEquals(24, editor.document().blocks().getFirst().runs().get(1).style().fontSize());
    }

    @Test
    void selectedTextCanBecomeAnInternalLinkWithoutChangingItsStyle() {
        TextStyle style = new TextStyle(true, false, false, 0x336699, 18);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                new Paragraph(List.of(new TextRun("第一章 第二章", style))))));

        assertTrue(editor.setLinkTarget(new TextSelection(0, 4, 0, 7), "第二章"));
        assertEquals(List.of(
                new TextRun("第一章 ", style),
                new TextRun("第二章", style, "第二章")), editor.document().blocks().getFirst().runs());
        assertTrue(editor.undo());
        assertEquals(List.of(new TextRun("第一章 第二章", style)), editor.document().blocks().getFirst().runs());
    }

    @Test
    void quoteCommandPreservesTheSameTextAndCanBeUndone() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("引文"), paragraph("正文"))));

        assertTrue(editor.toggleQuoteBlocks(new TextSelection(0, 0, 0, 2)));
        assertTrue(editor.document().blocks().getFirst() instanceof QuoteBlock);
        assertEquals("引文", blockText(editor.document().blocks().getFirst()));
        assertTrue(editor.undo());
        assertEquals(paragraph("引文"), editor.document().blocks().getFirst());
    }

    @Test
    void imageInsertionIsUndoableAndSurvivesAdjacentTextChanges() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("before"), paragraph("after"))));
        ImageBlock image = new ImageBlock("a".repeat(64), "diagram.png", ImageAlignment.CENTER, 320);

        assertTrue(editor.insertImageAfter(new TextSelection(0, 0, 0, 0), image));
        assertEquals(image, editor.document().blocks().get(1));

        assertTrue(editor.replaceLines(List.of("before revised", "", "after")));
        assertEquals(image, editor.document().blocks().get(1));
        assertTrue(editor.undo());
        assertEquals(image, editor.document().blocks().get(1));
    }

    @Test
    void imageInsertionAtTrailingEditorRowStaysBeforeTheFollowingPageBreak() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("第一页"), PageBreak.INSTANCE, paragraph("第二页"))));
        ImageBlock image = new ImageBlock("a".repeat(64), "页面底部", ImageAlignment.CENTER, 0);

        // The UI exposes one trailing blank row after local line 0. Passing
        // that row number must still insert inside page 0.
        assertTrue(editor.insertImageOnPage(0, 1, image));
        assertEquals(List.of(paragraph("第一页"), image, PageBreak.INSTANCE, paragraph("第二页")),
                editor.document().blocks());
    }

    @Test
    void imageInsertionAtCaretSplitsTextInsteadOfReplacingThePictureWithText() {
        TextStyle style = new TextStyle(true, false, false, 0x224466, 12);
        ImageBlock image = new ImageBlock("e".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                new Paragraph(List.of(new TextRun("前半后半", style))))));

        assertTrue(editor.insertImageAtSelection(new TextSelection(0, 2, 0, 2), image));
        assertEquals(List.of(
                new Paragraph(List.of(new TextRun("前半", style))),
                image,
                new Paragraph(List.of(new TextRun("后半", style)))), editor.document().blocks());

        assertTrue(editor.undo());
        assertEquals("前半后半", blockText(editor.document().blocks().getFirst()));
    }

    @Test
    void imageAtEndLeavesAnEditableParagraphAfterTheImage() {
        ImageBlock image = new ImageBlock("f".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("正文"))));

        assertTrue(editor.insertImageAtSelection(new TextSelection(0, 2, 0, 2), image));
        assertEquals(List.of(paragraph("正文"), image, new Paragraph(List.of())), editor.document().blocks());
    }

    @Test
    void joinsTextParagraphsAtTheirSharedLineBreakWithoutCrossingAnImage() {
        TextStyle firstStyle = new TextStyle(true, false, false, 0x224466, 12);
        TextStyle secondStyle = new TextStyle(false, true, false, 0x663399, 11);
        ImageBlock image = new ImageBlock("c".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                new Paragraph(List.of(new TextRun("前段", firstStyle))),
                new Paragraph(List.of(new TextRun("后段", secondStyle))),
                image,
                paragraph("图片后的文字"))));

        assertTrue(editor.mergeTextBlockWithPrevious(1));
        assertEquals(List.of("前段后段", "图片后的文字"), editor.document().blocks().stream()
                .filter(TextualBlock.class::isInstance).map(WikiDocumentEditor::blockText).toList());
        assertEquals(firstStyle, ((TextualBlock) editor.document().blocks().getFirst()).runs().getFirst().style());
        assertEquals(secondStyle, ((TextualBlock) editor.document().blocks().getFirst()).runs().get(1).style());
        assertFalse(editor.mergeTextBlockWithNext(0));
        assertTrue(editor.undo());
        assertEquals(List.of(new Paragraph(List.of(new TextRun("前段", firstStyle))),
                new Paragraph(List.of(new TextRun("后段", secondStyle))), image,
                paragraph("图片后的文字")), editor.document().blocks());
    }

    @Test
    void sortsOnlyAContiguousSelectedRunOfTextBlocksAndKeepsUndo() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("zebra"), paragraph("alpha"), paragraph("middle"), paragraph("bravo"))));

        assertTrue(editor.sortTextBlocks(new TextSelection(0, 0, 3, 5), true));
        assertEquals(List.of("alpha", "bravo", "middle", "zebra"), editor.document().blocks().stream()
                .map(WikiDocumentEditor::blockText).toList());

        assertTrue(editor.undo());
        assertEquals(List.of("zebra", "alpha", "middle", "bravo"), editor.document().blocks().stream()
                .map(WikiDocumentEditor::blockText).toList());
    }

    @Test
    void refusesToSortAcrossAnImageInsteadOfDetachingItsStructure() {
        ImageBlock image = new ImageBlock("d".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("zebra"), image, paragraph("alpha"))));

        assertFalse(editor.sortTextBlocks(new TextSelection(0, 0, 2, 5), true));
        assertEquals(List.of(paragraph("zebra"), image, paragraph("alpha")), editor.document().blocks());
    }

    @Test
    void tableInsertionAndCellTypingAreRealUndoableDocumentCommands() {
        TextStyle blue = new TextStyle(false, false, false, 0x224466, 12);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                new Paragraph(List.of(new TextRun("前后", blue))))));

        assertTrue(editor.insertTableAtSelection(new TextSelection(0, 1, 0, 1), 2, 3));
        assertEquals(3, editor.document().blocks().size());
        TableBlock table = (TableBlock) editor.document().blocks().get(1);
        assertEquals(2, table.rows().size());
        assertEquals(3, table.columnCount());
        assertTrue(editor.replaceTextInTableCell(1, 0, 1, 0, 0, "表头"));
        assertEquals("表头", tableText((TableBlock) editor.document().blocks().get(1), 0, 1));

        assertTrue(editor.undo());
        assertEquals("", tableText((TableBlock) editor.document().blocks().get(1), 0, 1));
        assertTrue(editor.undo());
        assertEquals("前后", blockText(editor.document().blocks().getFirst()));
    }

    @Test
    void selectedImagePropertiesAndDeletionAreUndoableDocumentCommands() {
        ImageBlock original = new ImageBlock("a".repeat(64), "旧说明", ImageAlignment.CENTER, 240);
        ImageBlock updated = new ImageBlock("a".repeat(64), "新说明", ImageAlignment.RIGHT, 360);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("正文"), original)));

        assertTrue(editor.updateImageBlock(1, updated));
        assertEquals(updated, editor.document().blocks().get(1));
        assertTrue(editor.undo());
        assertEquals(original, editor.document().blocks().get(1));
        assertTrue(editor.redo());
        assertEquals(updated, editor.document().blocks().get(1));

        assertTrue(editor.deleteImageBlock(1));
        assertEquals(List.of(paragraph("正文")), editor.document().blocks());
        assertTrue(editor.undo());
        assertEquals(updated, editor.document().blocks().get(1));
    }

    @Test
    void deletingOnlyImageLeavesAnEditableEmptyParagraph() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                new ImageBlock("b".repeat(64), "唯一图片", ImageAlignment.LEFT, 0))));

        assertTrue(editor.deleteImageBlock(0));
        assertEquals(List.of(new Paragraph(List.of())), editor.document().blocks());
    }

    @Test
    void imageCanReceiveRealParagraphsOnEitherSideForContinuedTyping() {
        ImageBlock image = new ImageBlock("c".repeat(64), "", ImageAlignment.LEFT, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("开头"), image, paragraph("结尾"))));

        assertEquals(1, editor.insertParagraphAdjacentToImage(1, true));
        assertEquals(3, editor.insertParagraphAdjacentToImage(2, false));
        assertEquals(List.of(
                paragraph("开头"), new Paragraph(List.of()), image, new Paragraph(List.of()), paragraph("结尾")),
                editor.document().blocks());

        assertTrue(editor.undo());
        assertEquals(List.of(paragraph("开头"), new Paragraph(List.of()), image, paragraph("结尾")),
                editor.document().blocks());
    }

    @Test
    void structuralBlocksSurviveTextFormattingAndHeadingCommands() {
        ImageBlock image = new ImageBlock("c".repeat(64), "结构图片", ImageAlignment.CENTER, 0);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("前文"), image, paragraph("后文"))));

        assertTrue(editor.toggleBold(new TextSelection(0, 0, 2, 2)));
        assertEquals(image, editor.document().blocks().get(1));
        assertTrue(editor.setBlockType(new TextSelection(0, 0, 2, 2), 1));
        assertEquals(image, editor.document().blocks().get(1));
        assertTrue(editor.document().blocks().get(0) instanceof Heading);
        assertTrue(editor.document().blocks().get(2) instanceof Heading);
    }

    @Test
    void pageTextEditingKeepsImageWhenNewLinesAreInsertedBeforeIt() {
        ImageBlock image = new ImageBlock("d".repeat(64), "地图", ImageAlignment.CENTER, 128);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("前文"), image, paragraph("后文"))));

        assertTrue(editor.replacePageLinesPreservingStructuralBlocks(0,
                List.of("前文", "插入行", "\u2063", "后文"), "\u2063"));

        assertEquals("前文", blockText(editor.document().blocks().get(0)));
        assertEquals("插入行", blockText(editor.document().blocks().get(1)));
        assertEquals(image, editor.document().blocks().get(2));
        assertEquals("后文", blockText(editor.document().blocks().get(3)));
    }

    @Test
    void missingStructuralMarkerCannotSilentlyDeleteImage() {
        ImageBlock image = new ImageBlock("e".repeat(64), "地图", ImageAlignment.CENTER, 128);
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("前文"), image, paragraph("后文"))));

        assertTrue(editor.replacePageLinesPreservingStructuralBlocks(0, List.of("改过的前文", "后文"), "\u2063"));

        assertTrue(editor.document().blocks().contains(image));
    }

    @Test
    void pageEditingChangesOnlyTheSelectedAuthoredPage() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("第一章"), PageBreak.INSTANCE, paragraph("第二章"))));

        assertTrue(editor.replacePageLines(1, List.of("第二章修订")));
        assertEquals("第一章", blockText(editor.document().blocks().get(0)));
        assertEquals(PageBreak.INSTANCE, editor.document().blocks().get(1));
        assertEquals("第二章修订", blockText(editor.document().blocks().get(2)));
    }

    @Test
    void documentPagesUseOnlyExplicitPageBreaks() {
        WikiDocument document = document(List.of(
                paragraph("第 1 章"), PageBreak.INSTANCE, PageBreak.INSTANCE, paragraph("第 3 章")));

        assertEquals(3, DocumentPages.count(document));
        assertEquals(new DocumentPages.PageRange(0, 1), DocumentPages.range(document, 0));
        assertEquals(new DocumentPages.PageRange(2, 2), DocumentPages.range(document, 1));
        assertEquals(List.of(paragraph("第 3 章")), DocumentPages.blocks(document, 2));
    }

    @Test
    void nextPageCreatesARealEmptyEditablePage() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(paragraph("第一页"))));

        assertTrue(editor.appendEmptyPage());
        assertEquals(2, DocumentPages.count(editor.document()));
        assertEquals(List.of(paragraph("第一页")), DocumentPages.blocks(editor.document(), 0));
        assertEquals(List.of(new Paragraph(List.of())), DocumentPages.blocks(editor.document(), 1));

        assertTrue(editor.replacePageLines(1, List.of("第二页内容")));
        assertEquals("第二页内容", blockText(DocumentPages.blocks(editor.document(), 1).getFirst()));
    }

    @Test
    void deletePageRemovesItsBoundaryButKeepsNeighboringPages() {
        WikiDocumentEditor editor = new WikiDocumentEditor(document(List.of(
                paragraph("第一页"), PageBreak.INSTANCE,
                paragraph("第二页"), PageBreak.INSTANCE,
                paragraph("第三页"))));

        assertTrue(editor.deletePage(1));
        assertEquals(2, DocumentPages.count(editor.document()));
        assertEquals("第一页", blockText(DocumentPages.blocks(editor.document(), 0).getFirst()));
        assertEquals("第三页", blockText(DocumentPages.blocks(editor.document(), 1).getFirst()));
        assertTrue(editor.deletePage(1));
        assertEquals(1, DocumentPages.count(editor.document()));
        assertFalse(editor.deletePage(0));
    }

    private static String blockText(Block block) {
        return WikiDocumentEditor.blockText(block);
    }

    private static String tableText(TableBlock table, int row, int column) {
        StringBuilder text = new StringBuilder();
        for (TextRun run : table.rows().get(row).cells().get(column).runs()) {
            text.append(run.text());
        }
        return text.toString();
    }

    @Test
    void tableRowAndColumnDeletionPreserveOtherCellsAndUndoIndependently() {
        TableCell a = new TableCell(List.of(new TextRun("a", new TextStyle(true, false, false, 0x224466, 16))));
        TableCell b = new TableCell(List.of(new TextRun("b", TextStyle.DEFAULT)));
        TableBlock table = new TableBlock(List.of(new TableRow(List.of(a, b)), new TableRow(List.of(b, a))));
        WikiDocument original = document(List.of(paragraph("before"), table, paragraph("after")));
        WikiDocumentEditor editor = new WikiDocumentEditor(original);
        assertTrue(editor.deleteTableRow(1, 0));
        assertEquals(List.of(new TableRow(List.of(b, a))), ((TableBlock) editor.document().blocks().get(1)).rows());
        assertTrue(editor.undo());
        assertEquals(original, editor.document());
        assertTrue(editor.deleteTableColumn(1, 0));
        assertEquals(new TableBlock(List.of(new TableRow(List.of(b)), new TableRow(List.of(a)))),
                editor.document().blocks().get(1));
        assertTrue(editor.undo());
        assertEquals(original, editor.document());
        assertTrue(editor.redo());
        assertTrue(editor.deleteTableBlock(1));
        assertEquals(List.of(paragraph("before"), paragraph("after")), editor.document().blocks());
        assertTrue(editor.undo());
        assertEquals(1, ((TableBlock) editor.document().blocks().get(1)).columnCount());
    }

    @Test
    void deletingLastTableRowOrColumnKeepsAnEmptyPageAndItsBoundaries() {
        TableBlock table = new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of())))));
        WikiDocument original = document(List.of(paragraph("before"), PageBreak.INSTANCE,
                table, PageBreak.INSTANCE, paragraph("after")));
        WikiDocumentEditor editor = new WikiDocumentEditor(original);
        assertTrue(editor.deleteTableRow(2, 0));
        assertEquals(List.of(paragraph("before"), PageBreak.INSTANCE, new Paragraph(List.of()),
                PageBreak.INSTANCE, paragraph("after")), editor.document().blocks());
        assertTrue(editor.undo());
        assertEquals(original, editor.document());
        assertTrue(editor.deleteTableColumn(2, 0));
        assertEquals(3, DocumentPages.count(editor.document()));
        WikiDocumentEditor only = new WikiDocumentEditor(document(List.of(table)));
        assertTrue(only.deleteTableBlock(0));
        assertEquals(List.of(new Paragraph(List.of())), only.document().blocks());
    }

    @Test
    void invalidTableDeletionDoesNotChangeDocumentOrUndo() {
        WikiDocument original = document(List.of(paragraph("text")));
        WikiDocumentEditor editor = new WikiDocumentEditor(original);
        assertFalse(editor.deleteTableBlock(0));
        assertFalse(editor.deleteTableBlock(-1));
        assertFalse(editor.deleteTableRow(0, 0));
        assertFalse(editor.deleteTableColumn(3, 0));
        assertEquals(original, editor.document());
        assertFalse(editor.undo());
    }

    private static Paragraph paragraph(String text) {
        return new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)));
    }

    private static WikiDocument document(List<Block> blocks) {
        UUID author = new UUID(0, 42);
        DocumentMetadata metadata = new DocumentMetadata(
                author, "测试", Instant.EPOCH, author, "测试", Instant.EPOCH);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "标题", blocks, metadata);
    }
}
