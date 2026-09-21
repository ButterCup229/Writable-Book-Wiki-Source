package com.writablebookwiki.core.document;

/** Aspect-preserving corner drag, projected onto the corner's diagonal. */
public final class ImageResizeGeometry {
    private ImageResizeGeometry() { }

    public static int width(int startWidth, int startHeight, int deltaX, int deltaY,
            boolean left, boolean top, int maximumWidth) {
        if (startWidth <= 0 || startHeight <= 0 || maximumWidth <= 0)
            throw new IllegalArgumentException("Image dimensions must be positive");
        double dx = left ? -(double) deltaX : deltaX;
        double dy = top ? -(double) deltaY : deltaY;
        double scale = 1 + (dx * startWidth + dy * startHeight)
                / ((double) startWidth * startWidth + (double) startHeight * startHeight);
        int minimum = Math.min(16, Math.min(startWidth, maximumWidth));
        return (int) Math.max(minimum, Math.min(maximumWidth, Math.round(startWidth * scale)));
    }
}
