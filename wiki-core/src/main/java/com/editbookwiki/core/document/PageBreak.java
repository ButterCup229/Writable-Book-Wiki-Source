package com.editbookwiki.core.document;

import java.util.List;

/**
 * An explicit author-controlled page boundary.
 *
 * <p>It is deliberately different from a visual soft wrap: a reader's
 * “下一页” command moves across this block boundary, which lets an author
 * deliberately separate chapter one from chapter two.</p>
 */
public enum PageBreak implements Block {
    INSTANCE;

    @Override
    public List<TextRun> runs() {
        return List.of();
    }
}
