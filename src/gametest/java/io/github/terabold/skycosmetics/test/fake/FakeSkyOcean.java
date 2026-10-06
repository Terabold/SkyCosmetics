package io.github.terabold.skycosmetics.test.fake;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A stand-in for SkyOcean's item customization, built like SkyOcean 1.17's {@code CustomItems}: a static map from
 * an item key (a UUID key, or a SkyBlock id key for every such item) to the item's data, a map from a component
 * ({@code skyocean:color}...) to its value. {@code save()} only marks it to be written, as SkyOcean's does.
 */
public final class FakeSkyOcean {
    public static final FakeSkyOcean INSTANCE = new FakeSkyOcean();
    private static final Map<Object, Data> map = new LinkedHashMap<>();
    public static int saves;

    public static final Comp<Component> NAME = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "name"));
    public static final Comp<Object> COLOR = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "color"));
    public static final Comp<Object> SKIN = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "skin"));
    public static final Comp<Boolean> GLINT = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "glint"));
    public static final Comp<Object> MODEL = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "model"));
    public static final Comp<Object> TRIM = new Comp<>(Identifier.fromNamespaceAndPath("skyocean", "armor_trim"));

    private FakeSkyOcean() {}

    public void save() {
        saves++;
    }

    /** Empties it, as if SkyOcean had loaded an empty file. */
    public static void reset() {
        map.clear();
        saves = 0;
    }

    /** The data of {@code key}, made if missing. */
    public static Data of(Object key) {
        return map.computeIfAbsent(key, Data::new);
    }

    public static final class Data {
        private final Object key;
        private final Map<Comp<?>, Object> data = new LinkedHashMap<>();

        Data(Object key) {
            this.key = key;
        }

        public Object getKey() {
            return key;
        }

        public Map<Comp<?>, Object> getData() {
            return data;
        }

        public <T> void set(Comp<T> component, T value) {
            if (value == null) data.remove(component);
            else data.put(component, value);
        }

        /** For tests: add without the type check. */
        public Data with(Comp<?> component, Object value) {
            data.put(component, value);
            return this;
        }
    }

    public record Comp<T>(Identifier id) {
        public Identifier getId() {
            return id;
        }
    }

    public static final class UuidKey {
        private final UUID uuid;

        public UuidKey(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUuid() {
            return uuid;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof UuidKey k && k.uuid.equals(uuid);
        }

        @Override
        public int hashCode() {
            return uuid.hashCode();
        }
    }

    /** Every item with this SkyBlock id ("item:zephyr_sword"). */
    public static final class IdKey {
        private final String key;

        public IdKey(String key) {
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IdKey k && k.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    public enum ColorType { STATIC, GRADIENT, SKYBLOCK_DYE, ANIMATED_SKYBLOCK_DYE }

    public enum SkinType { STATIC, SKYBLOCK_SKIN, ANIMATED_SKYBLOCK_SKIN }

    public static final class StaticItemColor {
        private final int colorCode;
        private final ColorType type = ColorType.STATIC;

        public StaticItemColor(int colorCode) {
            this.colorCode = colorCode;
        }
    }

    public static final class SkyBlockDye {
        private final String id;
        private final ColorType type = ColorType.SKYBLOCK_DYE;

        public SkyBlockDye(String id) {
            this.id = id;
        }
    }

    public static final class GradientItemColor {
        private final List<Integer> gradient;
        private final int time;
        private final ColorType type = ColorType.GRADIENT;

        public GradientItemColor(List<Integer> gradient, int time) {
            this.gradient = gradient;
            this.time = time;
        }
    }

    public static final class StaticSkin {
        private final String skin;
        private final SkinType type = SkinType.STATIC;

        public StaticSkin(String skin) {
            this.skin = skin;
        }
    }

    public static final class SkyblockSkin {
        private final String item;
        private final SkinType type = SkinType.SKYBLOCK_SKIN;

        public SkyblockSkin(String item) {
            this.item = item;
        }
    }

    public static final class ArmorTrim {
        private final Identifier trimMaterial;
        private final Identifier trimPattern;

        public ArmorTrim(Identifier trimMaterial, Identifier trimPattern) {
            this.trimMaterial = trimMaterial;
            this.trimPattern = trimPattern;
        }
    }

    public static final class StaticModel {
        private final Identifier location;

        public StaticModel(Identifier location) {
            this.location = location;
        }
    }
}
