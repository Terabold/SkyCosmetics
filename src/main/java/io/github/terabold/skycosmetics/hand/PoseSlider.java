package io.github.terabold.skycosmetics.hand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * One number of the pose being edited. Every step applies at once (the hand follows the drag); the settings save
 * when the drag ends. Sizes and speed move in powers of two, so 1x sits in the middle and 0.5x is as far from it
 * as 2x. Right-click puts the number back to Minecraft's.
 */
final class PoseSlider extends AbstractSliderButton {
    private final HandPose.Field field;
    private final Supplier<String> key;
    private final Runnable save;

    PoseSlider(int width, HandPose.Field field, Supplier<String> key, Runnable save) {
        super(0, 0, width, 20, Component.empty(), 0);
        this.field = field;
        this.key = key;
        this.save = save;
        refresh();
    }

    HandPose.Field field() {
        return field;
    }

    /** Reads the value again (another rule picked, a reset). */
    void refresh() {
        HandPose p = Hand.pose(key.get());
        value = toSlider(field, p == null ? field.vanilla : p.get(field));
        updateMessage();
    }

    private float current() {
        HandPose p = Hand.pose(key.get());
        return p == null ? field.vanilla : p.get(field);
    }

    static double toSlider(HandPose.Field f, float v) {
        if (f.multiplier()) {
            double lo = log2(f.min), hi = log2(f.max);
            return Math.clamp((log2(v) - lo) / (hi - lo), 0, 1);
        }
        return Math.clamp((v - f.min) / (double) (f.max - f.min), 0, 1);
    }

    static float fromSlider(HandPose.Field f, double t) {
        if (f.multiplier()) {
            double lo = log2(f.min), hi = log2(f.max), e = lo + t * (hi - lo);
            if (Math.abs(e) < 0.03) return 1; // a drag through the middle lands on 1x exactly
            return HandPose.round(f, (float) Math.pow(2, e));
        }
        double v = f.min + t * (f.max - f.min);
        return HandPose.round(f, (float) (Math.round(v / f.step) * f.step));
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("skycosmetics.hand.field." + field.key, text(field, current())));
    }

    /** "0.18", "-12°", "0.61x", or "Default" for a speed or multiplier at 1x. */
    static Component text(HandPose.Field f, float v) {
        if (f.multiplier() || f == HandPose.Field.SWING_X || f == HandPose.Field.SWING_Y || f == HandPose.Field.SWING_Z
            || f == HandPose.Field.SWING_TURN) {
            if (v == 1 && (f == HandPose.Field.SPEED || f == HandPose.Field.SWING_TURN)) {
                return Component.translatable("skycosmetics.menu.default");
            }
            return Component.literal(trim(v) + "x");
        }
        if (f == HandPose.Field.ROT_X || f == HandPose.Field.ROT_Y || f == HandPose.Field.ROT_Z) {
            return Component.literal(trim(v) + "°");
        }
        return Component.literal(trim(v));
    }

    /** At most two decimals, no trailing zeros: 1, 0.5, 0.61, -12.5. */
    static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.equals("-0") ? "0" : s;
    }

    @Override
    protected void applyValue() {
        float v = fromSlider(field, value);
        HandPose p = Hand.pose(key.get());
        if (p == null || p.get(field) == v) return;
        Hand.setPose(key.get(), p.with(field, v));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && active && visible && isMouseOver(event.x(), event.y())) {
            reset();
            playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Back to Minecraft's value for this number. */
    void reset() {
        HandPose p = Hand.pose(key.get());
        if (p != null) Hand.setPose(key.get(), p.with(field, field.vanilla));
        refresh();
        save.run();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        refresh(); // the knob snaps to the value kept (rounded, 1x)
        save.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean used = super.keyPressed(event);
        if (used) save.run();
        return used;
    }
}
