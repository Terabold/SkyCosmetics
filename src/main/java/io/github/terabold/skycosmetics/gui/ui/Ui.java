package io.github.terabold.skycosmetics.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/** Small drawing helpers the settings' widgets share. */
public final class Ui {
    private Ui() {}

    /** Focused by Tab or the arrows: only then a focused widget shows its ring (a clicked one doesn't). */
    public static boolean keyboardFocus(AbstractWidget w) {
        return w.isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
    }

    /** The accent ring around a widget focused from the keyboard. */
    public static void focusRing(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        Shapes.frame(g, x - 2, y - 2, w + 4, h + 4, r + 2, Theme.ACCENT);
    }

    /** The text cut to {@code px} with "..." when longer. Allocates: call it on change, not per frame. */
    public static String clip(Font font, String s, int px) {
        if (px <= 0) return "";
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("..."))) + "...";
    }

    /** A small down (or up) chevron, 5 wide and 3 tall, with its top left at (x, y). */
    public static void chevron(GuiGraphicsExtractor g, int x, int y, boolean up, int color) {
        for (int i = 0; i < 3; i++) {
            int row = up ? 2 - i : i;
            g.fill(x + i, y + row, x + 5 - i, y + row + 1, color);
        }
    }

    /** A small right-pointing chevron, 3 wide and 5 tall. */
    public static void chevronRight(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 3; i++) g.fill(x + i, y + i, x + i + 1, y + 5 - i, color);
    }

    /** A right-pointing arrow head, 4 wide and 7 tall, with its top left at (x, y). */
    public static void arrowRight(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 4; i++) g.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
    }

    /** An x, {@code size} across: two diagonals of 1 px squares. */
    public static void cross(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        for (int i = 0; i < size; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + size - 1 - i, y + i, x + size - i, y + i + 1, color);
        }
    }

    /** A magnifying glass, 9 px: a ring and a handle. */
    public static void magnifier(GuiGraphicsExtractor g, int x, int y, int color) {
        Shapes.frame(g, x, y, 7, 7, 3, color);
        g.fill(x + 6, y + 6, x + 7, y + 7, color);
        g.fill(x + 7, y + 7, x + 8, y + 8, color);
        g.fill(x + 8, y + 8, x + 9, y + 9, color);
    }
}
