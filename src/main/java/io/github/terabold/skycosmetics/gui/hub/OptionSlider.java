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
import org.lwjgl.glfw.GLFW;

/**
 * A {@link Control.Slider}: its value on the left, a track filling with the accent gradient and a round knob.
 * Click or drag anywhere on the track; the arrow keys step (Shift: ten steps), Home and End jump to the ends.
 * Every step applies live (a preview follows the drag) and the settings save once, when the drag ends or after
 * a key.
 */
public final class OptionSlider extends AbstractWidget {
    public static final int H = 14;
    private static final int KNOB = 10;
    private final Control.Slider slider;
    private final Host host;
    private final Anim knobHover = new Anim(90, 0);
    private final int labelW;
    private double current;
    private String label = "";
    private boolean dragging;

    /** Without a title: narration reads only the value. */
    public OptionSlider(int width, Control.Slider slider, Host host) {
        this(Component.empty(), width, slider, host);
    }

    public OptionSlider(Component title, int width, Control.Slider slider, Host host) {
        super(0, 0, width, H, title);
        this.slider = slider;
        this.host = host;
        Font font = Minecraft.getInstance().font;
        current = clamp(slider.get().getAsDouble());
        // Room for the widest of the ends and the start, so the track doesn't jump while dragging.
        int widest = Math.max(font.width(slider.label().apply(slider.min())), font.width(slider.label().apply(slider.max())));
        labelW = Math.min(Math.max(widest, font.width(slider.label().apply(current))) + 2, width / 2);
        updateLabel();
    }

    /** Reads the value again (another row may have changed it). */
    public void refresh() {
        double v = clamp(slider.get().getAsDouble());
        if (v != current) {
            current = v;
            updateLabel();
        }
    }

    public double value() {
        return current;
    }

    /** The track's left end and width, for tests aiming a real click. */
    public int trackX() {
        return getX() + labelW + KNOB_ROOM;
    }

    public int trackW() {
        return Math.max(1, getX() + getWidth() - KNOB / 2 - trackX());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        float a = active ? 1 : 0.4f;
        // The value sits left of the track, cut to its column: the knob never runs over it.
        String shown = font.width(label) <= labelW ? label : font.plainSubstrByWidth(label, labelW);
        g.text(font, shown, getX() + labelW - font.width(shown), getY() + (H - 8) / 2, Theme.fade(Theme.TEXT, a), false);
        double span = slider.max() - slider.min();
        float p = span <= 0 ? 0 : (float) ((current - slider.min()) / span);
        track(g, trackX(), trackW(), getY() + H / 2, p, knobHover.to(active && (dragging || isHovered()) ? 1 : 0), a);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), 3);
        if (isHovered() && active) g.requestCursor(CursorTypes.RESIZE_EW);
    }

    /**
     * The track from {@code tx} ({@code tw} wide) centred on {@code cy}, filled with the accent gradient up to the
     * knob at {@code p} (0 to 1); {@code hover} (0 to 1) grows the knob and rings it; {@code a} fades it all.
     * The studio's sliders share it, so every slider looks the same.
     */
    public static void track(GuiGraphicsExtractor g, int tx, int tw, int cy, float p, float hover, float a) {
        int kx = tx + Math.round(Math.clamp(p, 0f, 1f) * tw), ty = cy - 2;
        Shapes.round(g, tx - 2, ty, tw + 4, 4, 2, Theme.fade(Theme.SURFACE_HOVER, a));
        Shapes.roundGradient(g, tx - 2, ty, kx - tx + 4, 4, 2, Theme.fade(Theme.GRADIENT_START, a), Theme.fade(Theme.GRADIENT_END, a));
        int d = KNOB + Math.round(hover * 2);
        if (hover > 0) Shapes.circle(g, kx - d / 2 - 2, cy - d / 2 - 2, d + 4, Theme.fade(Theme.ACCENT, 0.3f * hover * a));
        Shapes.circle(g, kx - d / 2, cy - d / 2, d, Theme.fade(0xFFFFFFFF, a));
    }

    /** Room left of a track's start for its knob, grown and ringed: the value column ends this far before it. */
    public static final int KNOB_ROOM = KNOB / 2 + 6;

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        dragging = true;
        setFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        setFromMouse(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (!dragging) return;
        dragging = false;
        host.changed();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active) return false;
        int k = event.key();
        double v;
        if (event.isLeft() || event.isRight()) {
            int steps = event.hasShiftDown() ? 10 : 1;
            v = current + (event.isLeft() ? -steps : steps) * slider.step();
        } else if (k == GLFW.GLFW_KEY_HOME) {
            v = slider.min();
        } else if (k == GLFW.GLFW_KEY_END) {
            v = slider.max();
        } else {
            return false;
        }
        apply(v);
        host.changed();
        return true;
    }

    private void setFromMouse(double mx) {
        double p = Math.clamp((mx - trackX()) / trackW(), 0.0, 1.0);
        apply(slider.min() + p * (slider.max() - slider.min()));
    }

    /** Snaps to the step and applies it live, if it changed. */
    private void apply(double raw) {
        double v = clamp(raw);
        if (v == current) return;
        current = v;
        updateLabel();
        slider.set().accept(v);
    }

    private double clamp(double raw) {
        double step = slider.step() > 0 ? slider.step() : 1e-3;
        double v = Math.clamp(slider.min() + Math.round((raw - slider.min()) / step) * step, slider.min(), slider.max());
        return Math.round(v * 1000) / 1000.0; // 0.1 + 0.2 stays 0.3
    }

    private void updateLabel() {
        label = slider.label().apply(current).getString();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), slider.label().apply(current)));
    }
}
