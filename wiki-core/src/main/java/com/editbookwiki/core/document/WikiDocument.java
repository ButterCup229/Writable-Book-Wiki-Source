package com.editbookwiki.core.document;

import java.util.List;
import java.util.Objects;

/**
 * Immutable structured content shared by world books and private local notes.
 *
 * <p>Platform code must not put Minecraft, NeoForge, UI, packet, or file types
 * in this model.</p>
 */
public record WikiDocument(int formatVersion, String title, List<Block> blocks, DocumentMetadata metadata) {
    public static final int CURRENT_FORMAT_VERSION = 7;

    public WikiDocument {
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        title = Objects.requireNonNull(title, "title");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        metadata = Objects.requireNonNull(metadata, "metadata");
    }
}
