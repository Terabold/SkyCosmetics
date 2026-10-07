package io.github.terabold.skycosmetics.mixin.slots;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/**
 * Head Size scales a skull layer's local transform in place. It is reset to identity every time the state is
 * cleared, so a change never carries over to the next frame.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccess {
    @Accessor("localTransform")
    Matrix4f skycosmetics$localTransform();

    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> skycosmetics$specialRenderer();

    @Accessor("extents")
    Supplier<Vector3fc[]> skycosmetics$extents();
}
