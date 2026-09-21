package com.editbookwiki.core.document;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Plain text editing state for local comments, isolated from shared-body undo. */
public final class PlainTextEditor {
    private record State(String text, int caret, int anchor) { }
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    private String text = "";
    private int caret;
    private int anchor;
    private boolean typing;
    public String text() { return text; }
    public int caret() { return caret; }
    public int start() { return Math.min(caret, anchor); }
    public int end() { return Math.max(caret, anchor); }
    public String selectedText() { return text.substring(start(), end()); }
    public boolean hasSelection() { return caret != anchor; }

    public void reset(String value) {
        text = Objects.requireNonNull(value);
        caret = anchor = text.length();
        undo.clear();
        redo.clear();
        typing = false;
    }
    public void moveTo(int position, boolean extend) {
        caret = Math.max(0, Math.min(position, text.length()));
        if (!extend) anchor = caret;
        typing = false;
    }
    public void selectAll() {
        anchor = 0;
        caret = text.length();
        typing = false;
    }
    public boolean replaceSelection(String value) {
        Objects.requireNonNull(value);
        int from = start(), to = end();
        String next = text.substring(0, from) + value + text.substring(to);
        if (next.equals(text)) return false;
        if (!typing) {
            undo.push(new State(text, caret, anchor));
            if (undo.size() > WikiDocumentEditor.DEFAULT_HISTORY_LIMIT) undo.removeLast();
        }
        redo.clear();
        text = next;
        caret = anchor = from + value.length();
        typing = true;
        return true;
    }
    public boolean delete(int direction, boolean word) {
        if (!hasSelection()) anchor = word ? TextNavigation.word(text, caret, direction)
                : TextNavigation.character(text, caret, direction);
        return replaceSelection("");
    }
    public void move(int direction, boolean extend, boolean word) {
        int target = !extend && hasSelection() ? (direction < 0 ? start() : end())
                : word ? TextNavigation.word(text, caret, direction) : TextNavigation.character(text, caret, direction);
        moveTo(target, extend);
    }
    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.push(new State(text, caret, anchor));
        restore(undo.pop());
        return true;
    }
    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.push(new State(text, caret, anchor));
        restore(redo.pop());
        return true;
    }
    private void restore(State state) {
        text = state.text(); caret = state.caret(); anchor = state.anchor(); typing = false;
    }
}
