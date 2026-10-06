package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A {@link Control.Slider}: every step applies live (a preview follows the drag), and the settings save
 * once, when the drag ends or after an arrow key.
 */
public final class OptionSlider extends AbstractSliderButton {
    private final Control.Slider slider;
    private final Host host;
    private double current;

    public OptionSlider(int width, Control.Slider slider, Host host) {
        super(0, 0, width, 20, slider.label().apply(slider.get().getAsDouble()),
            (slider.get().getAsDouble() - slider.min()) / (slider.max() - slider.min()));
        this.slider = slider;
        this.host = host;
        this.current = slider.get().getAsDouble();
    }

    @Override
    protected void updateMessage() {
        setMessage(slider.label().apply(current));
    }

    @Override
    protected void applyValue() {
        double raw = slider.min() + value * (slider.max() - slider.min());
        double v = Math.clamp(Math.round(raw / slider.step()) * slider.step(), slider.min(), slider.max());
        v = Math.round(v * 1000) / 1000.0; // 0.1 + 0.2 stays 0.3
        if (v == current) return;
        current = v;
        slider.set().accept(v);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        host.changed();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean used = super.keyPressed(event);
        if (used) host.changed();
        return used;
    }
}
