package io.github.terabold.skycosmetics.mixin.nametag;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.nametag.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @WrapOperation(method="extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
        at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z"))
    private boolean skycosmetics$own(EntityRenderer<?,?> renderer,Entity entity,double distance,Operation<Boolean> original) {
        return original.call(renderer,entity,distance)||Nametags.ownVisible(entity);
    }
    @Inject(method="extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",at=@At("RETURN"))
    private void skycosmetics$extract(Entity entity,EntityRenderState state,float tick,CallbackInfo ci) {
        if(NametagSettings.p.enabled)Nametags.extract(entity,state);
    }
    @WrapMethod(method="submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V")
    private void skycosmetics$submit(EntityRenderState state,PoseStack pose,SubmitNodeCollector out,CameraRenderState camera,int offset,Operation<Void> original) {
        var name = state.nameTag; var score = state.scoreText;
        boolean discrete = state.isDiscrete; int light = state.lightCoords;
        pose.pushPose();
        try {
            boolean draw = true;
            try { draw = Nametags.prepare(state, pose, camera); }
            catch(RuntimeException ex) {
                state.nameTag = name; state.scoreText = score; state.isDiscrete = discrete; state.lightCoords = light;
                pose.popPose(); pose.pushPose(); Io.failed("nametags",ex);
            }
            if (draw) original.call(state,pose,out,camera,offset);
        } finally {
            state.nameTag = name; state.scoreText = score; state.isDiscrete = discrete; state.lightCoords = light;
            pose.popPose();
        }
    }
}
