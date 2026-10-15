package io.github.terabold.skycosmetics.hand;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * A pose's numbers on a slider and as text. Sizes and speed move in powers of two, so 1x sits in the middle and
 * 0.5x is as far from it as 2x.
 */
final class PoseNumbers {
    private PoseNumbers() {}

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

    /** "0.18", "-12°", "0.61x", or "Default" for a speed or tilt at 1x. */
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
}
