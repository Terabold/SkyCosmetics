package io.github.terabold.skycosmetics.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.compat.OtherLooks.Change;
import io.github.terabold.skycosmetics.compat.OtherLooks.Kind;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * SkyOcean's item customization: {@code CustomItems}' map from an item key (its UUID, or its SkyBlock id) to the
 * item's components (name, color, skin, glint, model, armor trim).
 *
 * A removal takes the component out of that item's data in memory, which SkyOcean draws from, then calls
 * {@code CustomItems.save()}, as SkyOcean's own customize screen does. If those aren't there (another SkyOcean
 * version), {@code skyocean/data/custom_items.json} is read instead, read-only.
 */
final class SkyOceanLooks implements OtherLooks.Source {
    static final String ITEMS = "me.owdding.skyocean.features.item.custom.CustomItems";

    /** The item's data and the component to take out of it. */
    private record Handle(Object data, Object component) {}

    private final String itemsClass;
    private final Path file;
    private Object instance;
    private Field map;
    private Method save;
    /** 0 not tried yet, 1 linked to the live data, -1 only the file can be read. */
    private int state;
    private long fileStamp = Long.MIN_VALUE;
    private List<Change> fromFile = List.of();

    SkyOceanLooks(String itemsClass, Path file) {
        this.itemsClass = itemsClass;
        this.file = file;
    }

    @Override
    public String name() {
        return "SkyOcean";
    }

    @Override
    public int color() {
        return 0xFF2FB8B0;
    }

    private boolean link() {
        if (state != 0) return state > 0;
        try {
            Class<?> c = Class.forName(itemsClass);
            instance = c.getField("INSTANCE").get(null);
            map = Reflect.require(c, "map");
            save = c.getMethod("save");
            state = map.get(null) instanceof Map<?, ?> ? 1 : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            state = -1;
        }
        if (state < 0) SkyCosmetics.LOG.info("SkyOcean's item data isn't one SkyCosmetics knows; its looks are shown read-only");
        return state > 0;
    }

    private Map<?, ?> items() {
        try {
            return map.get(null) instanceof Map<?, ?> m ? m : Map.of();
        } catch (IllegalAccessException e) {
            return Map.of();
        }
    }

    @Override
    public Object stamp() {
        if (!link()) return fileTime();
        // Values are replaced, never edited, so their identities change with every edit.
        long h = 1;
        for (Map.Entry<?, ?> e : items().entrySet()) {
            h = h * 31 + System.identityHashCode(e.getKey());
            if (Reflect.call(e.getValue(), "getData") instanceof Map<?, ?> data) {
                for (Map.Entry<?, ?> d : data.entrySet()) h = h * 31 + System.identityHashCode(d.getValue());
                h = h * 31 + data.size();
            }
        }
        return h;
    }

    @Override
    public List<Change> read() {
        if (!link()) return readFile();
        List<Change> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : items().entrySet()) {
            String item = itemKey(e.getKey());
            if (item == null || !(Reflect.call(e.getValue(), "getData") instanceof Map<?, ?> data)) continue;
            for (Map.Entry<?, ?> d : data.entrySet()) {
                try {
                    Change c = change(item, path(Reflect.call(d.getKey(), "getId")), d.getValue(),
                        new Handle(e.getValue(), d.getKey()), true);
                    if (c != null) out.add(c);
                } catch (RuntimeException ex) {
                    Io.failed("Reading a SkyOcean look", ex);
                }
            }
        }
        return out;
    }

    /** "name", "color"... from SkyOcean's component id ("skyocean:color"). */
    private static String path(Object id) {
        if (id instanceof Identifier i) return i.getPath();
        String s = String.valueOf(id);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    /** The item a key is for: its UUID, or "id:" and its SkyBlock id; null for a key of another kind. */
    private static String itemKey(Object key) {
        if (key == null) return null;
        Object uuid = Reflect.call(key, "getUuid");
        if (uuid == null) uuid = Reflect.field(key, "uuid");
        if (uuid instanceof UUID u) return u.toString();
        if (uuid instanceof String s && !s.isEmpty()) return s;
        Object id = Reflect.field(key, "key", "item");
        return id instanceof String s && !s.isEmpty() ? "id:" + skyblockId(s) : null;
    }

    /** SkyOcean's "item:necron_diamond_knight" as the repo's "NECRON_DIAMOND_KNIGHT". */
    private static String skyblockId(String s) {
        int colon = s.indexOf(':');
        return (colon >= 0 ? s.substring(colon + 1) : s).toUpperCase(Locale.ROOT);
    }

    /** One component as a change; {@code v} is SkyOcean's value object, or JSON from its file. */
    private Change change(String item, String kind, Object v, Object handle, boolean live) {
        return switch (kind) {
            case "name" -> {
                Component name = v instanceof Component c ? c : v instanceof JsonElement j ? component(j) : null;
                if (name == null) yield null;
                yield new Change(this, item, Kind.NAME, name.getString(), -1, name, handle, l -> l.withName(Names.toCodes(name)), live);
            }
            case "glint" -> {
                Boolean on = v instanceof Boolean b ? b : v instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
                if (on == null) yield null;
                yield new Change(this, item, Kind.GLINT, on ? "On" : "Off", -1, null, handle,
                    l -> l.withGlint(on ? "on" : "off"), live);
            }
            case "color" -> color(item, v, handle, live);
            case "skin" -> skin(item, v, handle, live);
            case "model" -> {
                Object at = v instanceof JsonObject o ? o.get("location") : Reflect.field(v, "location");
                if (at == null && v instanceof JsonObject o) at = o.get("model");
                yield new Change(this, item, Kind.MODEL, at != null ? Reflect.words(json(at)) : "Changed", -1, null, handle, null, live);
            }
            case "armor_trim" -> {
                Object material = v instanceof JsonObject o ? o.get("material") : Reflect.field(v, "trimMaterial");
                Object pattern = v instanceof JsonObject o ? o.get("pattern") : Reflect.field(v, "trimPattern");
                String text = pattern != null && material != null
                    ? Reflect.words(json(pattern)) + " (" + Reflect.words(json(material)) + ")" : "Changed";
                yield new Change(this, item, Kind.TRIM, text, -1, null, handle, null, live);
            }
            default -> null;
        };
    }

    private Change color(String item, Object v, Object handle, boolean live) {
        String type = v instanceof JsonObject o && o.get("type") instanceof JsonPrimitive p ? p.getAsString()
            : v == null ? "" : v.getClass().getSimpleName();
        Object code = v instanceof JsonObject o ? o.get("color") : Reflect.field(v, "colorCode");
        Object id = v instanceof JsonObject o ? o.get("id") : Reflect.field(v, "id");
        if (code instanceof JsonPrimitive p && p.isNumber()) code = p.getAsInt();
        if (code instanceof Number n && !type.toLowerCase(Locale.ROOT).contains("gradient")) {
            String hex = Reflect.hex(n.intValue());
            return new Change(this, item, Kind.DYE, hex, n.intValue() & 0xFFFFFF, null, handle, l -> l.withDye(hex), live);
        }
        if (id != null) {
            String dyeId = skyblockId(String.valueOf(json(id)));
            DyeEntry d = Repo.get().dye(dyeId);
            return new Change(this, item, Kind.DYE, d != null ? d.name : Reflect.words(dyeId), d != null ? d.rgbAt(0, 0) : -1,
                null, handle, d != null ? l -> l.withDye(dyeId) : null, live);
        }
        int first = -1;
        if (Reflect.field(v, "gradient", "colors") instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Number n) {
            first = n.intValue() & 0xFFFFFF;
        }
        return new Change(this, item, Kind.DYE, "Gradient", first, null, handle, null, live);
    }

    private Change skin(String item, Object v, Object handle, boolean live) {
        Object texture = v instanceof JsonObject o ? o.get("skin") : Reflect.field(v, "skin");
        if (json(texture) instanceof String tex && !tex.isEmpty() && !(v != null && v.getClass().getSimpleName().startsWith("Animated"))) {
            return new Change(this, item, Kind.SKIN, "Custom texture", -1, null, handle,
                l -> l.withSkin(Textures.CUSTOM_PREFIX + tex), live);
        }
        Object id = v instanceof JsonObject o ? (o.has("id") ? o.get("id") : o.get("item")) : Reflect.field(v, "id", "item");
        if (id == null) return new Change(this, item, Kind.SKIN, "Changed", -1, null, handle, null, live);
        String skinId = skyblockId(String.valueOf(json(id)));
        SkinEntry e = Repo.get().skin(skinId);
        UnaryOperator<Looks.Look> into = e != null ? l -> l.withSkin(skinId) : null;
        return new Change(this, item, Kind.SKIN, e != null ? e.name : Reflect.words(skinId), -1, null, handle, into, live);
    }

    private static Object json(Object v) {
        return v instanceof JsonPrimitive p ? p.getAsString() : v;
    }

    private static Component component(JsonElement j) {
        try {
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, j).result().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean remove(Change c) {
        if (c.source() != this || !link() || !(c.handle() instanceof Handle h)) return false;
        if (!(Reflect.call(h.data(), "getData") instanceof Map<?, ?> data)) return false;
        data.remove(h.component()); // what SkyOcean's own screen does to take a part off
        try {
            save.invoke(instance);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Io.failed("Saving SkyOcean's item data", e);
            return false;
        }
        return !data.containsKey(h.component());
    }

    // ------------------------------------------------------------ file ---

    private Object fileTime() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** SkyOcean's file, read-only: what it saved last. Read again only when the file changes. */
    private List<Change> readFile() {
        long t = (Long) fileTime();
        if (t == fileStamp) return fromFile;
        fileStamp = t;
        List<Change> out = new ArrayList<>();
        if (t != 0L) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = Io.parse(r);
                if (root instanceof JsonObject o && o.get("@skyocean:data") instanceof JsonArray list) {
                    for (JsonElement e : list) {
                        if (!(e instanceof JsonObject entry) || !(entry.get("key") instanceof JsonObject key)
                            || !(entry.get("data") instanceof JsonObject data)) continue;
                        String item = key.get("uuid") instanceof JsonPrimitive u ? u.getAsString()
                            : key.get("id") instanceof JsonPrimitive i ? "id:" + skyblockId(i.getAsString()) : null;
                        if (item == null) continue;
                        for (Map.Entry<String, JsonElement> d : data.entrySet()) {
                            try {
                                Change c = change(item, path(d.getKey()), d.getValue(), null, false);
                                if (c != null) out.add(c);
                            } catch (RuntimeException ex) {
                                Io.failed("Reading a SkyOcean look", ex);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Io.failed("Reading SkyOcean's custom_items.json", e);
            }
        }
        fromFile = List.copyOf(out);
        return fromFile;
    }

    /** For tests: how this source reads ("live" or "file"). */
    String mode() {
        return link() ? "live" : "file";
    }
}
