package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small square button that shows one glyph, its message: the studio's "i" (its help shows on hover) and its
 * "↺" resets. The font's ↺ is tiny, so a glyph can be drawn larger.
 */
public final class IconButton extends ThemedButton {
    private final float scale;
    private final boolean accent;

    /**
     * @param scale  how much bigger than the font the glyph is drawn
     * @param accent the glyph in the accent (the "i"), else in the text color
     */
    public IconButton(Component glyph, int width, int height, float scale, boolean accent, OnPress onPress) {
        super(width, height, glyph, onPress);
        this.scale = scale;
        this.accent = accent;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Math.min(Theme.SMALL_RADIUS + 1, Math.min(w, h) / 2);
        float a = active ? 1 : 0.4f;
        Shapes.round(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
        int color = accent ? Theme.ACCENT : Theme.mix(Theme.TEXT, 0xFFFFFFFF, hover);
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2f, y + h / 2f);
        g.pose().scale(scale, scale);
        Ui.centered(g, Minecraft.getInstance().font, getMessage().getString(), 0, scale >= 2 ? -5 : -4, Theme.fade(color, a));
        g.pose().popMatrix();
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
