package io.github.terabold.skycosmetics.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot skycosmetics$hoveredSlot();

    @Accessor("leftPos")
    int skycosmetics$leftPos();

    @Accessor("topPos")
    int skycosmetics$topPos();

    /** The screen's own click path, for slot binding's swap: what a number key press on that slot does. */
    @org.spongepowered.asm.mixin.gen.Invoker("slotClicked")
    void skycosmetics$slotClicked(Slot slot, int slotId, int button, net.minecraft.world.inventory.ContainerInput input);
}
