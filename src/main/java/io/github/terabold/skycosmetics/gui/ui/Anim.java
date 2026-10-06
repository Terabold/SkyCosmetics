package io.github.terabold.skycosmetics.gui.ui;

import net.minecraft.util.Util;

/**
 * A number that moves toward a target at a fixed speed, driven by real frame time: the same speed at 30 or
 * 300 FPS, and no allocation per frame. A value not drawn for a while jumps to its target on the next frame,
 * so nothing replays an old animation.
 */
public final class Anim {
    /** Longer than any animation here: after such a gap the value simply arrives. */
    private static final long STALE_MS = 250;
    private final float perMs;
    private float value;
    private long last = -1;

    /** @param durationMs how long a full 0 to 1 move takes */
    public Anim(int durationMs, float start) {
        this.perMs = 1f / Math.max(1, durationMs);
        this.value = start;
    }

    /** Steps toward {@code target} by the time since the last call and returns the new value. */
    public float to(float target) {
        long now = Util.getMillis();
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
        return value;
    }

    public float value() {
        return value;
    }

    /** Jumps there at once. */
    public void set(float v) {
        value = v;
        last = Util.getMillis();
    }

    /** Fast start, soft landing. */
    public static float easeOut(float t) {
        float u = 1 - Math.clamp(t, 0f, 1f);
        return 1 - u * u * u;
    }
}
