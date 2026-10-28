package com.writablebookwiki.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WikiDocumentCodecTest {
    private final WikiDocumentCodec codec = new WikiDocumentCodec();

    @Test
    void roundTripsUnicodeStructuredContentAndMetadata() {
        WikiDocument original = exampleDocument();

        byte[] encoded = codec.encode(original);

        assertEquals(original, codec.decode(encoded));
    }

    @Test
    void contentHashIgnoresAuditMetadataButDetectsVisibleContentChanges() {
        WikiDocument original = exampleDocument();
        DocumentMetadata editedMetadata = new DocumentMetadata(
                original.metadata().creatorId(),
                original.metadata().creatorName(),
                original.metadata().createdAt(),
                UUID.fromString("e43d1e4d-88eb-4e5f-a4e9-2e4f2d91e5b1"),
                "Bob",
                Instant.parse("2026-09-12T13:30:00Z"));
        WikiDocument metadataOnlyChange = new WikiDocument(
                original.formatVersion(), original.title(), original.blocks(), editedMetadata);
        WikiDocument visibleChange = new WikiDocument(
                original.formatVersion(), "改过的标题", original.blocks(), original.metadata());

        assertEquals(codec.contentHash(original), codec.contentHash(metadataOnlyChange));
        assertNotEquals(codec.contentHash(original), codec.contentHash(visibleChange));
    }

    @Test
    void rejectsUnknownFormatAndTruncatedInput() {
        assertThrows(DocumentFormatException.class, () -> codec.decode(new byte[] {0x45, 0x57, 0x44, 0x31, 0, 0, 0, 3}));
        assertThrows(DocumentFormatException.class, () -> codec.decode(new byte[] {0x45, 0x57, 0x44}));
    }

    @Test
    void rejectsInvalidStyleValues() {
        assertThrows(IllegalArgumentException.class, () -> new TextStyle(false, false, false, -1, 16));
        assertThrows(IllegalArgumentException.class, () -> new TextStyle(false, false, false, 0xFFFFFF, 0));
    }

    @Test
    void rejectsMalformedUtf8AndNonCanonicalBooleanValues() {
        byte[] malformedText = codec.encode(singleRunDocument());
        malformedText[malformedText.length - 16] = (byte) 0xC0;
        assertThrows(DocumentFormatException.class, () -> codec.decode(malformedText));

        byte[] malformedBoolean = codec.encode(singleRunDocument());
        malformedBoolean[malformedBoolean.length - 15] = 2;
        assertThrows(DocumentFormatException.class, () -> codec.decode(malformedBoolean));
    }

    @Test
    void rejectsDeclaredCountsThatCannotFitTheRemainingData() {
        byte[] malformedCount = codec.encode(singleRunDocument());
        malformedCount[malformedCount.length - 23] = 0x7F;

        assertThrows(DocumentFormatException.class, () -> codec.decode(malformedCount));
    }

    @Test
    void refusesToEncodeUnpairedUnicodeSurrogates() {
        WikiDocument invalidText = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                new String(new char[] {'b', 'a', 'd', (char) 0xD800}),
                List.of(),
                exampleDocument().metadata());

        assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidText));
    }

    @Test
    void imageReferenceIsStructuredContentAndRejectsInvalidHashes() {
        String assetHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        ImageBlock image = new ImageBlock(assetHash.toUpperCase(), "地牢入口", ImageAlignment.CENTER, 256,
                ImageLayoutMode.FLOATING, -32, 48);
        WikiDocument withImage = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "图片测试",
                List.of(image),
                exampleDocument().metadata());
        WikiDocument changedImage = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "图片测试",
                List.of(new ImageBlock(assetHash.replace('0', '1'), "地牢入口", ImageAlignment.CENTER, 256)),
                exampleDocument().metadata());

        assertEquals(assetHash, image.assetHash());
        assertEquals(ImageLayoutMode.FLOATING, image.layoutMode());
        assertEquals(withImage, codec.decode(codec.encode(withImage)));
        assertNotEquals(codec.contentHash(withImage), codec.contentHash(changedImage));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageBlock("not-a-sha256", "", ImageAlignment.LEFT, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageBlock(assetHash, "", ImageAlignment.LEFT, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageBlock(assetHash, "", ImageAlignment.LEFT, 0, ImageLayoutMode.COVER, 16_385, 0));
    }

    @Test
    void pageBreakRoundTripsAndChangesVisibleContentHash() {
        WikiDocument withBreak = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "分页测试",
                List.of(new Paragraph(List.of(new TextRun("第一章", TextStyle.DEFAULT))),
                        PageBreak.INSTANCE,
                        new Heading(1, List.of(new TextRun("第二章", TextStyle.DEFAULT)))),
                exampleDocument().metadata());
        WikiDocument withoutBreak = new WikiDocument(
                withBreak.formatVersion(), withBreak.title(),
                List.of(new Paragraph(List.of(new TextRun("第一章", TextStyle.DEFAULT))),
                        new Heading(1, List.of(new TextRun("第二章", TextStyle.DEFAULT)))),
                withBreak.metadata());

        assertEquals(withBreak, codec.decode(codec.encode(withBreak)));
        assertNotEquals(codec.contentHash(withBreak), codec.contentHash(withoutBreak));
    }

    @Test
    void quoteAndInternalLinkRoundTripAsVisibleStructuredContent() {
        TextRun link = new TextRun("前往第二章", new TextStyle(false, false, false, 0xC87900, 16), "第二章");
        QuoteBlock quote = new QuoteBlock("《旅行者手记》", List.of(
                new TextRun("不要把引用当作普通背景色文字。", TextStyle.DEFAULT)));
        WikiDocument original = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "链接与引用",
                List.of(new Paragraph(List.of(link)), quote),
                exampleDocument().metadata());
        WikiDocument differentTarget = new WikiDocument(
                original.formatVersion(), original.title(),
                List.of(new Paragraph(List.of(new TextRun(link.text(), link.style(), "第三章"))), quote),
                original.metadata());

        assertEquals(original, codec.decode(codec.encode(original)));
        assertNotEquals(codec.contentHash(original), codec.contentHash(differentTarget));
    }

    @Test
    void tableRoundTripsRichCellsAndParticipatesInTheContentHash() {
        TextStyle headerStyle = new TextStyle(true, false, false, 0x332211, 14);
        TableBlock table = new TableBlock(List.of(
                new TableRow(List.of(
                        new TableCell(List.of(new TextRun("名称", headerStyle))),
                        new TableCell(List.of(new TextRun("描述", headerStyle))))),
                new TableRow(List.of(
                        new TableCell(List.of(new TextRun("末地城", TextStyle.DEFAULT))),
                        new TableCell(List.of(
                                new TextRun("前往", TextStyle.DEFAULT),
                                new TextRun("第二章", new TextStyle(false, true, false, 0xC87900, 11), "第二章")))))));
        WikiDocument original = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION, "表格", List.of(table), exampleDocument().metadata());
        WikiDocument changed = new WikiDocument(
                original.formatVersion(), original.title(), List.of(new TableBlock(List.of(
                        table.rows().getFirst(),
                        new TableRow(List.of(
                                new TableCell(List.of(new TextRun("末地城", TextStyle.DEFAULT))),
                                new TableCell(List.of(new TextRun("已修改", TextStyle.DEFAULT)))))))),
                original.metadata());

        assertEquals(2, table.columnCount());
        assertEquals(List.of(new TextRun("名称", headerStyle), new TextRun("描述", headerStyle),
                new TextRun("末地城", TextStyle.DEFAULT), new TextRun("前往", TextStyle.DEFAULT),
                new TextRun("第二章", new TextStyle(false, true, false, 0xC87900, 11), "第二章")), table.runs());
        assertEquals(original, codec.decode(codec.encode(original)));
        assertNotEquals(codec.contentHash(original), codec.contentHash(changed));
    }

    @Test
    void tableShapeIsValidatedAndOlderDocumentsRemainReadable() {
        assertThrows(IllegalArgumentException.class, () -> new TableBlock(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TableRow(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TableBlock(List.of(
                new TableRow(List.of(new TableCell(List.of()))),
                new TableRow(List.of(new TableCell(List.of()), new TableCell(List.of()))))));

        WikiDocument noTable = singleRunDocument();
        byte[] versionThree = legacySingleRun(codec.encode(noTable));
        versionThree[Integer.BYTES * 2 - 1] = 3;
        assertEquals(noTable, codec.decode(versionThree));

        WikiDocument withTable = new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION, "", List.of(new TableBlock(List.of(
                        new TableRow(List.of(new TableCell(List.of())))))), noTable.metadata());
        byte[] invalidVersionThreeTable = codec.encode(withTable);
        invalidVersionThreeTable[Integer.BYTES * 2 - 1] = 3;
        assertThrows(DocumentFormatException.class, () -> codec.decode(invalidVersionThreeTable));
    }

    @Test
    void storedContentHashRecognisesAReadableLegacyFormatBeforeMigration() {
        byte[] versionThree = legacySingleRun(codec.encode(singleRunDocument()));
        versionThree[Integer.BYTES * 2 - 1] = 3;

        WikiDocument migrated = codec.decode(versionThree);

        // The old physical-book pointer was calculated with the old v3
        // header, while decoding intentionally migrates the model to v4.
        // The repository needs both facts to recover that real book safely.
        assertNotEquals(codec.contentHash(migrated), codec.storedContentHash(versionThree));
        assertThrows(DocumentFormatException.class,
                () -> codec.storedContentHash(new byte[] {0x45, 0x57, 0x44, 0x31}));
    }

    private static byte[] legacySingleRun(byte[] current) {
        // Version 3 had no underline/script/spoiler/code fields before the final link flag.
        byte[] legacy = java.util.Arrays.copyOf(current, current.length - 4);
        legacy[legacy.length - 1] = current[current.length - 1];
        return legacy;
    }

    @Test
    void migratesAllPreviousInlineFormatsAndVersionFiveImagePositions() {
        WikiDocument expected = singleRunDocument();
        for (int version = 2; version <= 5; version++) {
            byte[] bytes = legacySingleRun(codec.encode(expected));
            if (version == 2) bytes = java.util.Arrays.copyOf(bytes, bytes.length - 1);
            bytes[7] = (byte) version;
            assertEquals(expected, codec.decode(bytes), "legacy format " + version);
        }
        WikiDocument positioned = new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "image",
                List.of(new ImageBlock("a".repeat(64), "caption", ImageAlignment.LEFT, 160,
                        ImageLayoutMode.COVER, 20, 30)), expected.metadata());
        byte[] bytes = codec.encode(positioned);
        bytes[7] = 5;
        assertEquals(positioned, codec.decode(bytes));
        assertNotEquals(codec.contentHash(positioned), codec.storedContentHash(bytes));
    }

    private static WikiDocument singleRunDocument() {
        return new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "",
                List.of(new Paragraph(List.of(new TextRun("x", TextStyle.DEFAULT)))),
                exampleDocument().metadata());
    }

    private static WikiDocument exampleDocument() {
        DocumentMetadata metadata = new DocumentMetadata(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "Alice",
                Instant.parse("2026-09-12T12:00:00Z"),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"),
                "Alice",
                Instant.parse("2026-09-12T12:05:00Z"));
        TextStyle headingStyle = new TextStyle(true, false, false, 0x336699, 22);
        TextStyle emphasisStyle = new TextStyle(false, true, false, 0x111111, 16);
        return new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "第一本编辑书",
                List.of(
                        new Heading(1, List.of(new TextRun("欢迎", headingStyle))),
                        new Paragraph(List.of(
                                new TextRun("这是可编辑的 ", TextStyle.DEFAULT),
                                new TextRun("富文本", emphasisStyle),
                                new TextRun(" 文档。", TextStyle.DEFAULT))),
                        new ImageBlock(
                                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                                "示例图片", ImageAlignment.CENTER, 320)),
                metadata);
    }
}
