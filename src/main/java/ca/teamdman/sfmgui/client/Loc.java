package ca.teamdman.sfmgui.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Lightweight localization entry for the addon, mirroring the small slice of
 * SFM's {@code LocalizationEntry} API that the migrated editor screens use.
 * <p>
 * The addon ships its own {@code lang/*.json}; the {@code fallback} is only used
 * for reference/documentation and never emitted at runtime (translation goes
 * through the key).
 */
public record Loc(String key, String fallback) {
    public MutableComponent getComponent() {
        return Component.translatable(key);
    }

    public String getString() {
        return I18n.get(key);
    }

    /** Convenience: translate a raw key to a display string. */
    public static String tr(String key) {
        return I18n.get(key);
    }

    /** Convenience: translate a raw key with args. */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }
}
