package io.github.terabold.skycosmetics.render;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/** Resolves once with the saved look; equipment changes stay on render copies too. */
public final class ItemModels {
    private ItemModels() {}

    // NEU's pre-Foraging catalog compared with the current item IDs, including upgraded Daedalus.
    private static final Map<String, String> OLD_AXES = Map.of(
        "AXE_OF_THE_SHREDDED", "minecraft:diamond_axe",
        "DAEDALUS_AXE", "minecraft:golden_axe",
        "STARRED_DAEDALUS_AXE", "minecraft:golden_axe",
        "ENRAGER", "minecraft:iron_axe",
        "MERCENARY_AXE", "minecraft:iron_axe",
        "RAGNAROCK_AXE", "minecraft:golden_axe",
        "RAIDER_AXE", "minecraft:iron_axe");

    public static String oldAxe(String skyblockId) { return skyblockId == null ? null : OLD_AXES.get(skyblockId); }

    /** null inherits; original explicitly keeps the server model despite a type-level remodel. */
    public static String valid(String token) {
        if ("original".equals(token)) return token;
        Identifier id = token == null ? null : Identifier.tryParse(token);
        if (id == null || !id.getNamespace().equals("minecraft") || id.getPath().equals("air")
            || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        return id.toString();
    }

    /** Worn armor reads EQUIPPABLE, not ITEM_MODEL. Keep both appearances in agreement. */
    public static net.minecraft.world.item.Item armor(ItemStack original, String token) {
        String value = valid(token);
        if (value == null || value.equals("original")) return null;
        var source = original.get(DataComponents.EQUIPPABLE);
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(value));
        var target = item.components().get(DataComponents.EQUIPPABLE);
        return source != null && target != null && source.slot() == target.slot() && target.assetId().isPresent() ? item : null;
    }

    public static boolean tintable(net.minecraft.world.item.Item item) {
        return item == net.minecraft.world.item.Items.LEATHER_HELMET || item == net.minecraft.world.item.Items.LEATHER_CHESTPLATE
            || item == net.minecraft.world.item.Items.LEATHER_LEGGINGS || item == net.minecraft.world.item.Items.LEATHER_BOOTS;
    }

    public static Identifier resolve(ItemStack original, String token) {
        String value = valid(token);
        if (value == null) return null;
        if (value.equals("original")) return original.get(DataComponents.ITEM_MODEL);
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(value)).components().get(DataComponents.ITEM_MODEL);
    }
}
