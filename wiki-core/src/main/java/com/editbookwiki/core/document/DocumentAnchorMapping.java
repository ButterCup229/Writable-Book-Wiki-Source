package com.editbookwiki.core.document;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps local anchors through one immutable document edit. Unchanged/moved blocks
 * keep their identity; only the replaced block interval needs a text diff.
 * This class never adds comments or player-local data to the shared document.
 */
public final class DocumentAnchorMapping {
    private record Point(int block, int column) implements Comparable<Point> {
        @Override public int compareTo(Point other) {
            int b = Integer.compare(block, other.block);
            return b == 0 ? Integer.compare(column, other.column) : b;
        }
    }
    private final WikiDocument before;
    private final WikiDocument after;
    private final Map<Block, Integer> surviving = new IdentityHashMap<>();
    private final int[] oldStarts;
    private final int[] newStarts;
    private final int prefix;
    private final int oldEnd;
    private final int newEnd;

    public DocumentAnchorMapping(WikiDocument before, WikiDocument after) {
        this.before = before;
        this.after = after;
        for (int i = 0; i < after.blocks().size(); i++) surviving.putIfAbsent(after.blocks().get(i), i);
        oldStarts = starts(before);
        newStarts = starts(after);
        int first = 0;
        while (first < before.blocks().size() && first < after.blocks().size()
                && before.blocks().get(first) == after.blocks().get(first)) first++;
        int suffix = 0;
        while (suffix < before.blocks().size() - first && suffix < after.blocks().size() - first
                && before.blocks().get(before.blocks().size() - 1 - suffix)
                == after.blocks().get(after.blocks().size() - 1 - suffix)) suffix++;
        String oldText = flatten(before, first, before.blocks().size() - suffix);
        String newText = flatten(after, first, after.blocks().size() - suffix);
        int common = 0;
        while (common < oldText.length() && common < newText.length()
                && oldText.charAt(common) == newText.charAt(common)) common++;
        int tail = 0;
        while (tail < oldText.length() - common && tail < newText.length() - common
                && oldText.charAt(oldText.length() - tail - 1) == newText.charAt(newText.length() - tail - 1)) tail++;
        prefix = oldStarts[first] + common;
        oldEnd = oldStarts[first] + oldText.length() - tail;
        newEnd = newStarts[first] + newText.length() - tail;
    }

    public TextSelection map(TextSelection selection) {
        Point start = point(selection.startLine(), selection.startColumn(), true);
        Point end = point(selection.endLine(), selection.endColumn(), false);
        // A replaced/deleted anchor collapses; sorting whole blocks may reverse endpoints.
        if (start.compareTo(end) > 0) {
            if (selection.startLine() == selection.endLine()) end = start;
            else { Point swap = start; start = end; end = swap; }
        }
        return new TextSelection(start.block(), start.column(), end.block(), end.column());
    }

    private Point point(int block, int column, boolean rightAffinity) {
        if (before.blocks().isEmpty()) return atOffset(0);
        block = Math.max(0, Math.min(block, before.blocks().size() - 1));
        Block oldBlock = before.blocks().get(block);
        Integer moved = surviving.get(oldBlock);
        if (moved != null && oldBlock instanceof TextualBlock)
            return new Point(moved, Math.min(column, WikiDocumentEditor.blockText(oldBlock).length()));
        int offset = oldStarts[block] + Math.min(column, text(oldBlock).length());
        int mapped;
        if (offset < prefix) mapped = offset;
        else if (offset > oldEnd || offset == oldEnd && oldEnd > prefix) mapped = offset + newEnd - oldEnd;
        else if (offset == prefix) mapped = rightAffinity ? newEnd : prefix;
        else mapped = rightAffinity ? newEnd : prefix;
        return atOffset(mapped);
    }

    private Point atOffset(int offset) {
        Point last = new Point(0, 0);
        for (int i = 0; i < after.blocks().size(); i++) {
            if (!(after.blocks().get(i) instanceof TextualBlock block)) continue;
            int length = WikiDocumentEditor.blockText(block).length();
            if (offset <= newStarts[i] + length)
                return new Point(i, Math.max(0, offset - newStarts[i]));
            last = new Point(i, length);
        }
        return last;
    }

    private static int[] starts(WikiDocument document) {
        int[] starts = new int[document.blocks().size() + 1];
        for (int i = 0; i < document.blocks().size(); i++)
            starts[i + 1] = starts[i] + text(document.blocks().get(i)).length() + 1;
        return starts;
    }

    private static String flatten(WikiDocument document, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) text.append(text(document.blocks().get(i))).append('\n');
        return text.toString();
    }

    private static String text(Block block) {
        return block instanceof TextualBlock ? WikiDocumentEditor.blockText(block) : "\uFFFC";
    }
}
