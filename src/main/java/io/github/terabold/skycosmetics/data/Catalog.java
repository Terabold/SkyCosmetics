package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.Textures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the picker can offer, built from the NEU repo plus skins learned
 * in game. Immutable: a repo update or a learned skin builds a new Catalog and
 * swaps it in whole, so a reader never sees half of one.
 */
public final class Catalog {
    public static final Catalog EMPTY = new Catalog("nothing loaded yet", Map.of(), Map.of(), Map.of(), List.of());

    /** Where it came from, e.g. "Skyblocker@051234a9". */
    public final String source;
    /** Item files the repo had; 0 until a repo has loaded. */
    public final int items;
    /** Entries from {@code captured.json} that the repo does not have (yet). */
    public final int learned;
    public final Map<String, SkinEntry> skins;
    public final Map<String, DyeEntry> dyes;
    /** SkyBlock id -> clean display name, for labelling "all items of this type". */
    public final Map<String, String> itemNames;

    /** Every cosmetic skin with its variants: helmet, power orb, backpack, minion skins... */
    public final List<SkinEntry> skinTab;
    /** Helmet skins with their variants (the Skins tab's Helmet filter). */
    public final List<SkinEntry> helmetSkinTab;
    /** Power orb skins, the repo's {@code *_FLUX} items, with their variants. */
    public final List<SkinEntry> orbSkinTab;
    public final List<SkinEntry> animatedTab;
    public final List<SkinEntry> petSkinTab;
    public final List<SkinEntry> headTab;
    public final List<DyeEntry> dyeTab;

    /** Pasted textures, made on demand. Shared so repeated lookups return one entry. */
    private static final Map<String, SkinEntry> CUSTOM = new ConcurrentHashMap<>();
    /**
     * Custom animated dyes, expanded once: the studio asks for a look's dye
     * every frame. Bounded because the editor makes a new id per edit.
     */
    private static final Map<String, DyeEntry> ANIMATED = new ConcurrentHashMap<>();
    private static final int ANIMATED_CACHE = 256;
    /** Cached answer for a malformed {@code anim:} id, so it is not re-parsed every frame either. */
    private static final DyeEntry MALFORMED = new DyeEntry("anim:malformed", "", 0, new int[]{0});

    Catalog(String source, Map<String, SkinEntry> skins, Map<String, DyeEntry> dyes,
            Map<String, String> itemNames, List<DyeEntry> dyeOrder) {
        this(source, 0, 0, skins, dyes, itemNames, dyeOrder);
    }

    Catalog(String source, int items, int learned, Map<String, SkinEntry> skins, Map<String, DyeEntry> dyes,
            Map<String, String> itemNames, List<DyeEntry> dyeOrder) {
        this.source = source;
        this.items = items;
        this.learned = learned;
        this.skins = skins;
        this.dyes = dyes;
        this.itemNames = itemNames;

        List<SkinEntry> all = new ArrayList<>(skins.values());
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        // Variants go straight after their parent so "Knight Skin" and its
        // 15 colours sit together instead of scattered through the alphabet.
        Map<String, List<SkinEntry>> children = new HashMap<>();
        for (SkinEntry e : all) {
            if (e.parent != null && skins.containsKey(e.parent)) {
                children.computeIfAbsent(e.parent, k -> new ArrayList<>()).add(e);
            }
        }
        List<SkinEntry> skinTab = new ArrayList<>();
        List<SkinEntry> petTab = new ArrayList<>();
        List<SkinEntry> animTab = new ArrayList<>();
        List<SkinEntry> headTab = new ArrayList<>();
        for (SkinEntry e : all) {
            if (!e.listed) continue;
            if (e.animated()) animTab.add(e);
            headTab.add(e);
            if (e.parent != null && skins.containsKey(e.parent)) continue; // placed with parent
            List<SkinEntry> target = switch (e.kind) {
                case SKIN -> skinTab;
                case PET_SKIN -> petTab;
                case VARIANT -> e.id.startsWith("PET_SKIN_") ? petTab : skinTab;
                default -> null;
            };
            if (target == null) continue;
            target.add(e);
            List<SkinEntry> kids = children.get(e.id);
            if (kids != null) target.addAll(kids);
        }
        List<SkinEntry> helmets = new ArrayList<>();
        List<SkinEntry> orbs = new ArrayList<>();
        for (SkinEntry e : skinTab) {
            SkinEntry root = e.parent != null && skins.containsKey(e.parent) ? skins.get(e.parent) : e;
            if (root.use == SkinEntry.Use.ORB || root.id.contains("_FLUX")) orbs.add(e); // learned orb skins have no lore
            else if (root.use == SkinEntry.Use.HELMET) helmets.add(e);
        }
        this.skinTab = Collections.unmodifiableList(skinTab);
        this.helmetSkinTab = Collections.unmodifiableList(helmets);
        this.orbSkinTab = Collections.unmodifiableList(orbs);
        this.petSkinTab = Collections.unmodifiableList(petTab);
        this.animatedTab = Collections.unmodifiableList(animTab);
        this.headTab = Collections.unmodifiableList(headTab);
        this.dyeTab = Collections.unmodifiableList(dyeOrder);
    }

    /** Repo id, or {@code tex:<base64>} for a pasted texture. Null if unknown. */
    public SkinEntry skin(String id) {
        if (id == null) return null;
        if (id.startsWith(Textures.CUSTOM_PREFIX)) {
            return CUSTOM.computeIfAbsent(id, k -> SkinEntry.still(k, "Custom texture", 0xFFFFFFFF,
                SkinEntry.Kind.CUSTOM, k.substring(Textures.CUSTOM_PREFIX.length()), null));
        }
        return skins.get(id);
    }

    /**
     * Repo dye id, {@code #RRGGBB}, or a custom animated dye
     * {@code anim:<ticksPerStep>:<step|blend>:#RRGGBB,...} (see {@link DyeEntry#parseCustomAnimated}).
     * Null if unknown or malformed.
     */
    public DyeEntry dye(String id) {
        if (id == null) return null;
        if (id.startsWith("#")) {
            try {
                return DyeEntry.custom(Integer.parseInt(id.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (DyeEntry.isCustomAnimated(id)) {
            DyeEntry e = ANIMATED.get(id);
            if (e == null) {
                DyeEntry made = DyeEntry.customAnimated(id);
                if (ANIMATED.size() >= ANIMATED_CACHE) ANIMATED.clear();
                e = ANIMATED.computeIfAbsent(id, k -> made != null ? made : MALFORMED);
            }
            return e == MALFORMED ? null : e;
        }
        return dyes.get(id);
    }

    public String typeName(String type) {
        if (type == null) return "?";
        if (type.startsWith("PET:")) {
            String t = type.substring(4);
            return title(t) + " Pet";
        }
        String n = itemNames.get(type);
        return n != null ? n : title(type);
    }

    public static List<SkinEntry> filter(List<SkinEntry> src, String query) {
        if (query == null || query.isBlank()) return src;
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<SkinEntry> out = new ArrayList<>();
        outer:
        for (SkinEntry e : src) {
            for (String w : words) if (!e.searchKey.contains(w)) continue outer;
            out.add(e);
        }
        return out;
    }

    public static List<DyeEntry> filterDyes(List<DyeEntry> src, String query) {
        if (query == null || query.isBlank()) return src;
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<DyeEntry> out = new ArrayList<>();
        outer:
        for (DyeEntry e : src) {
            for (String w : words) if (!e.searchKey.contains(w)) continue outer;
            out.add(e);
        }
        return out;
    }

    static String title(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
