package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A themed push button. Primary is filled with the accent gradient (the one thing a section or screen is for, like
 * Done); secondary is a dark field that lights its frame on hover; danger is a dark red one, for Reset. A label too
 * long for the button is cut with "...". The label is drawn in the button's own colors, whatever formatting the
 * message has.
 */
public class ActionButton extends ThemedButton {
    public static final int H = 16;

    public enum Kind { PRIMARY, SECONDARY, DANGER }

    private final Kind kind;
    private String shown;
    /** The label with its own styles (bold, italic...), when {@link #showStyle} asked for it and it fits. */
    private FormattedCharSequence styled;

    public ActionButton(Component label, int width, boolean primary, OnPress onPress) {
        this(label, width, H, primary ? Kind.PRIMARY : Kind.SECONDARY, onPress);
    }

    public ActionButton(Component label, int width, int height, Kind kind, OnPress onPress) {
        super(width, height, label, onPress);
        this.kind = kind;
        this.shown = Ui.clip(Minecraft.getInstance().font, label.getString(), width - 8);
    }

    /**
     * Draws the label with its styles, so a Bold button reads bold (its colors still come from the button). Only
     * when the whole styled label fits.
     */
    public ActionButton showStyle() {
        Font font = Minecraft.getInstance().font;
        styled = font.width(getMessage()) <= getWidth() - 6 ? getMessage().getVisualOrderText() : null;
        return this;
    }

    /** The width that shows the whole label, with padding: what a row's button asks for. */
    public static int fit(Component label) {
        return Minecraft.getInstance().font.width(label) + 20;
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        this.shown = Ui.clip(Minecraft.getInstance().font, message.getString(), getWidth() - 8);
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        this.shown = Ui.clip(Minecraft.getInstance().font, getMessage().getString(), width - 8);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int r = Math.min(Theme.SMALL_RADIUS + 1, h / 2);
        float a = active ? 1 : 0.45f;
        int text;
        switch (kind) {
            case PRIMARY -> {
                int left = Theme.mix(Theme.GRADIENT_START, Theme.GRADIENT_START_HOVER, hover);
                int right = Theme.mix(Theme.GRADIENT_END, Theme.GRADIENT_END_HOVER, hover);
                Shapes.roundGradient(g, x, y, w, h, r, Theme.fade(left, a), Theme.fade(right, a));
                text = Theme.ON_ACCENT;
            }
            case DANGER -> {
                Shapes.round(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.DANGER, Theme.DANGER_HOVER, hover), a));
                Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.DANGER_LINE, Theme.WARN, hover), a));
                text = Theme.mix(Theme.WARN, 0xFFFFFFFF, hover);
            }
            default -> {
                Shapes.round(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
                Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
                text = Theme.TEXT;
            }
        }
        if (styled != null) g.text(font, styled, x + (w - font.width(styled)) / 2, y + (h - 7) / 2, Theme.fade(text, a), false);
        else Ui.centered(g, font, shown, x + w / 2, y + (h - 7) / 2, Theme.fade(text, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
