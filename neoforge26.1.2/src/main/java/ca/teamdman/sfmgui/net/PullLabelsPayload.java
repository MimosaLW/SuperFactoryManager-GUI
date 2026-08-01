package ca.teamdman.sfmgui.net;

import ca.teamdman.sfmgui.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: request that the SFM manager at {@code pos} copy the labels stored
 * on its inserted disk onto the label gun in the requesting player's inventory.
 */
public record PullLabelsPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<PullLabelsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(SFMGui.MOD_ID, "pull_labels"));

    public static final StreamCodec<FriendlyByteBuf, PullLabelsPayload> CODEC = StreamCodec.of(
            (buf, msg) -> buf.writeBlockPos(msg.pos),
            buf -> new PullLabelsPayload(buf.readBlockPos())
    );

    @Override
    public Type<PullLabelsPayload> type() {
        return TYPE;
    }
}
