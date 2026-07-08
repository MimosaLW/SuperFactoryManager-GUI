package ca.teamdman.sfmgui.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Atlas coordinates and blit helpers for the classic Steve's Factory Manager look,
 * ported from ModJam3's {@code flow_components.png}.
 * <p>
 * Source rectangles are given in the 256x256 atlas space. Blits are drawn at the
 * same size as the source region (no texture scaling); the whole editor is scaled
 * uniformly by the screen instead.
 */
public final class ClassicTextures {
    private ClassicTextures() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("sfmgui", "textures/gui/classic/" + name + ".png");
    }

    public static final ResourceLocation FLOW = tex("flow_components");
    public static final int ATLAS = 256;

    // node frame (large, nine-sliced)
    public static final int NODE_LARGE_SRC_X = 64, NODE_LARGE_SRC_Y = 0, NODE_LARGE_W = 124, NODE_LARGE_H = 152;

    // expand/collapse arrow (top-right of node): col by isLarge, row by hover
    public static final int ARROW_SRC_X = 0, ARROW_SRC_Y = 20, ARROW_W = 9, ARROW_H = 10;

    // menu bar title strip
    public static final int MENU_ITEM_SRC_X = 0, MENU_ITEM_SRC_Y = 152, MENU_ITEM_W = 120, MENU_ITEM_H = 13;
    // menu fold arrow: col by hover, row by open
    public static final int MENU_ARROW_SRC_X = 0, MENU_ARROW_SRC_Y = 40, MENU_ARROW_W = 9, MENU_ARROW_H = 9;
    public static final int MENU_ARROW_X = 109;

    // connection nubs (top=input srcY row 1, bottom=output srcY row 0); col by hover
    public static final int CONN_SRC_X = 0, CONN_SRC_Y = 58, CONN_W = 7, CONN_H = 6;

    // wide button (menu toggle/action)
    public static final int WIDEBTN_SRC_X = 0, WIDEBTN_SRC_Y = 106, WIDEBTN_W = 42, WIDEBTN_H = 12;

    // left toolbar buttons (frame + inner icon)
    public static final int TOOLBTN_SRC_X = 242, TOOLBTN_SRC_Y = 0, TOOLBTN_SIZE = 14;
    public static final int TOOLICON_SRC_X = 230, TOOLICON_SRC_Y = 0, TOOLICON_SIZE = 12;

    // ----- blit helpers -----

    /** Blit a region of the flow_components atlas 1:1 (no scaling). */
    public static void flow(GuiGraphics g, int x, int y, int srcX, int srcY, int w, int h) {
        g.blit(FLOW, x, y, (float) srcX, (float) srcY, w, h, ATLAS, ATLAS);
    }

    /**
     * A node frame stretched to an arbitrary width/height by nine-slicing the large
     * frame sprite. Corners are kept crisp; edges/center are stretched.
     */
    public static void nodeFrameSized(GuiGraphics g, int x, int y, int w, int h) {
        nineSlice(g, FLOW, NODE_LARGE_SRC_X, NODE_LARGE_SRC_Y, NODE_LARGE_W, NODE_LARGE_H,
                x, y, w, h, 6, 20, 6, 6);
        // The nine-sliced top edge can drop its 1px dark border row at some
        // scales/rounding — redraw just the top border so it's always present.
        g.fill(x, y, x + w, y + 1, 0xFF5E5E5E);
    }

    /** Nine-slice a region of the atlas into a destination rectangle. */
    private static void nineSlice(
            GuiGraphics g, ResourceLocation tex,
            int srcX, int srcY, int srcW, int srcH,
            int x, int y, int w, int h,
            int left, int top, int right, int bottom
    ) {
        if (w <= 0 || h <= 0) {
            return;
        }
        left = Math.min(left, w / 2);
        right = Math.min(right, w / 2);
        top = Math.min(top, h / 2);
        bottom = Math.min(bottom, h / 2);
        int smW = srcW - left - right, smH = srcH - top - bottom;
        int dmW = w - left - right, dmH = h - top - bottom;
        // corners
        blitRegion(g, tex, x, y, left, top, srcX, srcY, left, top);
        blitRegion(g, tex, x + w - right, y, right, top, srcX + srcW - right, srcY, right, top);
        blitRegion(g, tex, x, y + h - bottom, left, bottom, srcX, srcY + srcH - bottom, left, bottom);
        blitRegion(g, tex, x + w - right, y + h - bottom, right, bottom, srcX + srcW - right, srcY + srcH - bottom, right, bottom);
        // edges
        if (dmW > 0 && smW > 0) {
            blitRegion(g, tex, x + left, y, dmW, top, srcX + left, srcY, smW, top);
            blitRegion(g, tex, x + left, y + h - bottom, dmW, bottom, srcX + left, srcY + srcH - bottom, smW, bottom);
        }
        if (dmH > 0 && smH > 0) {
            blitRegion(g, tex, x, y + top, left, dmH, srcX, srcY + top, left, smH);
            blitRegion(g, tex, x + w - right, y + top, right, dmH, srcX + srcW - right, srcY + top, right, smH);
        }
        // center
        if (dmW > 0 && dmH > 0 && smW > 0 && smH > 0) {
            blitRegion(g, tex, x + left, y + top, dmW, dmH, srcX + left, srcY + top, smW, smH);
        }
    }

    private static void blitRegion(GuiGraphics g, ResourceLocation tex, int x, int y, int dw, int dh, int u, int v, int sw, int sh) {
        if (dw <= 0 || dh <= 0) {
            return;
        }
        g.blit(tex, x, y, dw, dh, (float) u, (float) v, sw, sh, ATLAS, ATLAS);
    }

    /** Expand/collapse arrow at a node's top-right; col by large, row by hover. */
    public static void nodeArrow(GuiGraphics g, int x, int y, boolean large, boolean hover) {
        int sx = ARROW_SRC_X + ARROW_W * (large ? 1 : 0);
        int sy = ARROW_SRC_Y + ARROW_H * (hover ? 1 : 0);
        flow(g, x, y, sx, sy, ARROW_W, ARROW_H);
    }

    /** A menu title strip. */
    public static void menuStrip(GuiGraphics g, int x, int y) {
        flow(g, x, y, MENU_ITEM_SRC_X, MENU_ITEM_SRC_Y, MENU_ITEM_W, MENU_ITEM_H);
    }

    /** Menu fold arrow; col by hover, row by open. */
    public static void menuArrow(GuiGraphics g, int x, int y, boolean open, boolean hover) {
        int sx = MENU_ARROW_SRC_X + MENU_ARROW_W * (hover ? 1 : 0);
        int sy = MENU_ARROW_SRC_Y + MENU_ARROW_H * (open ? 1 : 0);
        flow(g, x, y, sx, sy, MENU_ARROW_W, MENU_ARROW_H);
    }

    /**
     * A connection nub. {@code input} chooses the top-facing (row 1) vs bottom-facing
     * (row 0) sprite; {@code hover} shifts the column for highlight.
     */
    public static void connection(GuiGraphics g, int x, int y, boolean input, boolean hover) {
        int sx = CONN_SRC_X + CONN_W * (hover ? 1 : 0);
        int sy = CONN_SRC_Y + CONN_H * (input ? 1 : 0);
        flow(g, x, y, sx, sy, CONN_W, CONN_H);
    }

    /** Left toolbar button: outer frame (row by hover) + inner icon (row by index). */
    public static void toolbarButton(GuiGraphics g, int x, int y, int iconIndex, boolean hover) {
        toolbarFrame(g, x, y, hover);
        int iconY = TOOLICON_SRC_Y + TOOLICON_SIZE * iconIndex;
        flow(g, x + 1, y + 1, TOOLICON_SRC_X, iconY, TOOLICON_SIZE, TOOLICON_SIZE);
    }

    /** Just the toolbar button frame (for buttons that draw a custom inner icon). */
    public static void toolbarFrame(GuiGraphics g, int x, int y, boolean hover) {
        int frameY = TOOLBTN_SRC_Y + TOOLBTN_SIZE * (hover ? 1 : 0);
        flow(g, x, y, TOOLBTN_SRC_X, frameY, TOOLBTN_SIZE, TOOLBTN_SIZE);
    }

    /** Wide action button stretched (nine-sliced) to an arbitrary width. */
    public static void wideButton(GuiGraphics g, int x, int y, int w, boolean hover) {
        int sy = WIDEBTN_SRC_Y + WIDEBTN_H * (hover ? 1 : 0);
        nineSlice(g, FLOW, WIDEBTN_SRC_X, sy, WIDEBTN_W, WIDEBTN_H, x, y, w, WIDEBTN_H, 3, 3, 3, 3);
    }
}
