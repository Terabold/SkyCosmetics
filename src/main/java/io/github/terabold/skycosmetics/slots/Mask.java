package io.github.terabold.skycosmetics.slots;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * The background's look as a small white mask, tinted by the rarity color when drawn: one textured quad per slot,
 * and every slot of a menu shares one texture, so the whole menu is a single batch. Masks are painted on the CPU
 * once per style and GUI scale, at one texel per screen pixel and with 4x4 samples per texel, so circles and
 * gradients are smooth at every GUI scale and nothing is computed per frame.
 *
 * A cell is 18x18 GUI pixels: the slot's 16x16 item area plus the 1 px frame around it, which only Glow reaches.
 */
public final class Mask {
    /** The outline of the background. */
    public enum Shape {
        SQUARE, INSET, ROUNDED, CIRCLE, DIAMOND, FRAME, CORNER, GLOW;

        public Component label() {
            return Component.translatable("skycosmetics.rarityBackgrounds.shape." + name().toLowerCase(Locale.ROOT));
        }

        /** An outline on top would add nothing: the shape is already a line or has no edge. */
        boolean outlined() {
            return this != FRAME && this != GLOW;
        }
    }

    /** How the color spreads over the shape. */
    public enum Fill {
        SOLID, VERTICAL, HORIZONTAL, DIAGONAL, RADIAL, EDGES, BEVELED;

        public Component label() {
            return Component.translatable("skycosmetics.rarityBackgrounds.fill." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Everything a mask depends on. */
    public record Style(Shape shape, Fill fill, int opacity, boolean outline) {
        public Style {
            opacity = Math.clamp(opacity, 0, 100);
        }
    }

    /** GUI pixels per cell side. */
    public static final int CELL = 18;
    /** Above this GUI scale masks are stretched instead of painted bigger. */
    private static final int MAX_SCALE = 6;

    private Mask() {}

    /** The current GUI scale, as the mask resolution. */
    static int scale() {
        return Math.clamp(Minecraft.getInstance().getWindow().getGuiScale(), 1, MAX_SCALE);
    }

    // ------------------------------------------------------------ sheets ---

    /**
     * A texture of cells in a row-major grid, repainted only when what it shows changes: {@code key} names that,
     * and the GUI scale is part of it. The quad of a cell is drawn with {@code u = col * CELL}, {@code v = row *
     * CELL} on a texture {@code cols * CELL} by {@code rows * CELL} GUI pixels big.
     */
    static final class Sheet {
        interface Painter {
            /** Paints cell {@code i} at (x0, y0), {@code n} texels square, {@code s} texels per GUI pixel. */
            void paint(NativeImage img, int i, int x0, int y0, int n, int s);
        }

        final Identifier id;
        final int cols, rows;
        private DynamicTexture texture;
        private long key = Long.MIN_VALUE;
        private int scale;

        Sheet(String path, int cols, int rows) {
            this.id = Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, path);
            this.cols = cols;
            this.rows = rows;
        }

        int texWidth() {
            return cols * CELL;
        }

        int texHeight() {
            return rows * CELL;
        }

        /** Repaints when {@code key} or the GUI scale changed; returns the texture id. */
        Identifier ensure(long key, Painter painter) {
            int s = scale();
            if (texture != null && key == this.key && s == scale) return id;
            int n = CELL * s;
            NativeImage img = texture != null && s == scale ? texture.getPixels() : null;
            boolean fresh = img == null;
            if (fresh) img = new NativeImage(cols * n, rows * n, true);
            img.fillRect(0, 0, cols * n, rows * n, 0x00FFFFFF);
            for (int i = 0; i < cols * rows; i++) painter.paint(img, i, i % cols * n, i / cols * n, n, s);
            if (fresh) {
                texture = new DynamicTexture(id::toString, img);
                Minecraft.getInstance().getTextureManager().register(id, texture); // closes the old one
            } else {
                texture.upload();
            }
            this.key = key;
            this.scale = s;
            return id;
        }
    }

    // ------------------------------------------------------------ painting ---

    /** Paints one style into a cell. {@code ss} is the samples per texel side (4 for slots, 3 for pickers). */
    static void paint(NativeImage img, int x0, int y0, int n, int s, Style style, int ss) {
        float op = style.opacity() / 100f;
        float edge = Math.min(1f, op * 1.5f + 0.25f);
        boolean outline = style.outline() && style.shape().outlined();
        float inv = 1f / (ss * ss);
        for (int ty = 0; ty < n; ty++) {
            for (int tx = 0; tx < n; tx++) {
                float a = 0, lum = 0;
                for (int sy = 0; sy < ss; sy++) {
                    float py = (ty + (sy + 0.5f) / ss) / s;
                    for (int sx = 0; sx < ss; sx++) {
                        float px = (tx + (sx + 0.5f) / ss) / s;
                        float cov = coverage(style.shape(), px, py);
                        if (cov <= 0) continue;
                        float[] bm = fill(style.fill(), style.shape(), px, py);
                        float al = cov * bm[1] * op, b = bm[0];
                        if (outline && isEdge(style.shape(), px, py)) {
                            al = Math.max(al, edge);
                            b = 1f;
                        }
                        a += al;
                        lum += al * b;
                    }
                }
                if (a <= 0) continue;
                int gray = Math.round(Math.clamp(lum / a, 0f, 1f) * 255);
                int alpha = Math.round(Math.clamp(a * inv, 0f, 1f) * 255);
                img.setPixel(x0 + tx, y0 + ty, alpha << 24 | gray << 16 | gray << 8 | gray);
            }
        }
    }

    /** The recombobulated mark: a small white corner with a dark edge, top right, drawn untinted. */
    static void paintMark(NativeImage img, int x0, int y0, int n, int s) {
        int ss = 4;
        float inv = 1f / (ss * ss);
        for (int ty = 0; ty < n; ty++) {
            for (int tx = 0; tx < n; tx++) {
                float a = 0, lum = 0;
                for (int sy = 0; sy < ss; sy++) {
                    float py = (ty + (sy + 0.5f) / ss) / s;
                    for (int sx = 0; sx < ss; sx++) {
                        float px = (tx + (sx + 0.5f) / ss) / s;
                        float qx = 17 - px, qy = py - 1;
                        if (qx < 0 || qy < 0 || qx + qy > 5.5f) continue;
                        boolean rim = qx + qy > 4.5f || qx < 1 || qy < 1;
                        float al = rim ? 0.8f : 0.95f;
                        a += al;
                        lum += al * (rim ? 0.1f : 1f);
                    }
                }
                if (a <= 0) continue;
                int gray = Math.round(Math.clamp(lum / a, 0f, 1f) * 255);
                img.setPixel(x0 + tx, y0 + ty, Math.round(Math.clamp(a * inv, 0f, 1f) * 255) << 24 | gray << 16 | gray << 8 | gray);
            }
        }
    }

    /** 0..1: how much of the shape covers a point (x, y in GUI pixels of the 18x18 cell). */
    static float coverage(Shape shape, float x, float y) {
        if (shape == Shape.GLOW) {
            float r = (float) Math.hypot(x - 9, y - 9);
            if (r >= 9.2f) return 0;
            float t = Math.clamp((9.2f - r) / 6.7f, 0f, 1f);
            return t * t * (3 - 2 * t);
        }
        return inside(shape, x, y) ? 1 : 0;
    }

    static boolean inside(Shape shape, float x, float y) {
        float dx = Math.abs(x - 9), dy = Math.abs(y - 9);
        if (shape != Shape.GLOW && (dx > 8 || dy > 8)) return false;
        return switch (shape) {
            case SQUARE -> true;
            case INSET -> dx <= 7 && dy <= 7;
            case ROUNDED -> {
                float r = 4, ex = Math.max(dx - (8 - r), 0), ey = Math.max(dy - (8 - r), 0);
                yield ex * ex + ey * ey <= r * r;
            }
            case CIRCLE -> dx * dx + dy * dy <= 64;
            case DIAMOND -> dx + dy <= 9;
            case FRAME -> dx > 7 || dy > 7;
            case CORNER -> (x - 1) + (y - 1) <= 7.5f;
            case GLOW -> dx * dx + dy * dy <= 72;
        };
    }

    /** The point is in the shape and 1 GUI pixel away is not: where an outline goes. */
    private static boolean isEdge(Shape shape, float x, float y) {
        return inside(shape, x, y) && !(inside(shape, x + 1, y) && inside(shape, x - 1, y)
            && inside(shape, x, y + 1) && inside(shape, x, y - 1));
    }

    private static final float[] FILL = new float[2];

    /** Brightness and alpha factor of a fill at a point. Not thread safe: painting runs on the render thread. */
    private static float[] fill(Fill fill, Shape shape, float x, float y) {
        float u = Math.clamp((x - 1) / 16f, 0f, 1f), v = Math.clamp((y - 1) / 16f, 0f, 1f);
        float r = Math.min(1f, (float) Math.hypot(x - 9, y - 9) / 8.5f);
        float b = 1f, m = switch (fill) {
            case SOLID, BEVELED -> 1f;
            case VERTICAL -> 0.2f + 0.8f * v;
            case HORIZONTAL -> 1f - 0.8f * u;
            case DIAGONAL -> 1f - 0.8f * (u + (1 - v)) / 2f;
            case RADIAL -> 1f - 0.8f * r;
            case EDGES -> 0.2f + 0.8f * r * r;
        };
        if (fill == Fill.BEVELED) {
            if (!inside(shape, x - 1, y - 1)) b = 1f;
            else if (!inside(shape, x + 1, y + 1)) b = 0.45f;
            else b = 0.78f;
        }
        FILL[0] = b;
        FILL[1] = m;
        return FILL;
    }
}
