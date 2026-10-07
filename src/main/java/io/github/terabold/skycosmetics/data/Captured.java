package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.SkyCosmetics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Skins learned in game that the NEU repo does not have yet, kept in
 * {@code config/skycosmetics/captured.json}.
 *
 * "skins" holds still skins seen on items; "animated" holds preview recordings
 * in the exact animatedskulls.json entry format (ticks, ticksPerTexture,
 * "uuid:base64" textures), so an entry can be pasted into a repo PR as is.
 * Once the repo catches up it simply wins the merge. Learned skins come from
 * whatever a server shows, so only Mojang skin textures are kept, and at most
 * {@link #MAX_STILLS} stills and {@link #MAX_ANIMS} animations: past that the
 * oldest one no look uses makes room.
 *
 * Only touched on the repo thread.
 */
final class Captured {
    static final int MAX_STILLS = 2000;
    static final int MAX_ANIMS = 100;
    /** Real animations have up to ~50 frames. */
    static final int MAX_FRAMES = 100;
    /** Real files are a few hundred kB; a bigger one is junk and is not read into memory. */
    private static final long MAX_FILE = 32L << 20;

    record Still(String name, int color, SkinEntry.Kind kind, String texture, long at) {}

    record Anim(String name, int color, int ticks, int[] ticksPerTexture, String[] textures, long at) {}

    final Map<String, Still> stills = new LinkedHashMap<>();
    final Map<String, Anim> anims = new LinkedHashMap<>();

    static Path file() {
        return RepoSource.ownZip().resolveSibling("captured.json");
    }

    /** A file that cannot be read is set aside, never saved over; entries that are not real skins are dropped. */
    static Captured load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return new Captured();
        try {
            if (Files.size(f) > MAX_FILE) throw new IOException("larger than " + (MAX_FILE >> 20) + " MB");
            Captured c = fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
            c.trim(Looks.usedSkins());
            return c;
        } catch (IOException | RuntimeException e) {
            Io.setAside(f, "Could not read captured.json", e);
            return new Captured();
        }
    }

    void save() {
        Io.write(file(), toJson());
    }

    /** Drops the oldest learned skins that no look in {@code inUse} wears until both kinds fit their caps. */
    void trim(Set<String> inUse) {
        trim(stills, MAX_STILLS, inUse, Still::at);
        trim(anims, MAX_ANIMS, inUse, Anim::at);
    }

    private static <T> void trim(Map<String, T> m, int max, Set<String> inUse, ToLongFunction<T> at) {
        if (m.size() <= max) return;
        List<String> oldest = m.entrySet().stream().filter(e -> !inUse.contains(e.getKey()))
            .sorted(Comparator.comparingLong(e -> at.applyAsLong(e.getValue())))
            .limit(m.size() - max).map(Map.Entry::getKey).toList();
        oldest.forEach(m::remove);
    }

    // --------------------------------------------------------------- json ---

    /** Every entry that is a real learned skin; anything else (hand edits, junk from a server) is skipped. */
    static Captured fromJson(JsonObject root) {
        Captured c = new Captured();
        JsonObject skins = obj(root, "skins");
        if (skins != null) {
            for (Map.Entry<String, JsonElement> e : skins.entrySet()) {
                try {
                    if (!(e.getValue() instanceof JsonObject o) || !SkinLearner.validId(e.getKey())) continue;
                    String tex = str(o, "texture");
                    if (!SkinLearner.mojangTexture(tex)) continue;
                    SkinEntry.Kind kind = "PET_SKIN".equals(str(o, "kind")) ? SkinEntry.Kind.PET_SKIN : SkinEntry.Kind.SKIN;
                    c.stills.put(e.getKey(), new Still(nameOr(o, e.getKey()), color(o), kind, tex, at(o)));
                } catch (RuntimeException ex) {
                    SkyCosmetics.LOG.warn("Skipping learned skin {}: {}", e.getKey(), ex.toString());
                }
            }
        }
        JsonObject anims = obj(root, "animated");
        if (anims != null) {
            for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
                try {
                    if (!(e.getValue() instanceof JsonObject o) || !SkinLearner.validId(e.getKey())) continue;
                    if (!(o.get("textures") instanceof JsonArray tex) || tex.isEmpty() || tex.size() > MAX_FRAMES) continue;
                    String[] textures = new String[tex.size()];
                    for (int i = 0; i < textures.length; i++) {
                        textures[i] = tex.get(i).getAsString();
                        if (!SkinLearner.mojangTexture(frame(textures[i]))) throw new IllegalArgumentException("not a skin texture");
                    }
                    int[] per = RepoParser.ticks(o, textures.length);
                    String name = str(o, "name");
                    c.anims.put(e.getKey(), new Anim(name == null ? null : SkinLearner.clip(name), color(o),
                        RepoParser.tick(o.get("ticks"), 1), o.has("ticksPerTexture") ? per : null, textures, at(o)));
                } catch (RuntimeException ex) {
                    SkyCosmetics.LOG.warn("Skipping learned animation {}: {}", e.getKey(), ex.toString());
                }
            }
        }
        return c;
    }

    JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("about", "Skins SkyCosmetics learned in game that the NEU repo does not have yet. "
            + "'animated' entries use the constants/animatedskulls.json format. Repo entries win once they exist.");
        JsonObject skins = new JsonObject();
        for (Map.Entry<String, Still> e : stills.entrySet()) {
            Still s = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("color", hex(s.color));
            o.addProperty("kind", s.kind.name());
            o.addProperty("texture", s.texture);
            o.addProperty("learnedAt", s.at);
            skins.add(e.getKey(), o);
        }
        root.add("skins", skins);
        JsonObject animated = new JsonObject();
        for (Map.Entry<String, Anim> e : anims.entrySet()) {
            Anim a = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("ticks", a.ticks);
            if (a.ticksPerTexture != null) {
                JsonArray per = new JsonArray();
                for (int t : a.ticksPerTexture) per.add(t);
                o.add("ticksPerTexture", per);
            }
            JsonArray tex = new JsonArray();
            for (String t : a.textures) tex.add(t);
            o.add("textures", tex);
            if (a.name != null) o.addProperty("name", a.name);
            if (a.color != 0) o.addProperty("color", hex(a.color));
            o.addProperty("learnedAt", a.at);
            animated.add(e.getKey(), o);
        }
        root.add("animated", animated);
        return root;
    }

    // -------------------------------------------------------------- merge ---

    /**
     * Repo plus learned skins. The repo wins wherever it has the key: a learned
     * still is dropped once the repo lists the id, and a learned animation only
     * replaces a repo entry that has no frames of its own. Learned frame timings
     * ({@code timed}, may be null) then replace the timing of skins with as many frames.
     */
    static Catalog compose(RepoParser.Parsed base, Captured cap, Timings timed) {
        Map<String, SkinEntry> skins = new LinkedHashMap<>(base.skins);
        int learned = 0;
        for (Map.Entry<String, Still> e : cap.stills.entrySet()) {
            String id = e.getKey();
            if (skins.containsKey(id) || base.names.containsKey(id)) continue;
            Still s = e.getValue();
            SkinEntry entry = SkinEntry.still(id, s.name, s.color != 0 ? s.color : 0xFFFF55FF, s.kind, s.texture, null);
            entry.learned = true;
            skins.put(id, entry);
            learned++;
        }
        for (Map.Entry<String, Anim> e : cap.anims.entrySet()) {
            String key = e.getKey();
            Anim a = e.getValue();
            SkinEntry existing = skins.get(key);
            if (existing != null && !existing.learned && (existing.animated() || a.textures.length < 2)) continue;
            String[] frames = new String[a.textures.length];
            for (int i = 0; i < frames.length; i++) frames[i] = frame(a.textures[i]);
            int[] ticks = new int[frames.length];
            for (int i = 0; i < ticks.length; i++) {
                ticks[i] = a.ticksPerTexture != null && i < a.ticksPerTexture.length ? a.ticksPerTexture[i] : a.ticks;
            }
            SkinEntry entry;
            if (existing != null) {
                entry = new SkinEntry(key, existing.name, existing.color, existing.kind, frames, ticks, existing.parent);
                entry.use = existing.use;
            } else {
                String parent = RepoParser.parentOf(key, skins);
                SkinEntry p = parent == null ? null : skins.get(parent);
                String name = a.name != null ? a.name
                    : p != null ? p.name + " (" + Catalog.title(key.substring(parent.length() + 1)) + ")"
                    : Catalog.title(key);
                int color = a.color != 0 ? a.color : p != null ? p.color : 0xFFFF55FF;
                SkinEntry.Kind kind = p != null ? SkinEntry.Kind.VARIANT
                    : key.startsWith("PET_SKIN_") ? SkinEntry.Kind.PET_SKIN : SkinEntry.Kind.SKIN;
                entry = new SkinEntry(key, name, color, kind, frames, ticks, parent);
            }
            entry.learned = true;
            skins.put(key, entry);
            if (existing == null || !existing.learned) learned++;
        }
        if (timed != null) timed.apply(skins);
        return new Catalog(base.label, base.items, learned, skins, base.dyes, base.names, base.dyeOrder);
    }

    // ------------------------------------------------------------ helpers ---

    /** The base64 value of an animatedskulls "uuid:base64" frame. */
    static String frame(String t) {
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String nameOr(JsonObject o, String id) {
        String n = str(o, "name");
        return n != null && !n.isBlank() ? SkinLearner.clip(n) : Catalog.title(id);
    }

    private static int color(JsonObject o) {
        String s = str(o, "color");
        if (s == null) return 0;
        try {
            return 0xFF000000 | Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long at(JsonObject o) {
        JsonElement e = o.get("learnedAt");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0L;
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }
}
