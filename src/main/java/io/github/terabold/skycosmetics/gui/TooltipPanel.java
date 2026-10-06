package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Looks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * An item's tooltip drawn in place, the way the game shows it on hover: a big
 * item icon beside its name, then the lore, in the vanilla tooltip box. The
 * first lore lines sit beside the icon, so it costs no height; lore that still
 * does not fit scrolls with the mouse wheel, under the icon.
 *
 * From GUI scale 3 up the text is drawn one step smaller, (scale - 1) / scale:
 * whole screen pixels per font pixel, so it stays crisp, and a whole SkyBlock
 * tooltip fits the narrow column beside the player model.
 *
 * The lines come from the real stack, not the render copy: only the real one
 * answers {@code getStyledHoverName} with your custom name, and other mods add
 * their lines to it as they would on hover. They are built once per stack,
 * look version, width and GUI scale, never per frame.
 */
public final class TooltipPanel {
    private static final int MAX_NAME_LINES = 3;
    /** The tooltip frame is drawn this far outside the text area. */
    private static final int FRAME = 4;
    /** The icon is 3x where the name still has room beside it, else 2x. */
    private static final int WIDE_ICON = 150;

    private ItemStack stack;
    private int version = -1, wrap = -1, builtScale = -1;
    private boolean advanced;
    private Component title;
    /** Text is laid out in font units and drawn at this scale; a line is {@link #line} units tall. */
    private float textScale = 1;
    private int line = 10, icon = 32;
    private List<FormattedCharSequence> name = List.of();
    /** Lore lines beside the icon, never scrolled. */
    private List<FormattedCharSequence> beside = List.of();
    /** Lore lines under the icon; they scroll. */
    private List<FormattedCharSequence> lore = List.of();
    private int loreTop, besideX;
    private int scroll, builds, lastFit;
    /** The scrolling window last drawn, for the mouse wheel (GUI pixels). */
    private int loreX, loreY, loreW, loreH;

    void resetScroll() {
        scroll = 0;
    }

    /**
     * Draws the tooltip of {@code s} in the box at x, y (outer frame), at most
     * {@code maxH} tall, and returns the height it used. When the lore does not
     * fit, the box shows as many whole lines as it can and scrolls.
     */
    int render(GuiGraphicsExtractor g, Font font, ItemStack s, int x, int y, int w, int maxH) {
        int cx = x + FRAME, cy = y + FRAME, cw = w - 2 * FRAME;
        update(font, s, cw);
        float ts = textScale;
        // Font units from here on. The head is the icon and whatever sits beside it, centred on it when shorter.
        int iconH = Math.round(icon / ts), side = loreTop + beside.size() * line;
        int head = Math.max(iconH, side), dy = Math.max(0, (iconH - side) / 2);
        int below = head + 2;
        int room = (int) ((maxH - 2 * FRAME) / ts);
        int fit = Math.min(lore.size(), Math.max(0, (room - below) / line));
        lastFit = fit;
        int ch = (int) Math.ceil((fit > 0 ? below + fit * line : head) * ts);
        TooltipRenderUtil.extractTooltipBackground(g, cx, cy, cw, ch, null);

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(icon / 16f, icon / 16f);
        g.item(s, 0, 0);
        g.pose().popMatrix();

        scroll = Math.max(0, Math.min(scroll, lore.size() - fit));
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(ts, ts);
        for (int i = 0; i < name.size(); i++) g.text(font, name.get(i), besideX, dy + 1 + i * line, 0xFFFFFFFF, true);
        for (int i = 0; i < beside.size(); i++) {
            g.text(font, beside.get(i), besideX, dy + loreTop + i * line, 0xFFFFFFFF, true);
        }
        for (int i = 0; i < fit; i++) g.text(font, lore.get(scroll + i), 0, below + i * line, 0xFFFFFFFF, true);
        g.pose().popMatrix();

        loreX = cx;
        loreY = cy + (int) (below * ts);
        loreW = cw;
        loreH = (int) (fit * line * ts);
        if (fit > 0 && fit < lore.size()) {
            int thumb = Math.max(6, loreH * fit / lore.size());
            int ty = loreY + (loreH - thumb) * scroll / (lore.size() - fit);
            g.fill(cx + cw, loreY, cx + cw + 2, loreY + loreH, 0x40FFFFFF);
            g.fill(cx + cw, ty, cx + cw + 2, ty + thumb, 0xC0FFFFFF);
        }
        return ch + 2 * FRAME;
    }

    /** Scrolls the lore three lines per notch if the wheel turned over it and there is more to see. */
    boolean mouseScrolled(double mx, double my, double dy) {
        if (mx < loreX || mx >= loreX + loreW + FRAME || my < loreY || my >= loreY + loreH) return false;
        if (lore.size() <= lastFit) return false;
        scroll = Math.max(0, Math.min(lore.size() - lastFit, scroll - (int) Math.signum(dy) * 3));
        return true;
    }

    /** The name line as built, with its styles; null before the first frame. */
    public Component title() {
        return title;
    }

    /** Index of the first lore line shown under the icon. */
    public int scroll() {
        return scroll;
    }

    /** How often the lines were built: once per stack, look or width change, never per frame. */
    public int builds() {
        return builds;
    }

    /** Lore lines in all (beside and under the icon), and how many were drawn last frame. */
    public int[] linesShown() {
        return new int[]{beside.size() + lore.size(), beside.size() + lastFit};
    }

    /** The icon's size in GUI pixels. */
    public int iconSize() {
        return icon;
    }

    /** The text scale last used: 1 is the game's own size. */
    public float textScale() {
        return textScale;
    }

    /** One step smaller than the GUI from scale 3 up: whole screen pixels per font pixel, so still crisp. */
    static float textScale(int guiScale) {
        return guiScale >= 3 ? (guiScale - 1f) / guiScale : 1f;
    }

    private void update(Font font, ItemStack s, int width) {
        Minecraft mc = Minecraft.getInstance();
        boolean adv = mc.options.advancedItemTooltips;
        int v = Looks.version();
        int gs = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
        if (s == stack && v == version && width == wrap && adv == advanced && gs == builtScale) return;
        stack = s;
        version = v;
        wrap = width;
        advanced = adv;
        builtScale = gs;
        builds++;
        textScale = textScale(gs);
        line = textScale < 1 ? 9 : 10; // the smaller text reads fine at chat's spacing
        icon = width >= WIDE_ICON ? 48 : 32;
        int full = (int) (width / textScale) - 3;
        besideX = Math.round((icon + 4) / textScale);
        int narrow = Math.max(20, full - besideX);

        List<Component> lines;
        try {
            lines = Screen.getTooltipFromItem(mc, s);
        } catch (RuntimeException e) {
            lines = List.of(); // another mod's tooltip hook failed: show the name alone
        }
        title = lines.isEmpty() ? s.getStyledHoverName() : lines.getFirst();
        List<FormattedCharSequence> n = font.split(title, narrow);
        name = n.size() > MAX_NAME_LINES ? List.copyOf(n.subList(0, MAX_NAME_LINES)) : n;
        loreTop = name.size() * line + 2;
        int besideRows = Math.max(0, (Math.round(icon / textScale) + 1 - loreTop) / line);

        List<FormattedCharSequence> side = new ArrayList<>(), under = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Component l = lines.get(i);
            if (!under.isEmpty() || side.size() >= besideRows) {
                addSplit(under, font.split(l, full));
                continue;
            }
            // Beside the icon at its width; what does not fit there goes on under it, at full width.
            List<FormattedText> parts = font.getSplitter().splitLines(l, narrow, Style.EMPTY);
            if (parts.isEmpty()) side.add(FormattedCharSequence.EMPTY);
            int take = Math.min(parts.size(), besideRows - side.size());
            for (int p = 0; p < take; p++) side.add(Language.getInstance().getVisualOrder(parts.get(p)));
            if (take < parts.size()) {
                List<FormattedText> rest = new ArrayList<>();
                for (int p = take; p < parts.size(); p++) {
                    if (!rest.isEmpty()) rest.add(FormattedText.of(" "));
                    rest.add(parts.get(p));
                }
                addSplit(under, font.split(FormattedText.composite(rest), full));
            }
        }
        beside = side;
        lore = under;
    }

    private static void addSplit(List<FormattedCharSequence> out, List<FormattedCharSequence> split) {
        if (split.isEmpty()) out.add(FormattedCharSequence.EMPTY);
        else out.addAll(split);
    }
}
