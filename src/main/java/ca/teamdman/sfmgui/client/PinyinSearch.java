package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.SFMGui;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Pinyin-aware search backed by the bundled PinIn library. Chinese item names can
 * be matched by full pinyin, initials, or mixed (e.g. "shitou" / "st" -> 石头).
 * Falls back to a plain case-insensitive substring match, and never throws.
 */
public final class PinyinSearch {
    private PinyinSearch() {
    }

    private static Object pinin;
    private static Method containsMethod;
    private static boolean failed = false;

    private static Object pinin() {
        if (pinin == null && !failed) {
            try {
                Class<?> pininClass = Class.forName("me.towdium.pinin.PinIn");
                pinin = pininClass.getConstructor().newInstance();
                containsMethod = pininClass.getMethod("contains", String.class, String.class);
            } catch (ClassNotFoundException t) {
                failed = true;
                SFMGui.LOGGER.warn("PinIn library not found; pinyin search disabled");
            } catch (ReflectiveOperationException | LinkageError t) {
                failed = true;
                SFMGui.LOGGER.warn("PinIn init failed; pinyin search disabled", t);
            }
        }
        return pinin;
    }

    /**
     * Whether {@code source} matches {@code query}: plain substring OR pinyin.
     */
    public static boolean matches(String source, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        String s = source == null ? "" : source;
        if (s.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            return true;
        }
        Object p = pinin();
        if (p != null) {
            try {
                // PinIn only matches lowercase pinyin tokens, so normalise the query
                // (uppercase letters would otherwise never match).
                return (Boolean) containsMethod.invoke(p, s, query.toLowerCase(Locale.ROOT));
            } catch (ReflectiveOperationException | ClassCastException ignored) {
            }
        }
        return false;
    }
}
