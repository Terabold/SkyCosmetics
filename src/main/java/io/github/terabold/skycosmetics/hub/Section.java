package io.github.terabold.skycosmetics.hub;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A sidebar entry of the settings. A feature adds its own from its init(). The icon and the rows are built
 * only when the settings open, on the render thread, so they may create item stacks and read live values.
 *
 * @param order FIRST for the studio's own section, FEATURE for the rest (then by id), LAST for an About page
 * @param sub   a few words under the name in the sidebar
 * @param help  one line under the section's title
 * @param rows  gets the settings screen, so an action can open another screen with it as the parent
 */
public record Section(String id, int order, Component name, Component sub, Component help, Supplier<ItemStack> icon,
                      Function<Screen, List<Option>> rows) {
    public static final int FIRST = 0, FEATURE = 100, LAST = 1000;
}
