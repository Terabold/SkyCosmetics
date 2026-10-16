package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.gui.ui.Anim;
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


/**
 * Two values on one track, as {@link OptionSlider} draws one: a low knob and a high one, the track filled between
 * them. A click moves the nearer knob and a drag keeps it; the arrow keys move the high one (Shift: ten steps),
 * with Alt the low one. Changes apply live and save when the drag ends or after a key.
 */
public final class RangeSlider extends AbstractWidget {
    private static final int KNOB = 10;
    private final Control.Range range;
    private final double min, max, step;
    private final Host host;
    private final Anim hover = new Anim(90, 0);
    private final int labelW;
    private double lo, hi;
    /** The knob a drag holds: 0 low, 1 high, -1 none. */
    private int held = -1;

    public RangeSlider(Component title, int width, Control.Range range, Host host) {
        super(0, 0, width, OptionSlider.H, title);
        this.range = range;
        this.min = range.min();
        this.max = range.max();
        this.step = range.step();
        this.host = host;
        Font font = Minecraft.getInstance().font;
        labelW = Math.min(font.width(text(max, max)) + 2, width / 2);
        refresh();
    }

    /** Reads both values again (another row may have changed them). */
    public void refresh() {
        hi = snap(range.high().getAsDouble());
        lo = Math.min(snap(range.low().getAsDouble()), hi);
    }

    private static String text(double lo, double hi) {
        return Math.round(lo) + "–" + Math.round(hi) + "%";
    }

    private int trackX() {
        return getX() + labelW + OptionSlider.KNOB_ROOM;
    }

    private int trackW() {
        return Math.max(1, getX() + getWidth() - KNOB / 2 - trackX());
    }

    private float at(double v) {
        return max <= min ? 0 : (float) ((v - min) / (max - min));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        float a = active ? 1 : 0.4f;
        String label = text(lo, hi);
        g.text(font, label, getX() + labelW - font.width(label), getY() + (OptionSlider.H - 8) / 2, Theme.fade(Theme.TEXT, a), false);
        int tx = trackX(), tw = trackW(), cy = getY() + OptionSlider.H / 2, ty = cy - 2;
        int kl = tx + Math.round(at(lo) * tw), kh = tx + Math.round(at(hi) * tw);
        Shapes.round(g, tx - 2, ty, tw + 4, 4, 2, Theme.fade(Theme.SURFACE_HOVER, a));
        // Filled from the low knob to the high one, fainter at the low end like the gradient it sets.
        Shapes.roundGradient(g, kl - 2, ty, kh - kl + 4, 4, 2, Theme.fade(Theme.GRADIENT_START, 0.45f * a), Theme.fade(Theme.GRADIENT_END, a));
        float hv = hover.to(active && (held >= 0 || isHovered()) ? 1 : 0);
        int near = held >= 0 ? held : isHovered() ? nearer(mouseX) : -1;
        knob(g, kl, cy, near == 0 ? hv : 0, a);
        knob(g, kh, cy, near == 1 ? hv : 0, a);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), 3);
        if (isHovered() && active) g.requestCursor(CursorTypes.RESIZE_EW);
    }

    private static void knob(GuiGraphicsExtractor g, int kx, int cy, float hover, float a) {
        int d = KNOB + Math.round(hover * 2);
        if (hover > 0) Shapes.circle(g, kx - d / 2 - 2, cy - d / 2 - 2, d + 4, Theme.fade(Theme.ACCENT, 0.3f * hover * a));
        Shapes.circle(g, kx - d / 2, cy - d / 2, d, Theme.fade(0xFFFFFFFF, a));
    }

    /** The knob nearer to x; the high one when they sit together and x is right of them. */
    private int nearer(double x) {
        int tx = trackX(), tw = trackW();
        double kl = tx + at(lo) * tw, kh = tx + at(hi) * tw;
        if (Math.abs(kh - kl) < 1) return x >= kh ? 1 : 0;
        return Math.abs(x - kl) < Math.abs(x - kh) ? 0 : 1;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = nearer(event.x());
        setFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (held >= 0) setFromMouse(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (held < 0) return;
        held = -1;
        host.changed();
    }

    private void setFromMouse(double mx) {
        double v = min + Math.clamp((mx - trackX()) / trackW(), 0.0, 1.0) * (max - min);
        if (held == 0) apply(Math.min(v, hi), hi); else apply(lo, Math.max(v, lo));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) return false;
        double d = (event.isLeft() ? -1 : 1) * (event.hasShiftDown() ? 10 : 1) * step;
        if (event.hasAltDown()) apply(Math.min(lo + d, hi), hi); else apply(lo, Math.max(hi + d, lo));
        host.changed();
        return true;
    }

    private void apply(double l, double h) {
        double nh = snap(h), nl = Math.min(snap(l), nh);
        if (nl == lo && nh == hi) return;
        range.set().set(nl, nh);
        refresh(); // what the setting kept, which may clamp an end
    }

    private double snap(double raw) {
        double v = Math.clamp(min + Math.round((raw - min) / step) * step, min, max);
        return Math.round(v * 1000) / 1000.0;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), Component.literal(text(lo, hi))));
    }
}
