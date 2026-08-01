package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.SFMGui;
import me.towdium.pinin.PinIn;

import java.util.Locale;

/**
 * Pinyin-aware search backed by the bundled PinIn library. Chinese item names can
 * be matched by full pinyin, initials, or mixed (e.g. "shitou" / "st" -> 石头).
 * Falls back to a plain case-insensitive substring match, and never throws.
 */
public final class PinyinSearch {
    private PinyinSearch() {
    }

    private static PinIn pinin;
    private static boolean failed = false;

    private static PinIn pinin() {
        if (pinin == null && !failed) {
            try {
                pinin = new PinIn();
            } catch (Throwable t) {
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
        PinIn p = pinin();
        if (p != null) {
            try {
                // PinIn only matches lowercase pinyin tokens, so normalise the query
                // (uppercase letters would otherwise never match).
                return p.contains(s, query.toLowerCase(Locale.ROOT));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
