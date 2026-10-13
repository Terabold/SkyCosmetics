package de.hysky.skyblocker.skyblock.item;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/** Test-only binary shape of Skyblocker's virtual equipment slot; never packaged in the production mod. */
public final class SkyblockInventoryScreen {
    public static final class EquipmentSlot extends Slot {
        public EquipmentSlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return false; }
        @Override public boolean mayPlace(net.minecraft.world.item.ItemStack stack) { return false; }
    }
}
