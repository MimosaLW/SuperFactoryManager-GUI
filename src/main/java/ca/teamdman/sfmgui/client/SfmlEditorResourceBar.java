package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.SFMGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Injects a "resource picker + copy" bar onto SFM's program text-editor screens
 * (SFMTextEditScreenV1 / V2) without modifying SFM. Drawn and click-handled purely
 * from screen events:
 * <pre>
 *   [ icon ] [ resource name .......... ] [ Copy ]
 * </pre>
 * <ul>
 *   <li>clicking the icon (or the name field) opens the shared {@link ResourcePickerScreen}
 *       — the same searchable picker used by the visual editor;</li>
 *   <li>selecting a resource stores it (its icon + name show in the bar);</li>
 *   <li>clicking Copy writes the selected resource's SFML id to the system clipboard.</li>
 * </ul>
 * The selection persists across screen rebuilds via static fields.
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class SfmlEditorResourceBar {
    public static final Loc COPY = new Loc("gui.sfmgui.editor_bar.copy", "Copy");
    public static final Loc PICK_HINT = new Loc("gui.sfmgui.editor_bar.pick_hint", "Click to pick a resource");
    public static final Loc COPIED = new Loc("gui.sfmgui.editor_bar.copied", "Copied: %s");

    // SFM text-editor screen class names (matched reflectively; SFM stays compileOnly-free here).
    private static final String V1 = "ca.teamdman.sfm.client.screen.text_editor.SFMTextEditScreenV1";
    private static final String V2 = "ca.teamdman.sfm.client.screen.text_editor.SFMTextEditScreenV2";

    // Persisted selection (survives screen rebuilds / reopen while the game runs).
    private static @Nullable String selectedId = null;

    // Bar geometry.
    private static final int BAR_H = 20;
    private static final int ICON = 16;
    private static final int COPY_W = 44;
    private static final int GAP = 4;
    private static final int BAR_W = 360;
    private static final int TOAST_H = 12; // reserved line above the bar for the toast

    // Toast shown briefly after copying.
    private static long toastUntil = 0L;
    private static @Nullable String toastText = null;

    private SfmlEditorResourceBar() {
    }

    /** True when the screen is one of SFM's program text editors. */
    private static boolean isEditorScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals(V1) || n.equals(V2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isV2(Screen screen) {
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(V2)) {
                return true;
            }
        }
        return false;
    }

    /** Screen-space [x, y] top-left of the bar for the given editor screen. */
    private static int[] barPos(Screen screen) {
        int x = screen.width / 2 - BAR_W / 2;
        // Leave a ~14px gap above the bar so the "Copied" toast can sit there without
        // overlapping the code below. V1's text area starts at height/2 - 110 (plenty
        // of room above); for V2 (text drawn from y=0) put the bar a bit down from the
        // top so the toast line above it stays on-screen.
        int y = isV2(screen) ? (2 + TOAST_H) : (screen.height / 2 - 110 - BAR_H - 4);
        if (y < 2 + TOAST_H) {
            y = 2 + TOAST_H;
        }
        return new int[]{x, y};
    }

    // Sub-rects within the bar (all screen-space).
    private static int iconX(int bx) {
        return bx;
    }

    private static int nameX(int bx) {
        return bx + ICON + GAP;
    }

    private static int nameW() {
        return BAR_W - ICON - GAP - GAP - COPY_W;
    }

    private static int copyX(int bx) {
        return bx + BAR_W - COPY_W;
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isEditorScreen(screen)) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int[] p = barPos(screen);
        int bx = p[0], by = p[1];
        int mx = event.getMouseX(), my = event.getMouseY();

        // background strip
        // Fully opaque strip so it cleanly overlays whatever is behind it (V2 draws
        // its code text from y=0, so an opaque bar avoids visual bleed-through).
        g.fill(bx - 2, by - 2, bx + BAR_W + 2, by + BAR_H + 2, 0xFF101014);

        ResourceIndex.Entry entry = selectedId == null ? null : ResourceIndex.lookup(selectedId);

        // icon box
        int ix = iconX(bx);
        boolean iconHover = inRect(mx, my, ix, by + 2, ICON, ICON);
        g.fill(ix, by + 2, ix + ICON, by + 2 + ICON, iconHover ? 0xFF3A3A44 : 0xFF26262E);
        if (entry != null) {
            ResourceIndex.renderIcon(g, font, entry, ix, by + 2);
        }

        // name field
        int nx = nameX(bx), nw = nameW();
        boolean nameHover = inRect(mx, my, nx, by + 2, nw, ICON);
        g.fill(nx, by + 2, nx + nw, by + 2 + ICON, nameHover ? 0xFF2E2E38 : 0xFF1E1E24);
        String label;
        int labelColor;
        if (entry != null) {
            label = entry.displayName();
            labelColor = 0xFFE0E0E0;
        } else if (selectedId != null) {
            // #2: a bare type tag (e.g. "item::") has no ResourceIndex entry — show
            // the raw id so it's clear what Copy will put on the clipboard.
            label = selectedId;
            labelColor = 0xFFE0E0E0;
        } else {
            label = PICK_HINT.getComponent().getString();
            labelColor = 0xFF808088;
        }
        g.drawString(font, font.plainSubstrByWidth(label, nw - 6), nx + 4, by + 6, labelColor, false);

        // copy button
        int cxp = copyX(bx);
        boolean copyEnabled = selectedId != null;
        boolean copyHover = copyEnabled && inRect(mx, my, cxp, by + 2, COPY_W, ICON);
        g.fill(cxp, by + 2, cxp + COPY_W, by + 2 + ICON, copyHover ? 0xFF3A5A9A : (copyEnabled ? 0xFF2A3A6A : 0xFF2A2A30));
        g.drawCenteredString(font, COPY.getComponent(), cxp + COPY_W / 2, by + 6,
                copyEnabled ? 0xFFFFFFFF : 0xFF707078);

        // tooltip: show full SFML id on hovering the name/icon
        if ((iconHover || nameHover) && entry != null) {
            g.renderTooltip(font,
                    java.util.List.of(Component.literal(entry.displayName()), Component.literal(entry.sfmlId())),
                    java.util.Optional.empty(), mx, my);
        }

        // #3: transient "copied" toast, drawn in the reserved gap ABOVE the bar (with
        // its own opaque backing) so it never overlaps the code text below the bar.
        if (toastText != null && System.currentTimeMillis() < toastUntil) {
            int tw = font.width(toastText);
            int ty = by - 2 - TOAST_H;
            g.fill(bx - 2, ty, bx + Math.min(BAR_W + 2, tw + 8), ty + TOAST_H, 0xFF101014);
            g.drawString(font, toastText, bx + 2, ty + 2, 0xFF7CFC7C, false);
        } else {
            toastText = null;
        }
    }

    @SubscribeEvent
    public static void onMousePressedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isEditorScreen(screen) || event.getButton() != 0) {
            return;
        }
        double mx = event.getMouseX(), my = event.getMouseY();
        int[] p = barPos(screen);
        int bx = p[0], by = p[1];

        // icon or name field -> open the shared resource picker. Returning from the
        // picker rebuilds the editor from its CURRENT text (see returnTargetFor) so any
        // in-progress code edits survive the round-trip (V1 would otherwise reset on
        // re-init). Falls back to the live screen if we can't read/rebuild.
        if (inRect(mx, my, iconX(bx), by + 2, ICON, ICON)
                || inRect(mx, my, nameX(bx), by + 2, nameW(), ICON)) {
            Screen returnTo = returnTargetFor(screen);
            ResourcePickerScreen picker = new ResourcePickerScreen(returnTo, id -> {
                selectedId = id;
            });
            Minecraft.getInstance().setScreen(picker);
            event.setCanceled(true);
            return;
        }

        // copy button -> write selected id to clipboard
        if (selectedId != null && inRect(mx, my, copyX(bx), by + 2, COPY_W, ICON)) {
            try {
                Minecraft.getInstance().keyboardHandler.setClipboard(selectedId);
                toastText = Loc.tr(COPIED.key(), selectedId);
                toastUntil = System.currentTimeMillis() + 1600L;
            } catch (Throwable t) {
                SFMGui.LOGGER.error("Failed to copy resource id to clipboard", t);
            }
            event.setCanceled(true);
        }
    }

    /**
     * #4: clear the remembered selection when the user truly leaves the code editor.
     * Going editor -> resource picker (and back) must NOT clear it, so we only reset
     * when the destination is neither an editor nor our picker.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen from = event.getCurrentScreen();
        Screen to = event.getNewScreen();
        if (!isEditorScreen(from)) {
            return; // we only care about leaving an editor
        }
        boolean toEditor = isEditorScreen(to);
        boolean toPicker = to instanceof ResourcePickerScreen;
        if (!toEditor && !toPicker) {
            selectedId = null;
            toastText = null;
        }
    }

    /**
     * Build the screen the resource picker should return to. Re-showing an SFM editor
     * instance re-runs its {@code init()}, which for V1 resets the text to the context's
     * initialValue (losing edits). To preserve edits we read the editor's live text and
     * rebuild a fresh editor whose context reports that text as its initial value,
     * delegating save/close to the original context. On any failure we fall back to the
     * original screen (behaviour unchanged).
     */
    private static Screen returnTargetFor(Screen editor) {
        try {
            if (!(editor instanceof ca.teamdman.sfm.client.screen.text_editor.ISFMTextEditScreen te)) {
                return editor;
            }
            String live = readEditorText(editor);
            if (live == null) {
                return editor;
            }
            ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext base = te.openContext();
            ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext preserving =
                    new PreservingContext(base, live);
            return ca.teamdman.sfm.client.screen.SFMScreenChangeHelpers
                    .createProgramEditScreen(preserving).asScreen();
        } catch (Throwable t) {
            return editor;
        }
    }

    /** Read the current text from a V1 (textarea) or V2 (textEditContext) editor via reflection. */
    private static @Nullable String readEditorText(Screen editor) {
        // V2: textEditContext.getContent()
        try {
            var f = findField(editor.getClass(), "textEditContext");
            if (f != null) {
                f.setAccessible(true);
                Object ctx = f.get(editor);
                if (ctx != null) {
                    Object s = ctx.getClass().getMethod("getContent").invoke(ctx);
                    if (s instanceof String str) {
                        return str;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // V1: textarea.getValue()
        try {
            var f = findField(editor.getClass(), "textarea");
            if (f != null) {
                f.setAccessible(true);
                Object ta = f.get(editor);
                if (ta != null) {
                    Object s = ta.getClass().getMethod("getValue").invoke(ta);
                    if (s instanceof String str) {
                        return str;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static java.lang.reflect.@Nullable Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /** Wraps an open-context but reports a different initial value (the live text). */
    private record PreservingContext(
            ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext base,
            String liveText
    ) implements ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext {
        @Override
        public String initialValue() {
            return liveText;
        }

        @Override
        public java.util.function.Consumer<String> saveWriter() {
            return base.saveWriter();
        }

        @Override
        public ca.teamdman.sfm.common.label.LabelPositionHolder labelPositionHolder() {
            return base.labelPositionHolder();
        }

        @Override
        public void onSaveAndClose(String program) {
            base.onSaveAndClose(program);
        }

        @Override
        public void onTryClose(String program, Runnable closer) {
            base.onTryClose(program, closer);
        }
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
