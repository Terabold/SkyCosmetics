package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.SkyCosmetics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frame timings {@link TimingLearner} measured in game, kept in {@code config/skycosmetics/timings.json}:
 * {@code "skins": {"<skin id>": {"frames": 4, "ticksPerTexture": [51, 2, 4, 2], "learnedAt": ...}}}, the
 * animatedskulls.json field, so an entry can go into a repo PR as is.
 *
 * A timing replaces the repo's for its skin while the skin still has that many frames; after a repo update
 * that changes them it is left unused. At most {@link #MAX} entries (the repo has about 600 animations);
 * a file that cannot be read is set aside, never saved over, and the timings in memory are kept.
 *
 * Only touched on the repo thread.
 */
final class Timings {
    static final int MAX = 2000;
    /** One minute: a longer "frame" is a still head or a stalled server, not an animation. */
    static final int MAX_TICKS = 1200;
    /** Real files are a few kB. */
    private static final long MAX_FILE = 1L << 20;

    record Timing(int[] ticks, long at) {}

    final Map<String, Timing> skins = new LinkedHashMap<>();

    static Path file() {
        return RepoSource.ownZip().resolveSibling("timings.json");
    }

    /**
     * The saved timings. A file that cannot be read is set aside and {@code keep} (or nothing) is returned,
     * saved again at once so the timings in memory are not lost with it.
     */
    static Timings load(Timings keep) {
        Path f = file();
        if (!Files.isRegularFile(f)) return new Timings();
        try {
            if (Files.size(f) > MAX_FILE) throw new IOException("larger than " + (MAX_FILE >> 10) + " kB");
            return fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            Io.setAside(f, "Could not read timings.json", e);
            if (keep == null) return new Timings();
            keep.save();
            return keep;
        }
    }

    void save() {
        Io.write(file(), toJson());
    }

    /**
     * Records {@code ticks} for {@code id}; false if they are not a timing or are what is saved already.
     * Past {@link #MAX}, the oldest timing no look in {@code inUse} wears makes room.
     */
    boolean put(String id, int[] ticks, Set<String> inUse) {
        if (!valid(id, ticks)) return false;
        Timing had = skins.get(id);
        if (had != null && Arrays.equals(had.ticks, ticks)) return false;
        skins.remove(id); // re-added last: the newest
        skins.put(id, new Timing(ticks.clone(), System.currentTimeMillis()));
        if (skins.size() > MAX) {
            List<String> oldest = skins.entrySet().stream().filter(e -> !inUse.contains(e.getKey()))
                .sorted(Comparator.comparingLong(e -> e.getValue().at)).limit(skins.size() - MAX)
                .map(Map.Entry::getKey).toList();
            oldest.forEach(skins::remove);
        }
        return true;
    }

    /**
     * Puts every timing whose skin has that many frames into {@code skins}, as a copy of the entry marked
     * {@link SkinEntry#timed}. Skins without a timing stay the same objects.
     */
    void apply(Map<String, SkinEntry> skins) {
        for (Map.Entry<String, Timing> t : this.skins.entrySet()) {
            SkinEntry e = skins.get(t.getKey());
            int[] ticks = t.getValue().ticks;
            if (e == null || e.textures.length != ticks.length || e.textures.length < 2) continue;
            SkinEntry timed = e.withTicks(ticks);
            timed.timed = true;
            skins.put(e.id, timed);
        }
    }

    static boolean valid(String id, int[] ticks) {
        if (!SkinLearner.validId(id) || ticks == null || ticks.length < 2 || ticks.length > Captured.MAX_FRAMES) return false;
        for (int t : ticks) if (t < 1 || t > MAX_TICKS) return false;
        return true;
    }

    // --------------------------------------------------------------- json ---

    /** Every entry that is a real timing; anything else (hand edits, junk) is skipped. */
    static Timings fromJson(JsonObject root) {
        Timings t = new Timings();
        if (!(root.get("skins") instanceof JsonObject skins)) return t;
        for (Map.Entry<String, JsonElement> e : skins.entrySet()) {
            try {
                if (!(e.getValue() instanceof JsonObject o) || !(o.get("ticksPerTexture") instanceof JsonArray a)) continue;
                if (!(o.get("frames") instanceof JsonPrimitive f) || !f.isNumber() || f.getAsInt() != a.size()) continue;
                if (a.size() > Captured.MAX_FRAMES) continue;
                int[] ticks = new int[a.size()];
                for (int i = 0; i < ticks.length; i++) {
                    if (!(a.get(i) instanceof JsonPrimitive p) || !p.isNumber()) throw new IllegalArgumentException("not a number");
                    ticks[i] = p.getAsInt();
                }
                if (!valid(e.getKey(), ticks)) continue;
                long at = o.get("learnedAt") instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0L;
                t.skins.put(e.getKey(), new Timing(ticks, at));
            } catch (RuntimeException ex) {
                SkyCosmetics.LOG.warn("Skipping learned timing {}: {}", e.getKey(), ex.toString());
            }
        }
        while (t.skins.size() > MAX) t.skins.remove(t.skins.keySet().iterator().next());
        return t;
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("about", "How long each frame of an animated skin stays on screen in game, in ticks. "
            + "Used instead of the NEU repo's timing while the skin has this many frames.");
        JsonObject skins = new JsonObject();
        for (Map.Entry<String, Timing> e : this.skins.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("frames", e.getValue().ticks.length);
            JsonArray per = new JsonArray();
            for (int t : e.getValue().ticks) per.add(t);
            o.add("ticksPerTexture", per);
            o.addProperty("learnedAt", e.getValue().at);
            skins.add(e.getKey(), o);
        }
        root.add("skins", skins);
        return root;
    }
}
