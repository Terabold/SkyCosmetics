package io.github.terabold.skycosmetics.gui.ui;

/**
 * The colors of the settings and the studio: dark neutral panels and one accent the player picks (cyan by default).
 * All full ARGB (26.1 needs the alpha).
 *
 * Every accent color below is derived from the picked one by {@link #setAccent}, once per change, so drawing code
 * only reads fields. Read them when drawing, never copy them into a constant: they change while a screen is open.
 */
public final class Theme {
    /** Behind the window, over the blurred world or panorama. */
    public static final int SHADE = 0x88000000;
    /** The window's body, where the rows are. */
    public static final int BODY = 0xFF17171D;
    /** The header and the sidebar: one step darker than the body. */
    public static final int CHROME = 0xFF111116;
    /** Under a row the mouse is over: a breath lighter than the body. */
    public static final int ROW_HOVER = 0xFF1E1E26;
    /** Fields and controls at rest, and their hover. */
    public static final int SURFACE = 0xFF24242D, SURFACE_HOVER = 0xFF2F2F3A;
    /** The window's frame and separators. */
    public static final int LINE = 0xFF34343F, LINE_SOFT = 0xFF24242C;
    public static final int TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A, DIM = 0xFF5C5C68;
    /** Favorites' stars. */
    public static final int GOLD = 0xFFFFC94A;
    /** Errors, clashes and what removes something. */
    public static final int WARN = 0xFFFF6B6B;
    /** A remove or reset button at rest, its frame, and its fill under the mouse. */
    public static final int DANGER = 0xFF2E1D22, DANGER_LINE = 0xFF6A3A44, DANGER_HOVER = 0xFF8A3344;
    /** Window corner radius, and the radius of fields, buttons and rows. */
    public static final int RADIUS = 6, SMALL_RADIUS = 3;

    /** A crisp cyan: the accent until the player picks another. */
    public static final int DEFAULT_ACCENT = 0xFF22D3EE;

    /** The accent as text, rings, bars and headings: the picked color, lifted when it is too dark to read. */
    public static int ACCENT;
    /** The two ends of the accent gradient: switches, sliders, primary buttons, the chosen segment. */
    public static int GRADIENT_START, GRADIENT_END;
    /** The gradient under the mouse: a little lighter. */
    public static int GRADIENT_START_HOVER, GRADIENT_END_HOVER;
    /** A selected thing's dark background (the open section, the picked item, the open tab). */
    public static int ACCENT_BG;
    /** The far end of a big card's background. */
    public static int ACCENT_BG_END;
    /** Text and icons on the gradient: white, or near black on a light accent. */
    public static int ON_ACCENT;

    /** @deprecated the accent is a setting now: use {@link #GRADIENT_START}, which this follows. */
    @Deprecated
    public static int PURPLE;
    /** @deprecated the accent is a setting now: use {@link #GRADIENT_END}, which this follows. */
    @Deprecated
    public static int PINK;

    private static int accent;
    private static int version;

    static {
        setAccent(DEFAULT_ACCENT);
    }

    private Theme() {}

    /** The accent as the player picked it, opaque. */
    public static int accent() {
        return accent;
    }

    /** Goes up by one on every accent change, so text built with the accent can be built again. */
    public static int version() {
        return version;
    }

    /** Sets the accent (its alpha is ignored) and derives every accent color from it. Cheap, but not per frame. */
    @SuppressWarnings("deprecation")
    public static void setAccent(int rgb) {
        int picked = 0xFF000000 | rgb;
        if (picked == accent && version > 0) return;
        accent = picked;
        version++;
        // A color too dark to read on the panels would also vanish as a fill: both start from the lifted one.
        int base = readable(picked);
        float[] hsl = hsl(base);
        float h = hsl[0], s = hsl[1], l = hsl[2];
        int start, end;
        if (s < 0.08f) { // white and grays: light gray to near white
            start = rgb(h, s, l * 0.82f);
            end = rgb(h, s, Math.min(0.97f, l + (1 - l) * 0.5f));
        } else {
            // The far end leans to a neighbor hue: warm yellows toward orange, the rest one step around the wheel
            // (cyan to azure, blue to indigo, red to orange).
            float shift = h >= 25 && h < 75 ? -24 : 24;
            start = rgb(h, s, l * 0.86f);
            end = rgb(h + shift, s, Math.min(0.86f, l + (1 - l) * 0.18f));
        }

        ACCENT = base;
        GRADIENT_START = start;
        GRADIENT_END = end;
        GRADIENT_START_HOVER = mix(start, 0xFFFFFFFF, 0.18f);
        GRADIENT_END_HOVER = mix(end, 0xFFFFFFFF, 0.18f);
        ACCENT_BG = mix(BODY, start, 0.24f);
        ACCENT_BG_END = mix(SURFACE, end, 0.14f);
        // White while it keeps 3:1 against the middle of the gradient (the pixel font is bold enough), else near black.
        double y = luminance(mix(start, end, 0.5f));
        ON_ACCENT = 1.05 / (y + 0.05) >= 3.0 ? 0xFFFFFFFF : 0xFF0E1116;
        PURPLE = GRADIENT_START;
        PINK = GRADIENT_END;
    }

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

    /** Relative luminance, 0 (black) to 1 (white), as contrast ratios use it. */
    public static double luminance(int color) {
        return 0.2126 * linear(color >> 16 & 0xFF) + 0.7152 * linear(color >> 8 & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Lighter until it reads on the dark panels (about 4.5:1 or better), keeping its hue. */
    private static int readable(int color) {
        int c = color;
        for (int i = 0; i < 12 && luminance(c) < 0.2; i++) c = mix(c, 0xFFFFFFFF, 0.15f);
        return c;
    }

    /** Hue in degrees, saturation and lightness 0..1. */
    private static float[] hsl(int color) {
        float r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float l = (max + min) / 2, h = 0, s = 0;
        if (d > 0) {
            s = d / (1 - Math.abs(2 * l - 1));
            if (max == r) h = 60 * (((g - b) / d) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
        }
        return new float[]{(h + 360) % 360, Math.clamp(s, 0f, 1f), l};
    }

    private static int rgb(float h, float s, float l) {
        h = (h % 360 + 360) % 360;
        l = Math.clamp(l, 0f, 1f);
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(h / 60 % 2 - 1)), m = l - c / 2;
        float r, g, b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }
}
