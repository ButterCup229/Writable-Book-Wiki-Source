package com.writablebookwiki.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;

/** NeoForge entry point for the client-side local Wiki prototype. */
@Mod(WritableBookWikiMod.MOD_ID)
public final class WritableBookWikiMod {
    public static final String MOD_ID = com.writablebookwiki.core.ModIdentity.MOD_ID;

    public WritableBookWikiMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, WritableBookWikiServerConfig.SPEC,
                com.writablebookwiki.local.StoragePaths.serverConfigName());
        WritableBookWikiItems.register(modEventBus);
    }
}
