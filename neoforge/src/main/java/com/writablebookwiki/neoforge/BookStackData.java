package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookPointer;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Compact, synchronised pointer data carried by an editable-book ItemStack. */
final class BookStackData {
    private static final String ROOT_KEY = "writablebookwiki_book";
    private static final String LEGACY_ROOT_KEY = com.writablebookwiki.core.ModIdentity.LEGACY_MOD_ID + "_book";
    private static final String BOOK_ID_KEY = "book_id";
    private static final String CONTENT_HASH_KEY = "content_hash";
    private static final String LOCKED_KEY = "locked";

    private BookStackData() {
    }

    static Optional<BookPointer> read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return Optional.empty();
        }
        CompoundTag all = data.copyTag();
        String root = all.contains(ROOT_KEY, Tag.TAG_COMPOUND) ? ROOT_KEY : LEGACY_ROOT_KEY;
        if (!all.contains(root, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag value = all.getCompound(root);
        if (!value.hasUUID(BOOK_ID_KEY) || !value.contains(CONTENT_HASH_KEY, Tag.TAG_STRING)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BookPointer(value.getUUID(BOOK_ID_KEY), value.getString(CONTENT_HASH_KEY),
                    value.getBoolean(LOCKED_KEY)));
        } catch (IllegalArgumentException exception) {
            // Item data is untrusted when it comes from commands, old saves,
            // or an external mod. The server treats malformed pointers as new
            // books rather than reading an arbitrary document path.
            return Optional.empty();
        }
    }

    static void write(ItemStack stack, BookPointer pointer) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, all -> {
            CompoundTag value = new CompoundTag();
            value.putUUID(BOOK_ID_KEY, pointer.bookId());
            value.putString(CONTENT_HASH_KEY, pointer.contentHash());
            value.putBoolean(LOCKED_KEY, pointer.locked());
            all.put(ROOT_KEY, value);
            all.remove(LEGACY_ROOT_KEY);
        });
    }
}
