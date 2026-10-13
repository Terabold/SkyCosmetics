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
    private static boolean editSecondary;
    private static String preset = "Smooth";
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
            new String[]{"Instant", "Linear", "Smooth", "Cinematic"}, ZoomSection::style, ZoomSection::style);
        slider(r, "inSeconds", "Zoom-in time", "Time to reach the starting zoom.", () -> p().inSeconds, v -> p().inSeconds = v, 0, .5, .01, " s");
        slider(r, "outSeconds", "Zoom-out time", "Time to return to the normal view.", () -> p().outSeconds, v -> p().outSeconds = v, 0, .5, .01, " s");
        toggle(r, "smoothCamera", "Smooth camera", "Smooth mouse movement while zooming. Off gives direct camera control; releasing zoom stops smoothing immediately.",
            () -> p().cinematic != Cinematic.OFF, v -> { p().cinematic = v ? Cinematic.CUSTOM : Cinematic.OFF; Zoom.reset(); });
        r.add(Option.header("keys", text("Keybinds")));
        key(r, "primaryKey", "Zoom key", "Hold or toggle the primary profile. Conflicts are shown here.", () -> Zoom.primaryKey);
        key(r, "secondaryKey", "Secondary zoom key", "An independent profile; takes priority while active.", () -> Zoom.secondaryKey);
        key(r, "inKey", "Zoom in key", "Optional alternative to the mouse wheel.", () -> Zoom.inKey);
        key(r, "outKey", "Zoom out key", "Optional alternative to the mouse wheel.", () -> Zoom.outKey);
        key(r, "resetKey", "Reset zoom key", "Return to this profile's starting magnification. Can be a mouse button; avoid conflicting game actions.", () -> Zoom.resetKey);
        r.add(Option.header("global", text("Global controls")));
        toggle(r, "thirdPerson", "Allow third person", "Use zoom in first and third person.", () -> thirdPerson, v -> thirdPerson = v);
        choice(r, "modifier", "Wheel modifier", "Only capture scrolling with this modifier. Other scrolling keeps its normal behavior.", Modifier.values(), () -> modifier, v -> modifier = v);
        r.add(Option.header("profileSettings", text("Profile settings")));
        choice(r, "profile", "Edit profile", "Every setting below belongs to the selected profile.", new String[]{"Primary", "Secondary"}, () -> editSecondary ? "Secondary" : "Primary", v -> editSecondary = v.equals("Secondary"));
        choice(r, "preset", "Preset", "Choose a starting point, then press Apply. Only the selected profile changes.", new String[]{"Smooth", "Instant", "Classic cinematic", "Persistent", "Cinematic"}, () -> preset, v -> preset = v);
        r.add(Option.of("applyPreset", text("Apply preset"), text("Replaces the selected profile's settings; keeps your keybinds."),
            new Control.Action(text("Apply"), () -> ZoomSettings.preset(p(), preset), () -> true, Component.empty())));
        r.add(Option.header("behavior", text("Zoom amount")));
        choice(r, "mode", "Activation", "Hold the key or press it to toggle zoom. Menus and focus loss cancel toggles.", new Mode[]{Mode.HOLD, Mode.TOGGLE}, () -> p().mode, v -> { p().mode = v; Zoom.reset(); });
        magnification(r, "initial", "Starting zoom", "Optical magnification; 1x is the normal view. Logarithmic slider; arrow keys adjust finely.", () -> p().initial, v -> p().initial = p().bounded(v));
        magnification(r, "minimum", "Minimum zoom", "Lower limit for wheel and key adjustments.", () -> p().minimum, v -> { p().minimum = Math.min(v, p().maximum); p().initial = p().bounded(p().initial); });
        magnification(r, "maximum", "Maximum zoom", "Upper limit. Very high magnification exposes normal chunk and entity rendering limits.", () -> p().maximum, v -> { p().maximum = Math.max(v, p().minimum); p().initial = p().bounded(p().initial); });
        toggle(r, "remember", "Remember zoom level", "Keep wheel adjustments between uses during this game session.", () -> p().remember, v -> p().remember = v);
        r.add(Option.header("scrolling", text("Scroll adjustment")));
        toggle(r, "scroll", "Scroll to zoom", "Adjust the zoom with the wheel while zooming.", () -> p().scroll != Scroll.OFF, v -> p().scroll = v ? Scroll.EXPONENTIAL : Scroll.OFF);
        slider(r, "scrollStep", "Scroll speed", "Higher values change the zoom more with each wheel notch.", () -> p().scrollStep, v -> p().scrollStep = v, 1.01, 5, .01, "");
        toggle(r, "invertScroll", "Invert zoom wheel", "Reverse the direction used to zoom in and out.", () -> p().invertScroll, v -> p().invertScroll = v);
        r.add(Option.header("transitions", text("Transitions")));
        curve(r, "inCurve", "Zoom in curve", () -> p().inCurve, v -> p().inCurve = v);
        curve(r, "outCurve", "Zoom out curve", () -> p().outCurve, v -> p().outCurve = v);
        curve(r, "scrollCurve", "Wheel curve", () -> p().scrollCurve, v -> p().scrollCurve = v);
        slider(r, "scrollSeconds", "Wheel duration", "Smooth wheel adjustments independently from pressing the zoom key.", () -> p().scrollSeconds, v -> p().scrollSeconds = v, 0, 10, .01, " s");
        r.add(Option.header("camera", text("Mouse and camera")));
        choice(r, "sensitivity", "Sensitivity scaling", "Relative scales by magnification; Angular FOV follows the FOV ratio. Fixed uses only the multiplier; Unchanged keeps normal input.", Sensitivity.values(), () -> p().sensitivity, v -> p().sensitivity = v);
        slider(r, "sensitivityScale", "Mouse sensitivity", "Starts from your usual sensitivity and slows gently as you zoom further. Adjust the overall speed.", () -> p().sensitivityScale,
            v -> { p().sensitivity = Sensitivity.FIXED; p().sensitivityScale = v; }, .01, 2, .01, "x");
        choice(r, "cinematic", "Cinematic camera", "Off is direct; Vanilla uses Minecraft's cinematic filter; Custom smooths angular velocity using elapsed time.", Cinematic.values(), () -> p().cinematic, v -> p().cinematic = v);
        slider(r, "smoothingSeconds", "Camera smoothing", "Higher values add more camera lag. Applies only to Custom cinematic camera.", () -> p().smoothingSeconds, v -> p().smoothingSeconds = v, .01, 1, .01, " s");
        slider(r, "bobbing", "View bobbing", "0 removes bobbing while zoomed; 1 keeps Minecraft's normal bobbing.", () -> p().bobbing, v -> p().bobbing = v, 0, 1, .01, "x");
        toggle(r, "suppressFov", "Suppress dynamic FOV", "Reduce movement and item-use FOV changes while zoomed. Fluid and death effects are preserved.", () -> p().suppressFov, v -> p().suppressFov = v);
        toggle(r, "affectHand", "Zoom held item", "Apply the zoom to the first-person hand projection too.", () -> p().affectHand, v -> p().affectHand = v);
        toggle(r, "hideHand", "Hide hand", "Temporarily hide first-person hands and held items.", () -> p().hideHand, v -> p().hideHand = v);
        r.add(Option.header("appearance", text("HUD & effects")));
        toggle(r, "hideHud", "Hide HUD", "Hide the game HUD while zoomed; zoom overlays can remain visible.", () -> p().hideHud, v -> p().hideHud = v);
        toggle(r, "hideCrosshair", "Hide crosshair", "Hide only the crosshair while zoomed.", () -> p().hideCrosshair, v -> p().hideCrosshair = v);
        toggle(r, "indicator", "Show zoom level", "Show the magnification below the crosshair. Turn off to remove the on-screen text.", () -> p().indicator, v -> p().indicator = v);
        toggle(r, "sounds", "Zoom sounds", "Play a quiet click when entering and leaving zoom.", () -> p().sounds, v -> p().sounds = v);
        slider(r, "soundVolume", "Zoom sound volume", "Volume of optional zoom sounds.", () -> p().soundVolume, v -> p().soundVolume = v, 0, 1, .01, "");
        choice(r, "overlay", "Zoom overlay", "Optional dark vignette around the edges.", Overlay.values(), () -> p().overlay, v -> p().overlay = v);
        slider(r, "overlayOpacity", "Vignette opacity", "Darkness at the edges of the vignette.", () -> p().overlayOpacity, v -> p().overlayOpacity = v, 0, 1, .01, "");
        slider(r, "bars", "Cinematic bars", "Height of each bar as a fraction of the screen; animates with the zoom.", () -> p().bars, v -> p().bars = v, 0, .4, .01, "");
        r.add(Option.of("barColor", text("Bar color"), text("Color and opacity of cinematic bars."), new Control.Color(() -> p().barColor, v -> p().barColor = v, true)));
        r.add(Option.header("resetGroup", text("Reset")));
        r.add(Option.of("reset", text("Reset zoom settings"), text("Restores both profiles and disables zoom. Keeps keybinds."),
            new Control.Action(text("Reset"), ZoomSettings::reset, () -> true, Component.empty())));
        var release = java.util.Set.of("enabled", "style", "inSeconds", "outSeconds", "keys", "primaryKey", "behavior", "mode", "initial", "scroll", "camera", "smoothCamera", "hideHand", "sensitivityScale", "resetGroup", "reset");
        var basic = r.stream().filter(o -> release.contains(o.id())).map(o -> {
            Option configured = switch (o.id()) {
                case "smoothingSeconds" -> o.enabledWhen(() -> p().cinematic == Cinematic.CUSTOM);
                case "soundVolume" -> o.enabledWhen(() -> p().sounds);
                case "overlayOpacity" -> o.enabledWhen(() -> p().overlay == Overlay.VIGNETTE);
                case "barColor" -> o.enabledWhen(() -> p().bars > 0);
                case "invertScroll" -> o.enabledWhen(() -> p().scroll != Scroll.OFF);
                default -> o;
            };
            Option compact = configured.compact();
            if (o.id().equals("inSeconds") || o.id().equals("outSeconds")) compact = compact.beside();
            return compact.visibleWhen(() -> relevant(o.id()));
        }).toList();
        return basic;
    }

    private static String style() {
        if (p().inSeconds > 0 && p().inCurve == ZoomTransition.Curve.IN_OUT_SINE) return "Cinematic";
        if (p().inSeconds > 0 && p().inCurve == ZoomTransition.Curve.LINEAR) return "Linear";
        return p().inSeconds > 0 || p().outSeconds > 0 ? "Smooth" : "Instant";
    }
    private static void style(String value) {
        Profile profile = p();
        boolean instant = value.equals("Instant");
        boolean cinematic = value.equals("Cinematic");
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
        if (v == Scroll.EXPONENTIAL) return "Proportional steps";
        if (v == Scroll.LINEAR) return "Fixed steps";
        if (!(v instanceof Enum<?> e)) return v.toString();
        String s = e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static void curve(List<Option> r, String id, String title, Supplier<ZoomTransition.Curve> get, Consumer<ZoomTransition.Curve> set) {
        choice(r, id, title, "Instant, linear, or sine/quadratic/cubic/exponential easing. Timed in seconds, independent of frame rate.", ZoomTransition.Curve.values(), get, set);
    }
}
