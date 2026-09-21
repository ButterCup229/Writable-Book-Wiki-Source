package com.editbookwiki.core.document;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

/** Word-processor cursor boundaries; UTF-16 offsets never split a visible grapheme. */
public final class TextNavigation {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private TextNavigation() { }

    public static int character(String text, int column, int direction) {
        column = Math.max(0, Math.min(column, text.length()));
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) {
            if (direction < 0 && matcher.end() >= column) return matcher.start();
            if (direction > 0 && matcher.end() > column) return matcher.end();
        }
        return direction < 0 ? 0 : text.length();
    }

    public static int word(String text, int column, int direction) {
        column = Math.max(0, Math.min(column, text.length()));
        BreakIterator words = BreakIterator.getWordInstance(Locale.ROOT);
        words.setText(text);
        int position = direction < 0 ? words.preceding(column) : words.following(column);
        while (position != BreakIterator.DONE) {
            if (position < text.length() && Character.isLetterOrDigit(text.codePointAt(position))) return position;
            position = direction < 0 ? words.previous() : words.next();
        }
        return direction < 0 ? 0 : text.length();
    }
}
