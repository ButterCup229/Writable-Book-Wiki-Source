package com.editbookwiki.core.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A rectangular rich-text table.
 *
 * <p>This is deliberately only document structure. It does not yet prescribe
 * column widths, floating images, or an editor interaction model; those need
 * a visual layout and cell-selection layer. Keeping the table in the canonical
 * document now nevertheless makes future insertion safe to persist, hash and
 * transfer without smuggling it through paragraph text.</p>
 */
public record TableBlock(List<TableRow> rows, BlockFrame frame) implements Block {
    public TableBlock(List<TableRow> rows) { this(rows, null); }
    public TableBlock withFrame(BlockFrame value) { return new TableBlock(rows, value); }
    public TableBlock {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("A table must contain at least one row");
        }
        int columnCount = rows.getFirst().cells().size();
        for (TableRow row : rows) {
            if (row.cells().size() != columnCount) {
                throw new IllegalArgumentException("All table rows must have the same number of cells");
            }
        }
    }

    /** Returns the fixed column count shared by every row. */
    public int columnCount() {
        return rows.getFirst().cells().size();
    }

    /**
     * Exposes contained text for generic plain-text indexing only.
     *
     * <p>The renderer and editor must use {@link #rows()} to retain cell
     * boundaries; this flattened list is not a line-oriented editing surface.</p>
     */
    @Override
    public List<TextRun> runs() {
        List<TextRun> result = new ArrayList<>();
        for (TableRow row : rows) {
            for (TableCell cell : row.cells()) {
                result.addAll(cell.runs());
            }
        }
        return List.copyOf(result);
    }
}
