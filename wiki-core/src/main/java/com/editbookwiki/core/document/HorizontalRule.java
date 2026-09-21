package com.editbookwiki.core.document;

import java.util.List;

public record HorizontalRule() implements Block {
    public static final HorizontalRule INSTANCE = new HorizontalRule();
    @Override public List<TextRun> runs() { return List.of(); }
}
