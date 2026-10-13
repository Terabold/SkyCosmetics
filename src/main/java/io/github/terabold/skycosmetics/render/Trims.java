package io.github.terabold.skycosmetics.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

import java.util.HashMap;
import java.util.Map;

/** Resolves trim holders once per selection and world, then edits render copies only. */
public final class Trims {
    private static RegistryAccess registry;
    private static final Map<String, ArmorTrim> CACHE = new HashMap<>();
    private Trims() {}

    public static boolean allowed(ItemStack stack) {
        var eq = stack.get(DataComponents.EQUIPPABLE);
        return !stack.is(Items.PLAYER_HEAD) && eq != null && (eq.slot() == EquipmentSlot.HEAD || eq.slot() == EquipmentSlot.CHEST
            || eq.slot() == EquipmentSlot.LEGS || eq.slot() == EquipmentSlot.FEET);
    }

    public static String valid(String value) {
        if (value == null || value.equals("off")) return value;
        String[] parts = value.split("\\|", -1);
        return parts.length == 2 && Identifier.tryParse(parts[0]) != null && Identifier.tryParse(parts[1]) != null ? value : null;
    }

    public static String token(String pattern, String material) {
        Identifier p = Identifier.tryParse(pattern), m = Identifier.tryParse(material);
        return p == null || m == null ? null : p + "|" + m;
    }

    public static void apply(ItemStack copy, String token) {
        if (token == null || !allowed(copy)) return;
        if (token.equals("off")) { copy.remove(DataComponents.TRIM); return; }
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        RegistryAccess current = level.registryAccess();
        if (registry != current) { registry = current; CACHE.clear(); }
        if (!CACHE.containsKey(token)) {
            if (valid(token) == null) return;
            if (CACHE.size() >= 128) CACHE.clear();
            String[] parts = token.split("\\|");
            var p = current.lookupOrThrow(Registries.TRIM_PATTERN).get(Identifier.parse(parts[0]));
            var m = current.lookupOrThrow(Registries.TRIM_MATERIAL).get(Identifier.parse(parts[1]));
            CACHE.put(token, p.isPresent() && m.isPresent() ? new ArmorTrim(m.get(), p.get()) : null);
        }
        ArmorTrim trim = CACHE.get(token);
        if (trim != null) copy.set(DataComponents.TRIM, trim);
    }
}
