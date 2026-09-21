package com.writablebookwiki.core.document;

import java.util.List;
import java.util.Objects;

/** A normal paragraph. Empty paragraphs are valid while the player is editing. */
public record Paragraph(List<TextRun> runs) implements TextualBlock {
    public Paragraph {
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }
}
