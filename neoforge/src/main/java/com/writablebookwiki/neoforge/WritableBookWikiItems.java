package com.writablebookwiki.neoforge;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registration and creative-inventory placement for the physical editable book. */
final class WritableBookWikiItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WritableBookWikiMod.MOD_ID);

    static final DeferredItem<EditableBookItem> EDITABLE_BOOK = ITEMS.registerItem("editable_book",
            EditableBookItem::new, new Item.Properties().stacksTo(1));

    private WritableBookWikiItems() {
    }

    static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(WritableBookWikiItems::addToCreativeTab);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(EDITABLE_BOOK);
        }
    }
}
