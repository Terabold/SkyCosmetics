package io.github.terabold.skycosmetics.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The player's own name gradients, shown after the built-in ones. Kept in settings.json under
 * {@code nameGradients}, as each gradient's colors ({@code "#FF5FA8>#5FA8FF"}).
 */
public final class GradientPresets {
    /** At most this many; saving one more drops the oldest. */
    public static final int MAX = 10;
    private static final List<int[]> LIST = new ArrayList<>();
    private static boolean registered;

    private GradientPresets() {}

    /** Reads the saved ones; the studio calls it before it first lists them. */
    static void init() {
        if (registered) return;
        registered = true;
        Settings.register("nameGradients", GradientPresets::read, GradientPresets::write);
    }

    private static void read(JsonElement v) {
        if (!(v instanceof JsonArray a)) return; // missing or hand-broken: keep what is there
        LIST.clear();
        for (JsonElement e : a) {
            if (!(e instanceof JsonPrimitive p) || !p.isString()) continue;
            int[] stops = Names.stopsAt("&[" + p.getAsString() + "]", 0);
            if (stops != null && LIST.size() < MAX) LIST.add(stops);
        }
    }

    private static JsonElement write() {
        JsonArray a = new JsonArray();
        for (int[] stops : LIST) {
            String t = Names.token(stops);
            a.add(t.substring(2, t.length() - 1));
        }
        return a;
    }

    /** The saved gradients, oldest first. */
    public static List<int[]> list() {
        init();
        return Collections.unmodifiableList(LIST);
    }

    /** Saves a gradient; false if the same one is saved already. The oldest goes when there are {@link #MAX}. */
    static boolean add(int[] stops) {
        init();
        for (int[] s : LIST) if (Arrays.equals(s, stops)) return false;
        if (LIST.size() >= MAX) LIST.removeFirst();
        LIST.add(stops.clone());
        Settings.save();
        return true;
    }

    static void remove(int[] stops) {
        if (LIST.removeIf(s -> Arrays.equals(s, stops))) Settings.save();
    }
}
