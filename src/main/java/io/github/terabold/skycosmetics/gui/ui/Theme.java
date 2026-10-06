package io.github.terabold.skycosmetics.gui.ui;

/**
 * The settings' colors: the studio's dark panels with its purple accent and a pink end for gradients. All
 * full ARGB (26.1 needs the alpha).
 */
public final class Theme {
    /** Behind the window, over the blurred world or panorama. */
    public static final int SHADE = 0x88000000;
    /** The window's body, where the rows are. */
    public static final int BODY = 0xFF17171D;
    /** The header and the sidebar: one step darker than the body. */
    public static final int CHROME = 0xFF111116;
    /** Fields and controls at rest, and their hover. */
    public static final int SURFACE = 0xFF24242D, SURFACE_HOVER = 0xFF2F2F3A;
    /** The window's frame and separators. */
    public static final int LINE = 0xFF34343F, LINE_SOFT = 0xFF24242C;
    public static final int TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A, DIM = 0xFF5C5C68;
    /** The studio's accent, and the two ends of the accent gradient. */
    public static final int ACCENT = 0xFFD58CFF, PURPLE = 0xFF9D5CFF, PINK = 0xFFFF7AC8;
    /** A selected thing's dark purple background (the studio's selected segment). */
    public static final int ACCENT_BG = 0xFF3A2650;
    public static final int GOLD = 0xFFFFC94A, WARN = 0xFFFF6B6B;
    /** Window corner radius, and the radius of fields, buttons and rows. */
    public static final int RADIUS = 6, SMALL_RADIUS = 3;

    private Theme() {}

    /** The color with its alpha multiplied by {@code a} (0..1). */
    public static int fade(int color, float a) {
        int alpha = Math.round((color >>> 24) * Math.clamp(a, 0f, 1f));
        return alpha << 24 | color & 0xFFFFFF;
    }

    /** From {@code a} at t = 0 to {@code b} at t = 1, alpha included. */
    public static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
            | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
