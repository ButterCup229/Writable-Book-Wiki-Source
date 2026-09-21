package com.writablebookwiki.core.document;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit information stored with a document.
 *
 * <p>This is deliberately separate from visible content. Updating an editor or
 * timestamp must not change {@link WikiDocumentCodec#contentHash(WikiDocument)}
 * and therefore must not trigger copy-on-write by itself.</p>
 */
public record DocumentMetadata(
        UUID creatorId,
        String creatorName,
        Instant createdAt,
        UUID lastEditorId,
        String lastEditorName,
        Instant modifiedAt) {
    public DocumentMetadata {
        creatorId = Objects.requireNonNull(creatorId, "creatorId");
        creatorName = Objects.requireNonNull(creatorName, "creatorName");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        lastEditorId = Objects.requireNonNull(lastEditorId, "lastEditorId");
        lastEditorName = Objects.requireNonNull(lastEditorName, "lastEditorName");
        modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
    }
}
