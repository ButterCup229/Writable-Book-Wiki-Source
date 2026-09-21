package com.editbookwiki.neoforge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Releases abandoned server-side transfer buffers without trusting a client disconnect callback. */
@EventBusSubscriber(modid = EditBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class EditBookWikiServerGameEvents {
    private EditBookWikiServerGameEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BookNetwork.expireServerTransfers(event.getServer());
        BookNetwork.deliverQueuedTransfers(event.getServer());
    }
}
