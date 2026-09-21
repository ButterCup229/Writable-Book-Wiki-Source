package com.editbookwiki.core.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Versioned, deterministic serialization for {@link WikiDocument}.
 *
 * <p>The encoding is an internal storage format, not player-authored Markdown
 * and not a Minecraft packet. Network transport later splits the resulting
 * bytes into chunks. Its deterministic content-only encoding is the source of
 * truth for copy-on-write hashes.</p>
 */
public final class WikiDocumentCodec {
    private static final int MAGIC = 0x45574431; // EWD1
    private static final int PARAGRAPH = 1;
    private static final int HEADING = 2;
    private static final int IMAGE = 3;
    private static final int PAGE_BREAK = 4;
    private static final int QUOTE = 5;
    private static final int TABLE = 6;
    private static final int RICH_TEXT = 7;
    private static final int RULE = 8;
    private static final int MIN_BLOCK_BYTES = 1;
    private static final int MIN_TEXT_RUN_BYTES = Integer.BYTES + 3 + Integer.BYTES + Integer.BYTES;
    private static final int MIN_TABLE_ROW_BYTES = Integer.BYTES;
    private static final int MIN_TABLE_CELL_BYTES = Integer.BYTES;

    /** Serializes content and audit metadata for storage. */
    public byte[] encode(WikiDocument document) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeHeader(output, storageVersion(document));
                writeString(output, document.title());
                writeMetadata(output, document.metadata());
                writeBlocks(output, document.blocks(), storageVersion(document));
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Byte-array document encoding unexpectedly failed", exception);
        }
    }

    /** Decodes complete stored document data and rejects any trailing bytes. */
    public WikiDocument decode(byte[] data) {
        if (data == null) {
            throw new DocumentFormatException("Document data cannot be null");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int formatVersion = readHeader(input);
            String title = readString(input);
            DocumentMetadata metadata = readMetadata(input);
            List<Block> blocks = readBlocks(input, formatVersion);
            if (input.available() != 0) {
                throw new DocumentFormatException("Document contains trailing bytes");
            }
            // Older versions predate tables and/or image placement. Decode
            // them safely, then migrate in memory so the next save uses the
            // current format.
            return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, title, blocks, metadata);
        } catch (DocumentFormatException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new DocumentFormatException("Document data is truncated", exception);
        } catch (IOException | IllegalArgumentException | DateTimeException exception) {
            throw new DocumentFormatException("Document data is malformed", exception);
        }
    }

    /**
     * Returns the SHA-256 hash of visible document content only.
     * Metadata is excluded so audit updates do not fork physical book copies.
     */
    public String contentHash(WikiDocument document) {
        return sha256(encodeContent(document));
    }

    /**
     * Returns the content hash represented by an already-stored document.
     *
     * <p>This deliberately retains the original serialized format header. It
     * is used only to recognise a pointer created by an older supported
     * document format before the document is migrated in memory. Normal
     * callers should use {@link #contentHash(WikiDocument)} for a current
     * document instead.</p>
     *
     * @throws DocumentFormatException when {@code data} is not a complete,
     *         valid stored Wiki document
     */
    public String storedContentHash(byte[] data) {
        // Decode first so this method never accepts a partly-valid prefix or
        // unknown blocks merely because their bytes happen to hash correctly.
        decode(data);
        int titleEnd = skipSerializedString(data, Integer.BYTES * 2);
        int blockOffset = skipSerializedMetadata(data, titleEnd);
        byte[] content = new byte[titleEnd + data.length - blockOffset];
        System.arraycopy(data, 0, content, 0, titleEnd);
        System.arraycopy(data, blockOffset, content, titleEnd, data.length - blockOffset);
        return sha256(content);
    }

    private byte[] encodeContent(WikiDocument document) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeHeader(output, storageVersion(document));
                writeString(output, document.title());
                writeBlocks(output, document.blocks(), storageVersion(document));
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Byte-array content encoding unexpectedly failed", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Skips the six fixed-shape metadata fields without copying their values. */
    private static int skipSerializedMetadata(byte[] data, int offset) {
        offset = skipBytes(data, offset, Long.BYTES * 2, "creator UUID");
        offset = skipSerializedString(data, offset);
        offset = skipBytes(data, offset, Long.BYTES + Integer.BYTES, "creation time");
        offset = skipBytes(data, offset, Long.BYTES * 2, "last-editor UUID");
        offset = skipSerializedString(data, offset);
        return skipBytes(data, offset, Long.BYTES + Integer.BYTES, "modification time");
    }

    private static int skipSerializedString(byte[] data, int offset) {
        if (offset < 0 || offset > data.length - Integer.BYTES) {
            throw new DocumentFormatException("String length is missing");
        }
        int length = ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
        if (length < 0 || length > data.length - offset - Integer.BYTES) {
            throw new DocumentFormatException("String length is invalid");
        }
        return offset + Integer.BYTES + length;
    }

    private static int skipBytes(byte[] data, int offset, int count, String fieldName) {
        if (offset < 0 || count < 0 || count > data.length - offset) {
            throw new DocumentFormatException(fieldName + " is truncated");
        }
        return offset + count;
    }

    private static int storageVersion(WikiDocument document) {
        if (document.formatVersion() != WikiDocument.CURRENT_FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported document format version: " + document.formatVersion());
        // Unmodified v6 books keep their exact content hash and local comment namespace.
        return document.blocks().stream().anyMatch(b -> b.frame() != null) ? 7 : 6;
    }

    private static void writeHeader(DataOutputStream output, int formatVersion) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(formatVersion);
    }

    private static int readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new DocumentFormatException("Document magic is invalid");
        }
        int formatVersion = input.readInt();
        if (formatVersion < 2 || formatVersion > WikiDocument.CURRENT_FORMAT_VERSION) {
            throw new DocumentFormatException("Unsupported document format version: " + formatVersion);
        }
        return formatVersion;
    }

    private static void writeMetadata(DataOutputStream output, DocumentMetadata metadata) throws IOException {
        writeUuid(output, metadata.creatorId());
        writeString(output, metadata.creatorName());
        writeInstant(output, metadata.createdAt());
        writeUuid(output, metadata.lastEditorId());
        writeString(output, metadata.lastEditorName());
        writeInstant(output, metadata.modifiedAt());
    }

    private static DocumentMetadata readMetadata(DataInputStream input) throws IOException {
        return new DocumentMetadata(
                readUuid(input), readString(input), readInstant(input),
                readUuid(input), readString(input), readInstant(input));
    }

    private static void writeBlocks(DataOutputStream output, List<Block> blocks, int formatVersion) throws IOException {
        output.writeInt(blocks.size());
        for (Block block : blocks) {
            if (block instanceof Paragraph paragraph) {
                output.writeByte(PARAGRAPH);
                writeRuns(output, paragraph.runs());
            } else if (block instanceof RichTextBlock rich) {
                output.writeByte(RICH_TEXT);
                output.writeByte(rich.kind().ordinal());
                output.writeByte(rich.alignment().ordinal());
                output.writeByte(rich.headingLevel());
                output.writeBoolean(rich.checked());
                writeRuns(output, rich.runs());
            } else if (block instanceof HorizontalRule) {
                output.writeByte(RULE);
            } else if (block instanceof Heading heading) {
                output.writeByte(HEADING);
                output.writeByte(heading.level());
                writeRuns(output, heading.runs());
            } else if (block instanceof QuoteBlock quote) {
                output.writeByte(QUOTE);
                writeString(output, quote.source());
                writeRuns(output, quote.runs());
            } else if (block instanceof ImageBlock image) {
                output.writeByte(IMAGE);
                writeString(output, image.assetHash());
                writeString(output, image.caption());
                output.writeByte(image.alignment().ordinal());
                output.writeInt(image.requestedWidth());
                output.writeByte(image.layoutMode().ordinal());
                output.writeInt(image.offsetX());
                output.writeInt(image.offsetY());
            } else if (block instanceof PageBreak) {
                output.writeByte(PAGE_BREAK);
            } else if (block instanceof TableBlock table) {
                output.writeByte(TABLE);
                writeTable(output, table);
            } else {
                throw new IllegalArgumentException("Unsupported block type: " + block.getClass().getName());
            }
            if (formatVersion >= 7) {
                BlockFrame frame = block.frame();
                output.writeByte(frame == null ? 0 : frame.kind().ordinal() + 1);
                if (frame != null) output.writeInt(frame.group());
            }
        }
    }

    private static List<Block> readBlocks(DataInputStream input, int formatVersion) throws IOException {
        int count = readCount(input, "block count", MIN_BLOCK_BYTES);
        // Do not trust a declared count for the initial allocation. The input
        // may be malformed and the actual entries are still read incrementally.
        List<Block> blocks = new ArrayList<>(Math.min(count, 1024));
        for (int index = 0; index < count; index++) {
            switch (input.readUnsignedByte()) {
                case RULE -> {
                    if (formatVersion < 6) throw new DocumentFormatException("Rule requires format 6");
                    blocks.add(HorizontalRule.INSTANCE);
                }
                case RICH_TEXT -> {
                    if (formatVersion < 6) throw new DocumentFormatException("Rich text requires format 6");
                    int kind = input.readUnsignedByte(), align = input.readUnsignedByte();
                    if (kind >= RichTextBlock.Kind.values().length || align >= RichTextBlock.Alignment.values().length)
                        throw new DocumentFormatException("Invalid paragraph style");
                    blocks.add(new RichTextBlock(RichTextBlock.Kind.values()[kind], RichTextBlock.Alignment.values()[align],
                            input.readUnsignedByte(), readBoolean(input), readRuns(input, formatVersion)));
                }
                case PARAGRAPH -> blocks.add(new Paragraph(readRuns(input, formatVersion)));
                case HEADING -> blocks.add(new Heading(input.readUnsignedByte(), readRuns(input, formatVersion)));
                case QUOTE -> {
                    if (formatVersion < 3) {
                        throw new DocumentFormatException("Quote block requires document format 3");
                    }
                    blocks.add(new QuoteBlock(readString(input), readRuns(input, formatVersion)));
                }
                case IMAGE -> {
                    String assetHash = readString(input);
                    String caption = readString(input);
                    ImageAlignment alignment = ImageAlignment.fromWireId(input.readUnsignedByte());
                    int requestedWidth = input.readInt();
                    if (formatVersion < 5) {
                        blocks.add(new ImageBlock(assetHash, caption, alignment, requestedWidth));
                    } else {
                        blocks.add(new ImageBlock(assetHash, caption, alignment, requestedWidth,
                                ImageLayoutMode.fromWireId(input.readUnsignedByte()), input.readInt(), input.readInt()));
                    }
                }
                case PAGE_BREAK -> blocks.add(PageBreak.INSTANCE);
                case TABLE -> {
                    if (formatVersion < 4) {
                        throw new DocumentFormatException("Table block requires document format 4");
                    }
                    blocks.add(readTable(input, formatVersion));
                }
                default -> throw new DocumentFormatException("Unknown document block type");
            }
            if (formatVersion >= 7) {
                int frameKind = input.readUnsignedByte();
                if (frameKind > BlockFrame.Kind.values().length) throw new DocumentFormatException("Invalid frame kind");
                if (frameKind != 0) blocks.set(index, BlockFrame.attach(blocks.get(index),
                        new BlockFrame(BlockFrame.Kind.values()[frameKind - 1], input.readInt())));
            }
        }
        return List.copyOf(blocks);
    }

    private static void writeTable(DataOutputStream output, TableBlock table) throws IOException {
        output.writeInt(table.rows().size());
        for (TableRow row : table.rows()) {
            output.writeInt(row.cells().size());
            for (TableCell cell : row.cells()) {
                writeRuns(output, cell.runs());
            }
        }
    }

    private static TableBlock readTable(DataInputStream input, int formatVersion) throws IOException {
        int rowCount = readCount(input, "table row count", MIN_TABLE_ROW_BYTES);
        if (rowCount == 0) {
            throw new DocumentFormatException("A table must contain at least one row");
        }
        List<TableRow> rows = new ArrayList<>(Math.min(rowCount, 1024));
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            int cellCount = readCount(input, "table cell count", MIN_TABLE_CELL_BYTES);
            if (cellCount == 0) {
                throw new DocumentFormatException("A table row must contain at least one cell");
            }
            List<TableCell> cells = new ArrayList<>(Math.min(cellCount, 1024));
            for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
                cells.add(new TableCell(readRuns(input, formatVersion)));
            }
            rows.add(new TableRow(cells));
        }
        try {
            return new TableBlock(rows);
        } catch (IllegalArgumentException exception) {
            throw new DocumentFormatException("Table is malformed", exception);
        }
    }

    private static void writeRuns(DataOutputStream output, List<TextRun> runs) throws IOException {
        output.writeInt(runs.size());
        for (TextRun run : runs) {
            writeString(output, run.text());
            TextStyle style = run.style();
            output.writeBoolean(style.bold());
            output.writeBoolean(style.italic());
            output.writeBoolean(style.strikethrough());
            output.writeInt(style.rgbColor());
            output.writeInt(style.fontSize());
            output.writeBoolean(style.underline());
            output.writeByte(style.script());
            output.writeBoolean(style.hidden());
            output.writeBoolean(style.code());
            output.writeBoolean(run.isLink());
            if (run.isLink()) {
                writeString(output, run.linkTarget());
            }
        }
    }

    private static List<TextRun> readRuns(DataInputStream input, int formatVersion) throws IOException {
        int count = readCount(input, "text-run count", MIN_TEXT_RUN_BYTES);
        // See readBlocks: a hostile count must not force a large eager array.
        List<TextRun> runs = new ArrayList<>(Math.min(count, 1024));
        for (int index = 0; index < count; index++) {
            String text = readString(input);
            TextStyle style = new TextStyle(
                    readBoolean(input), readBoolean(input), readBoolean(input), input.readInt(), input.readInt());
            if (formatVersion >= 6) style = new TextStyle(style.bold(), style.italic(), style.strikethrough(),
                    style.rgbColor(), style.fontSize(), readBoolean(input), input.readByte(), readBoolean(input), readBoolean(input));
            String linkTarget = formatVersion >= 3 && readBoolean(input) ? readString(input) : null;
            runs.add(new TextRun(text, style, linkTarget));
        }
        return List.copyOf(runs);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = encodeUtf8(value);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new DocumentFormatException("String length is invalid");
        }
        byte[] utf8 = input.readNBytes(length);
        if (utf8.length != length) {
            throw new EOFException();
        }
        return decodeUtf8(utf8);
    }

    private static int readCount(DataInputStream input, String label, int minimumEntryBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > input.available() / minimumEntryBytes) {
            throw new DocumentFormatException(label + " is invalid");
        }
        return count;
    }

    private static boolean readBoolean(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) {
            throw new DocumentFormatException("Boolean value is invalid");
        }
        return value == 1;
    }

    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("String contains invalid Unicode", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DocumentFormatException("String is not valid UTF-8", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }
}
