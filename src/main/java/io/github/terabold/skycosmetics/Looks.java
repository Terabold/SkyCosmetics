package io.github.terabold.skycosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Saved looks, keyed two ways:
 * - by item UUID: only that one item (your Necron helmet, not anyone else's);
 * - by type: every item with that SkyBlock id ("NECRON_HEAD", or "PET:GOLDEN_DRAGON"), only on your own items
 *   ({@link io.github.terabold.skycosmetics.items.Mine}).
 * A UUID look wins over a type look, field by field. A UUID look also remembers its item's type, so the Saved
 * tab can sort it under Helmets, Armor... after the item itself is forgotten.
 */
public final class Looks {
    /**
     * Any field may be null, meaning "leave that part as Hypixel sent it".
     * {@code name} uses & colour codes; {@code glint} is "on" or "off";
     * {@code glintColor} is "#RRGGBB" (null = Minecraft's purple);
     * {@code glintSpeed} multiplies the glint's speed (null = Minecraft's own
     * Accessibility setting, 1 = normal, 0.1..4); {@code glintStrength}
     * multiplies how bright the glint is (null = as drawn, 0.25..3), for
     * resource packs whose glint is hard to see.
     */
    public record Look(String skin, String dye, String name, String glint, String glintColor, Float glintSpeed,
                       Float glintStrength, String label) {
        public static final Look NONE = new Look(null, null, null, null, null, null, null, null);

        public Look(String skin, String dye, String name, String glint, String label) {
            this(skin, dye, name, glint, null, null, null, label);
        }

        public boolean empty() {
            return skin == null && dye == null && name == null && glint == null && glintColor == null
                && glintSpeed == null && glintStrength == null;
        }

        public Look withSkin(String s) { return new Look(s, dye, name, glint, glintColor, glintSpeed, glintStrength, label); }
        public Look withDye(String d) { return new Look(skin, d, name, glint, glintColor, glintSpeed, glintStrength, label); }
        public Look withName(String n) { return new Look(skin, dye, n, glint, glintColor, glintSpeed, glintStrength, label); }
        public Look withGlint(String g) { return new Look(skin, dye, name, g, glintColor, glintSpeed, glintStrength, label); }
        public Look withGlintColor(String c) { return new Look(skin, dye, name, glint, c, glintSpeed, glintStrength, label); }
        public Look withGlintSpeed(Float v) { return new Look(skin, dye, name, glint, glintColor, v, glintStrength, label); }
        public Look withGlintStrength(Float v) { return new Look(skin, dye, name, glint, glintColor, glintSpeed, v, label); }
        public Look withLabel(String l) { return new Look(skin, dye, name, glint, glintColor, glintSpeed, glintStrength, l); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, Look> BY_UUID = new LinkedHashMap<>();
    private static final Map<String, Look> BY_TYPE = new LinkedHashMap<>();
    /** SkyBlock type of the item each UUID look is for, when known. */
    private static final Map<String, String> ITEM_TYPES = new HashMap<>();

    private static volatile int version;
    /** Skin ids some look uses; a snapshot any thread may read (learned skins in use are never dropped). */
    private static volatile Set<String> usedSkins = Set.of();

    private Looks() {}

    public static int version() {
        return version;
    }

    /** Invalidates every cached render copy. */
    public static void bump() {
        version++;
    }

    public static Look byUuid(String uuid) {
        return uuid == null ? null : BY_UUID.get(uuid);
    }

    public static Look byType(String type) {
        return type == null ? null : BY_TYPE.get(type);
    }

    public static Map<String, Look> uuidLooks() {
        return BY_UUID;
    }

    public static Map<String, Look> typeLooks() {
        return BY_TYPE;
    }

    public static Set<String> usedSkins() {
        return usedSkins;
    }

    /** The SkyBlock type of the item a UUID look is for ("NECRON_HEAD"), or null when it was never noted. */
    public static String itemType(String uuid) {
        return uuid == null ? null : ITEM_TYPES.get(uuid);
    }

    public static void put(boolean type, String key, Look look) {
        Map<String, Look> m = type ? BY_TYPE : BY_UUID;
        if (look == null || look.empty()) {
            m.remove(key);
            if (!type) ITEM_TYPES.remove(key);
        } else {
            m.put(key, look);
        }
        indexSkins();
        bump();
        save();
    }

    /** {@link #put} for one item, noting its SkyBlock type ({@code itemType} may be null). */
    public static void putItem(String uuid, String itemType, Look look) {
        if (itemType != null && look != null && !look.empty()) ITEM_TYPES.put(uuid, itemType);
        put(false, uuid, look);
    }

    private static void indexSkins() {
        Set<String> s = new HashSet<>();
        for (Look l : BY_UUID.values()) if (l.skin() != null) s.add(l.skin());
        for (Look l : BY_TYPE.values()) if (l.skin() != null) s.add(l.skin());
        usedSkins = Set.copyOf(s);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/looks.json");
    }

    /**
     * The whole file is read before anything changes, so a file that fails leaves the looks
     * in memory as they were. A file that cannot be read, or has looks that cannot, is set
     * aside ({@link Io#setAside}) and saved again from what could be read: the next save
     * never writes over your hand edits.
     */
    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        Map<String, Look> uuids = new LinkedHashMap<>();
        Map<String, Look> types = new LinkedHashMap<>();
        Map<String, String> itemTypes = new HashMap<>();
        int skipped;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            // "typeLooksOnOthers" (before 1.4) is ignored: "every item" looks now show only on your own items.
            skipped = read(o.get("items"), uuids, itemTypes) + read(o.get("types"), types, null);
        } catch (Exception e) {
            Io.setAside(f, "Could not read looks.json", e);
            save();
            return;
        }
        BY_UUID.clear();
        BY_UUID.putAll(uuids);
        BY_TYPE.clear();
        BY_TYPE.putAll(types);
        ITEM_TYPES.clear();
        ITEM_TYPES.putAll(itemTypes);
        indexSkins();
        bump();
        if (skipped > 0) {
            Io.setAside(f, "Some looks in looks.json could not be read", null);
            save();
        }
    }

    /**
     * Reads one section into {@code into} (and each look's item type into {@code itemTypes}, if given); returns how
     * many looks in it could not be read.
     */
    private static int read(JsonElement section, Map<String, Look> into, Map<String, String> itemTypes) {
        if (section == null || section.isJsonNull()) return 0;
        JsonObject o = section.getAsJsonObject(); // not an object: the whole file is unreadable
        int skipped = 0;
        for (String k : o.keySet()) {
            // One hand-edited or corrupt entry must not take every later look with it.
            try {
                JsonObject l = o.getAsJsonObject(k);
                Float speed = l.has("glintSpeed") && l.get("glintSpeed").isJsonPrimitive()
                    ? clampSpeed(l.get("glintSpeed").getAsFloat()) : null;
                Float strength = l.has("glintStrength") && l.get("glintStrength").isJsonPrimitive()
                    ? clampStrength(l.get("glintStrength").getAsFloat()) : null;
                String color = s(l, "glintColor");
                if (color != null && !color.matches("#[0-9A-Fa-f]{6}")) color = null;
                Look look = new Look(s(l, "skin"), s(l, "dye"), s(l, "name"), s(l, "glint"), color, speed,
                    strength, s(l, "label"));
                if (!look.empty()) into.put(k, look);
                String type = s(l, "type");
                if (itemTypes != null && type != null && !look.empty()) itemTypes.put(k, type);
            } catch (RuntimeException e) {
                SkyCosmetics.LOG.warn("Skipping unreadable look {}: {}", k, e.toString());
                skipped++;
            }
        }
        return skipped;
    }

    /** Glint speeds outside 0.1..4 are clamped; NaN and infinities are dropped. */
    public static Float clampSpeed(float v) {
        if (!Float.isFinite(v)) return null;
        return Math.max(0.1f, Math.min(4f, v));
    }

    /** Glint strengths outside 0.25..3 are clamped; NaN and infinities are dropped. */
    public static Float clampStrength(float v) {
        if (!Float.isFinite(v)) return null;
        return Math.max(0.25f, Math.min(3f, v));
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("items", write(BY_UUID, ITEM_TYPES));
        root.add("types", write(BY_TYPE, Map.of()));
        Io.writeAsync(file(), root);
    }

    private static JsonObject write(Map<String, Look> m, Map<String, String> itemTypes) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Look> e : m.entrySet()) {
            JsonObject l = new JsonObject();
            if (e.getValue().skin() != null) l.addProperty("skin", e.getValue().skin());
            if (e.getValue().dye() != null) l.addProperty("dye", e.getValue().dye());
            if (e.getValue().name() != null) l.addProperty("name", e.getValue().name());
            if (e.getValue().glint() != null) l.addProperty("glint", e.getValue().glint());
            if (e.getValue().glintColor() != null) l.addProperty("glintColor", e.getValue().glintColor());
            if (e.getValue().glintSpeed() != null) l.addProperty("glintSpeed", e.getValue().glintSpeed());
            if (e.getValue().glintStrength() != null) l.addProperty("glintStrength", e.getValue().glintStrength());
            if (e.getValue().label() != null) l.addProperty("label", e.getValue().label());
            String type = itemTypes.get(e.getKey());
            if (type != null) l.addProperty("type", type);
            o.add(e.getKey(), l);
        }
        return o;
    }
}
