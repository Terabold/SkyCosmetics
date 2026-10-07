package io.github.terabold.skycosmetics.gui.ui;

import net.minecraft.util.Util;

/**
 * A number that moves toward a target at a fixed speed, driven by real frame time: the same speed at 30 or
 * 300 FPS, and no allocation per frame. A value not drawn for a while jumps to its target on the next frame,
 * so nothing replays an old animation.
 *
 * Every animation of a frame reads one clock ({@link #frame}, set when the studio or the settings start drawing)
 * and one easing ({@link #ease}), so hovers, switches, tabs and pop-ups started together move together.
 */
public final class Anim {
    /** Longer than any animation here: after such a gap the value simply arrives. */
    private static final long STALE_MS = 250;
    /** Time constant of the glides (scrolls, the sidebar bar, sliding pills): about 3x this to settle. */
    public static final float GLIDE_MS = 50;
    private static long frame = Long.MIN_VALUE / 2;
    private final float perMs;
    private float value;
    private long last = -1;

    /** @param durationMs how long a full 0 to 1 move takes */
    public Anim(int durationMs, float start) {
        this.perMs = 1f / Math.max(1, durationMs);
        this.value = start;
    }

    /** Called when a screen starts a frame: every animation drawn in it reads this time. */
    public static void frame() {
        frame = Util.getMillis();
    }

    /** The frame's time; outside our screens (a widget drawn elsewhere), the time now. */
    public static long now() {
        long t = Util.getMillis();
        return t - frame < 100 ? frame : t;
    }

    /** Steps toward {@code target} by the time since the last call and returns the new value, eased. */
    public float to(float target) {
        long now = now();
        if (value != target) {
            long dt = last < 0 ? 0 : now - last;
            if (dt > STALE_MS) {
                value = target;
            } else {
                float step = dt * perMs;
                value = value < target ? Math.min(target, value + step) : Math.max(target, value - step);
            }
        }
        last = now;
        return ease(value);
    }

    /** The value, eased, without moving it. */
    public float value() {
        return ease(value);
    }

    /** Jumps there at once. */
    public void set(float v) {
        value = v;
        last = now();
    }

    /** The one easing every animation uses: a gentle start and a soft landing, the same both ways. */
    public static float ease(float t) {
        float x = Math.clamp(t, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    /** Fast start, soft landing: for things that only ever go one way (a flash fading out). */
    public static float easeOut(float t) {
        float u = 1 - Math.clamp(t, 0f, 1f);
        return 1 - u * u * u;
    }

    /** {@code current} glided toward {@code target} over {@code dtMs}, frame-rate independent ({@link #GLIDE_MS}). */
    public static float glide(float current, float target, long dtMs) {
        return current + (target - current) * (1 - (float) Math.exp(-Math.max(0, dtMs) / GLIDE_MS));
    }
}
