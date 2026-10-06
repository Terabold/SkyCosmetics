package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.data.DyeEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A drag colour picker: saturation/value square, hue bar, preview swatch,
 * editable hex box and a row of recently used colours.
 *
 * It is a plain helper rather than a vanilla widget so a screen can place it
 * anywhere (and move it with {@link #setPosition}) without the screen's focus
 * and drag rules getting in the way. The host forwards input: call
 * {@link #mouseClicked} for <b>every</b> click before its own handling (a click
 * elsewhere takes focus off the hex box), and {@link #keyPressed} /
 * {@link #charTyped} first while {@link #isEditing()}.
 *
 * Drawing is cheap: the square is one vertical gradient per pixel column
 * (HSV value is linear in RGB, so a gradient to black is exact), the hue bar is
 * six gradients. No texture to upload or free.
 */
public class ColorPicker {
    private static final int HUE_W = 10;
    private static final int GAP = 4;
    private static final int SWATCH = 16;
    private static final int HEX_W = 56;
    private static final int CHIP = 10;
    private static final int CHIP_GAP = 2;
    private static final int MAX_RECENT = 8;

    private static final int BORDER = 0xFF34343F;
    private static final int MUTED = 0xFF8C8C9A;

    /** Colours picked this session, newest first. Shared by every picker so a colour can be reused anywhere. */
    private static final List<Integer> RECENT = new ArrayList<>();

    private enum Drag { NONE, SV, HUE }

    private int x, y, width, height;
    private boolean showRecent = true;

    private int rgb;
    /** Kept apart from {@link #rgb} so dragging through grey or black does not lose the hue. */
    private float hue, sat, val;
    private int committed;
    private IntConsumer onChange = c -> {};
    private IntConsumer onCommit = c -> {};

    private Drag drag = Drag.NONE;
    private final EditBox hexBox;
    private boolean syncing;

    // Layout, from layout().
    private int svW, svH, hueX, infoY, recentY;
    /** Top colour of each square column; rebuilt only when the hue or width changes. */
    private int[] columnTop = new int[0];
    private float columnHue = Float.NaN;

    public ColorPicker(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        Font font = Minecraft.getInstance().font;
        hexBox = new EditBox(font, x, y, HEX_W, 14, Component.literal("Hex color"));
        hexBox.setMaxLength(7);
        hexBox.setHint(Component.literal("#RRGGBB").withStyle(ChatFormatting.DARK_GRAY));
        hexBox.setResponder(this::typed);
        setRgb(0xFFFFFF);
        layout();
    }

    // ------------------------------------------------------------- API ---

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        layout();
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        layout();
    }

    public int x() { return x; }

    public int y() { return y; }

    public int width() { return width; }

    public int height() { return height; }

    /** Hides the recent-colours row and gives its space to the square. */
    public void setShowRecent(boolean show) {
        this.showRecent = show;
        layout();
    }

    public int rgb() {
        return rgb;
    }

    /** Sets the colour without calling the listeners (the caller already knows). */
    public void setRgb(int rgb) {
        this.rgb = rgb & 0xFFFFFF;
        this.committed = this.rgb;
        fromRgb(this.rgb);
        syncHex();
    }

    /** Called on every change the user makes, many times per second while dragging. Use it for live previews. */
    public void setOnChange(IntConsumer onChange) {
        this.onChange = onChange == null ? c -> {} : onChange;
    }

    /**
     * Called once the user settles on a colour: mouse released after a drag,
     * a recent colour clicked, Enter in (or leaving) the hex box. Save here,
     * not in {@link #setOnChange}, to avoid a config write per mouse move.
     */
    public void setOnCommit(IntConsumer onCommit) {
        this.onCommit = onCommit == null ? c -> {} : onCommit;
    }

    /** True while the hex box has the keyboard; the host should send keys here first. */
    public boolean isEditing() {
        return hexBox.isFocused();
    }

    /** Takes the keyboard off the hex box (committing what was typed), e.g. when the host hides the picker. */
    public void stopEditing() {
        if (!hexBox.isFocused()) return;
        hexBox.setFocused(false);
        int typed = DyeEntry.parseHex(hexBox.getValue());
        if (typed >= 0 && typed != rgb) change(typed, true);
        syncHex();
        commit();
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /** Colours picked this session, newest first. */
    public static List<Integer> recentColours() {
        return List.copyOf(RECENT);
    }

    /** Adds a colour to the front of the recent row (e.g. one the host applied by other means). */
    public static void remember(int rgb) {
        Integer c = rgb & 0xFFFFFF;
        RECENT.remove(c);
        RECENT.addFirst(c);
        while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
    }

    // ---------------------------------------------------------- render ---

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;

        if (columnHue != hue || columnTop.length != svW) {
            columnTop = new int[svW];
            for (int i = 0; i < svW; i++) columnTop[i] = 0xFF000000 | hsvToRgb(hue, i / (float) Math.max(1, svW - 1), 1);
            columnHue = hue;
        }
        for (int i = 0; i < svW; i++) g.fillGradient(x + i, y, x + i + 1, y + svH, columnTop[i], 0xFF000000);
        g.outline(x - 1, y - 1, svW + 2, svH + 2, BORDER);
        int cx = x + Math.round(sat * (svW - 1)), cy = y + Math.round((1 - val) * (svH - 1));
        g.outline(cx - 3, cy - 3, 7, 7, 0xFF000000);
        g.outline(cx - 2, cy - 2, 5, 5, 0xFFFFFFFF);

        for (int i = 0; i < 6; i++) {
            int y0 = y + svH * i / 6, y1 = y + svH * (i + 1) / 6;
            g.fillGradient(hueX, y0, hueX + HUE_W, y1, 0xFF000000 | hsvToRgb(i / 6f, 1, 1),
                0xFF000000 | hsvToRgb((i + 1) / 6f, 1, 1));
        }
        g.outline(hueX - 1, y - 1, HUE_W + 2, svH + 2, BORDER);
        int hy = y + Math.round(hue * (svH - 1));
        g.fill(hueX - 2, hy - 1, hueX + HUE_W + 2, hy + 2, 0xFF000000);
        g.fill(hueX - 1, hy, hueX + HUE_W + 1, hy + 1, 0xFFFFFFFF);

        g.fill(x, infoY, x + SWATCH, infoY + SWATCH, 0xFF000000 | rgb);
        g.outline(x - 1, infoY - 1, SWATCH + 2, SWATCH + 2, BORDER);
        hexBox.extractRenderState(g, mouseX, mouseY, 0);
        int textX = hexBox.getX() + hexBox.getWidth() + 6;
        if (x + width - textX >= font.width("255 255 255")) {
            g.text(font, String.format(Locale.ROOT, "%d %d %d", rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF),
                textX, infoY + 4, MUTED);
        }

        if (showRecent) {
            int n = recentSlots();
            for (int i = 0; i < n; i++) {
                int rx = x + i * (CHIP + CHIP_GAP);
                boolean over = mouseX >= rx && mouseX < rx + CHIP && mouseY >= recentY && mouseY < recentY + CHIP;
                if (i < RECENT.size()) {
                    int c = RECENT.get(i);
                    g.fill(rx, recentY, rx + CHIP, recentY + CHIP, 0xFF000000 | c);
                    g.outline(rx, recentY, CHIP, CHIP, over ? 0xFFFFFFFF : 0xFF000000);
                    if (over) {
                        g.requestCursor(CursorTypes.POINTING_HAND);
                        g.setTooltipForNextFrame(font, Component.literal(hex(c) + " · recent"),
                            mouseX, mouseY);
                    }
                } else {
                    g.outline(rx, recentY, CHIP, CHIP, BORDER);
                }
            }
        }

        if (drag == Drag.SV || (drag == Drag.NONE && inSquare(mouseX, mouseY))) g.requestCursor(CursorTypes.CROSSHAIR);
        else if (drag == Drag.HUE || (drag == Drag.NONE && inHue(mouseX, mouseY))) g.requestCursor(CursorTypes.RESIZE_NS);
    }

    // ----------------------------------------------------------- input ---

    public boolean mouseClicked(double mx, double my, int button) {
        if (hexBox.isMouseOver(mx, my)) {
            if (hexBox.isFocused()) {
                hexBox.onClick(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)), false);
            } else {
                // The box is always full (7 of 7 characters), so select it all and let typing replace it.
                hexBox.setFocused(true);
                hexBox.moveCursorToEnd(false);
                hexBox.setHighlightPos(0);
            }
            return true;
        }
        stopEditing();
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return isMouseOver(mx, my);
        if (inSquare(mx, my)) {
            drag = Drag.SV;
            dragTo(mx, my);
            return true;
        }
        if (inHue(mx, my)) {
            drag = Drag.HUE;
            dragTo(mx, my);
            return true;
        }
        int chip = recentAt(mx, my);
        if (chip >= 0) {
            change(RECENT.get(chip), true);
            syncHex();
            commit();
            return true;
        }
        return isMouseOver(mx, my);
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (drag == Drag.NONE) return false;
        dragTo(mx, my);
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (drag == Drag.NONE) return false;
        drag = Drag.NONE;
        commit();
        return true;
    }

    /** Enter or Escape leaves the hex box; every other key goes to it, so the screen's shortcuts stay quiet. */
    public boolean keyPressed(KeyEvent event) {
        if (!hexBox.isFocused()) return false;
        int k = event.key();
        if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER || k == InputConstants.KEY_ESCAPE) {
            stopEditing();
            return true;
        }
        hexBox.keyPressed(event);
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!hexBox.isFocused()) return false;
        int cp = event.codepoint();
        if (cp == '#' || Character.digit(cp, 16) >= 0) hexBox.charTyped(event);
        return true;
    }

    // --------------------------------------------------------- helpers ---

    private void layout() {
        int recentH = showRecent ? CHIP + 4 : 0;
        svW = Math.max(8, width - HUE_W - GAP);
        svH = Math.max(8, height - SWATCH - GAP - recentH);
        hueX = x + svW + GAP;
        infoY = y + svH + GAP;
        recentY = infoY + SWATCH + 4;
        hexBox.setX(x + SWATCH + 4);
        hexBox.setY(infoY + 1);
        hexBox.setWidth(Math.max(20, Math.min(HEX_W, width - SWATCH - 4)));
    }

    private int recentSlots() {
        return Math.min(MAX_RECENT, (width + CHIP_GAP) / (CHIP + CHIP_GAP));
    }

    private int recentAt(double mx, double my) {
        if (!showRecent || my < recentY || my >= recentY + CHIP || mx < x) return -1;
        int i = (int) ((mx - x) / (CHIP + CHIP_GAP));
        if (i >= recentSlots() || i >= RECENT.size() || mx - x - i * (CHIP + CHIP_GAP) >= CHIP) return -1;
        return i;
    }

    /** The square and hue bar accept a click 2px outside their edge, so the extremes are easy to hit. */
    private boolean inSquare(double mx, double my) {
        return mx >= x - 2 && mx < x + svW + 2 && my >= y - 2 && my < y + svH + 2 && mx < hueX - 1;
    }

    private boolean inHue(double mx, double my) {
        return mx >= hueX - 1 && mx < hueX + HUE_W + 2 && my >= y - 2 && my < y + svH + 2;
    }

    private void dragTo(double mx, double my) {
        if (drag == Drag.SV) {
            sat = clamp01((mx - x) / Math.max(1, svW - 1));
            val = 1 - clamp01((my - y) / Math.max(1, svH - 1));
        } else if (drag == Drag.HUE) {
            hue = clamp01((my - y) / Math.max(1, svH - 1));
        }
        int now = hsvToRgb(hue, sat, val);
        if (now != rgb) {
            rgb = now;
            syncHex();
            onChange.accept(rgb);
        }
    }

    /** A colour from outside the square (hex box, recent chip): move the handles to it and tell the host. */
    private void change(int newRgb, boolean notify) {
        newRgb &= 0xFFFFFF;
        if (newRgb == rgb) return;
        rgb = newRgb;
        fromRgb(rgb);
        if (notify) onChange.accept(rgb);
    }

    private void commit() {
        if (rgb == committed) return;
        committed = rgb;
        remember(rgb);
        onCommit.accept(rgb);
    }

    /** Live while typing, but only for full 6-digit values: "#FFF" on the way to "#FFF000" must not flash white. */
    private void typed(String value) {
        if (syncing) return;
        String v = value.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() != 6) return;
        int parsed = DyeEntry.parseHex(v);
        if (parsed >= 0) change(parsed, true);
    }

    private void syncHex() {
        String want = hex(rgb);
        if (hexBox.getValue().equalsIgnoreCase(want)) return;
        if (hexBox.isFocused() && DyeEntry.parseHex(hexBox.getValue()) == rgb) return; // don't fight the typist
        syncing = true;
        hexBox.setValue(want);
        syncing = false;
    }

    private void fromRgb(int c) {
        float r = (c >> 16 & 0xFF) / 255f, g = (c >> 8 & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d > 0) {
            float h;
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            hue = h < 0 ? h + 1 : h;
        }
        if (max > 0) sat = d / max;
        val = max;
    }

    static int hsvToRgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6;
        int i = (int) hh;
        float f = hh - i;
        i %= 6; // float rounding can give exactly 6.0 for a hue just below 1
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static float clamp01(double v) {
        return (float) Math.clamp(v, 0.0, 1.0);
    }
}
