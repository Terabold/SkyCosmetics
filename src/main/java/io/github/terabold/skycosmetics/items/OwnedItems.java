package io.github.terabold.skycosmetics.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * "My Items": every skinnable item you own that the client has seen, so the
 * studio can list them all, not just what you are wearing.
 *
 * It only looks, twice a second, at your own inventory and armour and at
 * menus that hold your own things (wardrobe, equipment, loadouts, pets, ender
 * chest, backpacks, personal vault: see {@link #OWN_MENUS}). Auction house,
 * bazaar, trades and other players' items never get in. Only items with a SkyBlock UUID are kept,
 * because looks are saved per UUID.
 *
 * Items saved last session are decoded a few per tick once you are in a world, so opening the
 * studio never decodes hundreds at once; an item Hypixel resends unchanged is not re-encoded.
 */
public final class OwnedItems {
    public enum Category {
        HELMET("Helmets"), CHESTPLATE("Chestplates"), LEGGINGS("Leggings"), BOOTS("Boots"),
        PET("Pets"), WEAPON("Weapons & Tools"), DEPLOYABLE("Power Orbs");

        public final String label;

        Category(String label) { this.label = label; }
    }

    public static final class Owned {
        public final String uuid;
        public String type;
        public Category category;
        public String source;
        /** SkyBlock profile it was seen on ("Apple"), or null if seen before the profile was known. */
        public String profile;
        public long seen;
        JsonElement itemJson;
        ItemStack item;
        /** The live stack last scanned, so an unchanged slot costs one reference compare. */
        ItemStack lastRef;
        long refreshedAt;
        String sortName;

        Owned(String uuid) {
            this.uuid = uuid;
        }

        /** The item as last seen; decoded from disk once registries are available (see {@link #warm}). */
        public ItemStack stack() {
            if (item == null) item = decode(this);
            return item == null ? ItemStack.EMPTY : item;
        }

        String sortName() {
            if (sortName == null) sortName = io.github.terabold.skycosmetics.Cosmetics.originalName(stack()).getString();
            return sortName;
        }
    }

    /** A menu that only ever shows your own things: its whole title, and the source My Items shows. */
    private record Menu(Pattern title, String source) {
        Menu(String regex, String source) {
            this(Pattern.compile(regex), source);
        }
    }

    /**
     * Titles as Hypixel sends them in 2026 (SkyHanni 9.1, Skyblocker 6.10 and Firmament 44 agree):
     * pages read "(1/3) Armor Sets" or "Ender Chest (2/9)", a favourite page adds a "✦". Matched
     * whole, so the auction house, bazaar, trades, shops and other players' menus never count.
     */
    private static final Menu[] OWN_MENUS = {
        new Menu("(?:\\(\\d+/\\d+\\) )?Armor Sets|Wardrobe(?: \\(\\d+/\\d+\\))?", "Wardrobe"),
        new Menu("(?:\\(\\d+/\\d+\\) )?Equipment Sets", "Equipment wardrobe"),
        new Menu("(?:\\(\\d+/\\d+\\) )?Loadouts", "Loadouts"),
        new Menu("Stats & Equipment|Your Equipment and Stats", "Equipment"),
        new Menu("(?:\\(\\d+/\\d+\\) )?Pets(?:: \".*\")?(?: \\(\\d+/\\d+\\))?", "Pets"),
        new Menu("Ender Chest(?: ✦)?(?: \\(\\d+/\\d+\\))?", "Ender Chest"),
        new Menu(".+Backpack(?: ✦)? \\(Slot #\\d+\\)", "Backpack"),
        new Menu("Personal Vault", "Personal Vault"),
    };
    private static final int MAX_ITEMS = 1000;
    private static final int SCAN_EVERY = 10;
    private static final long SAVE_DELAY_MS = 2000;
    /** How stale the display copy of a known item may get (drills, farming tools tick counters in lore). */
    private static final long REFRESH_MS = 30_000;
    /** Decoding saved items costs up to this much of a tick. */
    private static final long WARM_NANOS = 1_000_000;

    private static final Map<String, Owned> ITEMS = new LinkedHashMap<>();
    /** Saved items still to decode. */
    private static final ArrayDeque<Owned> COLD = new ArrayDeque<>();
    /** Recombobulated lines end in an obfuscated " a"; some in "(ID ...)". Compiled once. */
    private static final Pattern RARITY_SUFFIX = Pattern.compile("(?: A)?(?: \\(ID \\w+\\))?$");
    /** Stacks already found not skinnable; weak, so stacks Hypixel replaces drop out. */
    private static final Map<ItemStack, Boolean> REJECTED = new java.util.WeakHashMap<>();
    private static int version;
    private static List<Owned> sorted = List.of();
    private static int sortedFor = -1;
    private static int tick;
    private static long saveAt = -1;
    /** The menu last scanned and what it is, so its title is matched once, not twice a second. */
    private static AbstractContainerScreen<?> menuScreen;
    private static String menuSource;

    private OwnedItems() {}

    public static void init() {
        load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                if (mc.player != null) {
                    if (++tick % SCAN_EVERY == 0) {
                        if (io.github.terabold.skycosmetics.Settings.storedItems) scan(mc);
                    }
                    else if (!COLD.isEmpty()) warm();
                }
                if (saveAt >= 0 && System.currentTimeMillis() >= saveAt) save();
            } catch (RuntimeException e) {
                Io.failed("Keeping My Items", e); // odd server data skips a scan, never the game's tick
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (saveAt >= 0) save();
        });
    }

    /** Bumped whenever the list changes, so the studio knows to rebuild. */
    public static int version() {
        return version;
    }

    /** Called by {@link Profiles} when Hypixel reports a different profile. */
    static void profileChanged() {
        version++;
    }

    /**
     * Your items on the current profile (or all profiles, per settings), grouped by category then name. None
     * while Stored Items is off: the studio then lists only what you wear and carry.
     */
    public static List<Owned> list() {
        if (!io.github.terabold.skycosmetics.Settings.storedItems) return List.of();
        if (sortedFor != version) {
            String profile = Profiles.current();
            List<Owned> out = new ArrayList<>();
            for (Owned o : ITEMS.values()) {
                if (io.github.terabold.skycosmetics.Settings.allProfiles || profile == null || o.profile == null
                    || o.profile.equals(profile)) out.add(o);
            }
            out.sort(Comparator.comparing((Owned o) -> o.category)
                .thenComparing(Owned::sortName, String.CASE_INSENSITIVE_ORDER));
            sorted = List.copyOf(out);
            sortedFor = version;
        }
        return sorted;
    }

    /** Whether this item was learned, whatever Stored Items and the profile filter show. */
    public static boolean knows(String uuid) {
        return ITEMS.containsKey(uuid);
    }

    /** The item with this UUID as last seen, whatever Stored Items and the profile filter show; null if never seen. */
    public static Owned get(String uuid) {
        return uuid == null ? null : ITEMS.get(uuid);
    }

    /** Any remembered item of this SkyBlock type ("NECRON_HEAD"), or null: Saved shows it for an "every item" look. */
    public static Owned anyOf(String type) {
        if (type == null) return null;
        for (Owned o : ITEMS.values()) if (type.equals(o.type)) return o;
        return null;
    }

    /**
     * A best guess at the category from a SkyBlock id alone ("NECRON_HEAD", "PET:BEE"), for an item never seen;
     * null when the id says nothing.
     */
    public static Category guess(String type) {
        if (type == null) return null;
        if (type.startsWith("PET:")) return Category.PET;
        if (type.endsWith("_POWER_ORB") || type.endsWith("_FLUX")) return Category.DEPLOYABLE;
        for (String w : new String[]{"HELMET", "_HEAD", "_HAT", "_MASK", "_CROWN", "_GOGGLES"}) if (type.endsWith(w)) return Category.HELMET;
        if (type.endsWith("CHESTPLATE") || type.endsWith("_TUNIC")) return Category.CHESTPLATE;
        if (type.endsWith("LEGGINGS") || type.endsWith("_PANTS") || type.endsWith("_TROUSERS")) return Category.LEGGINGS;
        if (type.endsWith("BOOTS") || type.endsWith("_SHOES") || type.endsWith("_SLIPPERS")) return Category.BOOTS;
        for (String w : new String[]{"SWORD", "BOW", "WAND", "AXE", "HOE", "SHOVEL", "DRILL", "ROD", "BLADE", "STAFF", "DAGGER"}) {
            if (type.endsWith(w)) return Category.WEAPON;
        }
        return null;
    }

    public static void forget(String uuid) {
        if (ITEMS.remove(uuid) != null) changed();
    }

    /** Any SkyBlock item that can wear a look, for listing your live inventory. */
    public static boolean skinnable(net.minecraft.world.item.ItemStack s) {
        Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
        return id != null && categorize(s, id) != null;
    }

    /** Re-sorts after a settings change such as "all profiles". */
    public static void refresh() {
        version++;
    }

    // ------------------------------------------------------------ scanning ---

    private static void scan(Minecraft mc) {
        LocalPlayer p = mc.player;
        for (int i = 0; i < 36; i++) record(p.getInventory().getItem(i), "Inventory");
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            record(p.getItemBySlot(s), "Worn");
        }
        if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) {
            menuScreen = null;
            return;
        }
        if (acs != menuScreen) { // a menu's title never changes while it is open
            menuScreen = acs;
            menuSource = menuSource(acs.getTitle().getString());
        }
        if (menuSource == null) return;
        for (Slot slot : acs.getMenu().slots) {
            if (slot.container != p.getInventory()) record(slot.getItem(), menuSource);
        }
    }

    /** "Wardrobe", "Ender Chest"... for a menu that only shows your own things; null for any other menu. */
    public static String menuSource(String title) {
        String t = ChatFormatting.stripFormatting(title);
        if (t == null) return null;
        t = t.trim();
        for (Menu m : OWN_MENUS) if (m.title().matcher(t).matches()) return m.source();
        return null;
    }

    private static void record(ItemStack s, String source) {
        if (s.isEmpty()) return;
        Cosmetics.Ident id = Cosmetics.identify(s);
        if (id == null || id.uuid() == null) return;
        long now = System.currentTimeMillis();

        Owned o = ITEMS.get(id.uuid());
        if (o != null) {
            // Known item: keep the in-memory copy roughly fresh, but never rewrite
            // items.json just because a counter in the lore ticked.
            o.seen = now;
            if (o.lastRef == s) return;
            o.lastRef = s;
            o.source = source;
            if (o.profile == null && Profiles.current() != null) o.profile = Profiles.current();
            if (o.item == null || now - o.refreshedAt > REFRESH_MS) {
                // Hypixel sends the same item again each time a menu opens: only a change is kept.
                if (!ItemStack.isSameItemSameComponents(o.stack(), s)) {
                    o.item = s.copyWithCount(1);
                    o.itemJson = null; // re-encoded on the next save that happens anyway
                    o.sortName = null;
                }
                o.refreshedAt = now;
            }
            return;
        }

        if (REJECTED.containsKey(s)) return;
        Category cat = categorize(s, id);
        if (cat == null) {
            REJECTED.put(s, Boolean.TRUE);
            return;
        }
        if (ITEMS.size() >= MAX_ITEMS) dropOldest();
        o = new Owned(id.uuid());
        o.type = id.type();
        o.category = cat;
        o.source = source;
        o.profile = Profiles.current();
        o.seen = now;
        o.item = s.copyWithCount(1);
        o.lastRef = s;
        o.refreshedAt = now;
        ITEMS.put(id.uuid(), o);
        changed();
    }

    /**
     * From Hypixel's rarity line, the last lore line: "LEGENDARY DUNGEON HELMET",
     * "EPIC BOW"... Pets are recognised by their SkyBlock id.
     */
    public static Category categorize(ItemStack s, Cosmetics.Ident id) {
        if (id.type().startsWith("PET:")) return Category.PET;
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore == null) return null;
        String last = "";
        for (Component c : lore.lines()) {
            String t = c.getString().trim();
            if (!t.isEmpty()) last = t.toUpperCase(Locale.ROOT);
        }
        // Recombobulated items wrap the rarity line in obfuscated "a"s; some lines end in "(ID ...)".
        last = RARITY_SUFFIX.matcher(last).replaceFirst("");
        if (last.endsWith("HELMET")) return Category.HELMET;
        if (last.endsWith("CHESTPLATE")) return Category.CHESTPLATE;
        if (last.endsWith("LEGGINGS")) return Category.LEGGINGS;
        if (last.endsWith("BOOTS")) return Category.BOOTS;
        if (last.endsWith("DEPLOYABLE") || id.type().endsWith("_POWER_ORB")) return Category.DEPLOYABLE;
        for (String w : new String[]{"SWORD", "BOW", "WAND", "AXE", "HOE", "SHOVEL", "DRILL", "ROD", "WEAPON", "GAUNTLET", "SHEARS"}) {
            if (last.endsWith(w)) return Category.WEAPON;
        }
        return null;
    }

    private static void dropOldest() {
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Owned o : ITEMS.values()) {
            if (o.seen < t) {
                t = o.seen;
                oldest = o.uuid;
            }
        }
        if (oldest != null) ITEMS.remove(oldest);
    }

    private static void changed() {
        version++;
        if (saveAt < 0) saveAt = System.currentTimeMillis() + SAVE_DELAY_MS;
    }

    /** Decodes saved items for up to {@link #WARM_NANOS} (at least one), in a world, so registries exist. */
    private static void warm() {
        long until = System.nanoTime() + WARM_NANOS;
        do {
            Owned o = COLD.poll();
            if (o.item == null && ITEMS.get(o.uuid) == o) o.stack();
        } while (!COLD.isEmpty() && System.nanoTime() < until);
    }

    // --------------------------------------------------------- persistence ---

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/items.json");
    }

    /** An items.json that cannot be read is set aside, never saved over; an odd entry is skipped. */
    private static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        List<Owned> read = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonElement items = Io.parse(r).getAsJsonObject().get("items");
            if (items == null) return;
            for (Map.Entry<String, JsonElement> e : items.getAsJsonObject().entrySet()) {
                Owned o = read(e.getKey(), e.getValue());
                if (o != null) read.add(o);
            }
        } catch (Exception e) {
            Io.setAside(f, "Could not read items.json", e);
            return;
        }
        for (Owned o : read) ITEMS.put(o.uuid, o);
        COLD.addAll(read);
        version++;
    }

    /** One saved item, or null if the entry is not one; fields of the wrong kind stay empty. */
    private static Owned read(String uuid, JsonElement json) {
        if (!(json instanceof JsonObject e)) return null;
        String type = Cosmetics.str(e, "type");
        String category = Cosmetics.str(e, "category");
        Category cat = null;
        for (Category c : Category.values()) if (c.name().equals(category)) cat = c;
        if (type == null || cat == null) return null;
        Owned o = new Owned(uuid);
        o.type = type;
        o.category = cat;
        String source = Cosmetics.str(e, "source");
        o.source = source != null ? source : "";
        o.profile = Cosmetics.str(e, "profile");
        o.seen = e.get("seen") instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0;
        JsonElement item = e.get("item");
        o.itemJson = Io.fits(item) ? item : null;
        return o;
    }

    private static void save() {
        saveAt = -1;
        JsonObject items = new JsonObject();
        for (Owned o : ITEMS.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("type", o.type);
            e.addProperty("category", o.category.name());
            e.addProperty("source", o.source);
            if (o.profile != null) e.addProperty("profile", o.profile);
            e.addProperty("seen", o.seen);
            if (o.itemJson == null && o.item != null) o.itemJson = encode(o.item);
            if (o.itemJson != null && !o.itemJson.isJsonNull()) e.add("item", o.itemJson);
            items.add(o.uuid, e);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", items);
        Io.writeAsync(file(), root);
    }

    /** Enchantments and similar components need the server's registries. */
    private static DynamicOps<JsonElement> ops() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        return c != null ? RegistryOps.create(JsonOps.INSTANCE, c.registryAccess()) : JsonOps.INSTANCE;
    }

    /**
     * The item as JSON; {@link JsonNull} (saved without it) if it cannot be encoded or is too big
     * to keep: a server item nested hundreds of levels deep would make items.json unreadable.
     */
    private static JsonElement encode(ItemStack s) {
        try {
            JsonElement json = ItemStack.CODEC.encodeStart(ops(), s).result().orElse(null);
            return Io.fits(json) ? json : JsonNull.INSTANCE;
        } catch (RuntimeException e) {
            return JsonNull.INSTANCE;
        }
    }

    /**
     * The saved item once registries exist; if it no longer decodes, a plain
     * head that still carries the id and UUID so its look keeps working.
     */
    private static ItemStack decode(Owned o) {
        if (Minecraft.getInstance().getConnection() == null) return null;
        if (o.itemJson != null && !o.itemJson.isJsonNull()) {
            try {
                ItemStack s = ItemStack.CODEC.parse(ops(), o.itemJson).result().orElse(null);
                if (s != null && !s.isEmpty()) return s;
            } catch (RuntimeException ignored) {
            }
        }
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", o.type.startsWith("PET:") ? "PET" : o.type);
        tag.putString("uuid", o.uuid);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(o.type));
        return s;
    }
}
