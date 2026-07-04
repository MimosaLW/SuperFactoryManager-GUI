package ca.teamdman.sfmgui.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Optional bridge to JEI. All JEI API references live behind this class and the
 * {@link SfmGuiJeiPlugin} that feeds it a runtime; when JEI is not installed every
 * method degrades gracefully (returns null / falls back to vanilla rendering), so the
 * addon works with or without JEI — mirroring the existing Mekanism guard pattern.
 * <p>
 * Why JEI: enumerating {@code BuiltInRegistries.ITEM} yields one bare {@code ItemStack}
 * per item, some of which render blank (need data components) and don't distinguish
 * subtypes/variants. JEI's ingredient list already contains the display-ready stacks and
 * a renderer that draws them correctly, so when present we use it for the picker grid.
 */
public final class JeiCompat {
    private JeiCompat() {
    }

    private static volatile boolean runtimeReady = false;

    /** True if JEI is loaded AND its runtime is available (set by the plugin). */
    public static boolean isAvailable() {
        return runtimeReady && isJeiLoaded();
    }

    private static boolean isJeiLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("jei");
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== called by SfmGuiJeiPlugin (only classloaded when JEI is present) =====

    static void onRuntimeAvailable() {
        runtimeReady = true;
    }

    static void onRuntimeUnavailable() {
        runtimeReady = false;
    }

    /**
     * All item stacks JEI would display, or {@code null} if JEI is unavailable. Isolated
     * so JEI classes are only touched when {@link #isAvailable()} is true.
     */
    public static List<ItemStack> itemStacksOrNull() {
        if (!isAvailable()) {
            return null;
        }
        try {
            return JeiRuntimeHolder.itemStacks();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Render an item icon via JEI's renderer when available, else via vanilla
     * {@link GuiGraphics#renderItem}. JEI's renderer draws at (0,0), so we translate.
     */
    public static void renderItem(GuiGraphics g, ItemStack stack, int x, int y) {
        if (isAvailable()) {
            try {
                JeiRuntimeHolder.renderItem(g, stack, x, y);
                return;
            } catch (Throwable ignored) {
                // fall through to vanilla
            }
        }
        g.renderItem(stack, x, y);
    }
}
