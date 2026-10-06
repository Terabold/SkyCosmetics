package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.Textures;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** A dye: one RGB colour, or the full colour cycle of an animated dye. */
public final class DyeEntry {
    /**
     * Hypixel advances animated dyes one colour every 2 ticks, and shifts each
     * armour piece 5 colours along so a full set ripples from boots to helmet.
     */
    public static final int TICKS_PER_COLOR = 2;
    public static final int PIECE_OFFSET = 5;

    /** Prefix of a custom animated dye id: {@code anim:<ticksPerStep>:<step|blend>:#RRGGBB,#RRGGBB,...}. */
    public static final String ANIM_PREFIX = "anim:";
    /** Colours a "blend" dye has per keyframe pair (the first keyframe included). */
    public static final int BLEND_STEPS = 10;
    public static final int MAX_TICKS_PER_STEP = 200;
    /** The editor offers 8; ids with more (written by hand) still load. */
    public static final int MAX_KEYFRAMES = 32;

    public final String id;
    public final String name;
    public final int nameColor;
    public final int[] colors;
    /** Ticks each colour of {@link #colors} is shown for. */
    public final int ticksPerColor;
    /** Colours each armour piece is shifted along the cycle, per step up from the boots. */
    public final int pieceOffset;
    final String searchKey;
    /** Skull texture of the dye's own item (Hypixel dyes are heads), used as its icon. */
    public String texture;
    /**
     * For vanilla dyes: the vanilla dye item to show instead. Kept as an Item,
     * not a stack: the repo can finish loading before item components are bound,
     * and a stack cannot be built until then.
     */
    public Item vanillaItem;
    private ItemStack icon;

    /** A repo dye, timed like Hypixel's. */
    public DyeEntry(String id, String name, int nameColor, int[] colors) {
        this(id, name, nameColor, colors, TICKS_PER_COLOR, PIECE_OFFSET);
    }

    public DyeEntry(String id, String name, int nameColor, int[] colors, int ticksPerColor, int pieceOffset) {
        this.id = id;
        this.name = name;
        this.nameColor = nameColor;
        this.colors = colors;
        this.ticksPerColor = Math.max(1, ticksPerColor);
        this.pieceOffset = Math.max(0, pieceOffset);
        this.searchKey = (name + " " + id.replace('_', ' ')).toLowerCase(Locale.ROOT);
    }

    /** The dye item itself (its head, or the vanilla dye), or null for a plain colour swatch. */
    public ItemStack icon() {
        if (icon != null) return icon;
        if (vanillaItem != null) {
            icon = new ItemStack(vanillaItem);
        } else if (texture != null) {
            icon = new ItemStack(Items.PLAYER_HEAD);
            icon.set(DataComponents.PROFILE, Textures.profile(texture));
        }
        return icon;
    }

    public boolean animated() {
        return colors.length > 1;
    }

    /** RGB at {@code tick} for an armour piece {@code pieceIndex} steps up from the boots. */
    public int rgbAt(long tick, int pieceIndex) {
        if (!animated()) return colors[0];
        long i = tick / ticksPerColor + (long) pieceIndex * pieceOffset;
        return colors[(int) (i % colors.length)];
    }

    /** Index into {@link #colors} the boots show at {@code tick}; lets a preview mark the position in the cycle. */
    public int indexAt(long tick) {
        return animated() ? (int) ((tick / ticksPerColor) % colors.length) : 0;
    }

    public static DyeEntry custom(int rgb) {
        String hex = String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
        return new DyeEntry(hex, "Custom " + hex, 0xFFFFFFFF, new int[]{rgb & 0xFFFFFF});
    }

    // ------------------------------------------------- custom animated ---

    /**
     * A custom animated dye as the player designed it: a few keyframes, a speed
     * and whether to fade between them. The id is the whole definition, so a
     * look saves it like any other dye id and needs no extra storage.
     */
    public record AnimSpec(int ticksPerStep, boolean blend, int[] keyframes) {
        public String id() {
            return customAnimatedId(ticksPerStep, blend, keyframes);
        }

        /**
         * The colour cycle: the keyframes as they are ("step"), or
         * {@link #BLEND_STEPS} colours per keyframe pair mixed in OkLab, wrapping
         * from the last keyframe back to the first ("blend"). OkLab keeps the
         * middle of a fade as bright as its ends, where an RGB mix turns grey.
         */
        public int[] colors() {
            int n = keyframes.length;
            if (!blend || n < 2) return keyframes.clone();
            int[] out = new int[n * BLEND_STEPS];
            for (int i = 0; i < n; i++) {
                int a = keyframes[i], b = keyframes[(i + 1) % n];
                for (int j = 0; j < BLEND_STEPS; j++) out[i * BLEND_STEPS + j] = mixOklab(a, b, j / (float) BLEND_STEPS);
            }
            return out;
        }

        /**
         * Blend dyes shift each piece 1/16 of the cycle, so the ripple looks the
         * same at any speed or keyframe count (Hypixel's fixed 5-colour shift is
         * 1/12 to 1/28 of its long cycles). Step dyes use no shift: shifting by
         * whole keyframes would only put a different colour on each piece.
         */
        public int pieceOffset(int colorCount) {
            return blend ? Math.max(1, colorCount / 16) : 0;
        }

        public DyeEntry toEntry(String id) {
            int[] cols = colors();
            String name = "Custom animated (" + keyframes.length + (keyframes.length == 1 ? " color, " : " colors, ")
                + (blend ? "blend" : "step") + ")";
            return new DyeEntry(id, name, 0xFFFF55FF, cols, ticksPerStep, pieceOffset(cols.length));
        }
    }

    /** True for an {@code anim:...} id; it may still be malformed (then {@link #customAnimated} returns null). */
    public static boolean isCustomAnimated(String id) {
        return id != null && id.regionMatches(true, 0, ANIM_PREFIX, 0, ANIM_PREFIX.length());
    }

    /** Builds {@code anim:<ticksPerStep>:<step|blend>:#RRGGBB,...}; ticks are clamped to 1..{@value #MAX_TICKS_PER_STEP}. */
    public static String customAnimatedId(int ticksPerStep, boolean blend, int[] rgbs) {
        if (rgbs == null || rgbs.length == 0) throw new IllegalArgumentException("an animated dye needs at least one color");
        StringBuilder b = new StringBuilder(ANIM_PREFIX)
            .append(Math.clamp(ticksPerStep, 1, MAX_TICKS_PER_STEP)).append(':')
            .append(blend ? "blend" : "step").append(':');
        for (int i = 0; i < rgbs.length; i++) {
            if (i > 0) b.append(',');
            b.append(String.format(Locale.ROOT, "#%06X", rgbs[i] & 0xFFFFFF));
        }
        return b.toString();
    }

    /** The spec in an {@code anim:} id, or null if it is not one or is malformed. Case and '#' are optional. */
    public static AnimSpec parseCustomAnimated(String id) {
        if (!isCustomAnimated(id)) return null;
        String[] parts = id.substring(ANIM_PREFIX.length()).split(":", -1);
        if (parts.length != 3) return null;
        int ticks;
        try {
            ticks = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (ticks < 1 || ticks > MAX_TICKS_PER_STEP) return null;
        String mode = parts[1].trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("step") && !mode.equals("blend")) return null;
        String[] hexes = parts[2].split(",", -1);
        if (hexes.length > MAX_KEYFRAMES) return null;
        int[] keyframes = new int[hexes.length];
        for (int i = 0; i < hexes.length; i++) {
            int rgb = parseHex(hexes[i]);
            if (rgb < 0) return null;
            keyframes[i] = rgb;
        }
        return new AnimSpec(ticks, mode.equals("blend"), keyframes);
    }

    /** A new entry for an {@code anim:} id, or null if malformed. Not cached here; {@link Catalog#dye} caches. */
    public static DyeEntry customAnimated(String id) {
        AnimSpec spec = parseCustomAnimated(id);
        return spec == null ? null : spec.toEntry(id);
    }

    /** "#RRGGBB", "RRGGBB" or "#RGB" to RGB; -1 if malformed. */
    public static int parseHex(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) return -1;
        for (int i = 0; i < 6; i++) if (Character.digit(s.charAt(i), 16) < 0) return -1;
        return Integer.parseInt(s, 16);
    }

    // ------------------------------------------------------------ OkLab ---

    /** Mixes two sRGB colours {@code t} of the way from {@code a} to {@code b} in OkLab. */
    public static int mixOklab(int a, int b, float t) {
        if (t <= 0) return a & 0xFFFFFF;
        if (t >= 1) return b & 0xFFFFFF;
        float[] la = oklab(a), lb = oklab(b);
        return fromOklab(la[0] + (lb[0] - la[0]) * t, la[1] + (lb[1] - la[1]) * t, la[2] + (lb[2] - la[2]) * t);
    }

    private static float[] oklab(int rgb) {
        float r = toLinear((rgb >> 16) & 0xFF), g = toLinear((rgb >> 8) & 0xFF), b = toLinear(rgb & 0xFF);
        float l = (float) Math.cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b);
        float m = (float) Math.cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b);
        float s = (float) Math.cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b);
        return new float[]{
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s};
    }

    private static int fromOklab(float lightness, float a, float b) {
        float l = lightness + 0.3963377774f * a + 0.2158037573f * b;
        float m = lightness - 0.1055613458f * a - 0.0638541728f * b;
        float s = lightness - 0.0894841775f * a - 1.2914855480f * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        int r = toSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s);
        int g = toSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s);
        int bl = toSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s);
        return r << 16 | g << 8 | bl;
    }

    private static float toLinear(int c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static int toSrgb(float v) {
        v = Math.clamp(v, 0f, 1f);
        float s = v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
        return Math.clamp(Math.round(s * 255), 0, 255);
    }
}
