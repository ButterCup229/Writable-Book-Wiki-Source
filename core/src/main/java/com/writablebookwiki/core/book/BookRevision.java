package com.writablebookwiki.core.book;

import com.writablebookwiki.core.document.WikiDocument;
import java.util.Objects;

/** One stored immutable document revision addressed by a physical book pointer. */
public record BookRevision(BookPointer pointer, WikiDocument document) {
    public BookRevision {
        pointer = Objects.requireNonNull(pointer, "pointer");
        document = Objects.requireNonNull(document, "document");
    }
}
