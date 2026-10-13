package io.github.terabold.skycosmetics.slots;

import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.pet.PetDebug;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * "/skycosmetics debug item": what the last item hovered in a menu carried and what rarity was read from it, for
 * menus where a background is missing. Typing a command closes the menu, so the hovered stack is remembered while
 * menus draw (one reference, no copy) and reported afterwards. Read only.
 */
public final class ItemDebug {
    private static ItemStack last = ItemStack.EMPTY;
    private static String menu = "";

    private ItemDebug() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            ClientCommands.literal("skycosmetics").then(ClientCommands.literal("debug").then(
                ClientCommands.literal("item").executes(ctx -> {
                    List<String> lines = report();
                    Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", lines));
                    lines.add(1, "Copied to the clipboard.");
                    return PetDebug.print(ctx.getSource(), lines);
                })))));
    }

    /** Called while a menu draws its slots. */
    static void seen(AbstractContainerScreen<?> screen) {
        var slot = ((AbstractContainerScreenAccessor) screen).skycosmetics$hoveredSlot();
        if (slot == null || !slot.hasItem()) return;
        last = slot.getItem();
        menu = screen.getTitle().getString();
    }

    static List<String> report() {
        List<String> out = new ArrayList<>();
        if (last.isEmpty()) {
            out.add("Hover an item in a menu, close the menu, then run this again.");
            return out;
        }
        ItemStack s = last;
        ItemFacts f = ItemFacts.of(s);
        out.add("Item in \"" + menu + "\": rarity " + (f.rarity() == null ? "none" : f.rarity().name())
            + (f.pet() ? ", pet" : "") + (f.skyblock() ? ", SkyBlock" : "") + (f.recombobulated() ? ", recombobulated" : ""));
        out.add("id " + BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount());
        out.add("name " + json(s.getCustomName()));
        out.add("item_name " + json(s.get(DataComponents.ITEM_NAME)));
        var style = s.get(DataComponents.TOOLTIP_STYLE);
        out.add("tooltip_style " + (style == null ? "none" : style));
        var data = s.get(DataComponents.CUSTOM_DATA);
        out.add("custom_data " + (data == null ? "none" : data.copyTag()));
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) out.add("lore none");
        else for (Component line : lore.lines()) out.add("lore " + json(line));
        return out;
    }

    private static String json(Component c) {
        if (c == null) return "none";
        try {
            var level = Minecraft.getInstance().level;
            var ops = level == null ? JsonOps.INSTANCE : level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return ComponentSerialization.CODEC.encodeStart(ops, c).result().map(Object::toString).orElse(c.getString());
        } catch (RuntimeException e) {
            SkyCosmetics.LOG.debug("Could not serialize a component", e);
            return c.getString();
        }
    }
}
