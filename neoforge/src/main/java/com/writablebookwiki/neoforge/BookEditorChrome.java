package com.writablebookwiki.neoforge;

import net.minecraft.client.gui.GuiGraphics;

/** A book's cover and page edges around the existing Word-style workspace. */
final class BookEditorChrome {
    static final int ENDPAPER = 0xFFE7DBC1;
    static final int BORDER = 0xFF998568;
    static final int SIDE_PAPER = 0xFFF2E9D5;
    static final int ACCENT = 0xFF416F64;

    private BookEditorChrome() { }

    static void cover(GuiGraphics g, int left, int top, int right, int bottom) {
        g.fill(left + 3, top + 3, right + 3, bottom + 3, 0x60000000);
        // Small square steps, a leather lip and a binding; no texture across the text.
        g.fill(left + 2, top, right - 2, bottom, 0xFF59422F);
        g.fill(left, top + 2, right, bottom - 2, 0xFF59422F);
        g.fill(left + 2, top + 2, right - 2, bottom - 2, 0xFFAC8C5C);
        g.fill(left + 4, top + 4, right - 4, bottom - 4, ENDPAPER);
        g.fill(left + 2, top + 4, left + 4, bottom - 4, 0xFF715536);
        g.fill(left + 5, top + 2, right - 5, top + 3, 0xFFE9D9AC);
        for (int x = left + 10; x < right - 8; x += 12) {
            g.fill(x, bottom - 3, Math.min(x + 4, right - 4), bottom - 2, 0xFFD1B77E);
        }
    }

    static void paper(GuiGraphics g, int x, int y, int width, int height, int rgb) {
        g.fill(x - 1, y - 1, x + width + 2, y + height + 1, BORDER);
        g.fill(x + width, y + 1, x + width + 2, y + height, 0xFFD4C6A6);
        g.fill(x, y, x + width, y + height, rgb);
    }
}
