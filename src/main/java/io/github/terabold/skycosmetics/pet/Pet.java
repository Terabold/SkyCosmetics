package io.github.terabold.skycosmetics.pet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One pet as the tracker knows it. A pet seen on a real item carries its look
 * key ({@link #uuid}, the same one {@link Cosmetics#identify} returns) and the
 * item itself; a pet heard of only in chat or the tab list has type, rarity and
 * name but no uuid, so only "all pets of this type" looks can apply to it.
 */
final class Pet {
    static final String[] TIERS = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};
    /** Chat colour char per rarity, same order as {@link #TIERS}. */
    private static final String TIER_CODES = "fa956d";
    private static final String TIER_BOOST = "PET_ITEM_TIER_BOOST";
    private static final Pattern NAME = Pattern.compile(
        "^(?:⭐ )?\\[Lvl (\\d{1,4})] (?:\\[\\d+[✦⚔]] )?([\\w '-]+?)(?: ✦)?$");
    /**
     * Pet names, nametags, chat lines and tab lines are far shorter than this. Longer server
     * text is not a pet and is never matched, so no pattern runs on unbounded input.
     */
    static final int MAX_TEXT = 128;
    static final String HELD = "Held Item: ";

    /** Look key: petInfo.uuid, else the top-level uuid. Null when known only from chat. */
    String uuid;
    /** petInfo.uniqueId: survives the pet being turned into an item and back. */
    String uniqueId;
    String type;
    String tier;
    /** petInfo.skin without the PET_SKIN_ prefix, or null. */
    String skin;
    /** A ✦ was shown after the name; for chat-only pets this is all we know of the skin. */
    boolean skinned;
    String heldItem;
    /**
     * The held item as the menu names it ("Green Bandana"), "" for none, null if not known. Autopet's
     * hover and the tab list name it this way, so it tells two pets of the same type apart.
     */
    String heldName;
    /** Clean name without level or star: "Golden Dragon". */
    String name;
    int level;
    /** The item's own head texture: what the menu showed for this pet's current look. */
    String texture;
    /** When this pet was last known for certain to be the one out (epoch ms); 0 if never. */
    long lastOut;

    /** Last real item seen for this pet; null until seen this session or decoded from {@link #itemJson}. */
    ItemStack item;
    /** {@link #item} encoded for pets.json; null when the item changed and needs re-encoding. */
    JsonElement itemJson;

    boolean resolved() {
        return uuid != null;
    }

    Cosmetics.Ident ident() {
        return new Cosmetics.Ident(uuid, "PET:" + type);
    }

    /** The pet a real SkyBlock pet item stands for, or null for anything else (and for repo placeholders). */
    static Pet of(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag t = ((CustomDataAccessor) (Object) data).skycosmetics$tag();
        if (!"PET".equals(t.getStringOr("id", ""))) return null;
        String hover = ChatFormatting.stripFormatting(io.github.terabold.skycosmetics.Cosmetics.originalName(s).getString());
        if (hover == null || hover.contains("{LVL}") || hover.contains("→")) return null;
        Cosmetics.Ident id = Cosmetics.identify(data);
        JsonObject info = info(t.get("petInfo"));
        if (id == null || info == null || !id.type().startsWith("PET:")) return null;

        Pet p = new Pet();
        p.uuid = id.uuid();
        p.type = id.type().substring(4);
        p.tier = field(info, "tier");
        p.skin = field(info, "skin");
        p.skinned = p.skin != null;
        p.heldItem = field(info, "heldItem");
        p.heldName = heldName(s);
        p.uniqueId = field(info, "uniqueId");
        Matcher m = hover.length() > MAX_TEXT ? null : NAME.matcher(hover.trim());
        if (m != null && m.matches()) {
            p.level = level(m.group(1));
            p.name = m.group(2);
        } else {
            p.name = title(p.type);
        }
        p.texture = texture(s);
        p.item = s.copy();
        return p;
    }

    /** Copies what a newer sighting of the same pet knows. True if anything visible changed. */
    boolean update(Pet newer) {
        boolean changed = !eq(skin, newer.skin) || !eq(texture, newer.texture) || !eq(tier, newer.tier)
            || !eq(type, newer.type);
        uniqueId = newer.uniqueId != null ? newer.uniqueId : uniqueId;
        type = newer.type;
        tier = newer.tier;
        skin = newer.skin;
        skinned = newer.skinned;
        heldItem = newer.heldItem;
        if (newer.heldName != null) heldName = newer.heldName;
        name = newer.name;
        if (newer.level > 0) level = newer.level;
        texture = newer.texture;
        if (newer.item != null && (item == null || !ItemStack.matches(item, newer.item))) {
            item = newer.item;
            itemJson = null;
        }
        return changed;
    }

    /** Does a name + rarity (+ star) heard in chat or the tab list describe this pet? */
    boolean matches(String chatName, char rarity, boolean star) {
        return name != null && name.equalsIgnoreCase(chatName) && skinned == star && rarityMatches(rarity);
    }

    /**
     * Does this pet hold the item called {@code itemName}? By the name its menu item showed, else by
     * its id ("Green Bandana" is GREEN_BANDANA; pet items such as PET_ITEM_* need the name).
     */
    boolean holds(String itemName) {
        if (heldName != null) return !heldName.isEmpty() && heldName.equalsIgnoreCase(itemName);
        return heldItem != null && heldItem.equals(itemName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_"));
    }

    /** A Tier Boost held item shows the pet one rarity higher than petInfo.tier says. */
    boolean rarityMatches(char rarity) {
        if (rarity == 0 || tier == null) return true;
        int i = TIER_CODES.indexOf(rarity);
        if (i < 0) return true;
        int t = tierIndex(tier);
        return t == i || TIER_BOOST.equals(heldItem) && t + 1 == i;
    }

    static int tierIndex(String tier) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(tier)) return i;
        return -1;
    }

    /** A petInfo string short enough to be Hypixel's (they are ids and UUIDs), else null. */
    private static String field(JsonObject info, String k) {
        String v = Cosmetics.str(info, k);
        return v != null && v.length() <= 128 ? v : null;
    }

    /** A level read from server text ("100", "1,000"); 0 if it is not one. */
    static int level(String digits) {
        String d = digits == null ? "" : digits.replace(",", "");
        if (d.isEmpty() || d.length() > 4) return 0;
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String tierOf(char code) {
        int i = TIER_CODES.indexOf(code);
        return i < 0 ? null : TIERS[i];
    }

    /** The first "textures" property on a head, or null (also for one too long to be a real texture). */
    static String texture(ItemStack s) {
        ResolvableProfile p = s.get(DataComponents.PROFILE);
        if (p == null) return null;
        for (var prop : p.partialProfile().properties().get("textures")) {
            String v = prop.value();
            return v != null && v.length() <= PetTextures.MAX_VALUE ? v : null;
        }
        return null;
    }

    /** The "Held Item: Green Bandana" lore line's item, "" if the pet holds nothing. */
    static String heldName(ItemStack s) {
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return "";
        for (Component c : lore.lines()) {
            String t = ChatFormatting.stripFormatting(c.getString());
            if (t != null && t.length() <= MAX_TEXT && t.startsWith(HELD)) return t.substring(HELD.length()).trim();
        }
        return "";
    }

    static boolean loreHas(ItemStack s, String line) {
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component c : lore.lines()) {
            if (line.equals(ChatFormatting.stripFormatting(c.getString()))) return true;
        }
        return false;
    }

    static String title(String type) {
        if (type == null) return null;
        StringBuilder b = new StringBuilder();
        for (String w : type.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static JsonObject info(Tag tag) {
        if (tag == null) return null;
        try {
            String json = tag.asString().orElse(null);
            if (json != null) return JsonParser.parseString(json).getAsJsonObject();
            if (tag instanceof CompoundTag ct) {
                JsonObject o = new JsonObject();
                for (String k : new String[]{"type", "tier", "skin", "heldItem", "uuid", "uniqueId"}) {
                    ct.getString(k).ifPresent(v -> o.addProperty(k, v));
                }
                return o;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
