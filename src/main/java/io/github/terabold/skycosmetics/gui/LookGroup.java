package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.network.chat.Component;

/** The sections of the Saved and Other Mods tabs, in the order they are listed. */
enum LookGroup {
    HELMETS("helmets"), ARMOR("armor"), WEAPONS("weapons"), PETS("pets"), ORBS("orbs"), OTHER("other"),
    TYPES("types"), UNSEEN("unseen");

    private final String key;

    LookGroup(String key) {
        this.key = key;
    }

    String title() {
        return Component.translatable("skycosmetics.studio.group." + key).getString();
    }

    /** Where an item of this category goes; null (not known): Other Items. */
    static LookGroup of(OwnedItems.Category c) {
        if (c == null) return OTHER;
        return switch (c) {
            case HELMET -> HELMETS;
            case CHESTPLATE, LEGGINGS, BOOTS -> ARMOR;
            case PET -> PETS;
            case WEAPON -> WEAPONS;
            case DEPLOYABLE -> ORBS;
        };
    }
}
