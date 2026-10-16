package io.github.terabold.skycosmetics.zoom;

import io.github.terabold.skycosmetics.hub.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.*;

import static io.github.terabold.skycosmetics.zoom.ZoomSettings.*;

/** Uses the same searchable controls and key conflict checks as the rest of the mod menu. */
final class ZoomSection {
    private static String compatibility;
    private ZoomSection() {}
    private static Component text(String s) { return Component.literal(s); }
    private static Profile p() { return primary; }

    static void register() {
        List<String> others = new ArrayList<>();
        for (String id : List.of("zoomify", "ok_zoomer", "wi_zoom", "logical_zoom", "justzoom", "zume", "cinematiczoom")) {
            if (FabricLoader.getInstance().isModLoaded(id)) others.add(id);
        }
        compatibility = others.isEmpty() ? "Optional camera feature. Check key conflicts before enabling another zoom mod."
            : "Other zoom mods detected: " + String.join(", ", others) + ". Disable their zoom or this one to avoid stacking.";
        Hub.add(new Section("zoom", Section.FEATURE, text("Zoom"), text(""),
            text(compatibility), () -> new ItemStack(Items.ENDER_EYE), screen -> rows()));
    }

    private static List<Option> rows() {
        List<Option> r = new ArrayList<>();
        toggle(r, "enabled", "Enable zoom", "Off by default to avoid competing with another mod's zoom.", () -> enabled, v -> { enabled = v; Zoom.reset(); });
        choice(r, "style", "Initial zoom animation", "How the view reaches the starting zoom when you press the key. Independent of mouse movement.",
            new String[]{"Instant", "Linear", "Smooth", "Eased"}, ZoomSection::style, ZoomSection::style);
        slider(r, "inSeconds", "Zoom-in time", "Time to reach the starting zoom.", () -> p().inSeconds, v -> p().inSeconds = v, 0, .5, .01, " s");
        slider(r, "outSeconds", "Zoom-out time", "Time to return to the normal view.", () -> p().outSeconds, v -> p().outSeconds = v, 0, .5, .01, " s");
        toggle(r, "smoothCamera", "Smooth camera", "Smooth mouse movement while zooming. Off gives direct camera control; releasing zoom stops smoothing immediately.",
            () -> p().cinematic != Cinematic.OFF, v -> { p().cinematic = v ? p().smoothStyle : Cinematic.OFF; Zoom.reset(); });
        choice(r, "smoothing", "Smoothing", "Responsive: light smoothing that stops almost as soon as your mouse does. Cinematic: Minecraft's cinematic camera, like OptiFine, Essential and Zoomify zoom: slow and floaty, it keeps gliding after you stop; faster at a higher mouse sensitivity.",
            new Cinematic[]{Cinematic.CUSTOM, Cinematic.VANILLA}, () -> p().cinematic == Cinematic.OFF ? p().smoothStyle : p().cinematic,
            v -> { p().smoothStyle = v; if (p().cinematic != Cinematic.OFF) p().cinematic = v; Zoom.reset(); });
        r.add(Option.of("glide", text("Glide"), text("How long Cinematic keeps gliding after you stop; 100% is Minecraft's own."),
            new Control.Slider(() -> p().glide * 100, v -> p().glide = v / 100, 25, 250, 5,
                v -> text(String.format(Locale.ROOT, "%d%% (%.1f s)", Math.round(v), Zoom.cinematicSeconds(v / 100))))));
        r.add(Option.header("keys", text("Keybinds")));
        key(r, "primaryKey", "Zoom key", "Hold or toggle zoom. Conflicts are shown here; zoom in, out and reset keys are in Minecraft's Controls.", () -> Zoom.primaryKey);
        choice(r, "mode", "Activation", "Hold the key or press it to toggle zoom. Menus and focus loss cancel toggles.", new Mode[]{Mode.HOLD, Mode.TOGGLE}, () -> p().mode, v -> { p().mode = v; Zoom.reset(); });
        r.add(Option.header("behavior", text("Zoom amount")));
        magnification(r, "initial", "Starting zoom", "Optical magnification; 1x is the normal view, up to 1000x. Logarithmic slider; arrow keys adjust finely.", () -> p().initial, v -> p().initial = p().bounded(v));
        toggle(r, "scroll", "Scroll to zoom", "Adjust the zoom with the wheel while zooming.", () -> p().scroll != Scroll.OFF, v -> p().scroll = v ? Scroll.EXPONENTIAL : Scroll.OFF);
        r.add(Option.header("camera", text("Mouse and camera")));
        slider(r, "sensitivityScale", "Mouse sensitivity", "Starts from your usual sensitivity and slows gently as you zoom further. Adjust the overall speed.", () -> p().sensitivityScale,
            v -> { p().sensitivity = Sensitivity.FIXED; p().sensitivityScale = v; }, .01, 2, .01, "x");
        toggle(r, "hideHand", "Hide hand", "Temporarily hide first-person hands and held items.", () -> p().hideHand, v -> p().hideHand = v);
        r.add(Option.header("resetGroup", text("Reset")));
        r.add(Option.of("reset", text("Reset zoom settings"), text("Restores the defaults and disables zoom. Keeps keybinds."),
            new Control.Action(text("Reset"), ZoomSettings::reset, () -> true, Component.empty())));
        return r.stream().map(o -> {
            Option compact = o.compact();
            if (o.id().equals("inSeconds") || o.id().equals("outSeconds")) compact = compact.beside();
            return compact.visibleWhen(() -> relevant(o.id()));
        }).toList();
    }

    private static String style() {
        if (p().inSeconds > 0 && p().inCurve == ZoomTransition.Curve.IN_OUT_SINE) return "Eased";
        if (p().inSeconds > 0 && p().inCurve == ZoomTransition.Curve.LINEAR) return "Linear";
        return p().inSeconds > 0 || p().outSeconds > 0 ? "Smooth" : "Instant";
    }
    private static void style(String value) {
        Profile profile = p();
        boolean instant = value.equals("Instant");
        boolean cinematic = value.equals("Eased");
        profile.inSeconds = instant ? 0 : cinematic ? .14 : .08;
        profile.outSeconds = instant ? 0 : cinematic ? .12 : .08;
        profile.scrollSeconds = instant ? 0 : .12;
        profile.inCurve = profile.outCurve = cinematic ? ZoomTransition.Curve.IN_OUT_SINE
            : value.equals("Linear") ? ZoomTransition.Curve.LINEAR : ZoomTransition.Curve.OUT_CUBIC;
    }

    private static boolean relevant(String id) {
        return switch (id) {
            case "inCurve", "inSeconds", "outSeconds" -> p().inSeconds > 0 || p().outSeconds > 0;
            case "smoothingSeconds" -> p().cinematic == Cinematic.CUSTOM;
            case "smoothing" -> p().cinematic != Cinematic.OFF;
            case "glide" -> p().cinematic == Cinematic.VANILLA;
            case "soundVolume" -> p().sounds;
            case "overlayOpacity" -> p().overlay == Overlay.VIGNETTE;
            case "barColor" -> p().bars > 0;
            case "invertScroll", "scrollStep", "scrollCurve", "scrollSeconds", "modifier" -> p().scroll != Scroll.OFF;
            default -> true;
        };
    }

    private static void toggle(List<Option> r, String id, String title, String help, BooleanSupplier get, Consumer<Boolean> set) {
        r.add(Option.of(id, text(title), text(help), new Control.Toggle(get, set)));
    }
    private static void key(List<Option> r, String id, String title, String help, Supplier<net.minecraft.client.KeyMapping> key) {
        r.add(Option.of(id, text(title), text(help), new Control.Key(key, true)));
    }
    private static void slider(List<Option> r, String id, String title, String help, DoubleSupplier get, DoubleConsumer set, double min, double max, double step, String suffix) {
        r.add(Option.of(id, text(title), text(help), new Control.Slider(get, set, min, max, step, v -> text(String.format(Locale.ROOT, "%.2f%s", v, suffix)))));
    }
    private static void magnification(List<Option> r, String id, String title, String help, DoubleSupplier get, DoubleConsumer set) {
        r.add(Option.of(id, text(title), text(help), new Control.Slider(() -> Math.log10(get.getAsDouble()),
            v -> set.accept(Math.pow(10, v)), 0, 3, .01, v -> {
                // The shared slider rounds its coordinate; display the actual saved magnification.
                double actual = get.getAsDouble();
                double shown = Math.abs(v - Math.log10(actual)) <= .005001 ? actual : Math.pow(10, v);
                return text(String.format(Locale.ROOT, "%.2fx", shown));
            })));
    }
    private static <T> void choice(List<Option> r, String id, String title, String help, T[] values, Supplier<T> get, Consumer<T> set) {
        r.add(Option.of(id, text(title), text(help), new Control.Choice<>(Arrays.asList(values), v -> text(label(v)), get, set)));
    }
    private static String label(Object v) {
        if (!(v instanceof Enum<?> e)) return v.toString();
        String s = e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
