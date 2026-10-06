package io.github.terabold.skycosmetics.slots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * What the slot features need to know about a stack: its rarity, whether it is recombobulated, a SkyBlock item or a
 * pet. Read once per stack and kept on it ({@link Holder}); read again only when the stack's lore or SkyBlock data
 * is replaced, so drawing a menu never parses anything.
 *
 * @param lore the lore component the facts were read from (by identity)
 * @param data the custom data component the facts were read from (by identity)
 * @param rarity null when the item has no rarity line (menu buttons, vanilla items)
 */
public record ItemFacts(Object lore, Object data, Rarity rarity, boolean recombobulated, boolean skyblock, boolean pet) {
    /** Mixed into ItemStack: the facts last read for it. */
    public interface Holder {
        ItemFacts skycosmetics$facts();

        void skycosmetics$facts(ItemFacts facts);
    }

    private static final String TIER_BOOST = "PET_ITEM_TIER_BOOST";

    /** The stack's facts; read now only if its lore or data changed since the last call. */
    public static ItemFacts of(ItemStack s) {
        ItemLore lore = s.get(DataComponents.LORE);
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        Holder h = (Holder) (Object) s;
        ItemFacts f = h.skycosmetics$facts();
        if (f != null && f.lore == lore && f.data == data) return f;
        f = read(lore, data);
        h.skycosmetics$facts(f);
        return f;
    }

    static ItemFacts read(ItemLore lore, CustomData data) {
        CompoundTag tag = null;
        String id = "";
        try {
            tag = data == null ? null : ((CustomDataAccessor) (Object) data).skycosmetics$tag();
            if (tag != null) id = tag.getStringOr("id", "");
        } catch (RuntimeException e) {
            Io.failed("Reading an item's SkyBlock data", e);
        }
        boolean pet = id.equals("PET");
        Line line = lore == null ? null : rarityLine(lore.lines());
        Rarity rarity = line == null ? null : line.rarity;
        if (pet) {
            Rarity tier = petTier(tag);
            if (tier != null) rarity = tier;
        }
        boolean recomb = line != null && line.recombobulated || tag != null && tag.getIntOr("rarity_upgrades", 0) > 0;
        return new ItemFacts(lore, data, rarity, recomb, !id.isEmpty(), pet);
    }

    /** A rarity line, and whether Hypixel wrapped it in the obfuscated letters of a recombobulated item. */
    public record Line(Rarity rarity, boolean recombobulated) {}

    /**
     * Hypixel's rarity line: "LEGENDARY DUNGEON HELMET", "VERY SPECIAL", "SHINY MYTHIC", or "a EPIC BOW a" when
     * recombobulated. It is the last line of the item's own lore; menus such as the auction house add lines under
     * it, so the lowest line that reads like one wins. A line with a lowercase letter (other than the obfuscated
     * ones) is never a rarity line.
     */
    public static Line rarityLine(List<Component> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line l = parse(lines.get(i).getString());
            if (l != null) return l;
        }
        return null;
    }

    static Line parse(String raw) {
        String t = ChatFormatting.stripFormatting(raw);
        if (t == null) return null;
        t = t.trim();
        if (t.length() < 3 || t.length() > 64) return null;
        String[] w = t.split(" +");
        int from = 0, to = w.length;
        boolean recomb = false;
        // Recombobulated: one obfuscated letter on each side.
        if (to >= 2 && w[0].length() == 1 && !Character.isDigit(w[0].charAt(0))) {
            from = 1;
            recomb = true;
            if (w[to - 1].length() == 1) to--;
        }
        if (from < to && w[from].equals("SHINY")) from++; // "SHINY LEGENDARY", "a SHINY MYTHIC a"
        if (from >= to) return null;
        Rarity r;
        int next;
        if (w[from].equals("VERY") && from + 1 < to && w[from + 1].equals("SPECIAL")) {
            r = Rarity.VERY_SPECIAL;
            next = from + 2;
        } else {
            r = w[from].equals("VERY_SPECIAL") ? null : Rarity.named(w[from]);
            next = from + 1;
            if (r != null && !w[from].equals(r.name()) && !w[from].equals("SUPREME")) r = null; // exact, uppercase
        }
        if (r == null) return null;
        for (int i = next; i < to; i++) {
            for (int c = 0; c < w[i].length(); c++) if (Character.isLowerCase(w[i].charAt(c))) return null;
        }
        return new Line(r, recomb);
    }

    /** A pet's tier from its petInfo, one up with a Tier Boost held. */
    private static Rarity petTier(CompoundTag tag) {
        if (tag == null) return null;
        try {
            Tag info = tag.get("petInfo");
            String tier = null, held = null;
            if (info instanceof CompoundTag c) {
                tier = c.getStringOr("tier", null);
                held = c.getStringOr("heldItem", null);
            } else if (info != null) {
                String json = info.asString().orElse(null);
                if (json == null) return null;
                JsonElement e = JsonParser.parseString(json);
                if (!(e instanceof JsonObject o)) return null;
                tier = str(o, "tier");
                held = str(o, "heldItem");
            }
            Rarity r = Rarity.named(tier);
            return r != null && TIER_BOOST.equals(held) ? r.boosted() : r;
        } catch (RuntimeException e) {
            return null; // not JSON: the lore's rarity line still counts
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
