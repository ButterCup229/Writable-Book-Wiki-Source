package com.writablebookwiki.core.document;

import java.util.List;
import java.util.stream.Collectors;

/** Immutable, local clipboard content; contains no document IDs or personal annotations. */
public record TextFragment(List<Part> parts) {
    public record Part(TextualBlock block, boolean wholeLine) {
        public Part { java.util.Objects.requireNonNull(block); }
    }
    public TextFragment {
        parts = List.copyOf(parts);
        if (parts.isEmpty()) throw new IllegalArgumentException("Empty text fragment");
    }

    public String plainText() {
        return parts.stream().map(p -> WikiDocumentEditor.blockText(p.block())).collect(Collectors.joining("\n"));
    }
}
