package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.gui.ColorPicker;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The color picker of a {@link ColorSwatch}, popped up next to it: the studio's {@link ColorPicker} (square, hue,
 * hex box, recent colors) and, for colors with opacity, an opacity bar. Changes apply live; the settings save
 * when a drag ends, on Enter in the hex box, and when it closes (x, Esc or a click outside).
 */
public final class ColorPopover implements Overlay {
    private static final int W = 184, PAD = 7, TITLE_H = 14, PICKER_H = 118, ALPHA_H = 10, CLOSE = 11;
    private final ColorSwatch swatch;
    private final Control.Color color;
    private final Host host;
    private final ColorPicker picker;
    private final Component title;
    private final Anim appear = new Anim(110, 0);
    private int x, y, h, alphaY;
    private int alpha;
    private boolean closed, draggingAlpha;
    private String alphaText = "";

    ColorPopover(Component title, ColorSwatch swatch, Control.Color color, Host host) {
        this.title = title;
        this.swatch = swatch;
        this.color = color;
        this.host = host;
        int v = color.get().getAsInt();
        alpha = color.alpha() ? v >>> 24 : 255;
        picker = new ColorPicker(0, 0, W - 2 * PAD, PICKER_H);
        picker.setRgb(v & 0xFFFFFF);
        picker.setOnChange(rgb -> apply());
        picker.setOnCommit(rgb -> host.changed());
        h = PAD + TITLE_H + PICKER_H + (color.alpha() ? 6 + ALPHA_H + 4 : 0) + PAD;
        updateAlphaText();
    }

    public ColorPicker picker() {
        return picker;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return W;
    }

    public int height() {
        return h;
    }

    /** The middle of the opacity bar at this opacity (0..255), for tests aiming a real click; null without one. */
    public int[] alphaAt(int a) {
        if (!color.alpha()) return null;
        return new int[]{x + PAD + Math.round(a / 255f * (W - 2 * PAD - 1)), alphaY + ALPHA_H / 2};
    }

    /**
     * Next to the swatch, never over it: under it and right-aligned with it, else above it, else on its left as
     * close to its height as the area allows.
     */
    @Override
    public void fit(int bx, int by, int bw, int bh) {
        int below = swatch.getBottom() + 3, above = swatch.getY() - 3 - h;
        boolean fitsBelow = below + h <= by + bh - 2, fitsAbove = above >= by + 2;
        int maxX = Math.max(bx + 2, bx + bw - W - 2);
        if (fitsBelow || fitsAbove) {
            x = Math.clamp(swatch.getX() + swatch.getWidth() - W, bx + 2, maxX);
            y = fitsBelow ? below : above;
        } else {
            x = Math.clamp(swatch.getX() - 4 - W, bx + 2, maxX);
            y = Math.clamp(swatch.getY() + swatch.getHeight() / 2 - h / 2, by + 2, Math.max(by + 2, by + bh - h - 2));
        }
        picker.setPosition(x + PAD, y + PAD + TITLE_H);
        alphaY = y + PAD + TITLE_H + PICKER_H + 6;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        g.nextStratum();
        float t = Anim.easeOut(appear.to(1));
        Shapes.shadow(g, x, y, W, h, Theme.RADIUS, 5);
        Shapes.round(g, x, y, W, h, Theme.RADIUS, Theme.BODY);
        Shapes.frame(g, x, y, W, h, Theme.RADIUS, Theme.mix(Theme.LINE, Theme.ACCENT, 0.5f * t));
        g.text(font, title, x + PAD, y + PAD + 1, Theme.TEXT, false);
        int cx = x + W - PAD - CLOSE, cy = y + PAD - 1;
        boolean overClose = inClose(mouseX, mouseY);
        if (overClose) {
            Shapes.round(g, cx - 1, cy - 1, CLOSE + 2, CLOSE + 2, 3, 0xFF803040);
            g.requestCursor(CursorTypes.POINTING_HAND);
        }
        Ui.cross(g, cx + 3, cy + 3, 5, overClose ? 0xFFFFFFFF : Theme.MUTED);
        picker.render(g, mouseX, mouseY);
        if (color.alpha()) {
            int ax = x + PAD, aw = W - 2 * PAD;
            ColorSwatch.checker(g, ax, alphaY, aw, ALPHA_H);
            Shapes.fadeOut(g, ax, alphaY, ax + aw, alphaY + ALPHA_H, 0xFF000000 | picker.rgb(), true);
            g.outline(ax - 1, alphaY - 1, aw + 2, ALPHA_H + 2, Theme.LINE);
            int kx = ax + Math.round(alpha / 255f * (aw - 1));
            g.fill(kx - 2, alphaY - 2, kx + 3, alphaY + ALPHA_H + 2, 0xFF000000);
            g.fill(kx - 1, alphaY - 1, kx + 2, alphaY + ALPHA_H + 1, 0xFFFFFFFF);
            if (overAlpha(mouseX, mouseY) || draggingAlpha) {
                g.requestCursor(CursorTypes.RESIZE_EW);
                Tips.show(g, font, List.of(Component.literal(alphaText)), Tips.beside(ax, alphaY, ax + aw, alphaY + ALPHA_H,
                    Math.clamp(kx - 10, ax, ax + aw), alphaY, Tips.Side.BELOW, Tips.Side.ABOVE), mouseX, mouseY);
            }
        }
    }

    @Override
    public void mouseClicked(MouseButtonEvent event) {
        double mx = event.x(), my = event.y();
        if (!(mx >= x && mx < x + W && my >= y && my < y + h) || inClose(mx, my)) {
            close();
            return;
        }
        if (color.alpha() && overAlpha(mx, my)) {
            picker.stopEditing();
            draggingAlpha = true;
            alphaTo(mx);
            return;
        }
        picker.mouseClicked(mx, my, event.button());
    }

    @Override
    public void mouseDragged(MouseButtonEvent event) {
        if (draggingAlpha) alphaTo(event.x());
        else picker.mouseDragged(event.x(), event.y(), event.button());
    }

    @Override
    public void mouseReleased(MouseButtonEvent event) {
        if (draggingAlpha) {
            draggingAlpha = false;
            host.changed();
        } else {
            picker.mouseReleased(event.x(), event.y(), event.button());
        }
    }

    /** Keys go to the hex box while it is typed in; otherwise Esc closes and the rest are swallowed. */
    @Override
    public void keyPressed(KeyEvent event) {
        if (picker.isEditing()) picker.keyPressed(event);
        else if (event.isEscape()) close();
    }

    @Override
    public void charTyped(CharacterEvent event) {
        picker.charTyped(event);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** Applies a half-typed hex value, then saves. */
    @Override
    public void close() {
        if (closed) return;
        picker.stopEditing();
        closed = true;
        host.changed();
    }

    private void alphaTo(double mx) {
        int a = Math.clamp(Math.round((float) (mx - x - PAD) / (W - 2 * PAD - 1) * 255), 0, 255);
        if (a == alpha) return;
        alpha = a;
        updateAlphaText();
        apply();
    }

    private void apply() {
        color.set().accept((color.alpha() ? alpha : 255) << 24 | picker.rgb());
        swatch.refresh();
    }

    private void updateAlphaText() {
        alphaText = Component.translatable("skycosmetics.menu.color.opacity", Math.round(alpha * 100 / 255f)).getString();
    }

    private boolean overAlpha(double mx, double my) {
        return mx >= x + PAD - 3 && mx < x + W - PAD + 3 && my >= alphaY - 2 && my < alphaY + ALPHA_H + 2;
    }

    private boolean inClose(double mx, double my) {
        int cx = x + W - PAD - CLOSE, cy = y + PAD - 1;
        return mx >= cx - 1 && mx < cx + CLOSE + 1 && my >= cy - 1 && my < cy + CLOSE + 1;
    }
}
