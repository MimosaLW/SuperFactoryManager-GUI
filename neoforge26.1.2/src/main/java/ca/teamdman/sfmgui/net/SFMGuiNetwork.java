package ca.teamdman.sfmgui.net;

import ca.teamdman.sfmgui.SFMGui;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the addon's own network payloads (independent of SFM's channel).
 */
public final class SFMGuiNetwork {
    private SFMGuiNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                PullLabelsPayload.TYPE,
                PullLabelsPayload.CODEC,
                PullLabelsHandler::handle
        );
    }
}
