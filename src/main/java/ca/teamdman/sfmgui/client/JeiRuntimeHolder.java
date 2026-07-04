package ca.teamdman.sfmgui.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the live JEI runtime and does the actual JEI API calls. This class directly
 * references JEI types, so it is only ever classloaded when JEI is installed (all entry
 * points go through {@link JeiCompat}, which guards on {@code isAvailable()} first).
 */
final class JeiRuntimeHolder {
    private static @Nullable IJeiRuntime runtime = null;

    private JeiRuntimeHolder() {
    }

    static void setRuntime(@Nullable IJeiRuntime rt) {
        runtime = rt;
    }

    /** All item stacks in JEI's ingredient list (display-ready, subtype-aware). */
    static List<ItemStack> itemStacks() {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return new ArrayList<>();
        }
        IIngredientManager mgr = rt.getIngredientManager();
        return new ArrayList<>(mgr.getAllIngredients(VanillaTypes.ITEM_STACK));
    }

    /** Render an item via JEI's ingredient renderer (draws at origin, so translate). */
    static void renderItem(GuiGraphics g, ItemStack stack, int x, int y) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            g.renderItem(stack, x, y);
            return;
        }
        IIngredientManager mgr = rt.getIngredientManager();
        IIngredientRenderer<ItemStack> renderer = mgr.getIngredientRenderer(VanillaTypes.ITEM_STACK);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        try {
            renderer.render(g, stack);
        } finally {
            pose.popPose();
        }
    }
}
