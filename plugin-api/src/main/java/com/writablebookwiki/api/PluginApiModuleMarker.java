package com.writablebookwiki.api;

/**
 * Phase 0 marker for platform-neutral backend contracts.
 *
 * <p>This module is intentionally free of Paper, Folia, Minecraft and NeoForge
 * APIs. Future plugin implementations depend on this contract.</p>
 */
public final class PluginApiModuleMarker {
    public static final String MODULE_NAME = "plugin-api";

    private PluginApiModuleMarker() {
    }
}
