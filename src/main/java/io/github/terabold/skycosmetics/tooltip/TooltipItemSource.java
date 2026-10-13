package io.github.terabold.skycosmetics.tooltip;

import net.minecraft.world.item.ItemStack;

/** Read-only screens can supply the hovered snapshot for rarity borders and scroll ownership. */
public interface TooltipItemSource {
    ItemStack tooltipItem();
}
