package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Looks;
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
 */
final class SpeedSlider extends AbstractSliderButton {
    private final String label;
    private final float min, max, step;
    private final Function<Float, Float> clamp;
    private final Consumer<Float> onChange;
    private final Runnable onRelease;
    private Float current;

    private SpeedSlider(int x, int y, int w, int h, String label, float min, float max, float step,
                        Function<Float, Float> clamp, Float current, Consumer<Float> onChange, Runnable onRelease) {
        super(x, y, w, h, Component.empty(), ((current == null ? 1f : current) - min) / (max - min));
        this.label = label;
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
        return new SpeedSlider(x, y, w, h, "Glint speed", 0.1f, 4f, 0.1f, Looks::clampSpeed, speed, onChange, onRelease);
    }

    static SpeedSlider strength(int x, int y, int w, int h, Float strength, Consumer<Float> onChange, Runnable onRelease) {
        return new SpeedSlider(x, y, w, h, "Glint strength", 0.25f, 3f, 0.05f, Looks::clampStrength, strength, onChange,
            onRelease);
    }

    /** "1.5x", "0.25x", "2.0x": one decimal, two when the step needs them. */
    static String format(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return (s.endsWith("0") ? s.substring(0, s.length() - 1) : s) + "x";
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
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        onRelease.run();
    }
}
