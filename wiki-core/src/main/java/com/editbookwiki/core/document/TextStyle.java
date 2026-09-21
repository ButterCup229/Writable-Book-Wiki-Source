package com.editbookwiki.core.document;

/**
 * The supported first-release inline styling. Fonts are intentionally not
 * configurable: all text uses the game's normal font family.
 */
public record TextStyle(boolean bold, boolean italic, boolean strikethrough, int rgbColor, int fontSize,
        boolean underline, int script, boolean hidden, boolean code) {
    public TextStyle(boolean bold, boolean italic, boolean strikethrough, int rgbColor, int fontSize) {
        this(bold, italic, strikethrough, rgbColor, fontSize, false, 0, false, false);
    }
    public TextStyle withBold(boolean value) { return new TextStyle(value, italic, strikethrough, rgbColor, fontSize, underline, script, hidden, code); }
    public TextStyle withItalic(boolean value) { return new TextStyle(bold, value, strikethrough, rgbColor, fontSize, underline, script, hidden, code); }
    public TextStyle withStrikethrough(boolean value) { return new TextStyle(bold, italic, value, rgbColor, fontSize, underline, script, hidden, code); }
    public TextStyle withColor(int value) { return new TextStyle(bold, italic, strikethrough, value, fontSize, underline, script, hidden, code); }
    public TextStyle withFontSize(int value) { return new TextStyle(bold, italic, strikethrough, rgbColor, value, underline, script, hidden, code); }
    public TextStyle withUnderline(boolean value) { return new TextStyle(bold, italic, strikethrough, rgbColor, fontSize, value, script, hidden, code); }
    public TextStyle withScript(int value) { return new TextStyle(bold, italic, strikethrough, rgbColor, fontSize, underline, value, hidden, code); }
    public TextStyle withHidden(boolean value) { return new TextStyle(bold, italic, strikethrough, rgbColor, fontSize, underline, script, value, code); }
    public TextStyle withCode(boolean value) { return new TextStyle(bold, italic, strikethrough, rgbColor, fontSize, underline, script, hidden, value); }
    /** Word-like body default: 11pt is readable without occupying the whole page. */
    public static final TextStyle DEFAULT = new TextStyle(false, false, false, 0x000000, 11);

    public TextStyle {
        if (script < -1 || script > 1) throw new IllegalArgumentException("Invalid baseline script");
        if (rgbColor < 0 || rgbColor > 0xFFFFFF) {
            throw new IllegalArgumentException("rgbColor must be between 0x000000 and 0xFFFFFF");
        }
        if (fontSize <= 0) {
            throw new IllegalArgumentException("fontSize must be positive");
        }
    }
}
