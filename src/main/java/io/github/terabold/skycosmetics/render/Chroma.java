package io.github.terabold.skycosmetics.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.hub.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** One shared, bounded palette. Settings changes build it; names and the glint shader only sample it. */
public final class Chroma {
    public static boolean custom, wave = true, reverse;
    public static double speed = 1, saturation = 70, brightness = 100, spread = 5.5;
    public static int count = 2;
    public static final int[] colors = {0xFF40A0, 0x20DDFF, 0xFFD040, 0x80FF80, 0x8080FF, 0xFF8080, 0xFFFFFF, 0x808080};
    private static volatile int[] palette = build();
    private static int version;

    private Chroma() {}

    public static void init() {
        Settings.register("chroma", Chroma::read, Chroma::write);
        Hub.add(new Section("chroma", Section.FEATURE, text("Animated chroma"), Component.empty(),
            text("Choose Rainbow or your own colors. Use the Chroma buttons in Name & glint to apply it to an item."),
            () -> new ItemStack(Items.MAGENTA_DYE), parent -> rows()));
    }

    private static Component text(String s) { return Component.literal(s); }

    private static List<Option> rows() {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.of("preview", text("Shared animated palette"), text("Used by item names, glint and your nametag whenever you choose Chroma. Static gradients are separate."),
            new Control.Custom((host, width) -> new io.github.terabold.skycosmetics.gui.hub.ChromaButton(width, 24, b -> {}))));
        rows.add(Option.of("custom", text("Animated palette"), text("Rainbow cycles through the spectrum. My colors blends your colors in a repeating loop."),
            new Control.Choice<>(List.of(false, true), v -> text(v ? "My colors" : "Rainbow"), () -> custom, v -> { custom = v; changed(); })));
        rows.add(Option.of("wave", text("Color wave"), text("Off gives the whole name or glint one moving color."),
            new Control.Toggle(() -> wave, v -> { wave = v; changed(); })));
        rows.add(Option.of("reverse", text("Reverse"), text("Reverses the movement of the colors."),
            new Control.Toggle(() -> reverse, v -> { reverse = v; changed(); })));
        rows.add(slider("speed", "Color speed", "Separate from glint movement speed. 1x cycles every 2.4 seconds.", () -> speed, v -> speed = v, 0.1, 4, 0.1, "x"));
        rows.add(slider("spread", "Color spread", "How far apart adjacent letters are in the color cycle.", () -> spread, v -> spread = v, 0, 25, 0.5, "%"));
        rows.add(slider("saturation", "Rainbow saturation", "Lower values soften rainbow colors.", () -> saturation, v -> saturation = v, 0, 100, 1, "%").enabledWhen(() -> !custom));
        rows.add(slider("brightness", "Rainbow brightness", "Brightness of the rainbow palette.", () -> brightness, v -> brightness = v, 10, 100, 1, "%").enabledWhen(() -> !custom));
        rows.add(slider("count", "Palette colors", "The first 2–8 colors below form a seamless loop.", () -> count, v -> count = (int) v, 2, 8, 1, "").enabledWhen(() -> custom));
        for (int i = 0; i < colors.length; i++) {
            int at = i;
            rows.add(Option.of("color" + i, text("Color " + (i + 1)), text("Colors blend in order, including the last back to the first."),
                new Control.Color(() -> 0xFF000000 | colors[at], v -> { colors[at] = v & 0xFFFFFF; changed(); }, false))
                .enabledWhen(() -> custom && at < count));
        }
        return rows.stream().map(o -> {
            Option compact = o.control() instanceof Control.Custom ? o : o.beside();
            return compact.visibleWhen(() -> switch (o.id()) {
                case "spread" -> wave;
                case "saturation", "brightness" -> !custom;
                case "count" -> custom;
                default -> !o.id().startsWith("color") || custom && Integer.parseInt(o.id().substring(5)) < count;
            });
        }).toList();
    }

    private static Option slider(String id, String title, String help, DoubleSupplier get, DoubleConsumer set,
                                 double min, double max, double step, String unit) {
        return Option.of(id, text(title), text(help), new Control.Slider(get, v -> { set.accept(v); changed(); },
            min, max, step, v -> text(String.format(Locale.ROOT, step >= 1 ? "%.0f%s" : "%.1f%s", v, unit))));
    }

    public static void changed() {
        palette = build();
        version++;
        Names.invalidateLive();
    }

    private static int[] build() {
        int[] out = new int[512];
        for (int i = 0; i < out.length; i++) {
            float p = i / (float) out.length;
            if (!custom) out[i] = Mth.hsvToRgb(p, (float) saturation / 100, (float) brightness / 100) & 0xFFFFFF;
            else {
                float n = p * count;
                int a = (int) n, c = colors[a % count], d = colors[(a + 1) % count];
                float t = n - a;
                int rgb = 0;
                for (int shift = 0; shift < 24; shift += 8) rgb |= Math.round((c >>> shift & 255) * (1 - t) + (d >>> shift & 255) * t) << shift;
                out[i] = rgb;
            }
        }
        return out;
    }

    public static int color(double phase) {
        int[] current = palette;
        return current[(int) ((phase - Math.floor(phase)) * current.length) % current.length];
    }

    public static int nameColor(int index) {
        double phase = Util.getMillis() / 2400.0 * speed * (reverse ? 1 : -1);
        return color(phase + (wave ? index * spread / 100 : 0));
    }

    public static int version() { return version; }

    public static JsonObject write() {
        JsonObject o = new JsonObject();
        o.addProperty("custom", custom); o.addProperty("wave", wave); o.addProperty("reverse", reverse);
        o.addProperty("speed", speed); o.addProperty("spread", spread);
        o.addProperty("saturation", saturation); o.addProperty("brightness", brightness); o.addProperty("count", count);
        JsonArray stops = new JsonArray();
        for (int c : colors) stops.add(String.format(Locale.ROOT, "%06X", c));
        o.add("colors", stops);
        return o;
    }

    public static void read(JsonElement value) {
        JsonObject o = Settings.obj(value);
        custom = Settings.bool(o.get("custom"), custom); wave = Settings.bool(o.get("wave"), wave); reverse = Settings.bool(o.get("reverse"), reverse);
        speed = Settings.num(o.get("speed"), speed, 0.1, 4); spread = Settings.num(o.get("spread"), spread, 0, 25);
        saturation = Settings.num(o.get("saturation"), saturation, 0, 100); brightness = Settings.num(o.get("brightness"), brightness, 10, 100);
        count = (int) Settings.num(o.get("count"), count, 2, 8);
        if (o.get("colors") instanceof JsonArray a) for (int i = 0; i < Math.min(colors.length, a.size()); i++) {
            String s = Settings.str(a.get(i), "");
            if (s.matches("[0-9a-fA-F]{6}")) colors[i] = Integer.parseInt(s, 16);
        }
        changed();
    }
}
