package com.writablebookwiki.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WritableBookWikiClientModEvents {
    static final KeyMapping OPEN_READER = new KeyMapping(
            "key.writablebookwiki.open_reader", InputConstants.Type.KEYSYM, InputConstants.KEY_F8,
            "key.categories.writablebookwiki");
    static final KeyMapping TOGGLE_WINDOW_HOST = new KeyMapping(
            "key.writablebookwiki.toggle_window_host", InputConstants.Type.KEYSYM, InputConstants.KEY_F7,
            "key.categories.writablebookwiki");

    private WritableBookWikiClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_READER);
        event.register(TOGGLE_WINDOW_HOST);
        ClientBookSessions.installReceiver();
    }
}
