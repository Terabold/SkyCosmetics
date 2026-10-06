package io.github.terabold.skycosmetics.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.compat.OtherLooks.Change;
import io.github.terabold.skycosmetics.compat.OtherLooks.Kind;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
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
    private static final String ANIMATED_HEADS = "de.hysky.skyblocker.skyblock.item.custom.CustomAnimatedHelmetTextures";

    /** One map Skyblocker keeps per item. */
    private record Spec(String field, Kind kind) {}

    /** Every map, in the order the studio lists an item's changes. */
    private static final Spec[] MAPS = {
        new Spec("customHelmetTextures", Kind.SKIN), new Spec("customAnimatedHelmetTextures", Kind.SKIN),
        new Spec("customDyeColors", Kind.DYE), new Spec("customAnimatedDyes", Kind.DYE),
        new Spec("customItemNames", Kind.NAME), new Spec("customGlint", Kind.GLINT),
        new Spec("customArmorTrims", Kind.TRIM), new Spec("customItemModel", Kind.MODEL),
        new Spec("customArmorModel", Kind.MODEL)};

    /** What a change needs to be removed and put back: its map, and the value it had. */
    private record Handle(String field, Object value) {}

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
            for (Spec spec : MAPS) {
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

    private Object generalConfig() {
        try {
            Object cfg = get.invoke(null);
            return cfg == null ? null : general.get(cfg);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null; // not loaded yet: nothing to show this time
        }
    }

    private Map<?, ?> map(Object gen, Field f) {
        try {
            return gen != null && f.get(gen) instanceof Map<?, ?> m ? m : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Override
    public Object stamp() {
        if (!link()) return fileTime();
        Object gen = generalConfig();
        if (gen == null) return null;
        // Every save makes a new copy to draw with; the sizes catch an edit made in place.
        long sizes = 0;
        for (Field f : fields.values()) {
            Map<?, ?> m = map(gen, f);
            sizes = sizes * 31 + (m == null ? -1 : m.size());
        }
        return List.of(System.identityHashCode(gen), sizes);
    }

    @Override
    public List<Change> read() {
        if (!link()) return readFile();
        Object gen = generalConfig();
        if (gen == null) return List.of();
        List<Change> out = new ArrayList<>();
        for (Spec spec : MAPS) {
            Field f = fields.get(spec.field());
            Map<?, ?> map = f == null ? null : map(gen, f);
            if (map == null) continue;
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
    private Change change(String uuid, Spec spec, Object v, boolean live) {
        String field = spec.field();
        Handle h = new Handle(field, v);
        return switch (field) {
            case "customItemNames" -> {
                Component name = v instanceof Component c ? c : v instanceof JsonElement j ? component(j) : null;
                if (name == null) yield null;
                String codes = Names.toCodes(name);
                yield new Change(this, uuid, Kind.NAME, name.getString(), -1, name, h, l -> l.withName(codes), live);
            }
            case "customDyeColors" -> {
                Integer rgb = v instanceof Number n ? Integer.valueOf(n.intValue())
                    : v instanceof JsonPrimitive p && p.isNumber() ? Integer.valueOf(p.getAsInt()) : null;
                if (rgb == null) yield null;
                String hex = Reflect.hex(rgb);
                yield new Change(this, uuid, Kind.DYE, hex, rgb & 0xFFFFFF, null, h, l -> l.withDye(hex), live);
            }
            case "customAnimatedDyes" -> animatedDye(uuid, v, h, live);
            case "customHelmetTextures" -> {
                String tex = string(v);
                if (tex == null) yield null;
                SkinEntry known = Values.skinByTexture(tex);
                String id = known != null ? known.id : Textures.CUSTOM_PREFIX + tex;
                yield new Change(this, uuid, Kind.SKIN, known != null ? known.name : "Custom texture", -1, null, h,
                    l -> l.withSkin(id), live);
            }
            case "customAnimatedHelmetTextures" -> {
                String id = string(v);
                if (id == null) yield null;
                SkinEntry e = Repo.get().skin(id);
                yield new Change(this, uuid, Kind.SKIN, e != null ? e.name : headName(id), -1, null, h,
                    e != null ? l -> l.withSkin(id) : null, live);
            }
            case "customArmorTrims" -> {
                Object material = v instanceof JsonObject o ? o.get("material") : Reflect.call(v, "material");
                Object pattern = v instanceof JsonObject o ? o.get("pattern") : Reflect.call(v, "pattern");
                String text = pattern != null && material != null
                    ? Reflect.words(json(pattern)) + " · " + Reflect.words(json(material)) : "Custom";
                yield new Change(this, uuid, Kind.TRIM, text, -1, null, h, null, live);
            }
            case "customGlint" -> {
                Boolean on = v instanceof Boolean b ? b : v instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
                if (on == null) yield null;
                yield new Change(this, uuid, Kind.GLINT, on ? "On" : "Off", -1, null, h,
                    l -> l.withGlint(on ? "on" : "off"), live);
            }
            case "customItemModel", "customArmorModel" -> new Change(this, uuid, Kind.MODEL,
                (field.equals("customArmorModel") ? "Worn: " : "") + Reflect.words(json(v)), -1, null, h, null, live);
            default -> null;
        };
    }

    /** Skyblocker's animated dye: keyframes it blends over a duration, maybe forth and back. */
    private Change animatedDye(String uuid, Object v, Handle h, boolean live) {
        Object frames = v instanceof JsonObject o ? o.get("keyframes") : Reflect.call(v, "keyframes");
        List<Integer> colors = new ArrayList<>();
        List<Float> times = new ArrayList<>();
        if (frames instanceof Iterable<?> it) {
            for (Object k : it) {
                Object c = k instanceof JsonObject o ? o.get("color") : Reflect.call(k, "color");
                Object t = k instanceof JsonObject o ? o.get("time") : Reflect.call(k, "time");
                Number cn = c instanceof JsonPrimitive p && p.isNumber() ? p.getAsNumber() : c instanceof Number n ? n : null;
                Number tn = t instanceof JsonPrimitive p && p.isNumber() ? p.getAsNumber() : t instanceof Number n ? n : null;
                if (cn == null) continue;
                colors.add(cn.intValue());
                times.add(tn == null ? colors.size() : tn.floatValue());
            }
        }
        Object back = v instanceof JsonObject o ? o.get("cycleBack") : Reflect.call(v, "cycleBack");
        Object duration = v instanceof JsonObject o ? o.get("duration") : Reflect.call(v, "duration");
        boolean b = back instanceof Boolean x ? x : back instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
        float secs = duration instanceof Number n ? n.floatValue()
            : duration instanceof JsonPrimitive p && p.isNumber() ? p.getAsFloat() : 2;
        int[] rgbs = colors.stream().mapToInt(Integer::intValue).toArray();
        float[] at = new float[times.size()];
        for (int i = 0; i < at.length; i++) at[i] = times.get(i);
        String id = Values.animatedDye(rgbs, at, b, secs);
        return new Change(this, uuid, Kind.DYE, "Animated · " + Names.count(rgbs.length, "color"),
            rgbs.length == 0 ? -1 : rgbs[0] & 0xFFFFFF, null, h, id != null ? l -> l.withDye(id) : null, live);
    }

    /** Skyblocker's own name for one of its animated heads, else the id in words. */
    private static String headName(String id) {
        try {
            Class<?> c = Class.forName(ANIMATED_HEADS);
            Method m = Reflect.method(c, "formatName", String.class);
            if (m != null && m.invoke(null, id) instanceof String s && !s.isBlank()) return s;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // read-only or another version: the id will do
        }
        return Reflect.words(id);
    }

    private static Object json(Object v) {
        return v instanceof JsonPrimitive p ? p.getAsString() : v;
    }

    private static String string(Object v) {
        Object s = json(v);
        return s instanceof String str && !str.isEmpty() ? str : null;
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
        Field f = fields.get(h.field());
        if (f == null) return false;
        edit(f, m -> m.remove(c.item()));
        Map<?, ?> m = map(generalConfig(), f);
        return m != null && !m.containsKey(c.item());
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean restore(Change c) {
        if (c.source() != this || !link() || !(c.handle() instanceof Handle h) || h.value() == null) return false;
        Field f = fields.get(h.field());
        if (f == null) return false;
        edit(f, m -> ((Map<Object, Object>) m).put(c.item(), h.value()));
        Map<?, ?> m = map(generalConfig(), f);
        return m != null && m.containsKey(c.item());
    }

    /** Skyblocker edits its config, saves it, and refreshes the copy it draws with: one call, like its commands. */
    private void edit(Field f, Consumer<Map<?, ?>> change) {
        Consumer<Object> edit = cfg -> {
            try {
                if (f.get(general.get(cfg)) instanceof Map<?, ?> m) change.accept(m);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            update.invoke(null, edit);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Io.failed("Changing a look in Skyblocker", e);
        }
    }

    // ------------------------------------------------------------ file ---

    private long fileTime() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Skyblocker's file, read-only: what it saved last. Read again only when the file changes. */
    private List<Change> readFile() {
        long t = fileTime();
        if (t == fileStamp) return fromFile;
        fileStamp = t;
        List<Change> out = new ArrayList<>();
        if (t != 0L) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = Io.parse(r);
                JsonObject gen = root instanceof JsonObject o && o.get("general") instanceof JsonObject g ? g : null;
                for (Spec spec : MAPS) {
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
