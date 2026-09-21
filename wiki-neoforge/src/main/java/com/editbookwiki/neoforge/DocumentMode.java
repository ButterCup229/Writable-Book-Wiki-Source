package com.editbookwiki.neoforge;

/** The two permission states of one native document canvas, never two documents. */
enum DocumentMode {
    READ,
    EDIT;

    boolean isEditable() {
        return this == EDIT;
    }

    String toggleLabel() {
        return this == READ ? "编辑" : "阅读";
    }
}
