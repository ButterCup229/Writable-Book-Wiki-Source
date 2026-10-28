package com.writablebookwiki.neoforge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Releases abandoned server-side transfer buffers without trusting a client disconnect callback. */
@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class WritableBookWikiServerGameEvents {
    private WritableBookWikiServerGameEvents() {
    }

    @SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        BookNetwork.disconnectPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        BookNetwork.stopServer();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BookNetwork.expireServerTransfers(event.getServer());
        BookNetwork.deliverQueuedTransfers(event.getServer());
    }
}
