package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.StackCache;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public class ItemStackMixin implements StackCache {
    @Unique
    private Object skycosmetics$entry;

    @Override
    public Object skycosmetics$entry() {
        return skycosmetics$entry;
    }

    @Override
    public void skycosmetics$entry(Object entry) {
        skycosmetics$entry = entry;
    }

    /** Display name (tooltips; the name above the hotbar is {@link GuiMixin}). */
    @Inject(method = "getStyledHoverName", at = @At("HEAD"), cancellable = true)
    private void skycosmetics$customName(CallbackInfoReturnable<Component> cir) {
        Component name = Cosmetics.customName((ItemStack) (Object) this);
        if (name != null) cir.setReturnValue(name);
    }

    /**
     * The name other mods read. Hypixel's unless "Show my names in other mods" is on; a
     * return-value hook so it chains with other mods' renames (Skyblocker's custom names).
     */
    @ModifyReturnValue(method = "getHoverName", at = @At("RETURN"))
    private Component skycosmetics$sharedName(Component original) {
        if (!Settings.namesInOtherMods) return original;
        Component name = Cosmetics.sharedName((ItemStack) (Object) this);
        return name != null ? name : original;
    }
}
