package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/**
 * One cell in a {@link TableBlock}.
 *
 * <p>A cell owns normal {@link TextRun}s instead of a flattened string. This
 * preserves the same first-release bold, italic, colour, size and internal
 * link data used by normal paragraphs. Empty runs represent an intentionally
 * blank cell.</p>
 */
public record TableCell(List<TextRun> runs) {
    public TableCell {
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
}
