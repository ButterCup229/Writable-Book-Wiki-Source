package com.editbookwiki.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;

/** NeoForge entry point for the client-side local Wiki prototype. */
@Mod(EditBookWikiMod.MOD_ID)
public final class EditBookWikiMod {
    public static final String MOD_ID = "editbookwiki";

    public EditBookWikiMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, EditBookWikiServerConfig.SPEC);
        EditBookWikiItems.register(modEventBus);
    }
}
