package io.github.terabold.skycosmetics.mixin;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which texture a render setup binds, for nametag text copies drawn from the same font atlas. */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface TextureBindingAccessor {
    @Accessor("location")
    Identifier skycosmetics$location();
}
