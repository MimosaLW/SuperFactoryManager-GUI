package ca.teamdman.sfmgui.net;

import ca.teamdman.sfmgui.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Serverbound: request that the SFM manager at {@code pos} copy the labels stored
 * on its inserted disk onto the label gun in the requesting player's inventory.
 */
public record PullLabelsPayload(BlockPos pos) {
    public static final ResourceLocation ID = new ResourceLocation(SFMGui.MOD_ID, "pull_labels");

    public static void encode(PullLabelsPayload msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static PullLabelsPayload decode(FriendlyByteBuf buf) {
        return new PullLabelsPayload(buf.readBlockPos());
    }
}
