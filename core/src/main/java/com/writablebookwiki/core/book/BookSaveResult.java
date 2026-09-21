package com.writablebookwiki.core.book;

/** Result of the server-authoritative copy-on-write save decision. */
public sealed interface BookSaveResult permits BookSaveResult.Unchanged, BookSaveResult.LockStateChanged,
        BookSaveResult.Forked, BookSaveResult.Denied {
    record Unchanged(BookPointer pointer) implements BookSaveResult {
    }

    record LockStateChanged(BookPointer pointer) implements BookSaveResult {
    }

    record Forked(BookRevision revision) implements BookSaveResult {
    }

    record Denied(String reason) implements BookSaveResult {
    }
}
