package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.BooleanSupplier;

/**
 * An On / Off switch: a pill whose knob slides across in a short ease-out and whose track fills with the accent
 * gradient. The message is the row's title, so a test can press it by name; narration says the state too.
 */
public final class ToggleSwitch extends ThemedButton {
    public static final int W = 24, H = 12;
    private final BooleanSupplier on;
    private final Anim slide;

    public ToggleSwitch(Component title, BooleanSupplier on, OnPress onPress) {
        super(W, H, title, onPress);
        this.on = on;
        this.slide = new Anim(130, on.getAsBoolean() ? 1 : 0);
    }

    public boolean isOn() {
        return on.getAsBoolean();
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float p = Anim.easeOut(slide.to(isOn() ? 1 : 0));
        float a = active ? 1 : 0.4f;
        Shapes.round(g, x, y, w, h, h / 2, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        if (p > 0) {
            Shapes.roundGradient(g, x, y, w, h, h / 2, Theme.fade(Theme.GRADIENT_START, p * a), Theme.fade(Theme.GRADIENT_END, p * a));
        }
        int d = h - 4;
        int kx = x + 2 + Math.round(p * (w - 4 - d));
        // Off: a gray knob that brightens on hover. On: the color text has on the accent, so it stands out on any accent.
        int knob = Theme.mix(Theme.mix(Theme.MUTED, 0xFFFFFFFF, hover * 0.5f), Theme.ON_ACCENT, p);
        Shapes.circle(g, kx, y + 2, d, Theme.fade(knob, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, h / 2);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(CommonComponents.optionNameValue(getMessage(),
            Component.translatable(isOn() ? "skycosmetics.menu.on" : "skycosmetics.menu.off")));
    }
}
