package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookRevision;
import java.io.IOException;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The in-world book stack. It carries only a compact pointer; its document is
 * stored in the current world's {@code writablebookwiki/documents} directory.
 *
 * <p>The client asks to open only after an explicit right click. The server
 * then reads the pointer and delivers the body through {@link BookNetwork}'s
 * bounded chunks; backpacks and containers never cause body transfer.</p>
 */
final class EditableBookItem extends Item {
    EditableBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            BookStackData.read(stack).ifPresentOrElse(pointer -> {
                boolean creative = player.getAbilities().instabuild;
                BookNetwork.requestOpen(hand, pointer, !pointer.locked() || creative, creative);
            }, () -> BookNetwork.requestOpen(hand));
        } else if (player instanceof ServerPlayer serverPlayer && BookStackData.read(stack).isEmpty()) {
            initializePointer(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void initializePointer(ServerPlayer player, ItemStack stack) {
        try {
            BookRevision initialRevision = WorldBookRepository.forLevel(player.serverLevel()).create(player);
            BookStackData.write(stack, initialRevision.pointer());
        } catch (IOException exception) {
            // Do not attach a partial pointer: the ItemStack must either point
            // to a verified on-disk revision or remain an uninitialised book.
            player.displayClientMessage(Component.translatable("item.writablebookwiki.editable_book.create_failed"), true);
        }
    }
}
