package ca.teamdman.sfmgui.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges to SFM's own syntax highlighter so the visual editor's code preview and
 * node summaries use the exact same token colours as the vanilla SFM program view.
 * <p>
 * SFM is a compile/runtime companion mod, but we call the highlighter reflectively
 * and cache the {@link Method} handle so that:
 * <ul>
 *   <li>a missing/renamed helper (e.g. a future SFM version) degrades gracefully to
 *       plain white text instead of crashing the screen, and</li>
 *   <li>we never hard-link a client-only SFM class into our compiled bytecode.</li>
 * </ul>
 */
public final class SfmlHighlight {
    private SfmlHighlight() {
    }

    private static final String HELPER_CLASS =
            "ca.teamdman.sfm.client.text_styling.ProgramSyntaxHighlightingHelper";

    /** Resolved once; null if SFM's highlighter is unavailable. */
    private static Method highlightMethod;
    private static boolean resolved = false;

    private static synchronized Method resolve() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> helper = Class.forName(HELPER_CLASS);
                // withSyntaxHighlighting(String program, boolean withContextActions)
                highlightMethod = helper.getMethod("withSyntaxHighlighting", String.class, boolean.class);
            } catch (Throwable t) {
                highlightMethod = null;
            }
        }
        return highlightMethod;
    }

    /**
     * Highlight a full SFML program into one coloured component per source line.
     * Falls back to plain (uncoloured) line components when SFM is unavailable.
     */
    @SuppressWarnings("unchecked")
    public static List<MutableComponent> lines(String sfml) {
        String src = sfml == null ? "" : sfml;
        Method m = resolve();
        if (m != null) {
            try {
                Object result = m.invoke(null, src, false);
                if (result instanceof List<?> list) {
                    List<MutableComponent> out = new ArrayList<>(list.size());
                    for (Object o : list) {
                        if (o instanceof MutableComponent mc) {
                            out.add(mc);
                        } else if (o != null) {
                            out.add(Component.literal(String.valueOf(o)));
                        }
                    }
                    return out;
                }
            } catch (Throwable ignored) {
                // fall through to plain rendering
            }
        }
        return plainLines(src);
    }

    /**
     * Highlight a single SFML fragment (e.g. a node summary such as
     * {@code "INPUT FROM a"}) into one component. Multi-line fragments are joined
     * with spaces so the caller always gets exactly one line back.
     */
    public static Component fragment(String sfmlFragment) {
        String src = sfmlFragment == null ? "" : sfmlFragment.strip();
        if (src.isEmpty()) {
            return Component.empty();
        }
        List<MutableComponent> lines = lines(src);
        if (lines.isEmpty()) {
            return Component.literal(src);
        }
        if (lines.size() == 1) {
            return lines.get(0);
        }
        MutableComponent joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(" "));
            }
            joined.append(lines.get(i));
        }
        return joined;
    }

    private static List<MutableComponent> plainLines(String src) {
        List<MutableComponent> out = new ArrayList<>();
        for (String line : src.split("\n", -1)) {
            out.add(Component.literal(line));
        }
        if (out.isEmpty()) {
            out.add(Component.empty());
        }
        return out;
    }
}
