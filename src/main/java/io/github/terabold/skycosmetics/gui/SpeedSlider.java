package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.gui.hub.OptionSlider;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A glint multiplier: speed from 0.1x to 4x in steps of 0.1, or strength from
 * 0.25x to 3x in steps of 0.05. Shows "Default" until it is moved. Every step
 * goes to {@code onChange}; the drag ending goes to {@code onRelease}, so the
 * host can save once.
 *
 * Drawn like the settings' sliders: the value in a column on the left ("Speed: 2.0x"), then the track and its
 * round knob, so the knob never covers the text. The column is as wide for both sliders, so their tracks line up.
 * Vanilla's slider still does the keys; a click or drag maps through the track.
 */
public final class SpeedSlider extends AbstractSliderButton {
    /** The visible names; the message keeps "Glint speed: ..." for narration. */
    private static final String[] SHORT = {"Speed", "Strength"};
    private final String label, shortLabel;
    private final float min, max, step;
    private final Function<Float, Float> clamp;
    private final Consumer<Float> onChange;
    private final Runnable onRelease;
    private Float current;
    private final Anim hover = new Anim(90, 0);
    private boolean held;

    private SpeedSlider(int x, int y, int w, int h, String label, String shortLabel, float min, float max, float step,
                        Function<Float, Float> clamp, Float current, Consumer<Float> onChange, Runnable onRelease) {
        super(x, y, w, h, Component.empty(), ((current == null ? 1f : current) - min) / (max - min));
        this.label = label;
        this.shortLabel = shortLabel;
        this.min = min;
        this.max = max;
        this.step = step;
        this.clamp = clamp;
        this.current = current;
        this.onChange = onChange;
        this.onRelease = onRelease;
        updateMessage();
    }

    static SpeedSlider speed(int x, int y, int w, int h, Float speed, Consumer<Float> onChange, Runnable onRelease) {
        return new SpeedSlider(x, y, w, h, "Glint speed", SHORT[0], 0.1f, 4f, 0.1f, Looks::clampSpeed, speed, onChange, onRelease);
    }

    static SpeedSlider strength(int x, int y, int w, int h, Float strength, Consumer<Float> onChange, Runnable onRelease) {
        return new SpeedSlider(x, y, w, h, "Glint strength", SHORT[1], 0.25f, 3f, 0.05f, Looks::clampStrength, strength,
            onChange, onRelease);
    }

    /** "1.5x", "0.25x", "2.0x": one decimal, two when the step needs them. */
    static String format(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return (s.endsWith("0") ? s.substring(0, s.length() - 1) : s) + "x";
    }

    /** The value column: the widest text either slider shows, at most half the slider. */
    private int labelW(Font font) {
        int w = 0;
        for (String s : SHORT) w = Math.max(w, Math.max(font.width(s + ": Default"), font.width(s + ": 0.25x")));
        return Math.min(w + 2, getWidth() / 2);
    }

    /** Where the track starts, and its width: tests aim real clicks with them. */
    public int trackX() {
        return getX() + labelW(Minecraft.getInstance().font) + OptionSlider.KNOB_ROOM;
    }

    public int trackW() {
        return Math.max(1, getX() + getWidth() - 7 - trackX());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight(), r = Math.min(Theme.SMALL_RADIUS + 1, h / 2);
        float a = active ? 1 : 0.45f, hv = hover.to(active && (held || isHoveredOrFocused()) ? 1 : 0);
        Font font = Minecraft.getInstance().font;
        int lw = labelW(font);
        String text = shortLabel + ": " + (current == null ? "Default" : format(current));
        if (font.width(text) > lw) text = font.plainSubstrByWidth(text, lw);
        g.text(font, text, x + lw - font.width(text), y + (h - 8) / 2, Theme.fade(current == null ? Theme.MUTED : Theme.TEXT, a), false);
        OptionSlider.track(g, trackX(), trackW(), y + h / 2, (float) Math.clamp(value, 0, 1), hv, a);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, r);
        if (isHovered() && active) g.requestCursor(CursorTypes.RESIZE_EW);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + ": " + (current == null ? "Default" : format(current))));
    }

    @Override
    protected void applyValue() {
        float raw = min + (float) value * (max - min);
        Float v = clamp.apply(Math.round(Math.round(raw / step) * step * 100) / 100f);
        if (v == null || v.equals(current)) return;
        current = v;
        onChange.accept(v);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        held = active;
        fromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (held) fromMouse(event.x());
    }

    private void fromMouse(double mx) {
        setValue(Math.clamp((mx - trackX()) / trackW(), 0.0, 1.0));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (!held) return;
        held = false;
        onRelease.run();
    }
}
