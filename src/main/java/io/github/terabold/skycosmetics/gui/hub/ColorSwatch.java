package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ColorPicker;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A {@link Control.Color}: the color on a small swatch (over a checkerboard when it is see-through) and its
 * hex code. A click opens a {@link ColorPopover} next to it. The message is the row's title.
 */
public final class ColorSwatch extends ThemedButton {
    public static final int H = 16, SWATCH_W = 22;
    private final Control.Color color;
    private final Host host;
    private int value;
    private String hex = "";
    private ColorPopover open;

    public ColorSwatch(Component title, int width, Control.Color color, Host host) {
        super(width, H, title, b -> ((ColorSwatch) b).toggle());
        this.color = color;
        this.host = host;
        refresh();
    }

    /** The width that fits the swatch and "#RRGGBB" (plus the opacity when the color has one). */
    public static int fit(boolean alpha) {
        Font font = Minecraft.getInstance().font;
        return SWATCH_W + 14 + font.width(alpha ? "#FFFFFF 100%" : "#FFFFFF");
    }

    public void refresh() {
        int v = color.get().getAsInt();
        if (!color.alpha()) v |= 0xFF000000;
        value = v;
        int alpha = v >>> 24;
        hex = ColorPicker.hex(v) + (color.alpha() && alpha != 255 ? " " + Math.round(alpha * 100 / 255f) + "%" : "");
    }

    public int value() {
        return value;
    }

    /** The open picker, or null. */
    public ColorPopover popover() {
        return open != null && !open.isClosed() ? open : null;
    }

    private void toggle() {
        if (popover() != null) {
            open.close();
            return;
        }
        if (!(host instanceof OverlayHost overlays)) return;
        open = new ColorPopover(getMessage(), this, color, host);
        overlays.openOverlay(open);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float a = active ? 1 : 0.4f;
        boolean isOpen = popover() != null;
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(isOpen ? Theme.ACCENT : Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
        swatch(g, x + 3, y + 3, SWATCH_W, h - 6, value, a);
        g.text(font, hex, x + SWATCH_W + 8, y + (h - 8) / 2, Theme.fade(Theme.TEXT, a), false);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }

    /** A rounded color chip; a see-through color shows a checkerboard under it. */
    static void swatch(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, float a) {
        if ((argb >>> 24) < 255) checker(g, x, y, w, h);
        Shapes.round(g, x, y, w, h, 2, Theme.fade(argb, a));
        Shapes.frame(g, x, y, w, h, 2, Theme.fade(0x40FFFFFF, a));
    }

    /** Light and dark 4 px squares. */
    static void checker(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF9A9AA4);
        for (int cy = 0; cy < h; cy += 4) {
            for (int cx = (cy / 4 % 2) * 4; cx < w; cx += 8) {
                g.fill(x + cx, y + cy, x + Math.min(w, cx + 4), y + Math.min(h, cy + 4), 0xFF5E5E68);
            }
        }
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(CommonComponents.optionNameValue(getMessage(), Component.literal(hex)));
    }
}
