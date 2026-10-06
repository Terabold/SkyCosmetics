package io.github.terabold.skycosmetics.test.fake;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * A stand-in for Skyblocker's config, built like Skyblocker 6.10's: {@code get()} returns the copy it draws with,
 * {@code update()} edits the saved copy, saves it and makes a new copy to draw with. The maps under
 * {@code general} have Skyblocker's names and types.
 */
public final class FakeSkyblocker {
    private static Config saved = new Config();
    private static Config drawn = saved.copy();
    /** How many times the config was saved; the next update throws when {@link #failNext} is set. */
    public static int saves;
    public static boolean failNext;

    private FakeSkyblocker() {}

    public static Config get() {
        return drawn;
    }

    public static void update(Consumer<Config> edit) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("a fake failure");
        }
        edit.accept(saved);
        saves++;
        drawn = saved.copy();
    }

    /** Starts over with {@code fill} applied to an empty config, as if Skyblocker had just loaded it. */
    public static void reset(Consumer<Config> fill) {
        saved = new Config();
        fill.accept(saved);
        drawn = saved.copy();
        saves = 0;
        failNext = false;
    }

    public static final class Config {
        public General general = new General();

        Config copy() {
            Config c = new Config();
            c.general = general.copy();
            return c;
        }
    }

    public static final class General {
        public Object2ObjectOpenHashMap<String, Component> customItemNames = new Object2ObjectOpenHashMap<>();
        public Object2IntOpenHashMap<String> customDyeColors = new Object2IntOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, ArmorTrimId> customArmorTrims = new Object2ObjectOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, AnimatedDye> customAnimatedDyes = new Object2ObjectOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, String> customHelmetTextures = new Object2ObjectOpenHashMap<>();
        public Object2BooleanOpenHashMap<String> customGlint = new Object2BooleanOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, Identifier> customItemModel = new Object2ObjectOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, Identifier> customArmorModel = new Object2ObjectOpenHashMap<>();
        public Object2ObjectOpenHashMap<String, String> customAnimatedHelmetTextures = new Object2ObjectOpenHashMap<>();

        General copy() {
            General g = new General();
            g.customItemNames.putAll(customItemNames);
            g.customDyeColors.putAll(customDyeColors);
            g.customArmorTrims.putAll(customArmorTrims);
            g.customAnimatedDyes.putAll(customAnimatedDyes);
            g.customHelmetTextures.putAll(customHelmetTextures);
            g.customGlint.putAll(customGlint);
            g.customItemModel.putAll(customItemModel);
            g.customArmorModel.putAll(customArmorModel);
            g.customAnimatedHelmetTextures.putAll(customAnimatedHelmetTextures);
            return g;
        }
    }

    public record ArmorTrimId(Identifier material, Identifier pattern) {}

    public record AnimatedDye(List<Keyframe> keyframes, boolean cycleBack, float delay, float duration) {}

    public record Keyframe(int color, float time) {}
}
