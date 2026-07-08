package ca.teamdman.sfmgui;

import ca.teamdman.sfmgui.net.SFMGuiNetwork;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Super Factory Manager Visual GUI — an addon that adds a node/wire based visual
 * program editor for SFM. Requires Super Factory Manager to be installed.
 * <p>
 * The addon never modifies SFM. It hooks into SFM's manager screen via a client
 * event and reuses SFM's public APIs (program string, packets, DSL parser) to
 * read and save programs.
 */
@Mod(SFMGui.MOD_ID)
public class SFMGui {
    public static final String MOD_ID = "sfmgui";
    public static final Logger LOGGER = LoggerFactory.getLogger("SFMGui");

    public SFMGui() {
        SFMGuiNetwork.register();
        LOGGER.info("Super Factory Manager Visual GUI loaded");
    }
}
