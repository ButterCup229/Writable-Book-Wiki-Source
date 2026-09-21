package com.writablebookwiki.core.document;

import java.util.List;
import java.util.Objects;

/** A first-, second-, or third-level heading. */
public record Heading(int level, List<TextRun> runs) implements TextualBlock {
    public Heading {
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException("heading level must be between 1 and 3");
        }
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
}
