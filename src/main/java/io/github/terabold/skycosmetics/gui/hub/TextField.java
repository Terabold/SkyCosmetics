package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Vanilla's text box (typing, selection, copy and paste, narration) drawn as a themed field: a rounded box whose
 * frame lights up on hover and takes the accent while typing. The text keeps the bordered box's 4 px inset, so
 * clicks land where vanilla expects them.
 */
public class TextField extends EditBox {
    private final Anim hover = new Anim(90, 0);
    private final Anim focus = new Anim(120, 0);

    public TextField(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        setTextColor(Theme.TEXT);
        setTextShadow(false);
    }

    /**
     * Vanilla draws its sprite frame only for a bordered box, and this one draws its own. The box stays bordered
     * underneath, which keeps the text inset (and click mapping) of a bordered box.
     */
    @Override
    public boolean isBordered() {
        return false;
    }

    /** The bordered box's text width: 4 px in on both sides. */
    @Override
    public int getInnerWidth() {
        return Math.max(1, getWidth() - 8);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (!isVisible()) return;
        background(g);
        super.extractWidgetRenderState(g, mouseX, mouseY, delta);
    }

    /** The rounded box and its frame, under the text. */
    protected void background(GuiGraphicsExtractor g) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Math.min(Theme.SMALL_RADIUS + 1, h / 2);
        float hv = hover.to(isHovered() ? 1 : 0), f = focus.to(isFocused() ? 1 : 0);
        Shapes.round(g, x, y, w, h, r, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hv * (1 - f)));
        Shapes.frame(g, x, y, w, h, r, Theme.mix(Theme.mix(Theme.LINE, Theme.MUTED, hv * 0.5f), Theme.ACCENT, f));
    }
}
