package io.github.terabold.skycosmetics.zoom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;

/** Configuration is parsed only on load; camera hooks read primitives. */
public final class ZoomSettings {
    public enum Mode { HOLD, TOGGLE, PERSISTENT }
    public enum Scroll { OFF, EXPONENTIAL, LINEAR }
    public enum Sensitivity { UNCHANGED, RELATIVE, ANGULAR_FOV, FIXED }
    public enum Cinematic { OFF, VANILLA, CUSTOM }
    public enum Overlay { OFF, VIGNETTE }
    public enum Modifier { NONE, SHIFT, CONTROL, ALT }

    public static boolean enabled, thirdPerson = true;
    public static Modifier modifier = Modifier.NONE;
    public static Profile primary = new Profile(), secondary = secondaryDefault();

    public static final class Profile {
        public Mode mode = Mode.HOLD;
        public Scroll scroll = Scroll.EXPONENTIAL;
        public Sensitivity sensitivity = Sensitivity.FIXED;
        public Cinematic cinematic = Cinematic.CUSTOM;
        public Overlay overlay = Overlay.OFF;
        public ZoomTransition.Curve inCurve = ZoomTransition.Curve.OUT_CUBIC;
        public ZoomTransition.Curve outCurve = ZoomTransition.Curve.OUT_CUBIC;
        public ZoomTransition.Curve scrollCurve = ZoomTransition.Curve.OUT_CUBIC;
        public double minimum = 1, maximum = 1000, initial = 5, scrollStep = 1.25;
        public double inSeconds = .08, outSeconds = .08, scrollSeconds = .1;
        public double sensitivityScale = 1, smoothingSeconds = .12, bobbing = 0;
        public double bars = 0, overlayOpacity = .65, soundVolume = .5;
        public int barColor = 0xFF000000;
        public boolean remember, invertScroll, affectHand, hideHand, hideHud, hideCrosshair;
        public boolean indicator, suppressFov, sounds;

        public double bounded(double v) { return Math.clamp(v, minimum, 1000); }
    }

    private ZoomSettings() {}
    private static Profile secondaryDefault() {
        Profile p = new Profile(); p.initial = 8; p.inSeconds = .8; p.outSeconds = .5;
        p.cinematic = Cinematic.CUSTOM; p.bars = .12; p.hideHud = true; p.indicator = false;
        return p;
    }

    public static void reset() {
        enabled = false; thirdPerson = true; modifier = Modifier.NONE;
        primary = new Profile(); secondary = secondaryDefault(); Zoom.forgetLevels();
    }

    public static void preset(Profile p, String name) {
        // Keep one profile's configuration independent of the other.
        JsonObject defaults = writeProfile(new Profile()); readProfile(p, defaults);
        switch (name) {
            case "Instant" -> { p.inSeconds = p.outSeconds = p.scrollSeconds = 0; }
            case "Classic cinematic" -> { p.cinematic = Cinematic.VANILLA; p.initial = 3; p.sensitivity = Sensitivity.RELATIVE; }
            case "Persistent" -> { p.mode = Mode.PERSISTENT; p.initial = 1; p.remember = true; }
            case "Cinematic" -> { p.inSeconds = .8; p.outSeconds = .5; p.cinematic = Cinematic.CUSTOM;
                p.hideHud = true; p.indicator = false; p.bars = .12; p.initial = 8; }
            default -> { }
        }
        Zoom.forgetLevel(p);
    }

    public static void read(JsonElement value) {
        JsonObject o = Settings.obj(value);
        enabled = Settings.bool(o.get("enabled"), enabled);
        thirdPerson = Settings.bool(o.get("thirdPerson"), thirdPerson);
        modifier = enumeration(o.get("modifier"), modifier);
        readProfile(primary, Settings.obj(o.get("primary")));
        readProfile(secondary, Settings.obj(o.get("secondary")));
        if (!o.has("timingVersion")) {
            upgradeStockTiming(primary); upgradeStockTiming(secondary);
        }
        Zoom.forgetLevels();
    }

    public static JsonObject write() {
        JsonObject o = new JsonObject(); o.addProperty("enabled", enabled); o.addProperty("thirdPerson", thirdPerson);
        o.addProperty("timingVersion", 1);
        o.addProperty("modifier", modifier.name());
        o.add("primary", writeProfile(primary)); o.add("secondary", writeProfile(secondary)); return o;
    }

    /** Migrate only the exact timings supplied by older styles; keep individually adjusted durations. */
    private static void upgradeStockTiming(Profile p) {
        if (p.inCurve == ZoomTransition.Curve.OUT_CUBIC && p.outCurve == ZoomTransition.Curve.OUT_CUBIC
            && p.inSeconds == .2 && (p.outSeconds == .5 || p.outSeconds == .2)) {
            p.inSeconds = p.outSeconds = .08;
            if (p.scrollSeconds == .18 || p.scrollSeconds == .12) p.scrollSeconds = .1;
        } else if (p.inCurve == ZoomTransition.Curve.IN_OUT_SINE && p.outCurve == ZoomTransition.Curve.IN_OUT_SINE
            && p.inSeconds == .8 && p.outSeconds == .5) {
            p.inSeconds = .14; p.outSeconds = .12;
        }
    }

    private static void readProfile(Profile p, JsonObject o) {
        p.mode = enumeration(o.get("mode"), p.mode); p.scroll = enumeration(o.get("scroll"), p.scroll);
        p.sensitivity = enumeration(o.get("sensitivity"), p.sensitivity);
        p.cinematic = enumeration(o.get("cinematic"), p.cinematic); p.overlay = enumeration(o.get("overlay"), p.overlay);
        if ("SPYGLASS".equals(Settings.str(o.get("overlay"), ""))) p.overlay = Overlay.OFF;
        p.inCurve = enumeration(o.get("inCurve"), p.inCurve); p.outCurve = enumeration(o.get("outCurve"), p.outCurve);
        p.scrollCurve = enumeration(o.get("scrollCurve"), p.scrollCurve);
        p.minimum = Settings.num(o.get("minimum"), p.minimum, 1, 1000);
        p.maximum = 1000;
        p.initial = p.bounded(Settings.num(o.get("initial"), p.initial, p.minimum, p.maximum));
        p.scrollStep = Settings.num(o.get("scrollStep"), p.scrollStep, 1.01, 5);
        p.inSeconds = Settings.num(o.get("inSeconds"), p.inSeconds, 0, 10);
        p.outSeconds = Settings.num(o.get("outSeconds"), p.outSeconds, 0, 10);
        p.scrollSeconds = Settings.num(o.get("scrollSeconds"), p.scrollSeconds, 0, 10);
        p.sensitivityScale = Settings.num(o.get("sensitivityScale"), p.sensitivityScale, .01, 2);
        p.smoothingSeconds = Settings.num(o.get("smoothingSeconds"), p.smoothingSeconds, .01, 1);
        p.bobbing = Settings.num(o.get("bobbing"), p.bobbing, 0, 1);
        p.bars = Settings.num(o.get("bars"), p.bars, 0, .4);
        p.overlayOpacity = Settings.num(o.get("overlayOpacity"), p.overlayOpacity, 0, 1);
        p.soundVolume = Settings.num(o.get("soundVolume"), p.soundVolume, 0, 1);
        p.barColor = (int) Settings.num(o.get("barColor"), p.barColor, Integer.MIN_VALUE, Integer.MAX_VALUE);
        p.remember = Settings.bool(o.get("remember"), p.remember);
        p.invertScroll = Settings.bool(o.get("invertScroll"), p.invertScroll);
        p.affectHand = Settings.bool(o.get("affectHand"), p.affectHand);
        p.hideHand = Settings.bool(o.get("hideHand"), p.hideHand);
        p.hideHud = Settings.bool(o.get("hideHud"), p.hideHud);
        p.hideCrosshair = Settings.bool(o.get("hideCrosshair"), p.hideCrosshair);
        p.indicator = Settings.bool(o.get("indicator"), p.indicator);
        p.suppressFov = Settings.bool(o.get("suppressFov"), p.suppressFov);
        p.sounds = Settings.bool(o.get("sounds"), p.sounds);
    }

    private static JsonObject writeProfile(Profile p) {
        JsonObject o = new JsonObject();
        // Reflection is confined to saving, never a render or input path. Public fields are the on-disk schema.
        for (var f : Profile.class.getFields()) {
            try {
                Object v = f.get(p);
                if (v instanceof Number n) o.addProperty(f.getName(), n);
                else if (v instanceof Boolean b) o.addProperty(f.getName(), b);
                else if (v instanceof Enum<?> e) o.addProperty(f.getName(), e.name());
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        return o;
    }

    private static <E extends Enum<E>> E enumeration(JsonElement e, E fallback) {
        try { return Enum.valueOf(fallback.getDeclaringClass(), Settings.str(e, fallback.name())); }
        catch (IllegalArgumentException ex) { return fallback; }
    }
}
