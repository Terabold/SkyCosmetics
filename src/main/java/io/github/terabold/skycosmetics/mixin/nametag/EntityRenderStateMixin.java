package io.github.terabold.skycosmetics.mixin.nametag;
import io.github.terabold.skycosmetics.nametag.NametagState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.*;
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements NametagState {
    @Unique private Data skycosmetics$tag;
    @Override public Data skycosmetics$tag() { if(skycosmetics$tag==null)skycosmetics$tag=new Data(); return skycosmetics$tag; }
}
