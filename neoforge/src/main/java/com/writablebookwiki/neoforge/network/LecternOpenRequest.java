package com.writablebookwiki.neoforge.network;

import com.writablebookwiki.core.ModIdentity;
import com.writablebookwiki.core.book.BookPointer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Request the exact book in the player's server-owned lectern menu, never an arbitrary world position. */
public record LecternOpenRequest(long requestId, int containerId, BookPointer pointer) implements CustomPacketPayload {
    public static final Type<LecternOpenRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "lectern_open_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LecternOpenRequest> STREAM_CODEC =
            CustomPacketPayload.codec(LecternOpenRequest::write, LecternOpenRequest::read);

    public LecternOpenRequest {
        if (!BookProtocol.isRequestIdValid(requestId) || containerId < 0 || pointer == null)
            throw new IllegalArgumentException("Invalid lectern-open request");
    }

    private static LecternOpenRequest read(RegistryFriendlyByteBuf buffer) {
        return new LecternOpenRequest(buffer.readLong(), buffer.readVarInt(), BookProtocol.readPointer(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(requestId);
        buffer.writeVarInt(containerId);
        BookProtocol.writePointer(buffer, pointer);
    }

    @Override public Type<LecternOpenRequest> type() { return TYPE; }
}
