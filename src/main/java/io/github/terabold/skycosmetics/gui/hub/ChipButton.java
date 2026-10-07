package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A filter pill, one of a few side by side (the studio's Helmet, Pet, Orb, All, Paste): the chosen one is filled with
 * the accent gradient and inactive; the others are dark and frame themselves in the accent under the mouse.
 */
public final class ChipButton extends ThemedButton {
    private final boolean chosen;
    private final String shown;

    public ChipButton(Component label, int width, int height, boolean chosen, OnPress onPress) {
        super(width, height, label, onPress);
        this.chosen = chosen;
        this.active = !chosen;
        this.shown = Ui.clip(Minecraft.getInstance().font, label.getString(), width - 6);
    }

    /** Room for the label with padding. */
    public static int fit(Component label) {
        return Minecraft.getInstance().font.width(label) + 14;
    }

    public boolean chosen() {
        return chosen;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = h / 2;
        int text;
        if (chosen) {
            Shapes.roundGradient(g, x, y, w, h, r, Theme.GRADIENT_START, Theme.GRADIENT_END);
            text = Theme.ON_ACCENT;
        } else {
            Shapes.round(g, x, y, w, h, r, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover));
            Shapes.frame(g, x, y, w, h, r, Theme.mix(Theme.LINE, Theme.ACCENT, hover));
            text = Theme.mix(Theme.MUTED, Theme.TEXT, hover);
        }
        Ui.centered(g, font, shown, x + w / 2, y + (h - 7) / 2, text);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
