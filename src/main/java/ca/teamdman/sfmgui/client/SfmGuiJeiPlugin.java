package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.SFMGui;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

/**
 * JEI plugin that captures the JEI runtime for {@link JeiCompat}. Only loaded by JEI
 * when JEI is installed; when JEI is absent this class is never touched.
 */
@JeiPlugin
public class SfmGuiJeiPlugin implements IModPlugin {
    private static final Identifier UID =
            Identifier.fromNamespaceAndPath(SFMGui.MOD_ID, "jei_plugin");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiRuntimeHolder.setRuntime(jeiRuntime);
        JeiCompat.onRuntimeAvailable();
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRuntimeHolder.setRuntime(null);
        JeiCompat.onRuntimeUnavailable();
    }
}
