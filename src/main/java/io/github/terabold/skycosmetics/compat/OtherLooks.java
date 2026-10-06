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
    /** What a change does to an item. */
    public enum Kind {
        NAME("Name", "Renamed by "), DYE("Dye", "Dyed by "), SKIN("Skin", "Skin by "), TRIM("Trim", "Trim by "),
        GLINT("Glint", "Glint by "), MODEL("Model", "Model by ");

        public final String label;
        /** The chip on the item in the editor, before the mod's name. */
        public final String by;

        Kind(String label, String by) {
            this.label = label;
            this.by = by;
        }
    }

    /**
     * One change another mod makes to one item.
     *
     * @param item   the item's SkyBlock UUID, or "id:" and its SkyBlock id for a look on items without one
     * @param value  what it is set to, short ("#FF8800", "Aurora Dye", "On")
     * @param rgb    a color to show beside the value, or -1
     * @param name   the custom name itself, for {@link Kind#NAME}; else null
     * @param handle what the source needs to remove it
     * @param into   the same change as a SkyCosmetics look, or null when SkyCosmetics has nothing like it
     * @param live   false when only the mod's file could be read: shown, never removed from here
     */
    public record Change(Source source, String item, Kind kind, String value, int rgb, Component name, Object handle,
                         UnaryOperator<Looks.Look> into, boolean live) {
        /** The item's UUID, or null for a look keyed by SkyBlock id. */
        public String uuid() {
            return item.startsWith("id:") ? null : item;
        }

        /** "Dyed by Skyblocker". */
        public String chip() {
            return kind.by + source.name();
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
        if (!grouped.equals(all)) version++;
        all = Collections.unmodifiableList(grouped);
        byItem = Collections.unmodifiableMap(index);
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
        if (!ok) SkyCosmetics.LOG.warn("{} kept its {} on {}", c.source().name(), c.kind().label, c.item());
        refresh(true);
        return ok;
    }

    /**
     * Moves {@code c} into SkyCosmetics: the same change as a look of this item ({@code label} names it in Saved),
     * then removed from its mod. Nothing changes if SkyCosmetics has no such change or the mod keeps its own.
     */
    public static boolean take(Change c, String itemType, String label) {
        if (c.into() == null || c.uuid() == null || !c.live()) return false;
        Looks.Look before = Looks.byUuid(c.uuid());
        Looks.Look next = c.into().apply(before == null ? Looks.Look.NONE : before);
        if (!remove(c)) return false;
        Looks.putItem(c.uuid(), itemType, next.withLabel(label != null ? label : before != null ? before.label() : null));
        return true;
    }
}
