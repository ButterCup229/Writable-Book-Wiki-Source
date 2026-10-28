package com.writablebookwiki.core.book;

/** Successful revision decisions. Declaration order is part of network protocol version 1. */
public enum SaveOutcome {
    UNCHANGED,
    LOCK_STATE_CHANGED,
    FORKED
}
