package io.github.terabold.skycosmetics.slots;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Hypixel's item rarities, lowest first, with the colors of their rarity lines. "SUPREME" was Divine's old name;
 * old items still say it.
 */
public enum Rarity {
    COMMON(0xFFFFFF),
    UNCOMMON(0x55FF55),
    RARE(0x5555FF),
    EPIC(0xAA00AA),
    LEGENDARY(0xFFAA00),
    MYTHIC(0xFF55FF),
    DIVINE(0x55FFFF),
    SPECIAL(0xFF5555),
    VERY_SPECIAL(0xFF5555),
    ULTIMATE(0xAA0000),
    ADMIN(0xAA0000);

    private static final Rarity[] ALL = values();

    /** The color of Hypixel's rarity line. */
    public final int hypixel;

    Rarity(int hypixel) {
        this.hypixel = hypixel;
    }

    public static Rarity byIndex(int i) {
        return i >= 0 && i < ALL.length ? ALL[i] : null;
    }

    /** "VERY_SPECIAL", "very special" or "SUPREME"; null for anything else. */
    public static Rarity named(String name) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (n.equals("SUPREME")) return DIVINE;
        for (Rarity r : ALL) if (r.name().equals(n)) return r;
        return null;
    }

    /** One tier up, as a Tier Boost makes a pet; Mythic and above stay. */
    public Rarity boosted() {
        return ordinal() < MYTHIC.ordinal() ? ALL[ordinal() + 1] : this;
    }

    public Component label() {
        return Component.translatable("skycosmetics.rarity." + name().toLowerCase(Locale.ROOT));
    }
}
