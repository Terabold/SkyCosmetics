package io.github.terabold.skycosmetics;

/**
 * Duck interface mixed into ItemStack: one slot to hang the resolved look on,
 * so each stack is identified once instead of on every frame it is drawn.
 */
public interface StackCache {
    Object skycosmetics$entry();

    void skycosmetics$entry(Object entry);
}
