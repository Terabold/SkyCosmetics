package io.github.terabold.skycosmetics.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.compat.OtherLooks.Change;
import io.github.terabold.skycosmetics.compat.OtherLooks.Kind;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Skyblocker's item customization: the {@code custom*} maps of its general config, keyed by item UUID.
 *
 * Read from {@code SkyblockerConfigManager.get()}, the config it draws with. A removal goes through
 * {@code SkyblockerConfigManager.update(...)}, the same call Skyblocker's own commands use: it edits the config,
 * saves it and refreshes the copy Skyblocker draws with. If those aren't there (another Skyblocker version), its
 * {@code skyblocker.json} is read instead, read-only.
 */
final class SkyblockerLooks implements OtherLooks.Source {
    static final String MANAGER = "de.hysky.skyblocker.config.SkyblockerConfigManager";

    /** Every map Skyblocker keeps per item, in the order the studio lists them. */
    private record Map_(String field, Kind kind) {}

    private static final Map_[] MAPS = {
        new Map_("customItemNames", Kind.NAME), new Map_("customDyeColors", Kind.DYE),
        new Map_("customAnimatedDyes", Kind.DYE), new Map_("customHelmetTextures", Kind.SKIN),
        new Map_("customAnimatedHelmetTextures", Kind.SKIN), new Map_("customArmorTrims", Kind.TRIM),
        new Map_("customGlint", Kind.GLINT), new Map_("customItemModel", Kind.MODEL),
        new Map_("customArmorModel", Kind.MODEL)};

    private final String managerClass;
    private final Path file;
    private Method get, update;
    private Field general;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    /** 0 not tried yet, 1 linked to the live config, -1 only the file can be read. */
    private int state;
    private long fileStamp = Long.MIN_VALUE;
    private List<Change> fromFile = List.of();

    SkyblockerLooks(String managerClass, Path file) {
        this.managerClass = managerClass;
        this.file = file;
    }

    @Override
    public String name() {
        return "Skyblocker";
    }

    @Override
    public int color() {
        return 0xFF3E8EDE;
    }

    /** Looks up Skyblocker's config classes once; false (for good) when they are not what this build knows. */
    private boolean link() {
        if (state != 0) return state > 0;
        try {
            Class<?> m = Class.forName(managerClass);
            get = m.getMethod("get");
            update = m.getMethod("update", Consumer.class);
            general = get.getReturnType().getField("general");
            for (Map_ spec : MAPS) {
                try {
                    Field f = general.getType().getField(spec.field());
                    if (Map.class.isAssignableFrom(f.getType())) fields.put(spec.field(), f);
                } catch (NoSuchFieldException ignored) {
                    // an older or newer Skyblocker without this one
                }
            }
            state = fields.isEmpty() ? -1 : 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            state = -1;
        }
        if (state < 0) SkyCosmetics.LOG.info("Skyblocker's config isn't one SkyCosmetics knows; its looks are shown read-only");
        return state > 0;
    }

    private Object config() {
        try {
            return get.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null; // not loaded yet: nothing to show this time
        }
    }

    @Override
    public Object stamp() {
        if (!link()) return fileTime();
        Object cfg = config();
        if (cfg == null) return null;
        // Every save makes a new copy to draw with; the sizes catch an edit made in place.
        long sizes = 0;
        try {
            Object gen = general.get(cfg);
            for (Field f : fields.values()) sizes = sizes * 31 + (f.get(gen) instanceof Map<?, ?> m ? m.size() : -1);
        } catch (IllegalAccessException e) {
            return null;
        }
        return List.of(System.identityHashCode(cfg), sizes);
    }

    @Override
    public List<Change> read() {
        if (!link()) return readFile();
        Object cfg = config();
        if (cfg == null) return List.of();
        Object gen;
        try {
            gen = general.get(cfg);
        } catch (IllegalAccessException e) {
            return List.of();
        }
        List<Change> out = new ArrayList<>();
        for (Map_ spec : MAPS) {
            Field f = fields.get(spec.field());
            if (f == null) continue;
            Object m;
            try {
                m = f.get(gen);
            } catch (IllegalAccessException e) {
                continue;
            }
            if (!(m instanceof Map<?, ?> map)) continue;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String uuid) || uuid.isEmpty()) continue;
                try {
                    Change c = change(uuid, spec, e.getValue(), true);
                    if (c != null) out.add(c);
                } catch (RuntimeException ex) {
                    Io.failed("Reading a Skyblocker look", ex); // one odd value, not the whole list
                }
            }
        }
        return out;
    }

    /** One map entry as a change; {@code live} values are Skyblocker's objects, else JSON from its file. */
    private Change change(String uuid, Map_ spec, Object v, boolean live) {
        String field = spec.field();
        return switch (field) {
            case "customItemNames" -> {
                Component name = v instanceof Component c ? c : v instanceof JsonElement j ? component(j) : null;
                if (name == null) yield null;
                yield new Change(this, uuid, Kind.NAME, name.getString(), -1, name, field,
                    l -> l.withName(Names.toCodes(name)), live);
            }
            case "customDyeColors" -> {
                Integer rgb = v instanceof Number n ? Integer.valueOf(n.intValue())
                    : v instanceof JsonPrimitive p && p.isNumber() ? Integer.valueOf(p.getAsInt()) : null;
                if (rgb == null) yield null;
                String hex = Reflect.hex(rgb);
                yield new Change(this, uuid, Kind.DYE, hex, rgb & 0xFFFFFF, null, field, l -> l.withDye(hex), live);
            }
            case "customAnimatedDyes" -> {
                // Skyblocker times its keyframes its own way: shown and removable, not copied.
                List<Integer> colors = keyframes(v);
                yield new Change(this, uuid, Kind.DYE, "Animated, " + Names.count(colors.size(),
                    "color"), colors.isEmpty() ? -1 : colors.getFirst() & 0xFFFFFF, null, field, null, live);
            }
            case "customHelmetTextures" -> {
                String tex = string(v);
                if (tex == null) yield null;
                yield new Change(this, uuid, Kind.SKIN, "Custom texture", -1, null, field,
                    l -> l.withSkin(Textures.CUSTOM_PREFIX + tex), live);
            }
            case "customAnimatedHelmetTextures" -> {
                String id = string(v);
                if (id == null) yield null;
                SkinEntry e = Repo.get().skin(id);
                yield new Change(this, uuid, Kind.SKIN, e != null ? e.name : Reflect.words(id), -1, null, field,
                    e != null ? l -> l.withSkin(id) : null, live);
            }
            case "customArmorTrims" -> {
                Object material = v instanceof JsonObject o ? o.get("material") : Reflect.call(v, "material");
                Object pattern = v instanceof JsonObject o ? o.get("pattern") : Reflect.call(v, "pattern");
                yield new Change(this, uuid, Kind.TRIM, Reflect.words(json(pattern)) + " (" + Reflect.words(json(material)) + ")",
                    -1, null, field, null, live);
            }
            case "customGlint" -> {
                Boolean on = v instanceof Boolean b ? b : v instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
                if (on == null) yield null;
                yield new Change(this, uuid, Kind.GLINT, on ? "On" : "Off", -1, null, field,
                    l -> l.withGlint(on ? "on" : "off"), live);
            }
            case "customItemModel", "customArmorModel" -> new Change(this, uuid, Kind.MODEL,
                (field.equals("customArmorModel") ? "Armor: " : "") + Reflect.words(json(v)), -1, null, field, null, live);
            default -> null;
        };
    }

    private static Object json(Object v) {
        return v instanceof JsonPrimitive p ? p.getAsString() : v;
    }

    private static String string(Object v) {
        Object s = json(v);
        return s instanceof String str && !str.isEmpty() ? str : null;
    }

    /** An animated dye's keyframe colors, from Skyblocker's record or its JSON. */
    private static List<Integer> keyframes(Object v) {
        List<Integer> out = new ArrayList<>();
        Object frames = v instanceof JsonObject o ? o.get("keyframes") : Reflect.call(v, "keyframes");
        if (frames instanceof Iterable<?> it) {
            for (Object k : it) {
                Object c = k instanceof JsonObject o ? o.get("color") : Reflect.call(k, "color");
                if (c instanceof Number n) out.add(n.intValue());
                else if (c instanceof JsonPrimitive p && p.isNumber()) out.add(p.getAsInt());
            }
        }
        return out;
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
        if (c.source() != this || !link() || !(c.handle() instanceof String fieldName)) return false;
        Field f = fields.get(fieldName);
        if (f == null) return false;
        Consumer<Object> edit = cfg -> {
            try {
                if (f.get(general.get(cfg)) instanceof Map<?, ?> m) m.remove(c.item());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            update.invoke(null, edit); // Skyblocker edits, saves, and refreshes the config it draws with
        } catch (IllegalAccessException | InvocationTargetException e) {
            Io.failed("Removing a look in Skyblocker", e);
            return false;
        }
        Object cfg = config();
        try {
            return cfg != null && !(f.get(general.get(cfg)) instanceof Map<?, ?> m && m.containsKey(c.item()));
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ file ---

    private Object fileTime() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Skyblocker's file, read-only: what it saved last. Read again only when the file changes. */
    private List<Change> readFile() {
        long t = (Long) fileTime();
        if (t == fileStamp) return fromFile;
        fileStamp = t;
        List<Change> out = new ArrayList<>();
        if (t != 0L) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = Io.parse(r);
                JsonObject gen = root instanceof JsonObject o && o.get("general") instanceof JsonObject g ? g : null;
                for (Map_ spec : MAPS) {
                    if (gen == null || !(gen.get(spec.field()) instanceof JsonObject m)) continue;
                    for (Map.Entry<String, JsonElement> e : m.entrySet()) {
                        try {
                            Change c = change(e.getKey(), spec, e.getValue(), false);
                            if (c != null) out.add(c);
                        } catch (RuntimeException ex) {
                            Io.failed("Reading a Skyblocker look", ex);
                        }
                    }
                }
            } catch (Exception e) {
                Io.failed("Reading skyblocker.json", e);
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
