package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A field with a name on the left and an On / Off switch on the right; a click anywhere on it flips it (the studio's
 * glint). The message says the name and the state ("Glint: On"), so narration and tests read both.
 */
public final class SwitchButton extends ThemedButton {
    private final String label;
    private final boolean on;

    public SwitchButton(Component message, Component label, boolean on, int width, int height, OnPress onPress) {
        super(width, height, message, onPress);
        this.label = Ui.clip(Minecraft.getInstance().font, label.getString(), width - ToggleSwitch.W - 14);
        this.on = on;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Math.min(Theme.SMALL_RADIUS + 1, h / 2);
        float a = active ? 1 : 0.45f;
        Shapes.round(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
        g.text(font, label, x + 6, y + (h - 7) / 2, Theme.fade(Theme.TEXT, a), false);
        int sw = ToggleSwitch.W, sh = Math.min(ToggleSwitch.H, h - 4), sx = x + w - sw - 4, sy = y + (h - sh) / 2;
        if (on) {
            Shapes.roundGradient(g, sx, sy, sw, sh, sh / 2, Theme.fade(Theme.GRADIENT_START, a), Theme.fade(Theme.GRADIENT_END, a));
        } else {
            Shapes.round(g, sx, sy, sw, sh, sh / 2, Theme.fade(Theme.mix(Theme.LINE, Theme.SURFACE_HOVER, hover), a));
        }
        int d = sh - 4, kx = on ? sx + sw - 2 - d : sx + 2;
        int knob = on ? Theme.ON_ACCENT : Theme.mix(Theme.MUTED, 0xFFFFFFFF, hover * 0.5f);
        Shapes.circle(g, kx, sy + 2, d, Theme.fade(knob, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
