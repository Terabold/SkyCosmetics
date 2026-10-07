package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A tab of the studio (Skins, Dyes, Name & Glint...): a dark tab that brightens under the mouse; the open one sits
 * on the accent's dark background with an accent bar along its bottom. The open tab is inactive (clicking it again
 * does nothing) but never looks grayed out.
 */
public final class TabButton extends ThemedButton {
    private final boolean open;
    private final String shown;
    private boolean bar = true;

    public TabButton(Component label, int width, int height, boolean open, OnPress onPress) {
        super(width, height, label, onPress);
        this.open = open;
        this.active = !open;
        this.shown = Ui.clip(Minecraft.getInstance().font, label.getString(), width - 6);
    }

    public boolean isOpen() {
        return open;
    }

    /** The screen draws the accent bar itself, so it can glide from tab to tab. */
    public TabButton slidingBar() {
        bar = false;
        return this;
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Theme.SMALL_RADIUS + 1;
        int text;
        if (open) {
            Shapes.round(g, x, y, w, h, r, Theme.ACCENT_BG);
            Shapes.frame(g, x, y, w, h, r, Theme.fade(Theme.ACCENT, 0.45f));
            if (bar) Shapes.round(g, x + 6, y + h - 3, w - 12, 2, 1, Theme.ACCENT);
            text = 0xFFFFFFFF;
        } else {
            Shapes.round(g, x, y, w, h, r, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover));
            Shapes.frame(g, x, y, w, h, r, Theme.mix(Theme.LINE, Theme.ACCENT, hover * 0.7f));
            text = Theme.mix(Theme.MUTED, Theme.TEXT, hover);
        }
        Ui.centered(g, font, shown, x + w / 2, y + (h - 7) / 2 - (open ? 1 : 0), text);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
    }
}
