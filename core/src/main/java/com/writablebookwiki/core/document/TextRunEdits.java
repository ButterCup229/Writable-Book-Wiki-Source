package com.writablebookwiki.core.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Stateless styled-text transformations shared by body and table-cell commands.
 * Positions use UTF-16 offsets; selection boundaries must not split surrogate pairs.
 * Undo, document identity and command sequencing belong to WikiDocumentEditor.
 */
final class TextRunEdits {
    private TextRunEdits() { }

    static int safeBoundary(String text, int requested, boolean startBoundary) {
        int boundary = Math.max(0, Math.min(requested, text.length()));
        if (boundary > 0 && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary))) {
            return startBoundary ? boundary - 1 : boundary + 1;
        }
        return boundary;
    }

    static Block replaceBlockText(Block oldBlock, String newText) {
        if (oldBlock == null) {
            return new Paragraph(defaultRuns(newText));
        }
        // A line-based text control represents every structural node as an
        // empty, non-editable row. Retain it when adjacent text changes. Its
        // specialised UI is responsible for intercepting direct typing and
        // routing delete/property commands to structural operations instead.
        if (!(oldBlock instanceof TextualBlock textualBlock)) {
            return newText.isEmpty() ? oldBlock : new Paragraph(defaultRuns(newText));
        }
        String oldText = blockText(oldBlock);
        if (oldText.equals(newText)) {
            return oldBlock;
        }
        int prefix = commonPrefix(oldText, newText);
        int suffix = commonSuffix(oldText, newText, prefix);
        TextStyle insertedStyle = styleAt(oldBlock.runs(), prefix);
        List<TextRun> runs = new ArrayList<>();
        addSlice(runs, oldBlock.runs(), 0, prefix, null);
        if (!newText.substring(prefix, newText.length() - suffix).isEmpty()) {
            append(runs, new TextRun(newText.substring(prefix, newText.length() - suffix), insertedStyle));
        }
        addSlice(runs, oldBlock.runs(), oldText.length() - suffix, oldText.length(), null);
        if (runs.isEmpty()) {
            runs = defaultRuns(newText);
        }
        return withRuns(textualBlock, runs);
    }

    static int commonPrefix(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return safeBoundary(left, index, true);
    }

    static int commonSuffix(String left, String right, int prefix) {
        int max = Math.min(left.length(), right.length()) - prefix;
        int count = 0;
        while (count < max && left.charAt(left.length() - count - 1) == right.charAt(right.length() - count - 1)) {
            count++;
        }
        int boundary = left.length() - count;
        return left.length() - safeBoundary(left, boundary, false);
    }

    static List<TextRun> styleRuns(List<TextRun> source, int start, int end, UnaryOperator<TextStyle> operation) {
        List<TextRun> result = new ArrayList<>();
        addSlice(result, source, 0, start, null);
        addSlice(result, source, start, end, operation);
        addSlice(result, source, end, textLength(source), null);
        return result;
    }

    static List<TextRun> linkRuns(List<TextRun> source, int start, int end, String target) {
        List<TextRun> result = new ArrayList<>();
        addSlice(result, source, 0, start, null);
        int offset = 0;
        for (TextRun run : source) {
            int runEnd = offset + run.text().length();
            int overlapStart = Math.max(offset, start);
            int overlapEnd = Math.min(runEnd, end);
            if (overlapStart < overlapEnd) {
                append(result, new TextRun(run.text().substring(overlapStart - offset, overlapEnd - offset),
                        run.style(), target));
            }
            offset = runEnd;
        }
        addSlice(result, source, end, textLength(source), null);
        return result;
    }

    static void addSlice(List<TextRun> target, List<TextRun> source, int start, int end,
            UnaryOperator<TextStyle> operation) {
        int offset = 0;
        for (TextRun run : source) {
            int runEnd = offset + run.text().length();
            int overlapStart = Math.max(offset, start);
            int overlapEnd = Math.min(runEnd, end);
            if (overlapStart < overlapEnd) {
                TextStyle style = operation == null ? run.style() : Objects.requireNonNull(operation.apply(run.style()));
                append(target, new TextRun(run.text().substring(overlapStart - offset, overlapEnd - offset), style,
                        run.linkTarget()));
            }
            offset = runEnd;
        }
    }

    static void append(List<TextRun> target, TextRun next) {
        if (next.text().isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            TextRun previous = target.get(target.size() - 1);
            if (previous.style().equals(next.style())
                    && Objects.equals(previous.linkTarget(), next.linkTarget())) {
                target.set(target.size() - 1, new TextRun(previous.text() + next.text(), previous.style(),
                        previous.linkTarget()));
                return;
            }
        }
        target.add(next);
    }

    static List<TextRun> defaultRuns(String text) {
        return List.of(new TextRun(text, TextStyle.DEFAULT));
    }

    static String runsText(List<TextRun> runs) {
        StringBuilder text = new StringBuilder();
        for (TextRun run : runs) {
            text.append(run.text());
        }
        return text.toString();
    }

    static List<TextRun> replaceRunsText(List<TextRun> oldRuns, String oldText,
            int start, int end, String replacement) {
        TextStyle insertedStyle = styleAt(oldRuns, start);
        List<TextRun> runs = new ArrayList<>();
        addSlice(runs, oldRuns, 0, start, null);
        if (!replacement.isEmpty()) {
            append(runs, new TextRun(replacement, insertedStyle));
        }
        addSlice(runs, oldRuns, end, oldText.length(), null);
        return runs.isEmpty() ? defaultRuns("") : List.copyOf(runs);
    }

    static TextStyle styleAt(List<TextRun> runs, int position) {
        int offset = 0;
        for (TextRun run : runs) {
            int runEnd = offset + run.text().length();
            if (position < runEnd) {
                return run.style();
            }
            if (position == runEnd) {
                return run.style();
            }
            offset = runEnd;
        }
        return runs.isEmpty() ? TextStyle.DEFAULT : runs.get(runs.size() - 1).style();
    }

    static int textLength(List<TextRun> runs) {
        int length = 0;
        for (TextRun run : runs) {
            length += run.text().length();
        }
        return length;
    }

    static String blockText(Block block) {
        StringBuilder text = new StringBuilder();
        for (TextRun run : block.runs()) {
            text.append(run.text());
        }
        return text.toString();
    }

    static TextualBlock withRuns(TextualBlock source, List<TextRun> runs) {
        if (source instanceof RichTextBlock rich) return rich.withRuns(runs);
        if (source instanceof Heading heading) {
            return new Heading(heading.level(), runs);
        }
        if (source instanceof QuoteBlock quote) {
            return new QuoteBlock(quote.source(), runs);
        }
        if (source instanceof Paragraph) {
            return new Paragraph(runs);
        }
        throw new IllegalArgumentException("Unsupported textual block: " + source.getClass().getName());
    }
}
