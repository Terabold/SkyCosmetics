package io.github.terabold.skycosmetics.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The texture bindings of a render setup; their type is package-private, so values are read through {@link TextureBindingAccessor}. */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, ?> skycosmetics$textures();
}
