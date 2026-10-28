package com.writablebookwiki.neoforge.mixin;

import com.writablebookwiki.neoforge.LecternBookSupport;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extend vanilla book recognition only; vanilla owns storage, menus, page data and removal. */
@Mixin(LecternBlockEntity.class)
abstract class LecternBlockEntityMixin {
    @Shadow public abstract ItemStack getBook();

    @Inject(method = "hasBook", at = @At("HEAD"), cancellable = true)
    private void writablebookwiki$hasBook(CallbackInfoReturnable<Boolean> result) {
        if (LecternBookSupport.isWikiBook(getBook())) result.setReturnValue(true);
    }

    @Inject(method = "getPageCount", at = @At("HEAD"), cancellable = true)
    private static void writablebookwiki$pageCount(ItemStack stack, CallbackInfoReturnable<Integer> result) {
        if (LecternBookSupport.isWikiBook(stack)) result.setReturnValue(LecternBookSupport.pageCount(stack));
    }
}
