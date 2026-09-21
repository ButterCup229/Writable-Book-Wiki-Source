package com.editbookwiki.local;

import java.util.Objects;

/**
 * A private comment attached to a precise piece of a shared Wiki document.
 *
 * <p>The anchor and quoted text are stored only in the player's local
 * configuration directory.  They are deliberately absent from the document
 * payload, so writing or viewing a comment never changes a book for anyone
 * else.</p>
 */
public record LocalTextAnnotation(int startBlock, int startColumn, int endBlock, int endColumn,
        String quotedText, String comment) {
    public LocalTextAnnotation {
        if (startBlock < 0 || endBlock < startBlock || startColumn < 0 || endColumn < 0
                || endBlock == startBlock && endColumn < startColumn) {
            throw new IllegalArgumentException("Annotation range is invalid");
        }
        quotedText = Objects.requireNonNull(quotedText, "quotedText");
        comment = Objects.requireNonNull(comment, "comment");
    }
}
