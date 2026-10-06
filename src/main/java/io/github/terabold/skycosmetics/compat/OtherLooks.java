package io.github.terabold.skycosmetics.compat;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Looks other mods saved for your items: Skyblocker's custom names, dyes, trims, helmet textures, glint and models,
 * and SkyOcean's item customizations. The studio lists them in its Other Mods tab and shows a chip ("Dyed by
 * Skyblocker") on the item they change, because those mods draw their change over SkyCosmetics' one.
 *
 * They are read from each mod's live config and changed only through it: a removal edits that mod's config in
 * memory, then calls the mod's own save, so the change sticks and the mod never writes the old value back. Their
 * files are never written. When a mod's classes are not what this build knows, its file is read instead and its
 * looks are shown read-only. Every call is guarded: a mod update can hide a look here, never break the game.
 */
public final class OtherLooks {
    /** What a change does to an item; {@code key} names its lang strings. */
    public enum Kind {
        NAME("name"), DYE("dye"), SKIN("skin"), TRIM("trim"), GLINT("glint"), MODEL("model");

        public final String key;

        Kind(String key) {
            this.key = key;
        }

        /** "Dye". */
        public Component label() {
            return Component.translatable("skycosmetics.other.kind." + key);
        }

        /** "Dyed by Skyblocker". */
        public Component by(String mod) {
            return Component.translatable("skycosmetics.other.chip." + key, mod);
        }
    }

    /**
     * One change another mod makes to one item.
     *
     * @param item   the item's SkyBlock UUID; "id:" and a SkyBlock id for a look on every item of that type; "one:"
     *               and a SkyBlock id for one item without a UUID
     * @param value  what it is set to, short ("#FF8800", "Aurora Dye", "On")
     * @param rgb    a color to show beside the value, or -1
     * @param name   the custom name itself, for {@link Kind#NAME}; else null
     * @param handle what the source needs to remove it, and to put it back
     * @param into   the same change as a SkyCosmetics look, or null when SkyCosmetics has nothing like it
     * @param live   false when only the mod's file could be read: shown, never removed from here
     */
    public record Change(Source source, String item, Kind kind, String value, int rgb, Component name, Object handle,
                         UnaryOperator<Looks.Look> into, boolean live) {
        /** The item's UUID, or null for a look keyed by SkyBlock id. */
        public String uuid() {
            return item.startsWith("id:") || item.startsWith("one:") ? null : item;
        }

        /** The SkyBlock id of a look keyed by it ("id:" or "one:"), else null. */
        public String type() {
            if (item.startsWith("id:")) return item.substring(3);
            if (item.startsWith("one:")) {
                int at = item.indexOf('@');
                return item.substring(4, at > 4 ? at : item.length());
            }
            return null;
        }

        /** Whether it is for every item of a type, not one item. */
        public boolean everyItem() {
            return item.startsWith("id:");
        }

        /** "Dyed by Skyblocker". */
        public Component chip() {
            return kind.by(source.name());
        }

        /** Whether SkyCosmetics can take it over ("Move here"). */
        public boolean movable() {
            return into != null && live && (uuid() != null || everyItem());
        }
    }

    /** One mod whose looks are listed. */
    public interface Source {
        /** The mod's name, as players know it. */
        String name();

        /** Its chip color, ARGB. */
        int color();

        /** A cheap value that changes when its looks change; compared on every refresh. */
        Object stamp();

        /** Every look it has now. */
        List<Change> read();

        /** Takes one change off through the mod's own config, then saves it there; true once it is gone. */
        boolean remove(Change change);

        /** Puts a change {@link #remove} took off back, the same way; true once it is back. */
        boolean restore(Change change);
    }

    /** How often the studio may look for changes made elsewhere (another mod's screen, a command). */
    private static final long CHECK_MS = 500;

    private static List<Source> sources = List.of();
    private static List<Change> all = List.of();
    private static Map<String, List<Change>> byItem = Map.of();
    private static Object[] stamps = new Object[0];
    private static long checkedAt = Long.MIN_VALUE;
    private static int version;

    private OtherLooks() {}

    /** Finds the supported mods that are installed; their classes are only touched when the studio asks. */
    public static void init() {
        List<Source> found = new ArrayList<>();
        Path config = FabricLoader.getInstance().getConfigDir();
        if (FabricLoader.getInstance().isModLoaded("skyblocker")) {
            found.add(new SkyblockerLooks(SkyblockerLooks.MANAGER, config.resolve("skyblocker.json")));
        }
        if (FabricLoader.getInstance().isModLoaded("skyocean")) {
            found.add(new SkyOceanLooks(SkyOceanLooks.ITEMS, config.resolve("skyocean/data/custom_items.json")));
        }
        use(found);
    }

    /** Lists these sources instead (tests: fake mods built like the real ones). */
    public static void use(List<Source> list) {
        sources = List.copyOf(list);
        stamps = new Object[sources.size()];
        all = List.of();
        byItem = Map.of();
        checkedAt = Long.MIN_VALUE;
        version++;
    }

    public static List<Source> sources() {
        return sources;
    }

    /** Whether any supported mod is installed: the studio shows its Other Mods tab then. */
    public static boolean present() {
        return !sources.isEmpty();
    }

    /** Changes when the lists change. */
    public static int version() {
        return version;
    }

    /** Every change, grouped by item in the order the mods list them. */
    public static List<Change> all() {
        refresh(false);
        return all;
    }

    /** The changes on one item ({@link Change#item}), or none. */
    public static List<Change> of(String item) {
        refresh(false);
        List<Change> l = item == null ? null : byItem.get(item);
        return l == null ? List.of() : l;
    }

    /**
     * Reads the mods again if one of them changed (at most every {@value #CHECK_MS} ms unless {@code now}). Cheap
     * when nothing changed: one stamp per mod.
     */
    public static void refresh(boolean now) {
        if (sources.isEmpty()) return;
        long t = Util.getMillis();
        if (!now && t - checkedAt < CHECK_MS) return;
        checkedAt = t;
        boolean changed = false;
        Object[] next = new Object[sources.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = stampOf(sources.get(i));
            if (i >= stamps.length || !Objects.equals(next[i], stamps[i])) changed = true;
        }
        if (!changed && !now) return;
        stamps = next;
        List<Change> list = new ArrayList<>();
        for (Source s : sources) {
            try {
                list.addAll(s.read());
            } catch (RuntimeException | LinkageError e) {
                Io.failed("Reading " + s.name() + "'s looks", e);
            }
        }
        Map<String, List<Change>> index = new LinkedHashMap<>();
        for (Change c : list) index.computeIfAbsent(c.item(), k -> new ArrayList<>()).add(c);
        index.replaceAll((k, v) -> Collections.unmodifiableList(v));
        List<Change> grouped = new ArrayList<>(list.size());
        for (List<Change> v : index.values()) grouped.addAll(v);
        if (!same(grouped, all)) version++;
        all = Collections.unmodifiableList(grouped);
        byItem = Collections.unmodifiableMap(index);
    }

    /** The same changes, compared by what is shown (handles are new objects on every read). */
    private static boolean same(List<Change> a, List<Change> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Change x = a.get(i), y = b.get(i);
            if (x.source() != y.source() || !x.item().equals(y.item()) || x.kind() != y.kind()
                || !x.value().equals(y.value()) || x.rgb() != y.rgb() || x.live() != y.live()) return false;
        }
        return true;
    }

    private static Object stampOf(Source s) {
        try {
            return s.stamp();
        } catch (RuntimeException | LinkageError e) {
            return e.getClass(); // a failing mod is read again only when it starts or stops failing
        }
    }

    /** Takes {@code c} off in its own mod; false (and a log line) if that mod would not. */
    public static boolean remove(Change c) {
        if (!c.live()) return false;
        boolean ok;
        try {
            ok = c.source().remove(c);
        } catch (RuntimeException | LinkageError e) {
            Io.failed("Removing a look in " + c.source().name(), e);
            ok = false;
        }
        if (!ok) SkyCosmetics.LOG.warn("{} kept its {} on {}", c.source().name(), c.kind().key, c.item());
        refresh(true);
        return ok;
    }

    /** Puts {@code c} back in its own mod after {@link #remove} (the studio's Undo). */
    public static boolean restore(Change c) {
        if (!c.live()) return false;
        boolean ok;
        try {
            ok = c.source().restore(c);
        } catch (RuntimeException | LinkageError e) {
            Io.failed("Restoring a look in " + c.source().name(), e);
            ok = false;
        }
        refresh(true);
        return ok;
    }

    /**
     * Moves {@code c} into SkyCosmetics: the same change as a look of this item ({@code label} names it in Saved),
     * or of every item of its type, then removed from its mod. Nothing changes if SkyCosmetics has no such change
     * or the mod keeps its own. Returns the look SkyCosmetics had there before (NONE for none), for an undo, or
     * null when nothing moved.
     */
    public static Looks.Look take(Change c, String itemType, String label) {
        if (!c.movable()) return null;
        boolean type = c.uuid() == null;
        String key = type ? c.type() : c.uuid();
        Looks.Look before = type ? Looks.byType(key) : Looks.byUuid(key);
        Looks.Look was = before == null ? Looks.Look.NONE : before;
        Looks.Look next = c.into().apply(was);
        if (!remove(c)) return null;
        String l = label != null ? label : was.label();
        if (type) Looks.put(true, key, next.withLabel(l));
        else Looks.putItem(key, itemType != null ? itemType : Looks.itemType(key), next.withLabel(l));
        return was;
    }
}
