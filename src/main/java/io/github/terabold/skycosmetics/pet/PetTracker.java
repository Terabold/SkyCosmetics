package io.github.terabold.skycosmetics.pet;

import io.github.terabold.skycosmetics.Io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Which pet you have summoned, from what the client is shown anyway: the Pets
 * menu ("Click to despawn!"), your clicks in it, the Stats & Equipment menu
 * (slot 47), the summon / despawn / Autopet chat lines, and the tab list's Pet
 * widget. Nothing is requested from the server.
 *
 * Every pet ever seen on a real item is remembered with its item, so a later
 * chat-only summon ("You summoned your Golden Dragon!") can be pinned to the
 * exact pet, and its uuid look, by name, rarity, skin, level and held item.
 * When several of your pets still fit (two Lv100 Rabbits), your click in the
 * Pets menu a moment before decides; failing that the best guess is kept and
 * marked as one (the pet out most recently), and the tab list or the next menu
 * settles it. A pet nothing fits stays unresolved and only "all pets of this
 * type" looks apply. Current and known pets persist in config/skycosmetics/pets.json.
 */
public final class PetTracker {
    enum Source { MENU, CLICK, EQUIPMENT, CHAT, AUTOPET, TAB, SAVED, TEST }

    /**
     * Which pet a chat or tab line names, and why. {@code fits} lists every pet it could be when more
     * than one does ({@code pet} is then the best guess); it is empty when the choice is certain.
     */
    record Choice(Pet pet, String why, List<Pet> fits) {
        boolean sure() {
            return fits.isEmpty();
        }
    }

    /** Menus, clicks and chat are exact; the tab widget lags, so it waits this long behind them. */
    private static final long TAB_DEFER_MS = 5_000;
    /** A summon line this soon after your click on a pet in the Pets menu is that pet. */
    private static final long CLICK_MS = 5_000;
    private static final long SAVE_DELAY_MS = 1_000;
    private static final int MAX_KNOWN = 400;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<String, Pet> KNOWN = new LinkedHashMap<>();
    private static Pet current;
    /** Why {@link #current} is the pet out, for "/skycosmetics debug pet". */
    private static String why = "";
    /** Every pet {@link #current} could be when it is a guess; empty when it is certain. */
    private static List<Pet> candidates = List.of();
    /** Your last click on a pet in the Pets menu, by mouse, number key or another mod. */
    private static Pet clicked;
    private static long clickedAt;
    private static int changes;
    private static long assertedAt;
    private static Source assertedBy = Source.SAVED;
    private static long saveAt = -1;

    private PetTracker() {}

    public static void init() {
        load();
        PetSources.init();
        PetDebug.register();
        // Each part on its own: odd server data skips that part for one tick, never the game's tick.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                PetSources.tick(mc);
            } catch (RuntimeException e) {
                Io.failed("Reading menus and the tab list for your pet", e);
            }
            try {
                WorldPet.tick(mc);
            } catch (RuntimeException e) {
                Io.failed("Finding your pet in the world", e);
            }
            if (saveAt >= 0 && System.currentTimeMillis() >= saveAt) {
                try {
                    save();
                } catch (RuntimeException e) {
                    Io.failed("Saving pets.json", e);
                }
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (saveAt >= 0) save();
        });
    }

    // ------------------------------------------------------------ public API ---

    /**
     * The summoned pet as last seen in a menu (a real pet item with petInfo), or
     * {@link ItemStack#EMPTY} when unknown or no pet is out. The studio shows it
     * as the "Pet" slot next to your armour.
     */
    public static ItemStack currentPetItem() {
        Pet p = current;
        if (p == null || !p.resolved()) return ItemStack.EMPTY;
        if (p.item == null) p.item = decode(p);
        return p.item == null ? ItemStack.EMPTY : p.item;
    }

    /** Look key of the summoned pet: its uuid (null if unresolved) and "PET:TYPE". Null if no pet is out. */
    public static Cosmetics.Ident currentIdent() {
        Pet p = current;
        return p == null || p.type == null ? null : p.ident();
    }

    /**
     * Test hook: treat {@code stack} (a pet item) as the summoned pet, as if the
     * Pets menu had shown it; EMPTY despawns.
     */
    public static void debugSetCurrent(ItemStack stack) {
        Pet p = Pet.of(stack);
        if (p == null || !p.resolved()) clearCurrent(Source.TEST);
        else setCurrent(seen(p), Source.TEST, "set by a test");
    }

    /**
     * A click about to be sent for container {@code containerId}: yours, a number key, or another mod's
     * (Odin's pet keybinds). Read only; called by the {@code MultiPlayerGameMode} hook.
     */
    public static void containerInput(int containerId, int slot, int button, ContainerInput input) {
        PetSources.containerInput(containerId, slot, button, input);
    }

    // -------------------------------------------------------------- state ---

    static Pet current() {
        return current;
    }

    /** Bumped whenever the summoned pet, or what it looks like, changes. */
    static int changes() {
        return changes;
    }

    static String why() {
        return why;
    }

    /** What last told us which pet is out (SAVED: nothing since pets.json was read). */
    static Source source() {
        return assertedBy;
    }

    /** The pets the current one was guessed among; empty when it is certain. */
    static List<Pet> candidates() {
        return candidates;
    }

    /** True when the current pet is a known pet and no guess. */
    static boolean certain() {
        return current != null && current.resolved() && candidates.isEmpty();
    }

    /** Record a pet seen on a real item; returns the one shared instance kept for it. */
    static Pet seen(Pet p) {
        Pet known = KNOWN.get(p.uuid);
        if (known == null && p.uniqueId != null) {
            // Converted to an item and back: same pet, new item uuid.
            for (Pet k : KNOWN.values()) {
                if (p.uniqueId.equals(k.uniqueId)) {
                    known = k;
                    break;
                }
            }
            if (known != null) {
                KNOWN.remove(known.uuid);
                known.uuid = p.uuid;
                KNOWN.put(known.uuid, known);
            }
        }
        if (known == null) {
            if (KNOWN.size() >= MAX_KNOWN) KNOWN.remove(KNOWN.keySet().iterator().next());
            KNOWN.put(p.uuid, p);
            scheduleSave();
            return p;
        }
        boolean looks = known.update(p);
        if (known.itemJson == null) scheduleSave();
        if (looks && known == current) changes++;
        return known;
    }

    static void choose(Choice c, Source src) {
        // A chat line answers the click before it; a later one is about another summon.
        if (src == Source.CHAT || src == Source.AUTOPET) clicked = null;
        setCurrent(c.pet(), src, c.why(), c.fits());
    }

    static void setCurrent(Pet p, Source src, String reason) {
        setCurrent(p, src, reason, List.of());
    }

    private static void setCurrent(Pet p, Source src, String reason, List<Pet> fits) {
        long now = System.currentTimeMillis();
        assertedAt = now;
        assertedBy = src;
        if (fits.isEmpty() && p.resolved()) p.lastOut = now;
        boolean noted = !reason.equals(why) || !fits.equals(candidates);
        why = reason;
        candidates = fits;
        if (p == current || same(p, current)) {
            if (noted) scheduleSave();
            return;
        }
        current = p;
        changes++;
        scheduleSave();
    }

    static void clearCurrent(Source src) {
        assertedAt = System.currentTimeMillis();
        assertedBy = src;
        if (src != Source.CLICK) clicked = null;
        why = "";
        candidates = List.of();
        if (current == null) return;
        current = null;
        changes++;
        scheduleSave();
    }

    /** Your click on a pet in the Pets menu: the summon line that may follow names this one. */
    static void clicked(Pet p) {
        clicked = p;
        clickedAt = System.currentTimeMillis();
    }

    /** "Your pet is now holding X." ("" when removed): kept for the current pet when it is certain. */
    static void held(String itemName) {
        if (!certain() || itemName.equals(current.heldName)) return;
        current.heldName = itemName;
        scheduleSave();
    }

    /** The pet became an item: it can no longer be summoned under this record. */
    static void forget(String uuid) {
        Pet p = KNOWN.remove(uuid);
        if (p != null && p == clicked) clicked = null;
        if (p != null && p == current) {
            clearCurrent(Source.CLICK);
        } else if (p != null && candidates.contains(p)) {
            List<Pet> left = new ArrayList<>(candidates);
            left.remove(p);
            candidates = left.size() > 1 ? List.copyOf(left) : List.of();
        }
        scheduleSave();
    }

    /** False while a menu, click or chat line has spoken in the last few seconds. */
    static boolean tabMayAssert() {
        return assertedBy == Source.TAB || assertedBy == Source.SAVED
            || System.currentTimeMillis() - assertedAt > TAB_DEFER_MS;
    }

    /** Two unresolved pets with the same description are the same summon, not a change. */
    private static boolean same(Pet a, Pet b) {
        if (a == null || b == null || a.resolved() || b.resolved()) return false;
        return a.skinned == b.skinned && eq(a.type, b.type) && eq(a.tier, b.tier) && eq(a.name, b.name);
    }

    // ---------------------------------------------------------- resolving ---

    /**
     * A pet named in chat or the tab list, pinned to a known pet that fits by
     * name, rarity and star, then by the star's colour (the skin item's rarity),
     * level and held item. When several still fit, the one you just clicked in
     * the Pets menu, else a {@link #guess}. With none, an unresolved pet
     * carrying what was said.
     *
     * @param rarity colour char of the name, 0 if unknown
     * @param starRgb colour of the ✦, -1 if none or unknown
     * @param held item names the line may show as held ("Green Bandana"); empty if none
     * @param summoned a summon or Autopet line: it never names the pet that was already out
     */
    static Choice resolve(String name, char rarity, boolean star, int level, int starRgb, List<String> held,
                          boolean summoned) {
        List<Pet> fits = new ArrayList<>();
        for (Pet p : KNOWN.values()) if (p.matches(name, rarity, star)) fits.add(p);
        if (fits.size() > 1 && star && starRgb >= 0) {
            fits = narrow(fits, p -> {
                SkinEntry e = p.skin == null ? null : Repo.get().skin("PET_SKIN_" + p.skin);
                return e != null && (e.color & 0xFFFFFF) == starRgb;
            });
        }
        if (fits.size() > 1 && level > 0) fits = narrow(fits, p -> p.level == level);
        if (fits.size() > 1 && !held.isEmpty()) fits = narrow(fits, p -> held.stream().anyMatch(p::holds));
        if (clicked != null && System.currentTimeMillis() - clickedAt <= CLICK_MS && fits.contains(clicked)) {
            return new Choice(clicked, PetSources.CLICKED, List.of());
        }
        if (fits.size() == 1) return new Choice(fits.get(0), "the only " + name + " of yours that fits", List.of());
        if (fits.size() > 1) {
            // Summoning the pet already out would despawn it instead. Not while a click's own line is on its
            // way, and not for a pet only remembered from last session (it may have changed elsewhere).
            boolean known = certain() && assertedBy != Source.CLICK && assertedBy != Source.SAVED;
            return guess(fits, summoned && known ? current : null);
        }

        Pet u = new Pet();
        u.name = name;
        u.type = typeOf(name, fits);
        u.tier = Pet.tierOf(rarity);
        u.skinned = star;
        u.level = level;
        return new Choice(u, "no pet seen in your Pets menu fits; open it once", List.of());
    }

    /**
     * Several of your pets fit and nothing tells them apart: not {@code wasOut}
     * (the one out before this summon), then the one out most recently, else
     * one with a skin look, else the first seen. When they all wear the same
     * skin the guess can't show the wrong one.
     */
    private static Choice guess(List<Pet> fits, Pet wasOut) {
        Pet best = null;
        for (Pet p : fits) {
            if (p == wasOut) continue;
            if (best == null || p.lastOut > best.lastOut
                || p.lastOut == best.lastOut && skinOf(best) == null && skinOf(p) != null) {
                best = p;
            }
        }
        String first = skinOf(fits.get(0));
        boolean alike = true;
        for (Pet p : fits) alike &= Objects.equals(skinOf(p), first);
        String reason = alike ? "they all look the same"
            : wasOut != null && fits.size() == 2 ? "the other one was already out"
            : best.lastOut > 0 ? "it was out most recently" : skinOf(best) != null ? "it has a look" : "it was seen first";
        return new Choice(best, reason, List.copyOf(fits));
    }

    /** The skin a pet's look puts on it in the world, or null. */
    static String skinOf(Pet p) {
        Looks.Look look = p.type == null ? null : Cosmetics.lookFor(p.ident(), true);
        return look == null ? null : look.skin();
    }

    private static List<Pet> narrow(List<Pet> in, Predicate<Pet> keep) {
        List<Pet> out = new ArrayList<>();
        for (Pet p : in) if (keep.test(p)) out.add(p);
        return out.isEmpty() ? in : out;
    }

    /** A known pet's own type when one shares the name, else the name as an id: "Golden Dragon" -> GOLDEN_DRAGON. */
    private static String typeOf(String name, List<Pet> fits) {
        for (Pet p : fits) if (p.type != null) return p.type;
        for (Pet p : KNOWN.values()) if (p.type != null && name.equalsIgnoreCase(p.name)) return p.type;
        return name.trim().toUpperCase(Locale.ROOT).replace("'", "").replace('-', '_').replace(' ', '_');
    }

    // -------------------------------------------------------- persistence ---

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/pets.json");
    }

    private static void scheduleSave() {
        if (saveAt < 0) saveAt = System.currentTimeMillis() + SAVE_DELAY_MS;
    }

    /** A pets.json that cannot be read is set aside, never saved over; an odd entry is skipped. */
    private static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        Map<String, Pet> known = new LinkedHashMap<>();
        Pet cur;
        String reason;
        List<Pet> fits = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject root = Io.parse(r).getAsJsonObject();
            if (root.get("pets") instanceof JsonObject pets) {
                for (Map.Entry<String, JsonElement> e : pets.entrySet()) {
                    Pet p = read(e.getValue());
                    if (p == null || p.type == null) continue;
                    p.uuid = e.getKey();
                    known.put(p.uuid, p);
                }
            }
            String c = Cosmetics.str(root, "current");
            cur = c != null ? known.get(c) : read(root.get("unresolved"));
            reason = Cosmetics.str(root, "why");
            if (root.get("candidates") instanceof JsonArray a) {
                for (JsonElement e : a) {
                    Pet p = e instanceof JsonPrimitive u && u.isString() ? known.get(u.getAsString()) : null;
                    if (p != null && !fits.contains(p)) fits.add(p);
                }
            }
        } catch (Exception e) {
            Io.setAside(f, "Could not read pets.json", e);
            return;
        }
        KNOWN.clear();
        KNOWN.putAll(known);
        clicked = null;
        current = cur != null && cur.type != null ? cur : null;
        why = current == null ? "" : reason != null ? reason : "remembered from your last session";
        candidates = current != null && fits.size() > 1 && fits.contains(current) ? List.copyOf(fits) : List.of();
        if (current != null && current.resolved() && current.lastOut == 0) current.lastOut = 1;
        assertedBy = Source.SAVED;
        assertedAt = 0;
        changes++;
    }

    private static void save() {
        saveAt = -1;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        if (current != null && current.resolved()) root.addProperty("current", current.uuid);
        else if (current != null) root.add("unresolved", write(current, false));
        if (current != null && !why.isEmpty()) root.addProperty("why", why);
        if (!candidates.isEmpty()) {
            JsonArray fits = new JsonArray();
            for (Pet p : candidates) fits.add(p.uuid);
            root.add("candidates", fits);
        }
        JsonObject pets = new JsonObject();
        for (Pet p : KNOWN.values()) pets.add(p.uuid, write(p, true));
        root.add("pets", pets);

        // Built here (item JSON needs the registries); written on the IO thread.
        Io.writeAsync(file(), root);
    }

    /** A pet saved in pets.json, or null if {@code e} is not one; fields of the wrong kind stay empty. */
    private static Pet read(JsonElement e) {
        if (!(e instanceof JsonObject o)) return null;
        Pet p = new Pet();
        p.uniqueId = Cosmetics.str(o, "uniqueId");
        p.type = Cosmetics.str(o, "type");
        p.tier = Cosmetics.str(o, "tier");
        p.skin = Cosmetics.str(o, "skin");
        p.skinned = p.skin != null || o.get("skinned") instanceof JsonPrimitive b && b.isBoolean() && b.getAsBoolean();
        p.heldItem = Cosmetics.str(o, "heldItem");
        p.heldName = Cosmetics.str(o, "heldName");
        p.name = Cosmetics.str(o, "name");
        p.level = o.get("level") instanceof JsonPrimitive n && n.isNumber() ? Math.clamp(n.getAsLong(), 0, 9999) : 0;
        p.lastOut = o.get("lastOut") instanceof JsonPrimitive t && t.isNumber() ? Math.max(0, t.getAsLong()) : 0;
        p.texture = Cosmetics.str(o, "texture");
        JsonElement item = o.get("item");
        p.itemJson = Io.fits(item) ? item : null;
        if (p.heldName == null) p.heldName = heldName(p.itemJson);
        return p;
    }

    /** The held item a saved item's lore names (pets.json from before 1.3.1 has no heldName), or null. */
    private static String heldName(JsonElement item) {
        if (!(item instanceof JsonObject o) || !(o.get("components") instanceof JsonObject c)
            || !(c.get("minecraft:lore") instanceof JsonArray lore)) {
            return null;
        }
        for (JsonElement line : lore) {
            String t = ChatFormatting.stripFormatting(text(line, 0));
            if (t != null && t.length() <= Pet.MAX_TEXT && t.startsWith(Pet.HELD)) return t.substring(Pet.HELD.length()).trim();
        }
        return "";
    }

    /** Plain text of a saved text component: a string, or "text" plus its "extra" parts. */
    private static String text(JsonElement e, int depth) {
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        if (depth > 4 || !(e instanceof JsonObject o)) return "";
        StringBuilder b = new StringBuilder(Objects.requireNonNullElse(Cosmetics.str(o, "text"), ""));
        if (o.get("extra") instanceof JsonArray extra) for (JsonElement x : extra) b.append(text(x, depth + 1));
        return b.toString();
    }

    private static JsonObject write(Pet p, boolean withItem) {
        JsonObject o = new JsonObject();
        put(o, "uniqueId", p.uniqueId);
        put(o, "type", p.type);
        put(o, "tier", p.tier);
        put(o, "skin", p.skin);
        if (p.skinned) o.addProperty("skinned", true);
        put(o, "heldItem", p.heldItem);
        put(o, "heldName", p.heldName);
        put(o, "name", p.name);
        if (p.level > 0) o.addProperty("level", p.level);
        if (p.lastOut > 0) o.addProperty("lastOut", p.lastOut);
        put(o, "texture", p.texture);
        if (withItem) {
            if (p.itemJson == null && p.item != null) p.itemJson = encode(p.item);
            if (p.itemJson != null && !p.itemJson.isJsonNull()) o.add("item", p.itemJson);
        }
        return o;
    }

    private static void put(JsonObject o, String k, String v) {
        if (v != null) o.addProperty(k, v);
    }

    /** Components such as enchantments need the server's registries; everything else encodes without them. */
    private static DynamicOps<JsonElement> ops() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        return c != null ? RegistryOps.create(JsonOps.INSTANCE, c.registryAccess()) : JsonOps.INSTANCE;
    }

    /** The item as JSON; {@link JsonNull} (saved without it) if it cannot be encoded or is too big to keep. */
    private static JsonElement encode(ItemStack s) {
        try {
            JsonElement json = ItemStack.CODEC.encodeStart(ops(), s).result().orElse(null);
            return Io.fits(json) ? json : JsonNull.INSTANCE;
        } catch (RuntimeException e) {
            return JsonNull.INSTANCE;
        }
    }

    /**
     * The saved item, once registries are available; if it no longer decodes,
     * a plain head carrying the same petInfo so looks still key on it.
     */
    private static ItemStack decode(Pet p) {
        if (Minecraft.getInstance().getConnection() == null) return null;
        if (p.itemJson != null && !p.itemJson.isJsonNull()) {
            try {
                ItemStack s = ItemStack.CODEC.parse(ops(), p.itemJson).result().orElse(null);
                if (s != null && !s.isEmpty()) return s;
            } catch (RuntimeException ignored) {
            }
        }
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        JsonObject info = new JsonObject();
        info.addProperty("type", p.type);
        if (p.tier != null) info.addProperty("tier", p.tier);
        if (p.skin != null) info.addProperty("skin", p.skin);
        info.addProperty("uuid", p.uuid);
        if (p.uniqueId != null) info.addProperty("uniqueId", p.uniqueId);
        CompoundTag t = new CompoundTag();
        t.putString("id", "PET");
        t.putString("uuid", p.uuid);
        t.putString("petInfo", info.toString());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        if (p.texture != null) s.set(DataComponents.PROFILE, Textures.profile(p.texture));
        String name = p.name != null ? p.name : Pet.title(p.type);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(p.level > 0 ? "[Lvl " + p.level + "] " + name : name));
        return s;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
