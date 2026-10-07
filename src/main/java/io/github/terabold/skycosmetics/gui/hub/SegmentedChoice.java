package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

/**
 * A {@link Control.Choice} with few, short values: every value side by side, the chosen one on an accent pill
 * that slides to the next choice. Click a value, or Left and Right when focused. Longer lists get a
 * {@link DropdownChoice} instead.
 */
public final class SegmentedChoice<T> extends AbstractWidget {
    public static final int H = 16, MAX = 4;
    /** Room around each value. */
    private static final int PAD = 14;
    private final Control.Choice<T> choice;
    private final Host host;
    private final FormattedCharSequence[] labels;
    private final int[] widths;
    /** The pill's position in segments, sliding toward the chosen one. */
    private float pill = -1;
    private long last;

    public SegmentedChoice(Component title, int width, Control.Choice<T> choice, Host host) {
        super(0, 0, width, H, title);
        this.choice = choice;
        this.host = host;
        Font font = Minecraft.getInstance().font;
        int n = choice.values().size();
        labels = new FormattedCharSequence[n];
        widths = new int[n];
        for (int i = 0; i < n; i++) {
            Component c = choice.label().apply(choice.values().get(i));
            labels[i] = c.getVisualOrderText();
            widths[i] = font.width(labels[i]);
        }
    }

    /** Equal segments as wide as the longest value plus padding; -1 for fewer than 2 or more than {@value MAX}. */
    public static <T> int naturalWidth(Control.Choice<T> choice) {
        int n = choice.values().size();
        if (n < 2 || n > MAX) return -1;
        return n * (widest(choice) + PAD);
    }

    /** True when these values fit side by side in {@code width}. */
    public static <T> boolean fits(Control.Choice<T> choice, int width) {
        int n = choice.values().size();
        return n >= 2 && n <= MAX && widest(choice) + PAD <= width / n;
    }

    private static <T> int widest(Control.Choice<T> choice) {
        Font font = Minecraft.getInstance().font;
        int widest = 0;
        for (T v : choice.values()) widest = Math.max(widest, font.width(choice.label().apply(v)));
        return widest;
    }

    private int selected() {
        return choice.values().indexOf(choice.get().get());
    }

    private int segmentAt(double mx) {
        int n = labels.length;
        return Math.clamp((int) ((mx - getX()) * n / getWidth()), 0, n - 1);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), n = labels.length;
        float a = active ? 1 : 0.4f;
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.SURFACE, a));
        int sel = selected();
        long now = Util.getMillis();
        if (pill < 0 || sel < 0 || now - last > 250) pill = Math.max(sel, 0);
        else pill += (sel - pill) * (1 - (float) Math.exp(-(now - last) / 45.0));
        if (Math.abs(sel - pill) < 0.01f) pill = sel;
        last = now;
        int hovered = isHovered() && active ? segmentAt(mouseX) : -1;
        if (hovered >= 0 && hovered != sel) {
            int hx = x + w * hovered / n;
            Shapes.round(g, hx + 1, y + 1, x + w * (hovered + 1) / n - hx - 2, h - 2, Theme.SMALL_RADIUS - 1, Theme.SURFACE_HOVER);
        }
        if (sel >= 0) {
            int px = x + Math.round(w * pill / n), pw = w / n;
            Shapes.roundGradient(g, px + 1, y + 1, pw - 2, h - 2, Theme.SMALL_RADIUS - 1, Theme.fade(Theme.GRADIENT_START, a), Theme.fade(Theme.GRADIENT_END, a));
        }
        for (int i = 0; i < n; i++) {
            int sx = x + w * i / n, sw = x + w * (i + 1) / n - sx;
            int color = i == sel ? Theme.ON_ACCENT : i == hovered ? Theme.TEXT : Theme.MUTED;
            g.text(font, labels[i], sx + (sw - widths[i]) / 2, y + (h - 8) / 2, Theme.fade(color, a), false);
        }
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        pick(segmentAt(event.x()));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) return false;
        int sel = Math.max(0, selected());
        pick(Math.clamp(sel + (event.isLeft() ? -1 : 1), 0, labels.length - 1));
        return true;
    }

    private void pick(int i) {
        if (i == selected()) return;
        choice.set().accept(choice.values().get(i));
        host.changed();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(),
            choice.label().apply(choice.get().get())));
    }

    /** The middle of the segment showing this value, for tests aiming a real click. */
    public int[] centerOf(int index) {
        int n = labels.length;
        return new int[]{getX() + getWidth() * (2 * index + 1) / (2 * n), getY() + getHeight() / 2};
    }
}
