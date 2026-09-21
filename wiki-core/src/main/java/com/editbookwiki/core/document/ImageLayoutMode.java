package com.editbookwiki.core.document;

/**
 * Word-style placement behaviour for an image object.
 *
 * <p>The document owns this choice rather than the screen, so every reader
 * sees the same intent. A renderer may temporarily fall back to
 * {@link #INLINE} while a newer layout mode is not yet visually available,
 * but it must never discard the authored value while saving.</p>
 */
public enum ImageLayoutMode {
    /** The image consumes its own line in the text flow. */
    INLINE,
    /**
     * A freely movable square-wrapped image. Text uses the usable space on
     * both sides of its rectangle and continues below it; it is not a
     * left-aligned inline thumbnail.
     */
    WRAP_LEFT,
    /** Legacy persisted value for old right-aligned documents; new UI uses {@link #WRAP_LEFT}. */
    WRAP_RIGHT,
    /** The image is painted over the document flow at its stored offset. */
    COVER,
    /** The image can be freely positioned relative to its authored anchor. */
    FLOATING;

    static ImageLayoutMode fromWireId(int id) {
        ImageLayoutMode[] values = values();
        if (id < 0 || id >= values.length) {
            throw new DocumentFormatException("Image layout mode is invalid");
        }
        return values[id];
    }
}
