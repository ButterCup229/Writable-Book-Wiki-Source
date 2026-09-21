package com.editbookwiki.core.document;

import java.util.Objects;

/** A contiguous piece of text that shares a style and optional internal link target. */
public record TextRun(String text, TextStyle style, String linkTarget) {
    public TextRun {
        text = Objects.requireNonNull(text, "text");
        style = Objects.requireNonNull(style, "style");
        if (linkTarget != null) {
            linkTarget = linkTarget.trim();
            if (linkTarget.isEmpty()) {
                linkTarget = null;
            }
        }
    }

    public TextRun(String text, TextStyle style) {
        this(text, style, null);
    }

    public boolean isLink() {
        return linkTarget != null;
    }
}
