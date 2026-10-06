package io.github.terabold.skycosmetics.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SequencedMap;

/** Glint copies need fixed buffers, so they are flushed after the item under them (see Glints). */
@Mixin(MultiBufferSource.BufferSource.class)
public interface BufferSourceAccessor {
    @Accessor("fixedBuffers")
    SequencedMap<RenderType, ByteBufferBuilder> skycosmetics$fixedBuffers();
}
