package com.editbookwiki.core.book;

import com.editbookwiki.core.document.WikiDocument;
import com.editbookwiki.core.document.WikiDocumentCodec;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure copy-on-write and lock policy shared by singleplayer and servers.
 * Platform code supplies the new UUID and persists a {@link BookSaveResult}.
 */
public final class BookRevisionPolicy {
    private final WikiDocumentCodec codec;
    private final Supplier<UUID> nextBookId;

    public BookRevisionPolicy(Supplier<UUID> nextBookId) {
        this(new WikiDocumentCodec(), nextBookId);
    }

    public BookRevisionPolicy(WikiDocumentCodec codec, Supplier<UUID> nextBookId) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.nextBookId = Objects.requireNonNull(nextBookId, "nextBookId");
    }

    /**
     * Evaluates one attempt to save a held physical copy.
     *
     * <p>An unlocked copy may be edited by its holder. A locked copy requires
     * creative mode; creative editing keeps it locked unless {@code unlock}
     * is explicitly requested. Only a changed content hash creates a new
     * bookId, so unchanged copies continue to point at their old revision.</p>
     */
    public BookSaveResult save(BookRevision current, WikiDocument proposed, boolean creativeMode, boolean unlock) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(proposed, "proposed");
        BookPointer pointer = current.pointer();
        if (pointer.locked() && !creativeMode) {
            return new BookSaveResult.Denied("此书已锁定，只有创造模式可强制编辑");
        }
        if (unlock && !creativeMode) {
            return new BookSaveResult.Denied("只有创造模式可以解除锁定");
        }
        boolean nextLocked = unlock ? false : pointer.locked();
        String nextHash = codec.contentHash(proposed);
        if (pointer.contentHash().equals(nextHash)) {
            return nextLocked == pointer.locked()
                    ? new BookSaveResult.Unchanged(pointer)
                    : new BookSaveResult.LockStateChanged(pointer.withLocked(nextLocked));
        }
        BookPointer fork = new BookPointer(nextBookId.get(), nextHash, nextLocked);
        return new BookSaveResult.Forked(new BookRevision(fork, proposed));
    }

    /**
     * Changes only the lock on one held physical copy.
     *
     * <p>The document revision and its hash deliberately remain untouched:
     * locking a copy is an access-control action, not an edit and must not
     * create a new {@code bookId}. Platform code is still responsible for
     * proving that the caller is holding this exact pointer.</p>
     */
    public BookSaveResult changeLock(BookRevision current, boolean creativeMode, boolean locked) {
        Objects.requireNonNull(current, "current");
        if (!creativeMode) {
            return new BookSaveResult.Denied("只有创造模式可以锁定或解除锁定");
        }
        BookPointer pointer = current.pointer();
        return pointer.locked() == locked
                ? new BookSaveResult.Unchanged(pointer)
                : new BookSaveResult.LockStateChanged(pointer.withLocked(locked));
    }
}
