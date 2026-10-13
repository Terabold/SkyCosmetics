package io.github.terabold.skycosmetics.mixin.nametag;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import io.github.terabold.skycosmetics.nametag.Nametags;
import io.github.terabold.skycosmetics.nametag.NametagOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the native label pipeline and other mods' render hooks; changes only marked local labels. */
@Mixin(value = NameTagFeatureRenderer.class, priority = 900)
public class NameTagFeatureRendererMixin {
    @WrapOperation(method = "renderTranslucent", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
    private void skycosmetics$style(Font font, Component text, float x, float y, int color, boolean shadow,
        Matrix4fc pose, MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, Operation<Void> original) {
        int originalColor = color, originalBackground = background;
        boolean originalShadow = shadow;
        try {
            var style = Nametags.drawStyle(text);
            if (style != null) {
                if (NametagOverlay.defer(text, x, y, color, pose, mode, light, style)) return;
                boolean normal = mode == Font.DisplayMode.NORMAL;
                shadow = style.shadow();
                color = (normal ? style.normalAlpha() : style.faintAlpha()) << 24 | color & 0xFFFFFF;
                background = 0;
                if (style.plate() != null && (style.seeThrough() ? mode == Font.DisplayMode.SEE_THROUGH : normal)) {
                    style.plate().draw(pose, buffers.getBuffer(io.github.terabold.skycosmetics.nametag.Plate.renderType(style.seeThrough())),
                        x + style.plate().width() / 2f, y, style.opacity(), light);
                    background = 0;
                }
            }
        } catch (RuntimeException failure) {
            color = originalColor; background = originalBackground; shadow = originalShadow;
            io.github.terabold.skycosmetics.Io.failed("Styling a nametag", failure);
        }
        original.call(font, text, x, y, color, shadow, pose, buffers, mode, background, light);
    }
}
