package io.github.terabold.skycosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Skins and dyes the player starred with a right-click in the studio, in
 * {@code config/skycosmetics/favorites.json}. The studio lists them first, so
 * the looks you keep coming back to are at the top of every list.
 */
public final class Favorites {
    private static final Set<String> SKINS = new LinkedHashSet<>();
    private static final Set<String> DYES = new LinkedHashSet<>();

    private Favorites() {}

    public static boolean skin(String id) {
        return SKINS.contains(id);
    }

    public static boolean dye(String id) {
        return DYES.contains(id);
    }

    /** Stars a skin, or unstars it; true when it is a favorite now. */
    public static boolean toggleSkin(String id) {
        return toggle(SKINS, id);
    }

    public static boolean toggleDye(String id) {
        return toggle(DYES, id);
    }

    private static boolean toggle(Set<String> set, String id) {
        if (!set.remove(id)) set.add(id);
        save();
        return set.contains(id);
    }

    /** {@code list} with its starred entries first, both parts in their own order; the same list when none is. */
    public static <T> List<T> first(List<T> list, Predicate<T> starred) {
        List<T> top = new ArrayList<>(), rest = new ArrayList<>(list.size());
        for (T t : list) (starred.test(t) ? top : rest).add(t);
        if (top.isEmpty()) return list;
        top.addAll(rest);
        return top;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/favorites.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            Set<String> skins = read(o, "skins"), dyes = read(o, "dyes");
            SKINS.clear();
            SKINS.addAll(skins);
            DYES.clear();
            DYES.addAll(dyes);
        } catch (Exception e) {
            Io.setAside(f, "Could not read favorites.json", e); // the favorites in memory stay as they were
        }
    }

    /** The ids in one list; a hand-edited entry that is not a string is skipped. */
    private static Set<String> read(JsonObject o, String key) {
        Set<String> out = new LinkedHashSet<>();
        if (o.get(key) instanceof JsonArray a) {
            for (JsonElement e : a) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.add("skins", array(SKINS));
        o.add("dyes", array(DYES));
        Io.writeAsync(file(), o);
    }

    private static JsonArray array(Set<String> ids) {
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(id);
        return a;
    }
}
