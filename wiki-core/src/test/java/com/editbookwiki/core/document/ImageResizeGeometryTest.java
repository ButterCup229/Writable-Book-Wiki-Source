package com.editbookwiki.core.document;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageResizeGeometryTest {
    @Test void everyCornerExpandsOutwardAndShrinksInward() {
        for (boolean left : new boolean[]{false, true}) {
            for (boolean top : new boolean[]{false, true}) {
                int dx = left ? -20 : 20, dy = top ? -10 : 10;
                assertEquals(120, ImageResizeGeometry.width(100, 50, dx, dy, left, top, 200));
                assertEquals(80, ImageResizeGeometry.width(100, 50, -dx, -dy, left, top, 200));
                // A perpendicular drag must not flip abruptly between growing and shrinking.
                assertEquals(100, ImageResizeGeometry.width(100, 50, dx / 2, -dy * 2, left, top, 200));
            }
        }
    }

    @Test void respectsSizeLimitsWithoutJumpingForSmallImages() {
        assertEquals(120, ImageResizeGeometry.width(100, 50, 500, 250, false, false, 120));
        assertEquals(16, ImageResizeGeometry.width(100, 50, -500, -250, false, false, 200));
        assertEquals(8, ImageResizeGeometry.width(8, 4, 0, 0, false, false, 200));
    }
}
