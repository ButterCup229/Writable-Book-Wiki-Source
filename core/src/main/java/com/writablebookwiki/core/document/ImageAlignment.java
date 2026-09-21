package com.writablebookwiki.core.document;

/** Horizontal placement of an image inside the document's text column. */
public enum ImageAlignment {
    LEFT,
    CENTER,
    RIGHT;

    static ImageAlignment fromWireId(int id) {
        ImageAlignment[] values = values();
        if (id < 0 || id >= values.length) {
            throw new DocumentFormatException("Image alignment is invalid");
        }
        return values[id];
    }
}
