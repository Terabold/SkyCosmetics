package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.ItemFacts;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** One field per stack for its {@link ItemFacts}, so rarity is read once per stack, not every frame. */
@Mixin(ItemStack.class)
public class ItemStackFactsMixin implements ItemFacts.Holder {
    @Unique
    private ItemFacts skycosmetics$facts;

    @Override
    public ItemFacts skycosmetics$facts() {
        return skycosmetics$facts;
    }

    @Override
    public void skycosmetics$facts(ItemFacts facts) {
        skycosmetics$facts = facts;
    }
}
