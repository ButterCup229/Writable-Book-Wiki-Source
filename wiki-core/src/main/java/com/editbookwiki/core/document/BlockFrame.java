package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/** Shared, single-level callout membership. Adjacent members form one frame, not separate boxes. */
public record BlockFrame(Kind kind, int group) {
    public enum Kind { QUOTE, INFO, WARNING }
    public BlockFrame {
        Objects.requireNonNull(kind);
        if (group < 1) throw new IllegalArgumentException("Frame group must be positive");
    }

    public static BlockFrame forInsertion(TextualBlock block, List<Block> document) {
        if (block.frame() != null) return block.frame();
        RichTextBlock rich = RichTextBlock.from(block);
        Kind kind = switch (rich.kind()) {
            case QUOTE, QUOTE_TASK -> Kind.QUOTE;
            case INFO -> Kind.INFO;
            case WARNING -> Kind.WARNING;
            default -> null;
        };
        return kind == null ? null : new BlockFrame(kind, nextGroup(document));
    }

    public static int nextGroup(List<Block> blocks) {
        return Math.addExact(blocks.stream().map(Block::frame).filter(Objects::nonNull)
                .mapToInt(BlockFrame::group).max().orElse(0), 1);
    }

    public static Block attach(Block block, BlockFrame frame) {
        if (block instanceof TextualBlock text) return RichTextBlock.from(text).withFrame(frame);
        if (block instanceof ImageBlock image) return image.withFrame(frame);
        if (block instanceof TableBlock table) return table.withFrame(frame);
        if (frame == null) return block;
        throw new IllegalArgumentException("This block cannot belong to a frame");
    }
}
