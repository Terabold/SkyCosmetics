package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.tooltip.TooltipRenderer;
import io.github.terabold.skycosmetics.tooltip.TooltipSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipGraphicsMixin {
    @WrapMethod(method = "tooltip")
    private void skycosmetics$tooltip(Font font, List<ClientTooltipComponent> parts, int x, int y,
                                      ClientTooltipPositioner positioner, Identifier style, Operation<Void> original) {
        if (!TooltipSettings.enabled || TooltipRenderer.drawingContent()) {
            original.call(font, parts, x, y, positioner, style);
            return;
        }
        try {
            TooltipRenderer.render((GuiGraphicsExtractor) (Object) this, font, parts, x, y, positioner, style,
                at -> original.call(font, parts, x, y, at, style));
        } catch (RuntimeException e) {
            TooltipRenderer.reset();
            Io.failed("Styling a tooltip", e);
            original.call(font, parts, x, y, positioner, style);
        }
    }
}
