package com.writablebookwiki.core.document;

/** Thrown when serialized document data is malformed or uses an unsupported format. */
public final class DocumentFormatException extends IllegalArgumentException {
    public DocumentFormatException(String message) {
        super(message);
    }

    public DocumentFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
