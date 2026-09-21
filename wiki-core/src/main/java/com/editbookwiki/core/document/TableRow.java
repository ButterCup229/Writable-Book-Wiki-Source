package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/** One ordered row of cells in a {@link TableBlock}. */
public record TableRow(List<TableCell> cells) {
    public TableRow {
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("A table row must contain at least one cell");
        }
    }
}
