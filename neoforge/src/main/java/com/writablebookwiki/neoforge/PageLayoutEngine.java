package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * One page measurement pass, independent of Screen events, save sessions and network state.
 * Rendering and hit testing consume the same immutable snapshot. Drag previews do not edit the document.
 */
final class PageLayoutEngine {
    static final int TABLE_CELL_PADDING = 4;
    static final int TABLE_ROW_HEIGHT = 20;

    private final WikiDocument document;
    private final Font font;
    private final Set<Integer> collapsedBlocks;
    private final Function<String, WikiImageTextures.LoadedImage> images;
    private final double guiScale;
    private final ResizePreview imageResize;
    private final MovePreview imageMove;

    PageLayoutEngine(WikiDocument document, Font font, Set<Integer> collapsedBlocks,
            Function<String, WikiImageTextures.LoadedImage> images, double guiScale,
            ResizePreview imageResize, MovePreview imageMove) {
        this.document = Objects.requireNonNull(document);
        this.font = Objects.requireNonNull(font);
        this.collapsedBlocks = Set.copyOf(collapsedBlocks);
        this.images = Objects.requireNonNull(images);
        this.guiScale = Math.max(1, guiScale);
        this.imageResize = imageResize;
        this.imageMove = imageMove;
    }

    Snapshot layout(int pageIndex, int bodyWidth) {
        List<LayoutLine> result = new ArrayList<>();
        List<ImageLayout> renderedImages = new ArrayList<>();
        List<TableLayout> renderedTables = new ArrayList<>();
        List<FrameLayout> renderedFrames = new ArrayList<>();
        List<TextFlowExclusion> wrappingImages = new ArrayList<>();
        int y = 0;
        DocumentPages.PageRange pageRange = DocumentPages.range(document, pageIndex);

        // Word treats every non-inline image as a page object first, not as a
        // paragraph-sized block.  Resolve those objects before measuring any
        // prose so an image moved into the middle of an existing paragraph
        // makes that prose flow on both its left and its right.
        for (int blockIndex = pageRange.startInclusive(); blockIndex < pageRange.endExclusive(); blockIndex++) {
            Block block = document.blocks().get(blockIndex);
            if (block instanceof ImageBlock image && image.frame() == null && image.layoutMode() != ImageLayoutMode.INLINE) {
                ImageAppend positioned = appendImage(renderedImages, 0, blockIndex, image, bodyWidth);
                if (positioned.flowExclusion() != null) {
                    wrappingImages.add(positioned.flowExclusion());
                }
            }
        }
        int frameStart = -1, frameEnd = -1, frameY = 0, frameFirstLine = 0, frameFirstImage = 0, frameFirstTable = 0, frameFirstExclusion = 0;
        for (int blockIndex = pageRange.startInclusive(); blockIndex < pageRange.endExclusive(); blockIndex++) {
            Block block = document.blocks().get(blockIndex);
            BlockFrame frame = block.frame();
            if (frame != null && blockIndex > frameEnd) {
                y = advancePastWrappingImages(y, wrappingImages);
                frameStart = blockIndex; frameEnd = blockIndex;
                while (frameEnd + 1 < pageRange.endExclusive() && frame.equals(document.blocks().get(frameEnd + 1).frame())) frameEnd++;
                frameY = y; frameFirstLine = result.size(); frameFirstImage = renderedImages.size(); frameFirstTable = renderedTables.size();
                frameFirstExclusion = wrappingImages.size();
                y += 4;
                for (int member = frameStart; member <= frameEnd; member++) {
                    if (document.blocks().get(member) instanceof ImageBlock image && image.layoutMode() != ImageLayoutMode.INLINE) {
                        ImageAppend positioned = appendImage(renderedImages, y, member, image,
                                bodyWidth - frameInset(frame) - 12, frameInset(frame), y);
                        if (positioned.flowExclusion() != null) wrappingImages.add(positioned.flowExclusion());
                    }
                }
            }
            int firstLine = result.size();
            if (block instanceof Heading heading) {
                y = appendTextBlock(result, y, blockIndex, heading.runs(), bodyWidth, headingIndent(heading.level()),
                        false, headingScale(heading.level()), true, wrappingImages);
            } else if (block instanceof RichTextBlock rich) {
                if (rich.alignment() != RichTextBlock.Alignment.LEFT) y = advancePastWrappingImages(y, wrappingImages);
                y = appendRichBlock(result, y, blockIndex, rich, bodyWidth, wrappingImages);
            } else if (block instanceof HorizontalRule) {
                y = advancePastWrappingImages(y, wrappingImages);
                result.add(new LayoutLine(blockIndex, 0, 0, y, 12, 0, false, List.of()));
                y += 18;
            } else if (block instanceof Paragraph paragraph) {
                y = appendTextBlock(result, y, blockIndex, paragraph.runs(), bodyWidth, 0, false, 1f, false,
                        wrappingImages);
            } else if (block instanceof QuoteBlock quote) {
                y = appendTextBlock(result, y, blockIndex, quote.runs(), bodyWidth, 8, true, 1f, false,
                        wrappingImages);
                if (!quote.source().isBlank()) {
                    y = appendTextBlock(result, y - 2, -1,
                            List.of(new TextRun("— " + quote.source(), new TextStyle(false, true, false, 0x62594E, 10))),
                            bodyWidth, 14, true, 0.85f, false, wrappingImages);
                }
            } else if (block instanceof ImageBlock image) {
                if (image.layoutMode() == ImageLayoutMode.INLINE) {
                    y = appendImage(renderedImages, y, blockIndex, image,
                            bodyWidth - (frame == null ? 0 : frameInset(frame) + 12),
                            frameInset(frame), frame == null ? 0 : frameY + 4).nextY();
                }
            } else if (block instanceof TableBlock table) {
                // Tables remain block-level in this phase.  Do not let one
                // accidentally paint through a square-wrapped picture.
                y = advancePastWrappingImages(y, wrappingImages);
                y = appendTable(renderedTables, y, blockIndex, table, bodyWidth, wrappingImages);
            }
            if (collapsedBlocks.contains(blockIndex) && canFold(document, blockIndex) && result.size() > firstLine + 1) {
                LayoutLine summary = result.get(firstLine);
                result.subList(firstLine + 1, result.size()).clear();
                y = summary.y() + summary.height() + 7;
            }
            if (frame != null && blockIndex == frameEnd) {
                for (int i = frameFirstImage; i < renderedImages.size(); i++) {
                    ImageLayout image = renderedImages.get(i);
                    y = Math.max(y, image.y() + image.height() + 4);
                }
                boolean foldable = frameEnd > frameStart || result.size() > frameFirstLine + 1;
                if (frameEnd == frameStart && block instanceof RichTextBlock rich && rich.isTask()) foldable = false;
                if (foldable && collapsedBlocks.contains(frameStart)) {
                    LayoutLine summary = result.size() > frameFirstLine ? result.get(frameFirstLine) : null;
                    result.subList(frameFirstLine, result.size()).clear();
                    if (summary != null) result.add(new LayoutLine(frameStart, summary.startColumn(), summary.endColumn(),
                            frameY + 7, summary.height(), summary.indent(), false, summary.fragments()));
                    renderedImages.subList(frameFirstImage, renderedImages.size()).clear();
                    renderedTables.subList(frameFirstTable, renderedTables.size()).clear();
                    y = frameY + (summary == null ? 14 : summary.height()) + 12;
                }
                // Images from this frame must never wrap unrelated text outside it.
                wrappingImages.subList(frameFirstExclusion, wrappingImages.size()).clear();
                renderedFrames.add(new FrameLayout(frameStart, frameEnd, frameY, y - frameY, frame, foldable));
                y += 7;
            }
        }

        int overlayBottom = 0;
        for (ImageLayout image : renderedImages) {
            overlayBottom = Math.max(overlayBottom, image.y() + image.height());
        }
        return new Snapshot(List.copyOf(result), List.copyOf(renderedImages), List.copyOf(renderedTables),
                List.copyOf(renderedFrames), Math.max(y, overlayBottom));
    }

    private TextFlowLayout.Result flowText(List<TextRun> runs, int width, int indent, float scale,
            int y, int gap, List<TextFlowExclusion> exclusions) {
        return TextFlowLayout.layout(runs, Math.max(indent + 1, width), indent, scale, y, gap,
                exclusions.stream().map(e -> new TextFlowLayout.Exclusion(
                        e.gapStart(), e.topY(), e.gapEnd(), e.bottomY())).toList(),
                new TextFlowLayout.Metrics() {
                    @Override
                    public float advance(String text, TextStyle style, float fontScale) {
                        return font.width(styled(text, style)) * fontScale;
                    }

                    @Override
                    public int height(float fontScale) {
                        return Math.max(1, (int) Math.ceil(font.lineHeight * fontScale));
                    }
                });
    }

    private static List<Fragment> fragments(TextFlowLayout.Line line) {
        return line.glyphs().stream().map(g -> new Fragment(g.text(), g.style(), g.scale(),
                g.link(), g.start(), g.end(), g.x(), g.advance(), styled(g.text(), g.style()))).toList();
    }

    private int appendTextBlock(List<LayoutLine> result, int y, int blockIndex, List<TextRun> runs, int totalWidth,
            int indent, boolean quote, float blockScale, boolean heading, List<TextFlowExclusion> flowExclusions) {
        TextFlowLayout.Result layout = flowText(runs, totalWidth - (quote ? 7 : 0) - (canFold(document, blockIndex) ? 12 : 0),
                indent, blockScale, y, 2, flowExclusions);
        for (TextFlowLayout.Line line : layout.lines()) {
            result.add(new LayoutLine(blockIndex, line.start(), line.end(), line.y(), line.height(),
                    indent, quote, fragments(line)));
        }
        return layout.bottom() + (heading ? 5 : 4);
    }

    private int appendRichBlock(List<LayoutLine> result, int y, int index, RichTextBlock rich, int width,
            List<TextFlowExclusion> exclusions) {
        if (canFold(document, index)) width -= 12;
        int indent = switch(rich.kind()) {
            case BULLET, NUMBERED -> 24; case TASK -> 18; case QUOTE_TASK -> 24; case QUOTE, CODE -> 8;
            case INFO, WARNING -> 14; case STEP -> 42; default -> 0;
        };
        if (rich.frame() != null) {
            indent = frameInset(rich.frame()) + switch (rich.kind()) {
                case TASK, QUOTE_TASK -> 14;
                case BULLET, NUMBERED -> 24;
                case STEP -> 42;
                default -> 0;
            };
            width -= 8;
        }
        if (rich.isList()) indent = frameInset(rich.frame()) + listIndent(index, rich.kind());
        float scale = rich.headingLevel() > 0 ? headingScale(rich.headingLevel()) : 1f;
        if ((rich.isTask() || rich.isList()) && !exclusions.isEmpty()) {
            // Wrapped pictures must avoid the checkbox/list marker as well as its text.
            int markerGutter = rich.isTask() ? 14 : font.width(listMarkerLabel(document, index, rich.kind())) + 4;
            exclusions = exclusions.stream().map(e -> new TextFlowExclusion(e.topY(), e.bottomY(), e.gapStart(), e.gapEnd() + markerGutter)).toList();
            if (WikiDocumentEditor.blockText(rich).isEmpty()) y = advancePastWrappingImages(y, exclusions);
        }
        TextFlowLayout.Result flow = flowText(rich.runs(), width - 4, indent, scale, y + 3, 2, exclusions);
        for (int i = 0; i < flow.lines().size(); i++) {
            TextFlowLayout.Line line = flow.lines().get(i);
            boolean last = i == flow.lines().size() - 1 || (line.end() < WikiDocumentEditor.blockText(rich).length()
                    && WikiDocumentEditor.blockText(rich).charAt(line.end()) == '\n');
            List<Fragment> fs = fragments(line);
            float end = fs.isEmpty() ? indent : fs.getLast().x()+fs.getLast().advance();
            float room = Math.max(0, width - 4 - end);
            float shift = switch(rich.alignment()) { case CENTER -> room/2; case RIGHT -> room; default -> 0; };
            int gaps = 0;
            if(rich.alignment() == RichTextBlock.Alignment.JUSTIFY && !last) {
                for(int n=0;n<fs.size()-1;n++) if(fs.get(n).text().isBlank()) gaps++;
            }
            boolean distribute = rich.alignment() == RichTextBlock.Alignment.JUSTIFY && !last;
            boolean every = gaps == 0;
            if(distribute && every) gaps = Math.max(0,fs.size()-1);
            List<Fragment> aligned = new ArrayList<>();
            float extra = shift;
            for(int n=0;n<fs.size();n++) {
                Fragment f=fs.get(n);
                float advance=f.advance();
                if(distribute && gaps>0 && n<fs.size()-1 && (every || f.text().isBlank())) advance += room/gaps;
                aligned.add(new Fragment(f.text(),f.style(),f.scale(),f.link(),f.startColumn(),f.endColumn(),f.x()+extra,advance,f.painted()));
                extra += advance-f.advance();
            }
            result.add(new LayoutLine(index,line.start(),line.end(),line.y(),line.height(),Math.round(indent+shift),false,aligned));
        }
        boolean continuesQuote = rich.isQuote() && index + 1 < document.blocks().size()
                && document.blocks().get(index + 1) instanceof TextualBlock following
                && RichTextBlock.from(following).isQuote();
        return flow.bottom() + (rich.frame() != null || continuesQuote ? 3 : 7);
    }

    static boolean canFold(WikiDocument document, int index) {
        return index >= 0 && index < document.blocks().size()
                && document.blocks().get(index).frame() == null
                && document.blocks().get(index) instanceof TextualBlock text
                && !(text instanceof RichTextBlock rich && rich.isTask())
                && WikiDocumentEditor.blockText(text).contains("\n");
    }

    static int frameInset(BlockFrame frame) {
        return frame == null ? 0 : frame.kind() == BlockFrame.Kind.QUOTE ? 8 : 14;
    }

    static int blockNumber(WikiDocument document, int index, RichTextBlock.Kind kind) {
        int count=1;
        BlockFrame frame = document.blocks().get(index).frame();
        for(int i=index-1;i>=0;i--) {
            Block block=document.blocks().get(i);
            if(block instanceof RichTextBlock r && r.kind()==kind && Objects.equals(frame, r.frame())) {count++;continue;}
            break;
        }
        return count;
    }

    static String listMarkerLabel(WikiDocument document, int index, RichTextBlock.Kind kind) {
        return kind == RichTextBlock.Kind.BULLET ? "•" : blockNumber(document, index, kind) + ".";
    }

    private int listIndent(int index, RichTextBlock.Kind kind) {
        if (kind == RichTextBlock.Kind.BULLET) return 24;
        int finalNumber = blockNumber(document, index, kind);
        BlockFrame frame = document.blocks().get(index).frame();
        for (int i = index + 1; i < document.blocks().size(); i++) {
            Block next = document.blocks().get(i);
            if (!(next instanceof RichTextBlock rich) || rich.kind() != kind || !Objects.equals(frame, rich.frame())) break;
            finalNumber++;
        }
        // Every item shares the width needed by the longest number, including 100 and beyond.
        return Math.max(24, font.width(finalNumber + ".") + 8);
    }

    private static int advancePastWrappingImages(int y, List<TextFlowExclusion> flowExclusions) {
        boolean advanced;
        do {
            advanced = false;
            for (TextFlowExclusion exclusion : flowExclusions) {
                if (y >= exclusion.topY() && y < exclusion.bottomY()) {
                    y = exclusion.bottomY();
                    advanced = true;
                }
            }
        } while (advanced);
        return y;
    }

    private int appendTable(List<TableLayout> result, int y, int blockIndex, TableBlock table, int bodyWidth,
            List<TextFlowExclusion> exclusions) {
        int xOffset = frameInset(table.frame());
        if (table.frame() != null) bodyWidth -= xOffset + 12;
        int tableWidth = Math.max(24, bodyWidth);
        int columns = table.columnCount();
        int baseColumnWidth = Math.max(1, tableWidth / columns);
        List<TableRowLayout> rows = new ArrayList<>(table.rows().size());
        int tableHeight = 0;
        for (TableRow row : table.rows()) {
            List<TableCellLayout> cells = new ArrayList<>(columns);
            int rowHeight = TABLE_ROW_HEIGHT;
            for (int column = 0; column < columns; column++) {
                int cellWidth = column == columns - 1
                        ? tableWidth - column * baseColumnWidth : baseColumnWidth;
                TableCellLayout cell = layoutTableCell(row.cells().get(column),
                        Math.max(1, cellWidth - TABLE_CELL_PADDING * 2));
                cells.add(cell);
                rowHeight = Math.max(rowHeight, cell.contentHeight() + TABLE_CELL_PADDING * 2);
            }
            rows.add(new TableRowLayout(tableHeight, rowHeight, List.copyOf(cells)));
            tableHeight += rowHeight;
        }
        if (table.frame() != null) {
            boolean advanced;
            do {
                advanced = false;
                for (TextFlowExclusion e : exclusions) if (y < e.bottomY() && y + tableHeight > e.topY()) {
                    y = e.bottomY(); advanced = true;
                }
            } while (advanced);
        }
        result.add(new TableLayout(blockIndex, y, xOffset, tableWidth, baseColumnWidth, columns, tableHeight,
                List.copyOf(rows), table));
        return y + tableHeight + 8;
    }

    private TableCellLayout layoutTableCell(TableCell cell, int availableWidth) {
        TextFlowLayout.Result layout = flowText(cell.runs(), availableWidth, 0, 1f, 0, 1, List.of());
        return new TableCellLayout(layout.lines().stream().map(line -> new TableCellLine(
                line.y(), line.height(), line.start(), line.end(), fragments(line))).toList(), layout.bottom());
    }

    private static float headingScale(int level) {
        return switch (level) {
            case 1 -> 1.52f;
            case 2 -> 1.28f;
            default -> 1.12f;
        };
    }

    private static int headingIndent(int level) {
        return switch (level) {
            case 1 -> 0;
            case 2 -> 4;
            default -> 8;
        };
    }

    static Component styled(String text, TextStyle style) {
        return Component.literal(text).withStyle(Style.EMPTY
                .withBold(style.bold())
                .withItalic(style.italic())
                .withStrikethrough(style.strikethrough())
                .withUnderlined(style.underline())
                .withColor(style.rgbColor()));
    }

    private ImageAppend appendImage(List<ImageLayout> result, int y, int blockIndex, ImageBlock image, int bodyWidth) {
        return appendImage(result, y, blockIndex, image, bodyWidth, 0, 0);
    }

    private ImageAppend appendImage(List<ImageLayout> result, int y, int blockIndex, ImageBlock image, int bodyWidth,
            int originX, int originY) {
        WikiImageTextures.LoadedImage loaded = images.apply(image.assetHash());
        if (loaded == null) {
            int width = Math.min(120, bodyWidth);
            boolean inline = image.layoutMode() == ImageLayoutMode.INLINE;
            int xOffset = inline ? 0 : clamp(toGuiCoordinate(image.offsetX()), 0, Math.max(0, bodyWidth - width));
            xOffset += originX;
            int imageY = inline ? y : originY + Math.max(0, toGuiCoordinate(image.offsetY()));
            result.add(new ImageLayout(blockIndex, imageY, xOffset, width, 32, image.alignment(), image.layoutMode(), null));
            return inline
                    ? new ImageAppend(y + 40, null)
                    : new ImageAppend(y, null);
        }
        // ImageBlock stores physical display pixels, not Minecraft GUI units.
        // Without this conversion a 300px source at GUI scale 4 is painted
        // as 1200px, causing the "large but blurry" result. Text and image
        // layout are intentionally independent: a picture begins at its own
        // pixel size and is only reduced when it would exceed this column.
        int physicalWidth = image.requestedWidth() == 0 ? loaded.width() : image.requestedWidth();
        int maximumWidth = switch (image.layoutMode()) {
            case WRAP_LEFT, WRAP_RIGHT -> Math.max(32, bodyWidth - 30);
            default -> bodyWidth;
        };
        int width = clamp(toGuiPixels(physicalWidth), 1, maximumWidth);
        int height = Math.max(1, Math.round((float) loaded.height() * width / loaded.width()));
        int xOffset = switch (image.alignment()) {
            case LEFT -> 0;
            case CENTER -> Math.max(0, (bodyWidth - width) / 2);
            case RIGHT -> Math.max(0, bodyWidth - width);
        };
        int imageY = y;
        if (image.layoutMode() != ImageLayoutMode.INLINE) {
            // Non-inline images use fixed coordinates on the authored page.
            // They therefore remain where the author placed them when text or
            // a new paragraph is inserted earlier in the document.
            xOffset = clamp(toGuiCoordinate(image.offsetX()), 0, Math.max(0, bodyWidth - width));
            imageY = originY + Math.max(0, toGuiCoordinate(image.offsetY()));
        }
        xOffset += originX;
        if (imageResize != null && imageResize.blockIndex() == blockIndex) {
            width = Math.min(maximumWidth, imageResize.previewWidth());
            height = Math.max(1, Math.round((float) loaded.height() * width / loaded.width()));
            if (image.layoutMode() != ImageLayoutMode.INLINE) {
                xOffset = clamp(imageResize.previewX(), originX, originX + Math.max(0, bodyWidth - width));
                imageY = Math.max(originY, imageResize.previewY());
            } else {
                xOffset = originX + switch (image.alignment()) {
                    case LEFT -> 0;
                    case CENTER -> Math.max(0, (bodyWidth - width) / 2);
                    case RIGHT -> Math.max(0, bodyWidth - width);
                };
            }
        }
        if (imageMove != null && imageMove.blockIndex() == blockIndex) {
            xOffset = clamp(imageMove.x(), originX, originX + Math.max(0, bodyWidth - width));
            imageY = Math.max(originY, imageMove.y());
        }
        result.add(new ImageLayout(blockIndex, imageY, xOffset, width, height, image.alignment(), image.layoutMode(), loaded));
        return switch (image.layoutMode()) {
            case WRAP_LEFT, WRAP_RIGHT -> new ImageAppend(y,
                    new TextFlowExclusion(Math.max(0, imageY - 4), imageY + height + 4, Math.max(0, xOffset - 4), xOffset + width + 4));
            case COVER, FLOATING -> new ImageAppend(y, null);
            case INLINE -> new ImageAppend(y + height + 8, null);
        };
    }



    private int toGuiPixels(int pixels) {
        return Math.max(1, toGuiCoordinate(pixels));
    }

    private int toGuiCoordinate(int pixels) {
        return (int) Math.round(pixels / guiScale);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Snapshot(List<LayoutLine> lines, List<ImageLayout> images, List<TableLayout> tables,
            List<FrameLayout> frames, int contentHeight) { }

    record ResizePreview(int blockIndex, int previewWidth, int previewX, int previewY) { }
    record MovePreview(int blockIndex, int x, int y) { }

    record FrameLayout(int start, int end, int y, int height, BlockFrame frame, boolean foldable) { }

    record Fragment(String text, TextStyle style, float scale, boolean link, int startColumn, int endColumn,
            float x, float advance, Component painted) {
    }

    record LayoutLine(int blockIndex, int startColumn, int endColumn, int y, int height, int indent,
            boolean quote, List<Fragment> fragments) {
        LayoutLine { fragments = List.copyOf(fragments); }
    }

    /** One independently sized, non-text document object on the current page. */
    record ImageLayout(int blockIndex, int y, int xOffset, int width, int height, ImageAlignment alignment,
            ImageLayoutMode layoutMode, WikiImageTextures.LoadedImage loaded) {
    }

    record ImageAppend(int nextY, TextFlowExclusion flowExclusion) {
    }

    /** A positioned wrapping image removes one middle span from later text lines. */
    record TextFlowExclusion(int topY, int bottomY, int gapStart, int gapEnd) {
    }


    /**
     * Per-page table layout.  Rows and cells retain their visual lines so input
     * hit-testing uses exactly the same wrapped geometry as drawing.
     */
    record TableLayout(int blockIndex, int y, int xOffset, int width, int baseColumnWidth, int columns,
            int totalHeight, List<TableRowLayout> rowLayouts, TableBlock table) {
        int columnWidth(int column) {
            return column == columns - 1 ? width - column * baseColumnWidth : baseColumnWidth;
        }

        int rowAt(int localY) {
            for (int row = 0; row < rowLayouts.size(); row++) {
                TableRowLayout layout = rowLayouts.get(row);
                if (localY < layout.yOffset() + layout.height()) {
                    return row;
                }
            }
            return Math.max(0, rowLayouts.size() - 1);
        }
    }

    record TableRowLayout(int yOffset, int height, List<TableCellLayout> cells) {
    }

    record TableCellLayout(List<TableCellLine> lines, int contentHeight) {
        TableCellLine lineAt(int localY) {
            if (lines.isEmpty()) {
                return new TableCellLine(0, 0, 0, 0, List.of());
            }
            for (TableCellLine line : lines) {
                if (localY < line.yOffset() + line.height()) {
                    return line;
                }
            }
            return lines.get(lines.size() - 1);
        }
    }

    record TableCellLine(int yOffset, int height, int startColumn, int endColumn,
            List<Fragment> fragments) {
    }


}
