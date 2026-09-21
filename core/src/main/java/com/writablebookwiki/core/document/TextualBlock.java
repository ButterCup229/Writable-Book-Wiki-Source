package com.writablebookwiki.core.document;

/**
 * A top-level block whose {@link #runs()} are directly editable as ordinary
 * document text. Structural blocks deliberately do not implement this type:
 * an image, page break, or future table must be selected and edited through
 * its own command instead of being silently rewritten as a paragraph.
 */
public sealed interface TextualBlock extends Block permits Heading, Paragraph, QuoteBlock, RichTextBlock {
}
