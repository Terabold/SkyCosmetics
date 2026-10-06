package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Locale;

/**
 * One way to hold an item: where it sits, how it is turned, how big it is and how it swings. Immutable; every
 * edit makes a new pose, so a pose being drawn never changes halfway through a frame.
 *
 * Numbers live in one array indexed by {@link Field}, so the editor, the JSON, share codes and resets all walk
 * the same list. Values are always clamped to the field's range: a hand-edited file or a pasted code can never
 * produce a NaN matrix or an item the size of the screen.
 */
public final class HandPose {
    /** Every number a pose has, with its range, slider step and vanilla value. */
    public enum Field {
        X("x", -2, 2, 0.01, 0), Y("y", -2, 2, 0.01, 0), Z("z", -2, 2, 0.01, 0),
        ROT_X("rotX", -180, 180, 1, 0), ROT_Y("rotY", -180, 180, 1, 0), ROT_Z("rotZ", -180, 180, 1, 0),
        SIZE("size", 0.1, 4, 0, 1), SIZE_X("sizeX", 0.25, 4, 0, 1), SIZE_Y("sizeY", 0.25, 4, 0, 1),
        SIZE_Z("sizeZ", 0.25, 4, 0, 1),
        SWING_X("swingX", 0, 2, 0.05, 1), SWING_Y("swingY", 0, 2, 0.05, 1), SWING_Z("swingZ", 0, 2, 0.05, 1),
        SPEED("speed", 0.25, 4, 0, 1);

        public final String key;
        public final float min, max, step, vanilla;

        Field(String key, double min, double max, double step, double vanilla) {
            this.key = key;
            this.min = (float) min;
            this.max = (float) max;
            this.step = (float) step;
            this.vanilla = (float) vanilla;
        }

        /** Sizes and speed are multipliers: their sliders move in powers of two, so 1x sits in the middle. */
        public boolean multiplier() {
            return step == 0;
        }

        public float clamp(float v) {
            return Float.isFinite(v) ? Math.clamp(v, min, max) : vanilla;
        }
    }

    /** What the rotation turns around. */
    public enum Pivot {
        /** The item turns around the point you hold it by; the swing is unchanged. */
        ITEM("item"),
        /** The hand turns where it rests, and the swing turns with it (as 1.8.9 animation mods did). */
        HAND("hand"),
        /** The whole hand circles around your eye. */
        VIEW("view");

        public final String key;

        Pivot(String key) {
            this.key = key;
        }
    }

    /** How a swing moves the item. */
    public enum Swing {
        /** Minecraft's arc: the item travels forward and back. */
        MOVING("moving"),
        /** The item only turns where it is; it doesn't travel. */
        IN_PLACE("inPlace"),
        /** No swing at all. */
        NONE("none");

        public final String key;

        Swing(String key) {
            this.key = key;
        }
    }

    private static final Field[] FIELDS = Field.values();

    public static final HandPose VANILLA = new HandPose(defaults(), Pivot.ITEM, Swing.MOVING);

    private final float[] v;
    public final Pivot pivot;
    public final Swing swing;

    // Precomputed once per pose, so the render path only reads booleans and floats.
    /** Moves at all. */
    public final boolean offset;
    /** Turns at all. */
    public final boolean turned;
    /** Resized at all. */
    public final boolean resized;
    /** The swing travels some other distance than vanilla's. */
    public final boolean swingScaled;
    /** Rotation in radians, for one reused quaternion. */
    public final float radX, radY, radZ;
    /** Final scale per axis: the uniform size times the axis' own. */
    public final float scaleX, scaleY, scaleZ;
    /** Swing distance per axis, 0 for In Place. */
    public final float swingDX, swingDY, swingDZ;

    private HandPose(float[] values, Pivot pivot, Swing swing) {
        this.v = values;
        this.pivot = pivot;
        this.swing = swing;
        offset = get(Field.X) != 0 || get(Field.Y) != 0 || get(Field.Z) != 0;
        turned = get(Field.ROT_X) != 0 || get(Field.ROT_Y) != 0 || get(Field.ROT_Z) != 0;
        radX = (float) Math.toRadians(get(Field.ROT_X));
        radY = (float) Math.toRadians(get(Field.ROT_Y));
        radZ = (float) Math.toRadians(get(Field.ROT_Z));
        float size = get(Field.SIZE);
        scaleX = size * get(Field.SIZE_X);
        scaleY = size * get(Field.SIZE_Y);
        scaleZ = size * get(Field.SIZE_Z);
        resized = scaleX != 1 || scaleY != 1 || scaleZ != 1;
        boolean inPlace = swing == Swing.IN_PLACE;
        swingDX = inPlace ? 0 : get(Field.SWING_X);
        swingDY = inPlace ? 0 : get(Field.SWING_Y);
        swingDZ = inPlace ? 0 : get(Field.SWING_Z);
        swingScaled = swingDX != 1 || swingDY != 1 || swingDZ != 1;
    }

    private static float[] defaults() {
        float[] d = new float[FIELDS.length];
        for (Field f : FIELDS) d[f.ordinal()] = f.vanilla;
        return d;
    }

    public float get(Field f) {
        return v[f.ordinal()];
    }

    /** This pose with one number changed (clamped, and rounded to what its slider can show). */
    public HandPose with(Field f, float value) {
        float c = round(f, f.clamp(value));
        if (c == get(f)) return this;
        float[] n = v.clone();
        n[f.ordinal()] = c;
        return new HandPose(n, pivot, swing);
    }

    public HandPose with(Pivot p) {
        return p == pivot || p == null ? this : new HandPose(v, p, swing);
    }

    public HandPose with(Swing s) {
        return s == swing || s == null ? this : new HandPose(v, pivot, s);
    }

    /** Swing speed as a multiplier of the item's own swing (1 = as Minecraft). */
    public float speed() {
        return get(Field.SPEED);
    }

    /** Nothing about this pose differs from how Minecraft draws a hand. */
    public boolean isVanilla() {
        return equals(VANILLA);
    }

    /** Two decimals for multipliers and positions, one for angles: what the sliders show is what is stored. */
    static float round(Field f, float value) {
        double scale = f == Field.ROT_X || f == Field.ROT_Y || f == Field.ROT_Z ? 10 : 100;
        return (float) (Math.round(value * scale) / scale);
    }

    // ------------------------------------------------------------ JSON ---

    /** Only what differs from vanilla, so a pose saved by an older version reads the same in a newer one. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (Field f : FIELDS) {
            if (get(f) != f.vanilla) o.addProperty(f.key, get(f));
        }
        if (pivot != Pivot.ITEM) o.addProperty("pivot", pivot.key);
        if (swing != Swing.MOVING) o.addProperty("swing", swing.key);
        return o;
    }

    /** A missing, broken or out-of-range value falls back to vanilla's; never throws. */
    public static HandPose fromJson(JsonElement e) {
        if (!(e instanceof JsonObject o)) return VANILLA;
        float[] n = defaults();
        for (Field f : FIELDS) {
            if (o.get(f.key) instanceof JsonPrimitive p && p.isNumber()) {
                n[f.ordinal()] = round(f, f.clamp(p.getAsFloat()));
            }
        }
        Pivot pivot = Pivot.ITEM;
        Swing swing = Swing.MOVING;
        if (o.get("pivot") instanceof JsonPrimitive p && p.isString()) {
            for (Pivot x : Pivot.values()) if (x.key.equals(p.getAsString())) pivot = x;
        }
        if (o.get("swing") instanceof JsonPrimitive p && p.isString()) {
            for (Swing x : Swing.values()) if (x.key.equals(p.getAsString())) swing = x;
        }
        return new HandPose(n, pivot, swing);
    }

    /** For importers: a pose from raw numbers, each clamped. */
    static HandPose of(Pivot pivot, Swing swing, float... values) {
        float[] n = defaults();
        for (int i = 0; i < Math.min(values.length, n.length); i++) n[i] = round(FIELDS[i], FIELDS[i].clamp(values[i]));
        return new HandPose(n, pivot, swing);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HandPose p && p.pivot == pivot && p.swing == swing && Arrays.equals(p.v, v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v) * 31 + pivot.hashCode() * 7 + swing.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("HandPose{");
        for (Field f : FIELDS) if (get(f) != f.vanilla) b.append(f.key).append('=').append(String.format(Locale.ROOT, "%.2f", get(f))).append(' ');
        return b.append("pivot=").append(pivot.key).append(" swing=").append(swing.key).append('}').toString();
    }
}
