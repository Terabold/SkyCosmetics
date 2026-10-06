package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A themed push button. Primary is filled with the accent gradient (the one thing a section is for); secondary
 * is a dark field that lights its frame on hover. A label too long for the button is cut with "...".
 */
public class ActionButton extends ThemedButton {
    public static final int H = 16;
    private final boolean primary;
    private String shown;

    public ActionButton(Component label, int width, boolean primary, OnPress onPress) {
        super(width, H, label, onPress);
        this.primary = primary;
        this.shown = Ui.clip(Minecraft.getInstance().font, label.getString(), width - 10);
    }

    /** The width that shows the whole label, with padding: what a row's button asks for. */
    public static int fit(Component label) {
        return Minecraft.getInstance().font.width(label) + 20;
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        this.shown = Ui.clip(Minecraft.getInstance().font, message.getString(), getWidth() - 10);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float a = active ? 1 : 0.45f;
        int text;
        if (primary) {
            int left = Theme.mix(Theme.PURPLE, 0xFFB57BFF, hover), right = Theme.mix(Theme.PINK, 0xFFFF9AD6, hover);
            Shapes.roundGradient(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(left, a), Theme.fade(right, a));
            text = 0xFFFFFFFF;
        } else {
            Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
            Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
            text = Theme.TEXT;
        }
        g.centeredText(font, shown, x + w / 2, y + (h - 8) / 2, Theme.fade(text, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }
}
