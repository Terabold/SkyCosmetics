package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Hand setups from elsewhere: other mods' viewmodel settings and share codes. Other mods' files are only read,
 * never written, and every value read is clamped like a hand-edited settings.json.
 *
 * Each mod draws its pose a little differently; the conversions below keep what you see, with the notes where it
 * can only be close: Devonian turns around your eye (the View pivot), NoFrills times swings in ticks and
 * NoammAddons in e-based steps (both converted to a speed for 6-tick items, Minecraft's default).
 */
public final class HandImport {
    /** A mod whose viewmodel settings can be imported. */
    public enum Source {
        DEVONIAN("devonian", "Devonian", "devonianConfig.json"),
        NOFRILLS("nofrills", "NoFrills", "NoFrills/Configuration.json"),
        NOAMM("noammaddons", "NoammAddons", "NoammAddons/config.json");

        public final String modId, name, file;

        Source(String modId, String name, String file) {
            this.modId = modId;
            this.name = name;
            this.file = file;
        }
    }

    /** What a mod's config says: the setup it amounts to, and whether its viewmodel is switched on there. */
    public record Found(Source source, JsonObject setup, boolean on) {}

    static final String CODE_PREFIX = "skyhand1:";
    private static final int MAX_CODE = 32 * 1024, MAX_JSON = 256 * 1024;
    /** A mod's config bigger than this is not read (they are a few hundred KB at most). */
    private static final long MAX_FILE = 8L * 1024 * 1024;

    private HandImport() {}

    // ------------------------------------------------------------ mods ---

    /** Reads the mod's config from the config folder; null when it isn't there or has no viewmodel settings. */
    public static Found find(Source s) {
        return find(s, FabricLoader.getInstance().getConfigDir());
    }

    public static Found find(Source s, Path configDir) {
        Path f = configDir.resolve(s.file);
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_FILE) return null;
            String text = Files.readString(f, StandardCharsets.UTF_8);
            return parse(s, JsonParser.parseString(text));
        } catch (Exception e) {
            // Another mod's file mid-write or in a format we don't know: no import offered, nothing logged loudly.
            SkyCosmeticsLog.debug("Could not read " + f + ": " + e);
            return null;
        }
    }

    /** The conversion alone, for tests and for files already read. */
    public static Found parse(Source s, JsonElement root) {
        if (!(root instanceof JsonObject o)) return null;
        return switch (s) {
            case DEVONIAN -> devonian(o);
            case NOFRILLS -> nofrills(o);
            case NOAMM -> noamm(o);
        };
    }

    /**
     * {@code config.itemAnimations$*}: offsets as given, then yaw/pitch/roll around the eye (Devonian turns before
     * the hand is placed, so the View pivot), size 2^scale, speed 2^swingSpeed. "No Swing" for the Terminator
     * becomes a Terminator rule.
     */
    private static Found devonian(JsonObject root) {
        JsonObject c = Settings.obj(root.get("config"));
        String p = "itemAnimations$";
        if (!c.has("itemAnimations") && !c.has(p + "xOffset")) return null;
        boolean noSwing = Settings.bool(c.get(p + "noSwing"), false);
        HandPose.Swing swing = noSwing ? HandPose.Swing.NONE
            : Settings.bool(c.get(p + "inplaceSwing"), false) ? HandPose.Swing.IN_PLACE : HandPose.Swing.MOVING;
        HandPose pose = HandPose.of(HandPose.Pivot.VIEW, swing,
            num(c, p + "xOffset", 0), num(c, p + "yOffset", 0), num(c, p + "zOffset", 0),
            num(c, p + "pitchOffset", 0), num(c, p + "yawOffset", 0), num(c, p + "rollOffset", 0),
            pow2(num(c, p + "scale", 0)), 1, 1, 1, 1, 1, 1, pow2(num(c, p + "swingSpeed", 0)));
        JsonObject setup = base(pose);
        setup.addProperty("ignoreEffects", Settings.bool(c.get(p + "ignoreEffects"), false));
        setup.addProperty("equip", Settings.bool(c.get(p + "noReequipReset"), false) ? "off" : "normal");
        setup.addProperty("sway", Settings.bool(c.get(p + "noHandSway"), false) ? 0 : 1);
        setup.addProperty("emptyHandAndMaps", Settings.bool(c.get(p + "changeHand"), true)
            || Settings.bool(c.get(p + "changeMap"), true));
        if (!noSwing && Settings.bool(c.get(p + "noSwingTerm"), false)) itemRule(setup, "TERMINATOR", "Terminator",
            pose.with(HandPose.Swing.NONE));
        return new Found(Source.DEVONIAN, setup, Settings.bool(c.get("itemAnimations"), false));
    }

    /**
     * {@code viewmodel}: offsets, rotation and per-axis scale at the grip, as SkyCosmetics' Item pivot; swing
     * distances as given; speed in ticks (0 = the item's own) as 6 / ticks. "No Bow Swing" becomes a bow rule.
     */
    private static Found nofrills(JsonObject root) {
        if (!(root.get("viewmodel") instanceof JsonObject v)) return null;
        int ticks = (int) num(v, "speed", 0);
        HandPose pose = HandPose.of(HandPose.Pivot.ITEM, HandPose.Swing.MOVING,
            num(v, "offsetX", 0), num(v, "offsetY", 0), num(v, "offsetZ", 0),
            num(v, "rotX", 0), num(v, "rotY", 0), num(v, "rotZ", 0),
            1, num(v, "scaleX", 1), num(v, "scaleY", 1), num(v, "scaleZ", 1),
            num(v, "swingX", 1), num(v, "swingY", 1), num(v, "swingZ", 1),
            ticks > 0 ? 6f / ticks : 1);
        JsonObject setup = base(pose);
        setup.addProperty("ignoreEffects", Settings.bool(v.get("noHaste"), false));
        setup.addProperty("equip", Settings.bool(v.get("noEquipAnim"), false) ? "off" : "normal");
        setup.addProperty("fullSwings", Settings.bool(v.get("noSwingReset"), false));
        setup.addProperty("emptyHandAndMaps", Settings.bool(v.get("applyToHand"), true));
        if (Settings.bool(v.get("noBowSwing"), false)) categoryRule(setup, HandCategory.BOW, pose.with(HandPose.Swing.NONE));
        return new Found(Source.NOFRILLS, setup, Settings.bool(v.get("enabled"), false));
    }

    /**
     * The "Animations" module: size 1 + Item Scale, offsets, rotation at the grip, swing distances, speed e^s (its
     * duration is 6e^-s). "Disable swing animation" with "Terminator Only" becomes a Terminator rule.
     */
    private static Found noamm(JsonObject root) {
        if (!(root.get("config") instanceof JsonArray list)) return null;
        JsonObject module = null;
        for (JsonElement e : list) {
            if (e instanceof JsonObject m && "Animations".equals(Settings.str(m.get("name"), ""))) module = m;
        }
        if (module == null) return null;
        JsonObject s = Settings.obj(module.get("configSettings"));
        boolean noSwing = Settings.bool(s.get("Disable swing animation"), false);
        boolean termOnly = Settings.bool(s.get("Terminator Only"), false);
        HandPose pose = HandPose.of(HandPose.Pivot.ITEM, noSwing && !termOnly ? HandPose.Swing.NONE : HandPose.Swing.MOVING,
            num(s, "X", 0), num(s, "Y", 0), num(s, "Z", 0),
            num(s, "Rotation X", 0), num(s, "Rotation Y", 0), num(s, "Rotation Z", 0),
            1 + num(s, "Item Scale", 0), 1, 1, 1,
            num(s, "swingX", 1), num(s, "swingY", 1), num(s, "swingZ", 1),
            (float) Math.exp(num(s, "Swing Speed", 0)));
        JsonObject setup = base(pose);
        setup.addProperty("ignoreEffects", Settings.bool(s.get("Ignore Haste"), false));
        setup.addProperty("equip", Settings.bool(s.get("Disable equip animation"), false) ? "off" : "normal");
        setup.addProperty("sway", Settings.bool(s.get("Disable hand movement"), false) ? 0 : 1);
        if (noSwing && termOnly) itemRule(setup, "TERMINATOR", "Terminator", pose.with(HandPose.Swing.NONE));
        return new Found(Source.NOAMM, setup, Settings.bool(module.get("enabled"), false));
    }

    private static JsonObject base(HandPose pose) {
        JsonObject setup = new JsonObject();
        setup.add("pose", pose.toJson());
        setup.add("rules", new JsonObject());
        return setup;
    }

    private static void itemRule(JsonObject setup, String id, String name, HandPose pose) {
        JsonObject r = new JsonObject();
        r.addProperty("name", name);
        r.add("pose", pose.toJson());
        setup.getAsJsonObject("rules").add(Hand.ITEM + id, r);
    }

    private static void categoryRule(JsonObject setup, HandCategory c, HandPose pose) {
        JsonObject r = new JsonObject();
        r.add("pose", pose.toJson());
        setup.getAsJsonObject("rules").add(c.key, r);
    }

    /** A finite number, else {@code fallback}: other mods' files may hold strings, nulls or NaN. */
    private static float num(JsonObject o, String k, float fallback) {
        if (!(o.get(k) instanceof JsonPrimitive p) || !p.isNumber()) return fallback;
        double d = p.getAsDouble();
        return Double.isFinite(d) ? (float) d : fallback;
    }

    private static float pow2(float x) {
        return (float) Math.pow(2, Math.clamp(x, -8, 8));
    }

    // ---------------------------------------------------------- codes ---

    /** The setup as a line of text to paste anywhere: a prefix, then the compressed JSON in URL-safe Base64. */
    public static String code(JsonObject setup) {
        byte[] json = setup.toString().getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(json);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } finally {
            d.end();
        }
    }

    /**
     * A setup from a pasted code, or null when it isn't one. Codes come from other players, so the size is capped
     * before and after inflating, and the setup is read like any settings file (clamped, rules capped).
     */
    public static JsonObject decode(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (!t.startsWith(CODE_PREFIX) || t.length() > MAX_CODE) return null;
        Inflater inf = new Inflater();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(t.substring(CODE_PREFIX.length()).replaceAll("\\s", ""));
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null;
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON) return null;
            }
            JsonElement e = JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
            return e instanceof JsonObject o ? o : null;
        } catch (IllegalArgumentException | DataFormatException | com.google.gson.JsonParseException e) {
            return null;
        } catch (RuntimeException e) {
            Io.failed("Reading a hand share code", e);
            return null;
        } finally {
            inf.end();
        }
    }

    /** Quiet logging for other mods' files: only with debug logging on. */
    private static final class SkyCosmeticsLog {
        static void debug(String msg) {
            io.github.terabold.skycosmetics.SkyCosmetics.LOG.debug(msg);
        }
    }
}
