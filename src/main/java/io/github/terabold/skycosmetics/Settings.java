package io.github.terabold.skycosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * User settings, in {@code config/skycosmetics/settings.json}. All toggles default to the safe, quiet choice.
 *
 * The core flags below keep their names and defaults. A feature owns one more top-level key through
 * {@link #register}, so it never edits this file; keys this build doesn't know (a newer version's, a dropped
 * feature's) are written back unchanged, so a downgrade never erases what a player set.
 */
public final class Settings {
    /** The brush button in the player inventory. */
    public static boolean brush = true;
    /** Reskin your summoned pet (and deployed power orbs) in the world. */
    public static boolean worldReskin = true;
    /** Learn skins the repo does not have yet from items and Elizabeth previews. */
    public static boolean learnSkins = true;
    /**
     * My Items also lists what menus you open showed (Wardrobe, Ender Chest, Backpacks...). Off: only what you
     * wear and carry; nothing more is learned and items.json stays as it is, so On brings them back.
     */
    public static boolean storedItems = true;
    /** The studio's first-open tip about binding a key has been shown. */
    public static boolean keyTipShown = false;

    private record Key(String name, Consumer<JsonElement> read, Supplier<JsonElement> write) {}

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();
    /** The file as last read: its unknown keys are written back, and features registered later read from it. */
    private static JsonObject last = new JsonObject();
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static {
        flag("brush", () -> brush, v -> brush = v);
        flag("worldReskin", () -> worldReskin, v -> worldReskin = v);
        flag("learnSkins", () -> learnSkins, v -> learnSkins = v);
        flag("storedItems", () -> storedItems, v -> storedItems = v);
        flag("keyTipShown", () -> keyTipShown, v -> keyTipShown = v);
    }

    private Settings() {}

    private static void flag(String name, BooleanSupplier get, Consumer<Boolean> set) {
        KEYS.put(name, new Key(name, v -> set.accept(bool(v, get.getAsBoolean())), () -> new JsonPrimitive(get.getAsBoolean())));
    }

    /**
     * A feature's own key: read at once from the file already loaded, then on every load; written on every save.
     * {@code read} gets null when the key is missing and must keep the current value then. A name used twice is
     * a programming error and throws.
     */
    public static void register(String name, Consumer<JsonElement> read, Supplier<JsonElement> write) {
        if (KEYS.containsKey(name)) throw new IllegalArgumentException("settings.json key '" + name + "' is taken");
        Key k = new Key(name, read, write);
        KEYS.put(name, k);
        read(k, last);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/settings.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        JsonObject o;
        try (InputStream in = Files.newInputStream(f)) {
            if (Files.size(f) > MAX_BYTES) throw new IOException("settings.json exceeds 2 MiB");
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("settings.json exceeds 2 MiB");
            o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            Io.setAside(f, "Could not read settings.json", e); // the settings in memory stay as they were
            return;
        }
        last = o;
        for (Key k : KEYS.values()) read(k, o);
    }

    /** One key failing (a hand-edited value a feature can't take) never stops the others. */
    private static void read(Key k, JsonObject o) {
        try {
            k.read().accept(o.get(k.name()));
        } catch (RuntimeException e) {
            Io.failed("Reading the setting " + k.name(), e);
        }
    }

    public static void save() {
        JsonObject o = new JsonObject();
        for (Key k : KEYS.values()) {
            try {
                JsonElement v = k.write().get();
                if (v != null) o.add(k.name(), v);
            } catch (RuntimeException e) {
                Io.failed("Saving the setting " + k.name(), e);
                if (last.get(k.name()) != null) o.add(k.name(), last.get(k.name()).deepCopy());
            }
        }
        for (Map.Entry<String, JsonElement> e : last.entrySet()) {
            if (!KEYS.containsKey(e.getKey())) o.add(e.getKey(), e.getValue().deepCopy());
        }
        Io.writeAsync(file(), o);
    }

    // For read lambdas: the value, or the current one when it is missing or of the wrong type.

    public static boolean bool(JsonElement v, boolean current) {
        return v instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : current;
    }

    /** A finite number, clamped to [min, max]. */
    public static double num(JsonElement v, double current, double min, double max) {
        if (!(v instanceof JsonPrimitive p) || !p.isNumber()) return current;
        double d = p.getAsDouble();
        return Double.isFinite(d) ? Math.clamp(d, min, max) : current;
    }

    public static String str(JsonElement v, String current) {
        return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : current;
    }

    /** The object, or an empty one: its members then each fall back to their current values. */
    public static JsonObject obj(JsonElement v) {
        return v instanceof JsonObject o ? o : new JsonObject();
    }
}
