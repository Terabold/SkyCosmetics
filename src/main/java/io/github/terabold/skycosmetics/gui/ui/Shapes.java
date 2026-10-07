package io.github.terabold.skycosmetics.gui.ui;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Io;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Rounded rectangles, circles and horizontal gradients that stay smooth at every GUI scale.
 *
 * A corner is a quarter of an anti-aliased disc made for the current scale, so one texel lands on one screen
 * pixel: a 4 px corner at GUI scale 3 is a 12-pixel disc, not a 4-step staircase blown up 3 times. The discs
 * are tiny, built once per size on first use and kept; a rounded rectangle is then 4 corner blits and 3 fills.
 * Anything that fails here falls back to square fills, so a shape is never missing.
 */
public final class Shapes {
    /** Disc or ring textures by key: size in pixels, times 64, plus the ring width (0 for a full disc). */
    private static final Int2ObjectOpenHashMap<Identifier> TEXTURES = new Int2ObjectOpenHashMap<>();
    private static final int RAMP_W = 256;
    private static Identifier ramp;
    private static boolean broken;

    private Shapes() {}

    /** A filled rectangle with rounded corners; the radius shrinks to fit small shapes. */
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        Identifier t = r <= 0 ? null : disc(2 * r * scale(), 0);
        if (t == null) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        corners(g, t, x, y, w, h, r, color);
        g.fill(x + r, y, x + w - r, y + h, color);
        if (h > 2 * r) {
            g.fill(x, y + r, x + r, y + h - r, color);
            g.fill(x + w - r, y + r, x + w, y + h - r, color);
        }
    }

    /** A one-pixel rounded frame, open inside, so it works over anything. */
    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        int s = scale();
        Identifier t = r <= 0 ? null : disc(2 * r * s, s);
        if (t == null) {
            g.outline(x, y, w, h, color);
            return;
        }
        corners(g, t, x, y, w, h, r, color);
        g.fill(x + r, y, x + w - r, y + 1, color);
        g.fill(x + r, y + h - 1, x + w - r, y + h, color);
        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
    }

    /** A filled circle {@code d} pixels across with its top left at (x, y). */
    public static void circle(GuiGraphicsExtractor g, int x, int y, int d, int color) {
        if (d <= 0 || (color >>> 24) == 0) return;
        int px = d * scale();
        Identifier t = disc(px, 0);
        if (t == null) {
            g.fill(x, y, x + d, y + d, color);
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, d, d, px, px, px, px, color);
    }

    /**
     * A rounded rectangle whose color runs from {@code left} to {@code right}. The left corners take the left
     * color and the right ones the right color; between them the gradient is a stretched alpha ramp.
     */
    public static void roundGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int left, int right) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        Identifier t = r <= 0 ? null : disc(2 * r * scale(), 0);
        if (t == null) {
            gradient(g, x, y, x + w, y + h, left, right);
            return;
        }
        int px = 2 * r * scale(), half = px / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, r, r, half, half, px, px, left);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y + h - r, 0, half, r, r, half, half, px, px, left);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y, half, 0, r, r, half, half, px, px, right);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y + h - r, half, half, r, r, half, half, px, px, right);
        if (h > 2 * r) {
            g.fill(x, y + r, x + r, y + h - r, left);
            g.fill(x + w - r, y + r, x + w, y + h - r, right);
        }
        gradient(g, x + r, y, x + w - r, y + h, left, right);
    }

    /**
     * A rounded rectangle through several colors, evenly spaced from left to right (opaque RGB or ARGB): rounded ends
     * in the first and last color, a ramp between each neighboring pair. A few draws, whatever the width.
     */
    public static void roundStops(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int[] stops) {
        if (w <= 0 || h <= 0 || stops.length == 0) return;
        int n = stops.length;
        if (n == 1) {
            round(g, x, y, w, h, r, 0xFF000000 | stops[0]);
            return;
        }
        r = Math.min(r, Math.min(w, h) / 2);
        round(g, x, y, 2 * r + 1, h, r, 0xFF000000 | stops[0]);
        round(g, x + w - 2 * r - 1, y, 2 * r + 1, h, r, 0xFF000000 | stops[n - 1]);
        int x0 = x + r, span = w - 2 * r;
        for (int i = 0; i < n - 1; i++) {
            int a = x0 + span * i / (n - 1), b = x0 + span * (i + 1) / (n - 1);
            gradient(g, a, y, b, y + h, 0xFF000000 | stops[i], 0xFF000000 | stops[i + 1]);
        }
    }

    /** A plain rectangle from {@code left} to {@code right}; best when {@code left} is opaque. */
    public static void gradient(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int left, int right) {
        if (x1 <= x0 || y1 <= y0) return;
        Identifier t = ramp();
        if (t == null || left == right) {
            g.fill(x0, y0, x1, y1, left);
            return;
        }
        g.fill(x0, y0, x1, y1, left);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x0, y0, 0, 0, x1 - x0, y1 - y0, RAMP_W, 1, RAMP_W, 1, right);
    }

    /** A rectangle in {@code color} that fades out to the right (or to the left when {@code toLeft}). */
    public static void fadeOut(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color, boolean toLeft) {
        if (x1 <= x0 || y1 <= y0) return;
        Identifier t = ramp();
        if (t == null) {
            g.fill(x0, y0, x1, y1, Theme.fade(color, 0.5f));
            return;
        }
        // The ramp runs transparent to opaque; read backwards it fades out to the right.
        g.blit(RenderPipelines.GUI_TEXTURED, t, x0, y0, toLeft ? 0 : RAMP_W, 0, x1 - x0, y1 - y0,
            toLeft ? RAMP_W : -RAMP_W, 1, RAMP_W, 1, color);
    }

    /** A soft shadow under a rounded rectangle: a few widening, fainter layers. */
    public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int spread) {
        for (int i = spread; i >= 1; i--) {
            int a = 0x30 * (spread - i + 1) / spread / 2;
            round(g, x - i, y - i + 2, w + 2 * i, h + 2 * i, r + i, a << 24);
        }
    }

    // ------------------------------------------------------------ textures ---

    private static void corners(GuiGraphicsExtractor g, Identifier t, int x, int y, int w, int h, int r, int color) {
        int px = 2 * r * scale(), half = px / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0, 0, r, r, half, half, px, px, color);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y, half, 0, r, r, half, half, px, px, color);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y + h - r, 0, half, r, r, half, half, px, px, color);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y + h - r, half, half, r, r, half, half, px, px, color);
    }

    private static int scale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /**
     * A white disc (or ring {@code ring} pixels wide) {@code px} pixels across, anti-aliased by 4x4
     * supersampling. Null if textures can't be made here; the caller then draws square.
     */
    private static Identifier disc(int px, int ring) {
        if (broken || px <= 0 || px > 512) return null;
        int key = px * 64 + Math.min(ring, 63);
        Identifier id = TEXTURES.get(key);
        if (id != null) return id;
        try {
            NativeImage img = new NativeImage(px, px, false);
            float c = px / 2f, outer = c, inner = ring > 0 ? c - ring : -1;
            for (int y = 0; y < px; y++) {
                for (int x = 0; x < px; x++) {
                    int hits = 0;
                    for (int sy = 0; sy < 4; sy++) {
                        for (int sx = 0; sx < 4; sx++) {
                            float dx = x + (sx + 0.5f) / 4 - c, dy = y + (sy + 0.5f) / 4 - c;
                            float d = (float) Math.sqrt(dx * dx + dy * dy);
                            if (d <= outer && d >= inner) hits++;
                        }
                    }
                    img.setPixel(x, y, (hits * 255 / 16) << 24 | 0xFFFFFF);
                }
            }
            id = Identifier.fromNamespaceAndPath("skycosmetics", "ui/disc_" + px + "_" + ring);
            Identifier name = id;
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(name::toString, img));
            TEXTURES.put(key, id);
            return id;
        } catch (RuntimeException e) {
            broken = true;
            Io.failed("Making the settings' round corners", e);
            return null;
        }
    }

    /** White, transparent on the left to opaque on the right. */
    private static Identifier ramp() {
        if (ramp != null || broken) return ramp;
        try {
            NativeImage img = new NativeImage(RAMP_W, 1, false);
            for (int x = 0; x < RAMP_W; x++) img.setPixel(x, 0, (x * 255 / (RAMP_W - 1)) << 24 | 0xFFFFFF);
            Identifier id = Identifier.fromNamespaceAndPath("skycosmetics", "ui/ramp");
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, img));
            ramp = id;
        } catch (RuntimeException e) {
            broken = true;
            Io.failed("Making the settings' gradients", e);
        }
        return ramp;
    }
}
