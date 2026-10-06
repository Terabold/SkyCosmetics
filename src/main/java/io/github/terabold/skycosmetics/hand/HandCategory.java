package io.github.terabold.skycosmetics.hand;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The kinds of held item a pose rule can name. SkyBlock items say what they are on their last lore line
 * ("LEGENDARY DUNGEON BOW", "EPIC DRILL"), whatever Minecraft item draws them: a Terminator is a bow even
 * though nothing else about it says so. Items without that line fall back to their Minecraft type.
 *
 * Read once per held stack (the pose cache keeps the answer), never per frame.
 */
public enum HandCategory {
    SWORD("sword", "skycosmetics.hand.category.sword"),
    BOW("bow", "skycosmetics.hand.category.bow"),
    TOOL("tool", "skycosmetics.hand.category.tool"),
    ROD("rod", "skycosmetics.hand.category.rod"),
    OTHER("other", "skycosmetics.hand.category.other"),
    EMPTY("empty", "skycosmetics.hand.category.empty");

    /** The rule key in settings.json. */
    public final String key;
    public final String lang;

    /** Recombobulated lines are wrapped in obfuscated letters; some end in "(ID ...)". */
    private static final Pattern RARITY_SUFFIX = Pattern.compile("(?:\\s+\\S)?(?:\\s+\\(ID \\w+\\))?$");

    HandCategory(String key, String lang) {
        this.key = key;
        this.lang = lang;
    }

    public Component label() {
        return Component.translatable(lang);
    }

    public static HandCategory byKey(String key) {
        for (HandCategory c : values()) if (c.key.equals(key)) return c;
        return null;
    }

    public static HandCategory of(ItemStack s) {
        if (s == null || s.isEmpty()) return EMPTY;
        HandCategory fromLore = fromRarityLine(rarityLine(s));
        if (fromLore != null) return fromLore;
        if (s.is(ItemTags.SWORDS)) return SWORD;
        if (s.is(Items.BOW) || s.is(Items.CROSSBOW)) return BOW;
        if (s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES)
            || s.is(Items.SHEARS)) return TOOL;
        if (s.is(Items.FISHING_ROD)) return ROD;
        return OTHER;
    }

    /** The last non-empty lore line in capitals, without the recombobulator's letters; "" when there is none. */
    static String rarityLine(ItemStack s) {
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return "";
        String last = "";
        for (Component c : lore.lines()) {
            String t = c.getString().trim();
            if (!t.isEmpty()) last = t;
        }
        return last.toUpperCase(Locale.ROOT);
    }

    /** "LEGENDARY DUNGEON SWORD" is a sword, "EPIC DRILL" a tool; null for anything a rule can't name. */
    static HandCategory fromRarityLine(String line) {
        if (line.isEmpty()) return null;
        String t = RARITY_SUFFIX.matcher(line).replaceFirst("");
        if (t.endsWith("FISHING ROD") || t.endsWith("FISHING WEAPON") || t.endsWith(" ROD")) return ROD;
        if (t.endsWith("SWORD")) return SWORD; // LONGSWORD too
        if (t.endsWith("BOW")) return BOW; // SHORTBOW, LONGBOW
        for (String w : new String[]{"PICKAXE", "DRILL", "AXE", "HOE", "SHOVEL", "GAUNTLET", "SHEARS", "CHISEL"}) {
            if (t.endsWith(" " + w) || t.equals(w)) return TOOL;
        }
        if (t.endsWith("WAND") || t.endsWith("STAFF") || t.endsWith("DEPLOYABLE")) return OTHER;
        return null;
    }
}
