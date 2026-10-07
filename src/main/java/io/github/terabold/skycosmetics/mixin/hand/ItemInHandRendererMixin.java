package io.github.terabold.skycosmetics.mixin.hand;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.hand.HandRender;
import io.github.terabold.skycosmetics.hand.HandSwing;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Hand feature's hooks. {@code ItemInHandRenderer} draws only the first-person hands and is used only by
 * {@code GameRenderer.renderItemInHand} and {@code Minecraft} (its tick and {@code itemUsed}, which write only this
 * renderer's own fields), so everything here is drawing: no player state, no packets, no gameplay.
 *
 * The swing progress and swinging arm are replaced only where this renderer reads them; the player keeps its own.
 * Each hook is optional ({@code require = 0}): if another mod moved the code it targets, that part of the pose
 * is simply skipped and the rest still works. All bodies fall back to Minecraft's values ({@link HandRender}
 * catches its own failures).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow
    private ItemStack mainHandItem;

    // ------------------------------------------------------ renderHandsWithItems ---

    @WrapOperation(method = "renderHandsWithItems", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"))
    private float skycosmetics$swingProgress(LocalPlayer player, float partialTick, Operation<Float> original) {
        float vanilla = original.call(player, partialTick);
        return HandRender.active() ? HandSwing.progress(partialTick) : vanilla;
    }

    @WrapOperation(method = "renderHandsWithItems", require = 0,
        at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/player/LocalPlayer;swingingArm:Lnet/minecraft/world/InteractionHand;"))
    private InteractionHand skycosmetics$swingingArm(LocalPlayer player, Operation<InteractionHand> original) {
        InteractionHand vanilla = original.call(player);
        return HandRender.active() ? HandSwing.arm() : vanilla;
    }

    /** The first two rotations are the sway: how far the hand lags behind your view. */
    @ModifyArg(method = "renderHandsWithItems", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 0))
    private float skycosmetics$swayPitch(float angle) {
        return HandRender.active() ? HandRender.sway(angle) : angle;
    }

    @ModifyArg(method = "renderHandsWithItems", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
    private float skycosmetics$swayYaw(float angle) {
        return HandRender.active() ? HandRender.sway(angle) : angle;
    }

    @WrapOperation(method = "renderHandsWithItems", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem("
            + "Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void skycosmetics$hand(ItemInHandRenderer self, AbstractClientPlayer player, float partialTick, float xRot,
                                   InteractionHand hand, float attack, ItemStack stack, float height, PoseStack pose,
                                   SubmitNodeCollector collector, int light, Operation<Void> original) {
        if (!HandRender.active() || !(player instanceof LocalPlayer local)) {
            original.call(self, player, partialTick, xRot, hand, attack, stack, height, pose, collector, light);
            return;
        }
        ItemStack shown = HandRender.shown(hand, stack);
        float swing = HandRender.begin(local, hand, shown, attack);
        try {
            original.call(self, player, partialTick, xRot, hand, swing, shown, height, pose, collector, light);
        } finally {
            HandRender.end();
        }
    }

    // --------------------------------------------------------- renderArmWithItem ---

    @Inject(method = "renderArmWithItem", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER))
    private void skycosmetics$place(AbstractClientPlayer player, float partialTick, float xRot, InteractionHand hand,
                                    float attack, ItemStack stack, float height, PoseStack pose,
                                    SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HandRender.afterPush(pose);
    }

    @Inject(method = "renderArmWithItem", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem("
            + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void skycosmetics$beforeItem(AbstractClientPlayer player, float partialTick, float xRot, InteractionHand hand,
                                         float attack, ItemStack stack, float height, PoseStack pose,
                                         SubmitNodeCollector collector, int light, CallbackInfo ci) {
        HandRender.beforeItem(pose);
    }

    @Inject(method = "applyItemArmTransform", at = @At("TAIL"), require = 0)
    private void skycosmetics$atRest(PoseStack pose, HumanoidArm arm, float height, CallbackInfo ci) {
        HandRender.atRest(pose);
    }

    @WrapOperation(method = "swingArm", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void skycosmetics$swingTravel(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        original.call(pose, HandRender.swingX(x), HandRender.swingY(y), HandRender.swingZ(z));
    }

    /** The swing's tilt: three turns scaled by Swing Turn; the fourth (a fixed -45 degrees) is not part of it. */
    @ModifyArg(method = "applyItemArmAttackTransform", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 0))
    private float skycosmetics$swingTurnY(float angle) {
        return HandRender.swingTurn(angle, Math.copySign(45, angle)); // side * (45 - 20 sin(p^2 pi)): rests at 45
    }

    @ModifyArg(method = "applyItemArmAttackTransform", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
    private float skycosmetics$swingTurnZ(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @ModifyArg(method = "applyItemArmAttackTransform", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 2))
    private float skycosmetics$swingTurnX(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    // ------------------------------------------------------------- empty hand ---

    /** The bare arm's swing turns (the first rotation is its fixed 45 degrees). */
    @ModifyArg(method = "renderPlayerArm", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
    private float skycosmetics$armTurnY(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @ModifyArg(method = "renderPlayerArm", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 2))
    private float skycosmetics$armTurnZ(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @WrapOperation(method = "renderPlayerArm", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0))
    private void skycosmetics$armPlace(PoseStack pose, float x, float y, float z, Operation<Void> original,
                                       @Local(ordinal = 1, argsOnly = true) float attack,
                                       @Local(argsOnly = true) HumanoidArm arm) {
        float[] t = HandRender.armTranslate(x, y, z, attack, arm);
        original.call(pose, t[0], t[1], t[2]);
        HandRender.afterArmPlaced(pose);
    }

    // ------------------------------------------------------------------- maps ---

    @WrapOperation(method = "renderOneHandedMap", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 2))
    private void skycosmetics$mapSwing(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        original.call(pose, HandRender.swingX(x), HandRender.swingY(y), HandRender.swingZ(z));
    }

    @WrapOperation(method = "renderTwoHandedMap", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0))
    private void skycosmetics$mapsSwing(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        original.call(pose, HandRender.swingX(x), HandRender.swingY(y), HandRender.swingZ(z));
    }

    @ModifyArg(method = "renderOneHandedMap", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 1))
    private float skycosmetics$mapTurnX(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @ModifyArg(method = "renderOneHandedMap", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 2))
    private float skycosmetics$mapTurnY(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @ModifyArg(method = "renderTwoHandedMap", require = 0, index = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;", ordinal = 2))
    private float skycosmetics$mapsTurn(float angle) {
        return HandRender.swingTurn(angle, 0);
    }

    @Inject(method = "renderMap", at = @At("HEAD"), require = 0)
    private void skycosmetics$map(PoseStack pose, SubmitNodeCollector collector, int light, ItemStack stack, CallbackInfo ci) {
        HandRender.beforeMap(pose);
    }

    // ------------------------------------------------------------------ equip ---

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void skycosmetics$tick(CallbackInfo ci) {
        if (HandRender.active()) HandRender.tick(mainHandItem);
    }

    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"), require = 0)
    private boolean skycosmetics$instantSwap(boolean vanilla) {
        return HandRender.instantSwap(vanilla);
    }

    @WrapOperation(method = "tick", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
    private float skycosmetics$swapDip(LocalPlayer player, float partialTick, Operation<Float> original) {
        return HandRender.swapScale(original.call(player, partialTick));
    }

    /** The dip when an ability is used: this renderer's own field only, so skipping it changes nothing else. */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$useDip(InteractionHand hand, CallbackInfo ci) {
        if (!HandRender.keepUseDip()) ci.cancel();
    }
}
