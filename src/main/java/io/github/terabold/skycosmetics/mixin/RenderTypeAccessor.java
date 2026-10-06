package io.github.terabold.skycosmetics.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderType.create is package-private; glint copies are made with it like vanilla's. */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    @Invoker("create")
    static RenderType skycosmetics$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
