package ca.teamdman.sfmgui.client;

import ca.teamdman.sfm.client.screen.ManagerScreen;
import ca.teamdman.sfmgui.SFMGui;
import ca.teamdman.sfmgui.net.OpenEditorHelper;
import ca.teamdman.sfmgui.net.PullLabelsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Injects addon buttons ("Visual Edit" and "Pull Labels") onto SFM's manager
 * screen without modifying SFM. Buttons are drawn and click-handled entirely
 * from screen events, stacked in SFM's left button column just above the "Edit"
 * button (in the gap below "Paste from clipboard").
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class SFMGuiClientEvents {
    public static final Loc VISUAL_EDIT = new Loc("gui.sfmgui.manager.visual_edit", "Visual Edit");
    public static final Loc PULL_LABELS = new Loc("gui.sfmgui.manager.pull_labels", "Pull Labels");

    // Match SFM's left button column: x = guiLeft - 120, w = 120, h = 16. The two
    // addon buttons sit stacked in the 34px gap between SFM's "Paste from clipboard"
    // (bottom at guiTop+32) and "Edit" (top at guiTop+66) buttons.
    private static final int BTN_W = 120;
    private static final int BTN_H = 16;
    private static final int COL_DX = 120; // left column offset from guiLeft

    private SFMGuiClientEvents() {
    }

    private static int colX(AbstractContainerScreen<?> s) {
        return s.getLeftPos() - COL_DX;
    }

    /** Visual-edit button: first of the two, at guiTop + 32. */
    private static int visualY(AbstractContainerScreen<?> s) {
        return s.getTopPos() + 32;
    }

    /** Pull-labels button: stacked directly below, at guiTop + 49. */
    private static int pullY(AbstractContainerScreen<?> s) {
        return s.getTopPos() + 49;
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ManagerScreen ms)) {
            return;
        }
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int x = colX(ms);
        drawButton(g, x, visualY(ms), VISUAL_EDIT.getComponent().getString(),
                inside(event.getMouseX(), event.getMouseY(), x, visualY(ms)));
        drawButton(g, x, pullY(ms), PULL_LABELS.getComponent().getString(),
                inside(event.getMouseX(), event.getMouseY(), x, pullY(ms)));
    }

    private static void drawButton(GuiGraphicsExtractor g, int x, int y, String text, boolean hover) {
        g.fill(x, y, x + BTN_W, y + BTN_H, 0xFF000000);
        g.fill(x + 1, y + 1, x + BTN_W - 1, y + BTN_H - 1, hover ? 0xFF5A5A5A : 0xFF3A3A3A);
        g.centeredText(Minecraft.getInstance().font, text, x + BTN_W / 2, y + 3, 0xFFFFFFFF);
    }

    @SubscribeEvent
    public static void onMousePressedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ManagerScreen ms)) {
            return;
        }
        if (event.getButton() != 0) {
            return;
        }
        double mx = event.getMouseX(), my = event.getMouseY();
        int x = colX(ms);
        if (inside(mx, my, x, visualY(ms))) {
            try {
                OpenEditorHelper.open(ms);
            } catch (Throwable t) {
                SFMGui.LOGGER.error("Failed to open visual editor", t);
            }
            event.setCanceled(true);
        } else if (inside(mx, my, x, pullY(ms))) {
            try {
                ClientPacketDistributor.sendToServer(new PullLabelsPayload(ms.getMenu().MANAGER_POSITION));
            } catch (Throwable t) {
                SFMGui.LOGGER.error("Failed to pull labels", t);
            }
            event.setCanceled(true);
        }
    }

    private static boolean inside(double mx, double my, int x, int y) {
        return mx >= x && mx <= x + BTN_W && my >= y && my <= y + BTN_H;
    }
}
