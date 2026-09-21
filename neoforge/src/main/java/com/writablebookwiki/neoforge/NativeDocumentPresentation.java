package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.Block;
import com.writablebookwiki.core.document.DocumentPages;
import com.writablebookwiki.core.document.Heading;
import com.writablebookwiki.core.document.RichTextBlock;
import com.writablebookwiki.core.document.PageBreak;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentEditor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * UI-toolkit-neutral view of one {@link WikiDocument}.
 *
 * <p>This class deliberately has no rendering, texture, or window dependency.
 * Both the independent desktop window and the in-game
 * {@code Screen} use it to agree on authored pages and Wiki-style heading
 * numbers. A soft visual wrap is never a page boundary.</p>
 */
final class NativeDocumentPresentation {
    private NativeDocumentPresentation() {
    }

    static int pageCount(WikiDocument document) {
        return DocumentPages.count(Objects.requireNonNull(document, "document"));
    }

    static List<Block> pageBlocks(WikiDocument document, int pageIndex) {
        return DocumentPages.blocks(Objects.requireNonNull(document, "document"), pageIndex);
    }

    /**
     * Builds the left-side Wiki directory from the document's real headings.
     * The number is a presentation detail, while the selected page is an
     * explicit {@link PageBreak}-defined document address.
     */
    static List<TableOfContentsEntry> tableOfContents(WikiDocument document) {
        Objects.requireNonNull(document, "document");
        List<TableOfContentsEntry> entries = new ArrayList<>();
        int[] headingCounters = new int[3];
        int pageIndex = 0;
        for (int blockIndex = 0; blockIndex < document.blocks().size(); blockIndex++) {
            Block block = document.blocks().get(blockIndex);
            if (block instanceof PageBreak) {
                pageIndex++;
                continue;
            }
            int level = block instanceof Heading heading ? heading.level()
                    : block instanceof RichTextBlock rich ? rich.headingLevel() : 0;
            if (level == 0) continue;
            headingCounters[level - 1]++;
            for (int lower = level; lower < headingCounters.length; lower++) {
                headingCounters[lower] = 0;
            }
            entries.add(new TableOfContentsEntry(
                    pageIndex,
                    blockIndex,
                    level,
                    headingNumber(headingCounters, level),
                    WikiDocumentEditor.blockText(block)));
        }
        return List.copyOf(entries);
    }

    private static String headingNumber(int[] counters, int level) {
        // A malformed/imported document may begin at H2 or H3. Present it in a
        // stable, understandable way instead of displaying a leading zero.
        for (int index = 0; index < level - 1; index++) {
            if (counters[index] == 0) {
                counters[index] = 1;
            }
        }
        StringBuilder number = new StringBuilder();
        for (int index = 0; index < level; index++) {
            if (index > 0) {
                number.append('.');
            }
            number.append(counters[index]);
        }
        return number.toString();
    }

    /**
     * A heading has both an authored-page address and its actual document
     * block address.  The latter is required because a page can be taller
     * than the viewport: choosing a directory entry must reveal the heading,
     * rather than merely opening the top of its page.
     */
    record TableOfContentsEntry(int pageIndex, int blockIndex, int level, String number, String title) {
        TableOfContentsEntry {
            if (pageIndex < 0 || blockIndex < 0 || level < 1 || level > 3) {
                throw new IllegalArgumentException("invalid table-of-contents entry");
            }
            number = Objects.requireNonNull(number, "number");
            title = Objects.requireNonNull(title, "title");
        }

        String displayText() {
            return number + " " + title;
        }
    }
}
