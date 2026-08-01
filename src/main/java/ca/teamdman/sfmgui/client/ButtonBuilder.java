package ca.teamdman.sfmgui.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A small fluent button builder mirroring the subset of SFM's {@code SFMButtonBuilder}
 * API that the editor uses, so the addon does not depend on SFM's client widget classes.
 */
public class ButtonBuilder {
    private @Nullable Component text = null;
    private int x = 0;
    private int y = 0;
    private int width = 150;
    private int height = 20;
    private @Nullable Button.OnPress onPress = null;

    public ButtonBuilder setText(Component text) {
        this.text = text;
        return this;
    }

    public ButtonBuilder setSize(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public ButtonBuilder setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public ButtonBuilder setOnPress(Button.OnPress onPress) {
        this.onPress = onPress;
        return this;
    }

    public Button build() {
        if (text == null) {
            throw new IllegalArgumentException("Text must be set");
        }
        if (onPress == null) {
            throw new IllegalArgumentException("OnPress must be set");
        }
        return Button.builder(text, onPress)
                .bounds(x, y, width, height)
                .build();
    }
}
