package io.github.terabold.skycosmetics.tooltip;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** At most eight cached quads: gradients and rounded borders are painted only on a cache miss. */
public final class TooltipBackdrop {
    private record Key(int w, int h, int scale, int radius, int borderWidth, int a, int b, int border, boolean fill,
                       TooltipSettings.Background background, TooltipSettings.Direction direction) {}
    private record Texture(Identifier id, int w, int h) {}
    private static final Map<Key, Texture> CACHE = new LinkedHashMap<>(8, 0.75f, true);
    private static int serial, builds;

    private TooltipBackdrop() {}

    public static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        int p = TooltipSettings.padding, w = width + 2 * p, h = height + 2 * p;
        drawBox(g, x - p, y - p, w, h, true, true, TooltipRenderer.borderColor());
    }

    public static void drawBox(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean fill, boolean border, int borderColor) {
        if (w < 1 || h < 1) return;
        Key key = new Key(w, h, Math.min(3, Minecraft.getInstance().getWindow().getGuiScale()), TooltipSettings.radius,
            border ? TooltipSettings.borderWidth : 0, TooltipSettings.color, TooltipSettings.colorEnd, border ? borderColor : 0, fill,
            TooltipSettings.effectiveBackground(), TooltipSettings.direction);
        Texture texture = CACHE.get(key);
        if (texture == null) {
            texture = paint(key);
            CACHE.put(key, texture);
            if (CACHE.size() > 8) {
                Key first = CACHE.keySet().iterator().next();
                Texture old = CACHE.remove(first);
                Minecraft.getInstance().getTextureManager().release(old.id());
            }
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture.id(), x, y, 0, 0, w, h,
            texture.w(), texture.h(), texture.w(), texture.h());
    }

    private static Texture paint(Key k) {
        int tw = Math.clamp(k.w() * k.scale(), 1, 1024), th = Math.clamp(k.h() * k.scale(), 1, 1024);
        NativeImage pixels = new NativeImage(tw, th, false);
        try {
            float sx = k.w() / (float) tw, sy = k.h() / (float) th;
            float radius = Math.min(k.radius(), Math.min(k.w(), k.h()) / 2f);
            for (int iy = 0; iy < th; iy++) {
                for (int ix = 0; ix < tw; ix++) {
                    float x = (ix + 0.5f) * sx, y = (iy + 0.5f) * sy;
                    float distance = roundedDistance(x, y, k.w(), k.h(), radius);
                    float coverage = Math.clamp(0.5f - distance / Math.max(sx, sy), 0f, 1f);
                    int c = k.fill() ? color(k, x, y) : 0;
                    if (k.borderWidth() > 0) {
                        float edge = Math.clamp((distance + k.borderWidth()) / Math.max(sx, sy) + 0.5f, 0f, 1f);
                        c = k.fill() ? mix(c, k.border(), edge)
                            : (int) ((k.border() >>> 24) * edge) << 24 | k.border() & 0xFFFFFF;
                    }
                    pixels.setPixel(ix, iy, (int) ((c >>> 24) * coverage) << 24 | c & 0xFFFFFF);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath("skycosmetics", "tooltip/background/" + serial++);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, pixels));
            builds++;
            return new Texture(id, tw, th);
        } catch (RuntimeException e) {
            pixels.close();
            throw e;
        }
    }

    private static float roundedDistance(float x, float y, float w, float h, float r) {
        if (x >= r && x <= w - r || y >= r && y <= h - r) return -Math.min(Math.min(x, w - x), Math.min(y, h - y));
        float qx = Math.abs(x - w / 2) - (w / 2 - r), qy = Math.abs(y - h / 2) - (h / 2 - r);
        return (float) Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
    }

    private static int color(Key k, float x, float y) {
        return switch (k.background()) {
            case SOLID -> k.a();
            case GRADIENT -> mix(k.a(), k.b(), switch (k.direction()) {
                case HORIZONTAL -> x / k.w();
                case VERTICAL -> y / k.h();
                case DIAGONAL -> (x / k.w() + y / k.h()) / 2;
            });
        };
    }

    private static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 255, cb = b >>> shift & 255;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    private static int over(int base, int image, float opacity) {
        float a = (image >>> 24) / 255f * opacity, b = (base >>> 24) / 255f, total = a + b * (1 - a);
        if (total <= 0) return 0;
        int result = Math.round(total * 255) << 24;
        for (int shift = 0; shift < 24; shift += 8) {
            float c = ((image >>> shift & 255) * a + (base >>> shift & 255) * b * (1 - a)) / total;
            result |= Math.round(c) << shift;
        }
        return result;
    }

    public static int builds() { return builds; }
    public static int cached() { return CACHE.size(); }
}
