package io.github.terabold.skycosmetics.nametag;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

/** Local player model with the same personal name styling; no entity or inventory is created. */
final class NametagPreview extends AbstractWidget {
    private Component source, styled;
    private long lastRevision = -1, tick = -1;
    NametagPreview(int width) { super(0, 0, width, 130, Component.literal("Nametag preview")); }
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        var mc = Minecraft.getInstance();
        Shapes.round(g, getX(), getY(), width, height, Theme.SMALL_RADIUS, Theme.SURFACE);
        if (mc.player == null) {
            g.centeredText(mc.font, Component.literal("Join a world to preview your nametag."), getX() + width / 2, getY() + 58, Theme.MUTED);
            return;
        }
        try {
            Component next = mc.player.getDisplayName();
            long now = System.nanoTime() / 50_000_000;
            if (!next.equals(source) || lastRevision != revision || (p.text == Text.CHROMA || ownAnimated) && tick != now) {
                source = next.copy(); styled = p.enabled ? Nametags.style(next, true, mc.player.getGameProfile().name()) : next;
                lastRevision = revision; tick = now;
            }
            int modelScale = Math.clamp((height - 30) / 3, 22, 64);
            Nametags.previewFit = Math.min(1, (width - 24f) / Math.max(1,
                (mc.font.width(styled) + 2 * (float)p.paddingX) * modelScale * .025f * (float)p.scale));
            Nametags.previewModel = true;
            try { InventoryScreen.extractEntityInInventoryFollowsMouse(g, getX(), getY() + 30, getX() + width, getY() + height - 4,
                modelScale, .0625f, mx, my, mc.player); }
            finally { Nametags.previewModel = false; Nametags.previewFit = 1; }
            if (p.enabled && (p.hide || p.opacity < .02)) return;
            int textWidth = mc.font.width(styled), center = getX() + width / 2, top = getY() + 12;
            float scale = Math.min((float)p.scale, (width - 24f) / Math.max(1, textWidth + 2));
            g.pose().pushMatrix();
            try {
                g.pose().translate(center, top); g.pose().scale(scale, scale);
                if (!p.enabled || p.background) {
                    int px = p.enabled ? (int)Math.round(p.paddingX) : 1, py = p.enabled ? (int)Math.round(p.paddingY) : 0;
                    Shapes.round(g, -textWidth / 2 - px, -1 - py, textWidth + 2 * px, 10 + 2 * py,
                        p.enabled && p.rounded ? (int)Math.round(p.radius) : 0,
                        p.enabled ? Theme.fade(p.backgroundColor, (float)p.opacity) : 0x40000000);
                }
                g.text(mc.font, styled, -textWidth / 2, 0, p.enabled ? Theme.fade(0xFFFFFFFF, (float)p.opacity) : 0xFFFFFFFF, p.enabled && p.shadow);
            } finally { g.pose().popMatrix(); }
        } catch (RuntimeException e) { Io.failed("Drawing the nametag preview", e); }
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, getMessage()); }
}
