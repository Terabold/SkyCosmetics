package io.github.terabold.skycosmetics.tooltip;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measures active artwork once per sprite, including transparent margins and nine-slice metadata. */
public final class TooltipArtwork {
    private record Insets(int left, int top, int right, int bottom) {}
    private static final Map<SpriteContents, Insets> CACHE = new LinkedHashMap<>(16, .75f, true);
    private TooltipArtwork() {}

    public static void draw(GuiGraphicsExtractor g, Identifier artwork, int x, int y, int w, int h, boolean fill, boolean border) {
        if (TooltipSettings.customBackground && TooltipSettings.customBorder) {
            int p = TooltipSettings.padding;
            TooltipBackdrop.drawBox(g, x + 12 - p, y + 12 - p, w - 24 + p * 2, h - 24 + p * 2,
                fill, border, TooltipRenderer.borderColor());
            return;
        }
        SpriteContents sprite = g.getSprite(new SpriteId(Sheets.GUI_SHEET, artwork)).contents();
        Insets insets = CACHE.get(sprite);
        if (insets == null) {
            insets = measure(sprite);
            if (CACHE.size() >= 32) CACHE.clear();
            CACHE.put(sprite, insets);
        }
        var scaling = sprite.getAdditionalMetadata(GuiMetadataSection.TYPE).orElse(GuiMetadataSection.DEFAULT).scaling();
        int left, top, right, bottom;
        if (scaling instanceof GuiSpriteScaling.NineSlice nine) {
            var b = nine.border();
            left = margin(insets.left(), sprite.width(), nine.width(), b.left(), w);
            right = margin(insets.right(), sprite.width(), nine.width(), b.right(), w);
            top = margin(insets.top(), sprite.height(), nine.height(), b.top(), h);
            bottom = margin(insets.bottom(), sprite.height(), nine.height(), b.bottom(), h);
        } else {
            left = insets.left() * w / sprite.width(); right = insets.right() * w / sprite.width();
            top = insets.top() * h / sprite.height(); bottom = insets.bottom() * h / sprite.height();
        }
        TooltipBackdrop.drawBox(g, x + left, y + top, Math.max(1, w - left - right), Math.max(1, h - top - bottom),
            fill, border, TooltipRenderer.borderColor());
    }

    private static int margin(int pixels, int source, int logical, int border, int target) {
        int inset = pixels * logical / source;
        if (inset <= border) return Math.min(inset, target / 2);
        return Math.min(target / 2, border + (inset - border) * Math.max(0, target - 2 * border) / Math.max(1, logical - 2 * border));
    }

    private static Insets measure(SpriteContents sprite) {
        int w = sprite.width(), h = sprite.height();
        if ((long) w * h > 256 * 256) return new Insets(9, 9, 9, 9);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (sprite.isTransparent(0, x, y)) continue;
            left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y);
        }
        return right < 0 ? new Insets(9, 9, 9, 9) : new Insets(left, top, w - 1 - right, h - 1 - bottom);
    }
}
