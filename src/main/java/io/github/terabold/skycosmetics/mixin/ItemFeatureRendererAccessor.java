package io.github.terabold.skycosmetics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's decal pose for compass/clock-style ("special") glint, reused by coloured glint. */
@Mixin(ItemFeatureRenderer.class)
public interface ItemFeatureRendererAccessor {
    @Invoker("computeFoilDecalPose")
    static PoseStack.Pose skycosmetics$decalPose(ItemDisplayContext context, PoseStack.Pose pose) {
        throw new AssertionError();
    }
}
