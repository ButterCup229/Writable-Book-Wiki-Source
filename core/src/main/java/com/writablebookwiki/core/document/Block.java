package com.writablebookwiki.core.document;

import java.util.List;

/** A visible top-level document block. */
public sealed interface Block permits TextualBlock, ImageBlock, PageBreak, TableBlock, HorizontalRule {
    List<TextRun> runs();
    default BlockFrame frame() { return null; }
}
