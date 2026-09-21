package com.writablebookwiki.core.document;

import java.util.List;
import java.util.Objects;

/**
 * Author-controlled page boundaries for a {@link WikiDocument}.
 *
 * <p>A page is delimited only by an explicit {@link PageBreak}. Soft wrapping,
 * window resizing, and scrolling never create a new logical page. Keeping this
 * calculation in the core module prevents a reader, an editor, and a future
 * document source from disagreeing about what “next page” means.</p>
 */
public final class DocumentPages {
    private DocumentPages() {
    }

    /** Returns at least one page, including for a document with no blocks. */
    public static int count(WikiDocument document) {
        Objects.requireNonNull(document, "document");
        int count = 1;
        for (Block block : document.blocks()) {
            if (block instanceof PageBreak) {
                count++;
            }
        }
        return count;
    }

    /** Returns the block range for one page, excluding its trailing page break. */
    public static PageRange range(WikiDocument document, int pageIndex) {
        Objects.requireNonNull(document, "document");
        if (pageIndex < 0 || pageIndex >= count(document)) {
            throw new IndexOutOfBoundsException("pageIndex: " + pageIndex);
        }
        List<Block> blocks = document.blocks();
        int currentPage = 0;
        int start = 0;
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index) instanceof PageBreak) {
                if (currentPage == pageIndex) {
                    return new PageRange(start, index);
                }
                currentPage++;
                start = index + 1;
            }
        }
        return new PageRange(start, blocks.size());
    }

    /** Returns the visible document blocks on one page. */
    public static List<Block> blocks(WikiDocument document, int pageIndex) {
        PageRange range = range(document, pageIndex);
        return List.copyOf(document.blocks().subList(range.startInclusive(), range.endExclusive()));
    }

    /** A half-open range in {@link WikiDocument#blocks()}. */
    public record PageRange(int startInclusive, int endExclusive) {
        public PageRange {
            if (startInclusive < 0 || endExclusive < startInclusive) {
                throw new IllegalArgumentException("invalid page range");
            }
        }
    }
}
