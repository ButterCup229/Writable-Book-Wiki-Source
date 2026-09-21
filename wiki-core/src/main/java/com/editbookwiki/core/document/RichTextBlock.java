package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/** Paragraph-level presentation; runs remain ordinary selectable, editable text. */
public record RichTextBlock(Kind kind, Alignment alignment, int headingLevel, boolean checked,
        List<TextRun> runs, BlockFrame frame) implements TextualBlock {
    public RichTextBlock(Kind kind, Alignment alignment, int headingLevel, boolean checked, List<TextRun> runs) {
        this(kind, alignment, headingLevel, checked, runs, null);
    }
    // Append wire IDs only: existing format-6 documents retain their exact bytes/hash.
    public enum Kind { NORMAL, BULLET, NUMBERED, QUOTE, CODE, TASK, INFO, WARNING, STEP, QUOTE_TASK }
    public enum Alignment { LEFT, CENTER, RIGHT, JUSTIFY }
    public RichTextBlock {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(alignment);
        if (headingLevel < 0 || headingLevel > 3) throw new IllegalArgumentException("Invalid heading level");
        runs = List.copyOf(runs);
    }
    public RichTextBlock withRuns(List<TextRun> value) { return new RichTextBlock(kind, alignment, headingLevel, checked, value, frame); }
    public RichTextBlock withFrame(BlockFrame value) { return new RichTextBlock(kind, alignment, headingLevel, checked, runs, value); }
    public boolean isTask() { return kind == Kind.TASK || kind == Kind.QUOTE_TASK; }
    public boolean isList() { return kind == Kind.BULLET || kind == Kind.NUMBERED; }
    public boolean isQuote() { return frame != null ? frame.kind() == BlockFrame.Kind.QUOTE : kind == Kind.QUOTE || kind == Kind.QUOTE_TASK; }

    /** Quote framing and the task checkbox can be toggled independently. */
    public RichTextBlock withPresentation(Kind requested, boolean enabled) {
        Kind next;
        if (requested == Kind.TASK) next = enabled ? (isQuote() ? Kind.QUOTE_TASK : Kind.TASK)
                : (isQuote() ? Kind.QUOTE : Kind.NORMAL);
        else if (requested == Kind.QUOTE) next = enabled ? (isTask() ? Kind.QUOTE_TASK : Kind.QUOTE)
                : (isTask() ? Kind.TASK : Kind.NORMAL);
        else next = enabled ? requested : Kind.NORMAL;
        BlockFrame nextFrame = frame;
        if (frame != null && (requested == Kind.QUOTE || requested == Kind.INFO || requested == Kind.WARNING)) {
            nextFrame = enabled ? new BlockFrame(BlockFrame.Kind.valueOf(requested.name()), frame.group()) : null;
            if (isList()) next = kind;
        }
        return new RichTextBlock(next, alignment, 0,
                (next == Kind.TASK || next == Kind.QUOTE_TASK) && isTask() && checked, runs, nextFrame);
    }
    public static RichTextBlock from(TextualBlock block) {
        if (block instanceof RichTextBlock rich) return rich;
        List<TextRun> content = block.runs();
        if (block instanceof QuoteBlock quote && !quote.source().isBlank()) {
            content = new java.util.ArrayList<>(content);
            content.add(new TextRun("\n— " + quote.source(), TextStyle.DEFAULT.withItalic(true)));
        }
        return new RichTextBlock(block instanceof QuoteBlock ? Kind.QUOTE : Kind.NORMAL,
                Alignment.LEFT, block instanceof Heading heading ? heading.level() : 0, false, content);
    }
}
