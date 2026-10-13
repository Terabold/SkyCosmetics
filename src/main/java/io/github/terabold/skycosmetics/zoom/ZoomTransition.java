package io.github.terabold.skycosmetics.zoom;

/** Finite, reversible transitions in optical magnification, driven by monotonic elapsed time. */
public final class ZoomTransition {
    public enum Curve {
        INSTANT, LINEAR, IN_SINE, OUT_SINE, IN_OUT_SINE, IN_QUAD, OUT_QUAD, IN_OUT_QUAD,
        IN_CUBIC, OUT_CUBIC, IN_OUT_CUBIC, IN_EXPO, OUT_EXPO, IN_OUT_EXPO;

        public double at(double t) {
            t = Math.clamp(t, 0, 1);
            if (t == 0 || t == 1) return t;
            return switch (this) {
                case INSTANT -> 1;
                case LINEAR -> t;
                case IN_SINE -> 1 - Math.cos(t * Math.PI / 2);
                case OUT_SINE -> Math.sin(t * Math.PI / 2);
                case IN_OUT_SINE -> (1 - Math.cos(Math.PI * t)) / 2;
                case IN_QUAD -> t * t;
                case OUT_QUAD -> 1 - (1 - t) * (1 - t);
                case IN_OUT_QUAD -> t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
                case IN_CUBIC -> t * t * t;
                case OUT_CUBIC -> 1 - Math.pow(1 - t, 3);
                case IN_OUT_CUBIC -> t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                case IN_EXPO -> Math.pow(2, 10 * t - 10);
                case OUT_EXPO -> 1 - Math.pow(2, -10 * t);
                case IN_OUT_EXPO -> t < .5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
            };
        }
    }

    private double from, target, start, seconds;
    private Curve curve = Curve.INSTANT;

    public double value(double now) {
        if (seconds <= 0 || curve == Curve.INSTANT) return Math.exp(target);
        double t = curve.at((now - start) / seconds);
        return Math.exp(from + (target - from) * t);
    }

    public void to(double magnification, double now, double duration, Curve easing) {
        to(magnification, now, duration, easing, false);
    }

    /** An activation change may replace a wheel transition even when both end at 1x. */
    public void to(double magnification, double now, double duration, Curve easing, boolean retime) {
        magnification = Double.isFinite(magnification) ? Math.clamp(magnification, 1, 1000) : 1;
        double next = Math.log(magnification);
        if (next == target && !retime) return;
        from = Math.log(value(now));
        target = next;
        start = now;
        seconds = Double.isFinite(duration) ? Math.clamp(duration, 0, 10) : 0;
        curve = easing;
    }

    public void reset() { from = target = start = seconds = 0; }
}
