package io.github.terabold.skycosmetics.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A scrolling list of rows of different heights inside one panel of the studio: the Saved and Other Mods tabs.
 * Rows are built once per change of what they show ({@link #stamp}), never per frame; a frame draws only the
 * rows in view.
 */
abstract class RowList {
    /** One row: it draws itself at (x, y) with the list's width, and says what a click or hover there does. */
    interface Line {
        int height();

        void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick);

        /** A click at (mouseX, mouseY) on this row; true if it did something. */
        default boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            return false;
        }

        /** The tooltip for the mouse at (mouseX, mouseY) on this row, or null. */
        default List<Component> tooltip(int x, int y, int w, int mouseX, int mouseY) {
            return null;
        }

        /** Whether the row lights up under the mouse. */
        default boolean hoverable() {
            return false;
        }
    }

    static final int TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A, ACCENT = 0xFFD58CFF, LINE = 0xFF34343F;
    static final int HOVER = 0x30FFFFFF, RED = 0xFFFF6666;
    /** The big remove button on a row, and the small one on a part. */
    static final int X_BIG = 16, X_SMALL = 11;

    protected final Font font;
    private List<Line> lines = List.of();
    private Object builtFor;
    private int total;
    private int scroll;
    /** Where the list was drawn last frame, for clicks and the wheel. */
    private int x, y, w, h;

    RowList(Font font) {
        this.font = font;
    }

    /** What the rows show; when it changes they are built again. */
    protected abstract Object stamp();

    protected abstract List<Line> build();

    /** Builds the rows now if what they show changed; cheap otherwise. */
    final List<Line> lines() {
        Object s = stamp();
        if (!Objects.equals(s, builtFor)) {
            builtFor = s;
            lines = build();
            int t = 0;
            for (Line l : lines) t += l.height();
            total = t;
        }
        return lines;
    }

    /** The rows are built again on the next frame (an expand, a removal). */
    final void invalidate() {
        builtFor = null;
    }

    final int scroll() {
        return scroll;
    }

    final void resetScroll() {
        scroll = 0;
    }

    /** Where the rows were drawn last frame: left edge, top, and their width (the scroll bar left out). */
    final int drawnX() {
        return x;
    }

    final int drawnY() {
        return y;
    }

    final int drawnRowWidth() {
        return w - (total > h ? 4 : 0);
    }

    /** Draws the rows in view, the scroll bar and the hovered row's tooltip; returns whether any row is shown. */
    final boolean render(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY, long tick) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        List<Line> ls = lines();
        scroll = Math.clamp(scroll, 0, Math.max(0, total - h));
        boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bar = total > h ? 4 : 0;
        int rw = w - bar;
        g.enableScissor(x, y, x + w, y + h);
        int ly = y - scroll;
        Line hovered = null;
        int hy = 0;
        for (Line l : ls) {
            int lh = l.height();
            if (ly + lh > y && ly < y + h) {
                boolean over = inside && mouseY >= ly && mouseY < ly + lh && mouseX < x + rw;
                if (over && l.hoverable()) g.fill(x, ly, x + rw, ly + lh, HOVER);
                l.draw(g, x, ly, rw, inside ? mouseX : -10000, inside ? mouseY : -10000, tick);
                if (over) {
                    hovered = l;
                    hy = ly;
                }
            }
            ly += lh;
            if (ly >= y + h) break;
        }
        g.disableScissor();
        if (bar > 0) {
            int thumb = Math.max(12, h * h / total);
            int ty = y + (h - thumb) * scroll / Math.max(1, total - h);
            g.fill(x + w - 2, y, x + w, y + h, 0x30FFFFFF);
            g.fill(x + w - 2, ty, x + w, ty + thumb, 0x80FFFFFF);
        }
        if (hovered != null) {
            List<Component> tip = hovered.tooltip(x, hy, rw, mouseX, mouseY);
            if (tip != null && !tip.isEmpty()) g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
        return !ls.isEmpty();
    }

    /** A click inside the list's panel; true if a row took it. */
    final boolean click(double mx, double my, int button) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        int bar = total > h ? 4 : 0;
        int ly = y - scroll;
        for (Line l : List.copyOf(lines)) {
            int lh = l.height();
            if (my >= ly && my < ly + lh) return l.click(x, ly, w - bar, (int) mx, (int) my, button);
            ly += lh;
        }
        return false;
    }

    /** The wheel over the list: three text lines a notch (shift: a page). */
    final boolean scrolled(double mx, double my, double dy, boolean fast) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        int step = fast ? Math.max(30, h - 20) : 30;
        scroll = Math.clamp(scroll - (long) Math.signum(dy) * step, 0, Math.max(0, total - h));
        return true;
    }

    // ------------------------------------------------------------ helpers ---

    /** A remove button: a dark red box with a ×; brighter under the mouse. */
    static void drawX(GuiGraphicsExtractor g, Font font, int x, int y, int size, boolean over) {
        g.fill(x, y, x + size, y + size, over ? 0xFF8A3344 : 0xFF3A2228);
        g.outline(x, y, size, size, over ? 0xFFFF8899 : 0xFF6A3A44);
        g.pose().pushMatrix();
        float s = size >= 14 ? 1.5f : 1f;
        g.pose().translate(x + size / 2f, y + size / 2f);
        g.pose().scale(s, s);
        g.centeredText(font, "×", 0, -4, over ? 0xFFFFFFFF : 0xFFFFC0C8);
        g.pose().popMatrix();
    }

    /** A small triangle: pointing down when {@code open}, else right. */
    static void drawChevron(GuiGraphicsExtractor g, int x, int y, boolean open, int color) {
        if (open) {
            for (int i = 0; i < 3; i++) g.fill(x + i, y + 1 + i, x + 5 - i, y + 2 + i, color);
        } else {
            for (int i = 0; i < 3; i++) g.fill(x + 1 + i, y + i, x + 2 + i, y + 5 - i, color);
        }
    }

    static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Cuts {@code s} to {@code px} with "...". */
    String clip(String s, int px) {
        if (px <= 0) return "";
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("..."))) + "...";
    }

    /** A section title over a group of rows: accent text, its count, and a rule under it. */
    final class Header implements Line {
        private final String title;
        private final String count;

        Header(String title, int count) {
            this.title = title;
            this.count = count > 0 ? String.valueOf(count) : "";
        }

        @Override
        public int height() {
            return 15;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            g.text(font, title, x + 3, y + 4, ACCENT);
            if (!count.isEmpty()) g.text(font, count, x + 3 + font.width(title) + 5, y + 4, MUTED);
            g.fill(x + 2, y + 14, x + w - 2, y + 15, LINE);
        }
    }

    /** Lines in one place for the tests: what each row is. */
    final List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Line l : lines()) out.add(l.toString());
        return out;
    }
}
