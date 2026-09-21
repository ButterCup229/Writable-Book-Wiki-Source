package com.writablebookwiki.core.document;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * An image reference owned by the note or world-book asset store.
 *
 * <p>The actual bytes never appear in a document, ItemStack, or network
 * metadata. {@code assetHash} identifies the separately stored SHA-256 image
 * bytes; {@code requestedWidth == 0} means use the source image's natural
 * pixel width. A non-zero value is a player-selected physical display width
 * in pixels; the UI converts it to its own coordinate scale when rendering.
 * {@code layoutMode} and offsets preserve the author's Word-style placement
 * intent independently from one particular screen implementation.</p>
 */
public record ImageBlock(String assetHash, String caption, ImageAlignment alignment, int requestedWidth,
        ImageLayoutMode layoutMode, int offsetX, int offsetY, BlockFrame frame) implements Block {
    public ImageBlock(String assetHash, String caption, ImageAlignment alignment, int requestedWidth,
            ImageLayoutMode layoutMode, int offsetX, int offsetY) {
        this(assetHash, caption, alignment, requestedWidth, layoutMode, offsetX, offsetY, null);
    }
    public ImageBlock withFrame(BlockFrame value) {
        return new ImageBlock(assetHash, caption, alignment, requestedWidth, layoutMode, offsetX, offsetY, value);
    }
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final int MAX_POSITION_OFFSET = 16_384;

    /** Compatibility constructor for all pre-layout documents and callers. */
    public ImageBlock(String assetHash, String caption, ImageAlignment alignment, int requestedWidth) {
        this(assetHash, caption, alignment, requestedWidth, ImageLayoutMode.INLINE, 0, 0);
    }

    public ImageBlock {
        assetHash = Objects.requireNonNull(assetHash, "assetHash").toLowerCase(Locale.ROOT);
        caption = Objects.requireNonNull(caption, "caption");
        alignment = Objects.requireNonNull(alignment, "alignment");
        layoutMode = Objects.requireNonNull(layoutMode, "layoutMode");
        if (assetHash.length() != SHA_256_HEX_LENGTH || !assetHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("assetHash must be a lowercase SHA-256 hex value");
        }
        if (requestedWidth < 0) {
            throw new IllegalArgumentException("requestedWidth cannot be negative");
        }
        if (offsetX < -MAX_POSITION_OFFSET || offsetX > MAX_POSITION_OFFSET
                || offsetY < -MAX_POSITION_OFFSET || offsetY > MAX_POSITION_OFFSET) {
            throw new IllegalArgumentException("image position offset is outside the supported range");
        }
    }

    @Override
    public List<TextRun> runs() {
        return List.of();
    }
}
