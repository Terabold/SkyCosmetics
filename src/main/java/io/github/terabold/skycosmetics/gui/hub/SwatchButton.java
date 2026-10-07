package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A color to click: a rounded chip filled with one color, or with a gradient through several. A faint frame keeps
 * dark colors visible on the dark panels; under the mouse it turns white. The chosen one wears a white ring. The
 * message names the color for narration, tooltips and tests (the studio's "■" in its color, a gradient's name).
 */
public final class SwatchButton extends ThemedButton {
    private final int[] stops;
    private boolean chosen;

    /** @param stops opaque RGB colors, left to right; one is a plain color */
    public SwatchButton(Component message, int width, int height, int[] stops, OnPress onPress) {
        super(width, height, message, onPress);
        this.stops = stops.clone();
    }

    public SwatchButton chosen(boolean chosen) {
        this.chosen = chosen;
        return this;
    }

    public boolean isChosen() {
        return chosen;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Math.min(Theme.SMALL_RADIUS, Math.min(w, h) / 2);
        float a = active ? 1 : 0.4f;
        Shapes.roundStops(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), stops);
        if (!active) Shapes.round(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), 0x99111116);
        if (chosen) {
            Shapes.frame(g, x, y, w, h, r, 0xFFFFFFFF);
            Shapes.frame(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), 0xC0000000);
        } else {
            Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.mix(0x50FFFFFF, 0xFFFFFFFF, hover), a));
        }
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
