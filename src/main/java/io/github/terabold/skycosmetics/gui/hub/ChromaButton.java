package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.render.Chroma;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A moving, labeled sample distinguishes shared chroma from static gradient swatches. */
public final class ChromaButton extends ThemedButton {
    public ChromaButton(int width, int height, OnPress press) {
        super(width, height, Component.literal("Animated chroma"), press);
    }
    @Override protected void draw(GuiGraphicsExtractor g, int mx, int my, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.SURFACE);
        for (int i = 0; i < 16; i++) {
            int a = x + 2 + (w - 4) * i / 16, b = x + 2 + (w - 4) * (i + 1) / 16;
            g.fill(a, y + h - 5, b, y + h - 2, 0xFF000000 | Chroma.nameColor(i * 2));
        }
        g.centeredText(Minecraft.getInstance().font, Component.literal("▶ Chroma"), x + w / 2, y + Math.max(1, (h - 12) / 2), Theme.TEXT);
        Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, isHoveredOrFocused() ? Theme.ACCENT : Theme.LINE);
    }
}
