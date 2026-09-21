package com.writablebookwiki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreModuleMarkerTest {
    @Test
    void exposesTheStableCoreModuleName() {
        assertEquals("core", CoreModuleMarker.MODULE_NAME);
    }
}

