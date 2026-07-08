package ca.teamdman.sfmgui.net;

import ca.teamdman.sfmgui.SFMGui;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Registers the addon's own Forge networking channel (independent of SFM's channel).
 */
public final class SFMGuiNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SFMGui.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextPacketId = 0;
    private static boolean registered = false;

    private SFMGuiNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.messageBuilder(PullLabelsPayload.class, nextPacketId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PullLabelsPayload::encode)
                .decoder(PullLabelsPayload::decode)
                .consumerMainThread(PullLabelsHandler::handle)
                .add();
    }
}
