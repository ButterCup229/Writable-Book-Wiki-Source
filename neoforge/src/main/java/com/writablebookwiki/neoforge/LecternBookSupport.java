package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.DocumentPages;
import java.io.IOException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Vanilla lectern adapter. Only a page count is cached on the stack, never document/image bytes. */
@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID)
public final class LecternBookSupport {
    private static final String PAGE_COUNT = "writablebookwiki_lectern_pages";

    private LecternBookSupport() { }

    public static boolean isWikiBook(ItemStack stack) {
        return stack.is(WritableBookWikiItems.EDITABLE_BOOK.get());
    }

    public static int pageCount(ItemStack stack) {
        return Math.max(1, stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getInt(PAGE_COUNT));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void preparePlacement(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()
                || event.getUseBlock() == TriState.FALSE || player.isSpectator()
                || player.isSecondaryUseActive()) return;
        var state = event.getLevel().getBlockState(event.getPos());
        ItemStack stack = event.getItemStack();
        if (!state.is(Blocks.LECTERN) || state.getValue(LecternBlock.HAS_BOOK) || !isWikiBook(stack)) return;
        try {
            prepareBook(player, stack);
        } catch (IOException | IllegalArgumentException exception) {
            player.displayClientMessage(Component.literal("这本书的存档内容无法读取，未放入讲台。"), true);
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    static void prepareBook(ServerPlayer player, ItemStack stack) throws IOException {
        var revision = BookNetwork.loadOrCreateBook(player, stack);
        int count = DocumentPages.count(revision.document());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(PAGE_COUNT, count));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(revision.document().title()));
    }
}
