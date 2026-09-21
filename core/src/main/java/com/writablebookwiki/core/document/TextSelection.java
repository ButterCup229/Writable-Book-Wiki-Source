package com.writablebookwiki.core.document;

/**
 * An editor selection expressed in document lines and UTF-16 text offsets.
 *
 * <p>The UI toolkit reports offsets in this form. {@link WikiDocumentEditor}
 * normalizes it against the actual document before changing text, including
 * protecting surrogate-pair boundaries.</p>
 */
public record TextSelection(int startLine, int startColumn, int endLine, int endColumn) {
    public TextSelection {
        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            throw new IllegalArgumentException("selection positions cannot be negative");
        }
    }
}
