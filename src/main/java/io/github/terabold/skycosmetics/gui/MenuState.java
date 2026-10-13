package io.github.terabold.skycosmetics.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.hub.Hub;

/** Local navigation preferences, separate from feature enablement. Saved through the normal atomic writer. */
public final class MenuState {
    private static String section = Hub.STUDIO;
    private static boolean zoomAdvanced, nametagsAdvanced;
    private static JsonObject original = new JsonObject();
    private MenuState() {}

    public static void init() { Settings.register("menu", MenuState::read, MenuState::write); }

    public static String section() { return Hub.find(section) == null ? Hub.STUDIO : section; }

    /** Called when a settings screen is actually left, including closing it to open an editor. */
    public static void remember(String id) {
        if (Hub.find(id) != null && !id.equals(section)) { section = id; Settings.save(); }
    }

    public static boolean advanced(String id) {
        return switch (id) { case "zoom" -> zoomAdvanced; case "nametags" -> nametagsAdvanced; default -> false; };
    }

    /** The settings host saves after the disclosure changes; no extra write on a render frame. */
    public static void advanced(String id, boolean value) {
        switch (id) {
            case "zoom" -> zoomAdvanced = value;
            case "nametags" -> nametagsAdvanced = value;
            default -> throw new IllegalArgumentException("Unknown disclosure: " + id);
        }
    }

    public static void read(JsonElement value) {
        if (!(value instanceof JsonObject o)) return;
        original = o.deepCopy();
        String next = Settings.str(o.get("section"), section);
        if (next.length() <= 64 && next.matches("[a-z][a-z0-9_-]*")) section = next;
        JsonObject advanced = Settings.obj(o.get("advanced"));
        zoomAdvanced = Settings.bool(advanced.get("zoom"), zoomAdvanced);
        nametagsAdvanced = Settings.bool(advanced.get("nametags"), nametagsAdvanced);
    }

    public static JsonObject write() {
        JsonObject o = original.deepCopy();
        o.addProperty("section", section);
        JsonObject advanced = Settings.obj(o.get("advanced")).deepCopy();
        advanced.addProperty("zoom", zoomAdvanced);
        advanced.addProperty("nametags", nametagsAdvanced);
        o.add("advanced", advanced);
        return o;
    }
}
