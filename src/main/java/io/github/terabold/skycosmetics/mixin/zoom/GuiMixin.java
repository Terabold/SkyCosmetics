package io.github.terabold.skycosmetics.mixin.zoom;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.zoom.Zoom;
import io.github.terabold.skycosmetics.zoom.ZoomSettings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class GuiMixin {
    @Unique private static final Identifier SKYCOSMETICS_VIGNETTE = Identifier.withDefaultNamespace("textures/misc/vignette.png");

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$hideHud(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        try {
            Zoom.update();
            if (Zoom.hideHud()) { skycosmetics$draw(g); ci.cancel(); }
        } catch (RuntimeException e) { Io.failed("Hiding zoom HUD", e); }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void skycosmetics$overlay(GuiGraphicsExtractor g, DeltaTracker dt, CallbackInfo ci) {
        try { skycosmetics$draw(g); }
        catch (RuntimeException e) { Io.failed("Drawing zoom overlay", e); }
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$crosshair(CallbackInfo ci) {
        try { if (Zoom.hideCrosshair()) ci.cancel(); }
        catch (RuntimeException e) { Io.failed("Hiding zoom crosshair", e); }
    }

    @Unique private void skycosmetics$draw(GuiGraphicsExtractor g) {
        if (!Zoom.visible()) return;
        var mc = Minecraft.getInstance(); var p = Zoom.profile();
        int w = g.guiWidth(), h = g.guiHeight(); double amount = Zoom.amount();
        if (p.overlay == ZoomSettings.Overlay.VIGNETTE) {
            int brightness = (int) (255 * p.overlayOpacity * amount);
            int color = 0xFF000000 | brightness << 16 | brightness << 8 | brightness;
            g.blit(RenderPipelines.VIGNETTE, SKYCOSMETICS_VIGNETTE, 0, 0, 0, 0, w, h, w, h, color);
        }
        int bars = (int) Math.round(h * p.bars * amount);
        if (bars > 0) { g.fill(0, 0, w, bars, p.barColor); g.fill(0, h - bars, w, h, p.barColor); }
        if (p.indicator && !mc.options.hideGui) g.centeredText(mc.font, Zoom.indicator(), w / 2, h / 2 + 16, 0xFFFFFFFF);
    }
}
