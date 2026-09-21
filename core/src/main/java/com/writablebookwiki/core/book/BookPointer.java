package com.writablebookwiki.core.book;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The small identity record carried by one physical editable-book item.
 * Document bytes and image bytes deliberately never belong in this record.
 */
public record BookPointer(UUID bookId, String contentHash, boolean locked) {
    private static final int SHA_256_HEX_LENGTH = 64;

    public BookPointer {
        bookId = Objects.requireNonNull(bookId, "bookId");
        contentHash = Objects.requireNonNull(contentHash, "contentHash").toLowerCase(Locale.ROOT);
        if (contentHash.length() != SHA_256_HEX_LENGTH || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex value");
        }
    }

    public BookPointer withLocked(boolean nextLocked) {
        return locked == nextLocked ? this : new BookPointer(bookId, contentHash, nextLocked);
    }
}
