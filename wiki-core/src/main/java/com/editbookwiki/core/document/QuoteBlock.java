package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/**
 * A separately rendered Wiki quotation or excerpt. Its folded/expanded state
 * belongs to the reader UI, so the document always retains the full text.
 */
public record QuoteBlock(String source, List<TextRun> runs) implements TextualBlock {
    public QuoteBlock {
        source = Objects.requireNonNull(source, "source").trim();
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
}
