package com.writablebookwiki.core.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.writablebookwiki.core.document.DocumentMetadata;
import com.writablebookwiki.core.document.Paragraph;
import com.writablebookwiki.core.document.TextRun;
import com.writablebookwiki.core.document.TextStyle;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookRevisionPolicyTest {
    private static final UUID ORIGINAL_ID = new UUID(0, 10);
    private static final UUID FORK_ID = new UUID(0, 11);

    @Test
    void unchangedContentKeepsTheOriginalBookId() {
        WikiDocument document = document("原文");
        BookRevision current = revision(document, false);
        BookSaveResult result = new BookRevisionPolicy(() -> FORK_ID).save(current, document, false, false);

        BookSaveResult.Unchanged unchanged = assertInstanceOf(BookSaveResult.Unchanged.class, result);
        assertEquals(ORIGINAL_ID, unchanged.pointer().bookId());
    }

    @Test
    void changedUnlockedCopyForksOnlyThatPhysicalBook() {
        BookRevision current = revision(document("原文"), false);
        BookSaveResult result = new BookRevisionPolicy(() -> FORK_ID).save(current, document("修订文"), false, false);

        BookSaveResult.Forked forked = assertInstanceOf(BookSaveResult.Forked.class, result);
        assertEquals(FORK_ID, forked.revision().pointer().bookId());
        assertEquals("修订文", forked.revision().document().blocks().getFirst().runs().getFirst().text());
    }

    @Test
    void lockedBookDeniesSurvivalEditsButCreativeCanForkWithoutUnlocking() {
        BookRevision current = revision(document("原文"), true);
        BookRevisionPolicy policy = new BookRevisionPolicy(() -> FORK_ID);

        assertInstanceOf(BookSaveResult.Denied.class, policy.save(current, document("修订文"), false, false));
        BookSaveResult.Forked forced = assertInstanceOf(BookSaveResult.Forked.class,
                policy.save(current, document("修订文"), true, false));
        assertEquals(true, forced.revision().pointer().locked());
    }

    @Test
    void creativeCanExplicitlyUnlockWithoutCreatingAnUnchangedContentFork() {
        BookRevision current = revision(document("原文"), true);
        BookSaveResult result = new BookRevisionPolicy(() -> FORK_ID).save(current, current.document(), true, true);

        BookSaveResult.LockStateChanged changed = assertInstanceOf(BookSaveResult.LockStateChanged.class, result);
        assertEquals(ORIGINAL_ID, changed.pointer().bookId());
        assertEquals(false, changed.pointer().locked());
    }

    @Test
    void onlyCreativeCanChangeTheLockOfTheHeldPhysicalCopy() {
        BookRevision unlocked = revision(document("原文"), false);
        BookRevisionPolicy policy = new BookRevisionPolicy(() -> FORK_ID);

        assertInstanceOf(BookSaveResult.Denied.class, policy.changeLock(unlocked, false, true));
        BookSaveResult.LockStateChanged locked = assertInstanceOf(BookSaveResult.LockStateChanged.class,
                policy.changeLock(unlocked, true, true));
        assertEquals(ORIGINAL_ID, locked.pointer().bookId());
        assertEquals(unlocked.pointer().contentHash(), locked.pointer().contentHash());
        assertEquals(true, locked.pointer().locked());

        BookRevision lockedRevision = new BookRevision(locked.pointer(), unlocked.document());
        BookSaveResult.LockStateChanged unlockedAgain = assertInstanceOf(BookSaveResult.LockStateChanged.class,
                policy.changeLock(lockedRevision, true, false));
        assertEquals(ORIGINAL_ID, unlockedAgain.pointer().bookId());
        assertEquals(false, unlockedAgain.pointer().locked());
    }

    private static BookRevision revision(WikiDocument document, boolean locked) {
        String hash = new WikiDocumentCodec().contentHash(document);
        return new BookRevision(new BookPointer(ORIGINAL_ID, hash, locked), document);
    }

    private static WikiDocument document(String text) {
        UUID player = new UUID(0, 1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "测试书",
                List.of(new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)))),
                new DocumentMetadata(player, "测试", Instant.EPOCH, player, "测试", Instant.EPOCH));
    }
}
