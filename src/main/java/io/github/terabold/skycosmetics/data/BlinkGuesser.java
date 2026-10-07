package io.github.terabold.skycosmetics.data;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.SkyCosmetics;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estimates the timing of blinks the NEU repo times evenly, from the pictures of the frames themselves.
 *
 * About a hundred animations in animatedskulls.json have a tick count per frame, and nearly all of them are
 * blinks: eyes open for about two and a half seconds, then 2 ticks half closed, 4 closed (and 2 half closed
 * again). Hundreds of skins of the same design have one tick count for every frame, so their eyes stay shut as
 * long as they stay open. Once every frame of such a skin is in Minecraft's skin cache on disk, the frames are
 * compared: when they differ only in a small part of the head and one of them loses much of the detail there
 * (the eyes close), it is a blink. The frame with the most detail there stays up for {@link #OPEN_TICKS}, the
 * one that differs most from it {@link #CLOSED_TICKS}, the rest {@link #HALF_TICKS}: the median and the usual
 * values of the repo's measured blinks, whose pattern this finds in 77 of 80 (the rest are a tick off).
 * Moving or color-cycling animations, and any with more than four frames, keep the repo's timing.
 *
 * Eyes that close into a dark line on a light face can keep as much detail as open ones. Such a skin counts
 * as a blink only when it is a color variant of a skin whose other variants (at least two, and more than
 * those that are not) are blinks: variants share their animation.
 *
 * An estimate only replaces even repo timing; a timing measured in game or shipped with the mod replaces it.
 * Each skin is looked at once: the answer (blink or not) goes to {@code estimated-timings.json} with a
 * signature of the frames, so a repo update that changes them is looked at again.
 *
 * Pictures are read on a background thread, never the render thread; a download finishing only sets a flag.
 */
public final class BlinkGuesser {
    /** Bump when the method changes, so saved answers are worked out again. */
    static final int METHOD = 1;
    static final int MIN_FRAMES = 2;
    static final int MAX_FRAMES = 4;
    static final int OPEN_TICKS = 49;
    static final int CLOSED_TICKS = 4;
    static final int HALF_TICKS = 2;
    /** At most this share of the head may change: a blink is the eyes, a color cycle is everything. */
    static final double MAX_CHANGED = 0.20;
    /** The most closed frame keeps at most this share of the open frame's detail where they differ. */
    static final double MAX_DETAIL_LEFT = 0.80;
    /** Summed red, green and blue difference below which two pixels count as the same. */
    private static final int SAME_COLOR = 24;
    /** An outer-layer pixel this opaque covers the inner one (1 in 10, like the cutout). */
    private static final int HAT_ALPHA = 26;
    /** The head's visible area in a skin: six 8x8 faces in the top 16 rows and 32 columns. */
    private static final int ROWS = 16;
    private static final int COLS = 32;
    /** Animations read per sweep: all of them at once, so one catalog rebuild takes every answer. */
    private static final int PER_SWEEP = 256;
    /** Downloads finishing ask for a sweep at most this often; without any, one runs every minute. */
    private static final long BUSY_SWEEP_MS = 2_000;
    private static final long IDLE_SWEEP_MS = 60_000;

    private static volatile Path root;
    private static volatile List<Candidate> candidates = List.of();
    /** {@code id@sig} of every skin looked at, this session or before (from the store). */
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DIRTY = new AtomicBoolean();
    private static ScheduledExecutorService exec;
    private static long lastSweep;
    private static volatile int estimated;
    private static volatile int waiting;

    /** An animation that may be a blink, waiting for all its frames to be on disk. */
    private static final class Candidate {
        final String id;
        final String sig;
        final String[] values;
        /** Where its frames are cached; worked out on the blink thread once the skin folder is known. */
        Path[] files;

        Candidate(String id, String sig, String[] values) {
            this.id = id;
            this.sig = sig;
            this.values = values;
        }
    }

    private BlinkGuesser() {}

    // ------------------------------------------------------------ pictures ---

    /**
     * The head as it shows, as {@link #ROWS} x {@link #COLS} RGB values from a 64x64 or 64x32 skin: the outer layer
     * where it covers, else the inner one; -1 outside the six faces. Null for any other size. Like Minecraft, a
     * 64x32 skin whose outer area has no see-through pixel at all has no outer layer.
     */
    public static int[] head(int[] argb, int width, int height) {
        if (width != 64 || height != 64 && height != 32 || argb.length < width * height) return null;
        boolean hat = true;
        if (height == 32) {
            hat = false;
            for (int y = 0; y < 32 && !hat; y++) for (int x = 32; x < 64; x++) if (argb[y * 64 + x] >>> 24 < 128) {
                hat = true;
                break;
            }
        }
        int[] out = new int[ROWS * COLS];
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (!face(y, x)) {
                    out[y * COLS + x] = -1;
                    continue;
                }
                int outer = argb[y * 64 + x + 32];
                int px = hat && outer >>> 24 >= HAT_ALPHA ? outer : argb[y * 64 + x];
                out[y * COLS + x] = px & 0xFFFFFF;
            }
        }
        return out;
    }

    /** Top and bottom faces in rows 0-7 (columns 8-23), the four sides in rows 8-15. */
    private static boolean face(int y, int x) {
        return y >= 8 || x >= 8 && x < 24;
    }

    /**
     * Ticks per frame if {@code heads} (from {@link #head}) are a blink, else null. Pure, for the gametest too.
     */
    public static int[] guess(int[][] heads) {
        return guess(heads, true);
    }

    /**
     * The same, but without asking that a frame lose detail: what the frames would be timed as if they are a
     * blink, for a skin whose sibling variants are. Null if they cannot be one (size, frames, change).
     */
    public static int[] guessLoosely(int[][] heads) {
        return guess(heads, false);
    }

    private static int[] guess(int[][] heads, boolean strict) {
        int n = heads.length;
        if (n < MIN_FRAMES || n > MAX_FRAMES) return null;
        for (int[] h : heads) if (h == null || h.length != ROWS * COLS) return null;
        boolean[] changed = new boolean[ROWS * COLS];
        int faces = 0, changes = 0;
        for (int c = 0; c < changed.length; c++) {
            if (heads[0][c] < 0) continue;
            faces++;
            for (int i = 0; i < n && !changed[c]; i++) for (int j = i + 1; j < n; j++) {
                if (differ(heads[i][c], heads[j][c])) {
                    changed[c] = true;
                    break;
                }
            }
            if (changed[c]) changes++;
        }
        if (changes == 0 || changes > faces * MAX_CHANGED) return null;

        double[] detail = new double[n];
        for (int i = 0; i < n; i++) detail[i] = detail(heads[i], changed);
        int open = 0;
        for (int i = 1; i < n; i++) if (detail[i] > detail[open]) open = i;
        if (detail[open] <= 0) return null;
        double least = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) if (i != open) least = Math.min(least, detail[i]);
        if (strict && least > detail[open] * MAX_DETAIL_LEFT) return null; // nothing closes: something moves or glows

        int closed = -1, most = -1;
        for (int i = 0; i < n; i++) {
            if (i == open) continue;
            int d = 0;
            for (int c = 0; c < changed.length; c++) if (changed[c] && differ(heads[open][c], heads[i][c])) d++;
            if (d > most || d == most && detail[i] < detail[closed]) {
                most = d;
                closed = i;
            }
        }
        int[] ticks = new int[n];
        Arrays.fill(ticks, HALF_TICKS);
        ticks[open] = OPEN_TICKS;
        ticks[closed] = CLOSED_TICKS;
        return ticks;
    }

    private static boolean differ(int a, int b) {
        return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF))
            + Math.abs((a & 0xFF) - (b & 0xFF)) > SAME_COLOR;
    }

    /** How much light changes between neighbors where the frames differ: open eyes have pupils and shine. */
    private static double detail(int[] h, boolean[] changed) {
        double sum = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                int c = y * COLS + x;
                if (h[c] < 0) continue;
                if (x + 1 < COLS && h[c + 1] >= 0 && (changed[c] || changed[c + 1])) sum += Math.abs(lum(h[c]) - lum(h[c + 1]));
                if (y + 1 < ROWS && h[c + COLS] >= 0 && (changed[c] || changed[c + COLS])) sum += Math.abs(lum(h[c]) - lum(h[c + COLS]));
            }
        }
        return sum;
    }

    private static double lum(int rgb) {
        return 0.299 * (rgb >> 16 & 0xFF) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF);
    }

    // --------------------------------------------------------------- files ---

    /** Called once by Minecraft's skin manager with the folder it caches skins in. */
    public static void skinsAt(Path skins) {
        root = skins;
        nudge();
    }

    /**
     * The file Minecraft caches the skin of texture value {@code value} in: {@code <skins>/<xx>/<sha1>}, the SHA-1
     * of the texture's hash as {@code SkinManager} names it. Null if the value names no skin.
     */
    @SuppressWarnings("deprecation") // SHA-1 is what Minecraft names the files with
    static Path file(Path skins, String value) {
        String hash = skinHash(value);
        if (hash == null) return null;
        String name = Hashing.sha1().hashUnencodedChars(hash).toString();
        return skins.resolve(name.substring(0, 2)).resolve(name);
    }

    /** The last part of the skin URL in a texture value, without extension, as authlib's {@code getHash}. */
    static String skinHash(String value) {
        if (value == null || value.length() > 4096) return null;
        try {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '=') end--;
            String json = new String(Base64.getDecoder().decode(value.substring(0, end)), StandardCharsets.UTF_8);
            JsonObject skin = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
            String path = URI.create(skin.get("url").getAsString()).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            int dot = last.lastIndexOf('.');
            if (dot >= 0) last = last.substring(0, dot);
            return last.isEmpty() ? null : last;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Where the skin of texture value {@code value} is (or would be) cached on disk; null while unknown. */
    public static Path skinFile(String value) {
        Path skins = root;
        return skins == null ? null : file(skins, value);
    }

    /** A short signature of an animation's frames, or null if a frame has no skin texture. */
    static String sig(String[] values) {
        long h = 0xcbf29ce484222325L;
        for (String v : values) {
            String t = TimingLearner.hash(v);
            if (t == null) return null;
            for (int i = 0; i < t.length(); i++) h = (h ^ t.charAt(i)) * 0x100000001b3L;
            h = (h ^ ',') * 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    // ------------------------------------------------------------ pipeline ---

    /** A skin download finished: look for frames that just arrived. Any thread; only sets a flag. */
    public static void nudge() {
        DIRTY.set(true);
    }

    /** Which animations of {@code c} may be blinks that are not looked at yet. Repo thread, with each new catalog. */
    static void index(Catalog c, Store store) {
        List<Candidate> list = new ArrayList<>();
        int guessed = 0;
        for (SkinEntry e : c.skins.values()) {
            if (e.timing == SkinEntry.Timing.GUESSED) guessed++;
            if (!maybe(e) || e.timing != SkinEntry.Timing.REPO) continue;
            String sig = sig(e.textures);
            if (sig == null) continue;
            String key = e.id + "@" + sig;
            if (store != null && store.knows(e.id, sig)) DONE.add(key);
            if (!DONE.contains(key)) list.add(new Candidate(e.id, sig, e.textures.clone()));
        }
        candidates = List.copyOf(list);
        estimated = guessed;
        if (!list.isEmpty()) start();
        nudge();
    }

    /** An animation with even repo timing and few enough frames to be a blink. */
    static boolean maybe(SkinEntry e) {
        return !e.perFrame && e.textures.length >= MIN_FRAMES && e.textures.length <= MAX_FRAMES;
    }

    private static synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SkyCosmetics-blinks");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                long since = now - lastSweep;
                if (since < IDLE_SWEEP_MS && !(since >= BUSY_SWEEP_MS && DIRTY.get())) return;
                DIRTY.set(false);
                lastSweep = now;
                sweep();
            } catch (Throwable e) {
                SkyCosmetics.LOG.error("Looking for blinks failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** Looks at up to {@link #PER_SWEEP} candidates whose frames are all on disk. Blink thread. */
    private static void sweep() {
        Path skins = root;
        if (skins == null) return;
        int done = 0, wait = 0;
        for (Candidate c : candidates) {
            String key = c.id + "@" + c.sig;
            if (DONE.contains(key)) continue;
            if (done >= PER_SWEEP) {
                DIRTY.set(true); // the rest in the next sweep
                wait++;
                continue;
            }
            if (c.files == null) {
                c.files = new Path[c.values.length];
                for (int i = 0; i < c.files.length; i++) c.files[i] = file(skins, c.values[i]);
            }
            Path[] files = c.files;
            boolean all = true;
            for (int i = 0; i < files.length && all; i++) all = files[i] != null && Files.isRegularFile(files[i]);
            if (!all) {
                wait++;
                continue;
            }
            done++;
            DONE.add(key);
            int[][] heads = new int[files.length][];
            try {
                for (int i = 0; i < files.length; i++) heads[i] = read(files[i]);
            } catch (IOException | RuntimeException e) {
                SkyCosmetics.LOG.debug("Could not read the frames of {}: {}", c.id, e.toString());
                continue; // looked at again next session
            }
            int[] ticks = guess(heads);
            int[] loose = ticks == null ? guessLoosely(heads) : null;
            if (ticks != null) SkyCosmetics.LOG.debug("{} looks like a blink: {}", c.id, Arrays.toString(ticks));
            Repo.guessTiming(c.id, c.sig, ticks != null ? ticks : loose, ticks == null && loose != null);
        }
        waiting = wait;
    }

    private static int[] read(Path file) throws IOException {
        if (Files.size(file) > 1 << 20) throw new IOException("not a skin");
        try (InputStream in = Files.newInputStream(file); NativeImage img = NativeImage.read(in)) {
            return head(img.getPixels(), img.getWidth(), img.getHeight());
        }
    }

    /** One line for {@code /skycosmetics debug}. */
    public static String status() {
        return "Blink timing: estimated for " + Names.count(estimated, "skin") + "; "
            + Names.count(waiting, "animation") + " waiting for frames"
            + (root == null ? "; skin folder unknown" : "");
    }

    // --------------------------------------------------------------- store ---

    /**
     * What was found per skin, kept in {@code config/skycosmetics/estimated-timings.json}: {@code "skins": {"<id>":
     * {"frames": 3, "sig": "...", "ticksPerTexture": [49, 2, 4]}}}, without ticks for a skin that is no blink, and
     * with {@code "ifVariantsBlink": true} for one that is a blink only if its sibling variants are.
     * Only touched on the repo thread.
     */
    static final class Store {
        static final int MAX = 2000;
        private static final long MAX_FILE = 1L << 20;

        /** {@code ticks} null: no blink; {@code family}: a blink only if its sibling variants are. */
        record Guess(String sig, int[] ticks, boolean family, long at) {}

        final Map<String, Guess> skins = new LinkedHashMap<>();

        static Path file() {
            return RepoSource.ownZip().resolveSibling("estimated-timings.json");
        }

        /** The saved answers; a file that cannot be read is set aside, and one from another method is ignored. */
        static Store load() {
            Path f = file();
            if (!Files.isRegularFile(f)) return new Store();
            try {
                if (Files.size(f) > MAX_FILE) throw new IOException("larger than " + (MAX_FILE >> 10) + " kB");
                return fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException | RuntimeException e) {
                Io.setAside(f, "Could not read estimated-timings.json", e);
                return new Store();
            }
        }

        void save() {
            Io.write(file(), toJson());
        }

        boolean knows(String id, String sig) {
            Guess g = skins.get(id);
            return g != null && g.sig.equals(sig);
        }

        /** Records an answer for {@code id}; false if it is the one saved already. */
        boolean put(String id, String sig, int[] ticks, boolean family, Set<String> inUse) {
            if (!SkinLearner.validId(id) || sig == null || ticks != null && !Timings.valid(id, ticks)) return false;
            family &= ticks != null;
            Guess had = skins.get(id);
            if (had != null && had.sig.equals(sig) && Arrays.equals(had.ticks, ticks) && had.family == family) return false;
            skins.remove(id);
            skins.put(id, new Guess(sig, ticks == null ? null : ticks.clone(), family, System.currentTimeMillis()));
            if (skins.size() > MAX) {
                skins.entrySet().stream().filter(e -> !inUse.contains(e.getKey()))
                    .sorted(Comparator.comparingLong(e -> e.getValue().at)).limit(skins.size() - MAX)
                    .map(Map.Entry::getKey).toList().forEach(skins::remove);
            }
            return true;
        }

        /**
         * Estimated blinks into {@code skins}, for skins the repo times evenly whose frames are still the ones looked
         * at. A blink only if its variants are needs at least two sibling variants (same parent, as many frames) that
         * are blinks, and more of them than ones that are not.
         */
        void apply(Map<String, SkinEntry> skins) {
            Map<String, int[]> votes = new java.util.HashMap<>(); // parent and frames -> {blinks, not blinks}
            List<Map.Entry<SkinEntry, Guess>> current = new ArrayList<>();
            for (Map.Entry<String, Guess> g : this.skins.entrySet()) {
                SkinEntry e = skins.get(g.getKey());
                Guess guess = g.getValue();
                if (e == null || !maybe(e) || e.timing != SkinEntry.Timing.REPO || guess.ticks != null && e.textures.length != guess.ticks.length
                    || !guess.sig.equals(sig(e.textures))) continue;
                current.add(Map.entry(e, guess));
                if (e.parent != null && !guess.family) votes.computeIfAbsent(e.parent + "#" + e.textures.length, k -> new int[2])[guess.ticks != null ? 0 : 1]++;
            }
            for (Map.Entry<SkinEntry, Guess> c : current) {
                SkinEntry e = c.getKey();
                Guess guess = c.getValue();
                if (guess.ticks == null) continue;
                if (guess.family) {
                    int[] v = e.parent == null ? null : votes.get(e.parent + "#" + e.textures.length);
                    if (v == null || v[0] < 2 || v[0] <= v[1]) continue;
                }
                skins.put(e.id, e.withTicks(guess.ticks, SkinEntry.Timing.GUESSED));
            }
        }

        static Store fromJson(JsonObject root) {
            Store s = new Store();
            if (!(root.get("method") instanceof JsonPrimitive m) || !m.isNumber() || m.getAsInt() != METHOD) return s;
            if (!(root.get("skins") instanceof JsonObject skins)) return s;
            for (Map.Entry<String, JsonElement> e : skins.entrySet()) {
                try {
                    if (!(e.getValue() instanceof JsonObject o) || !(o.get("sig") instanceof JsonPrimitive sig)) continue;
                    int[] ticks = null;
                    if (o.get("ticksPerTexture") instanceof JsonArray a) {
                        ticks = new int[a.size()];
                        for (int i = 0; i < ticks.length; i++) ticks[i] = a.get(i).getAsInt();
                        if (!Timings.valid(e.getKey(), ticks)) continue;
                    }
                    if (!SkinLearner.validId(e.getKey())) continue;
                    long at = o.get("at") instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0L;
                    boolean family = ticks != null && o.get("ifVariantsBlink") instanceof JsonPrimitive f && f.isBoolean() && f.getAsBoolean();
                    s.skins.put(e.getKey(), new Guess(sig.getAsString(), ticks, family, at));
                } catch (RuntimeException ex) {
                    SkyCosmetics.LOG.warn("Skipping estimated timing {}: {}", e.getKey(), ex.toString());
                }
            }
            while (s.skins.size() > MAX) s.skins.remove(s.skins.keySet().iterator().next());
            return s;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("method", METHOD);
            root.addProperty("about", "Animations SkyCosmetics looked at for a blink the NEU repo times evenly. "
                + "With ticksPerTexture: the estimated ticks per frame (ifVariantsBlink: only used if other variants "
                + "of the skin are blinks); without: not a blink.");
            JsonObject skins = new JsonObject();
            for (Map.Entry<String, Guess> e : this.skins.entrySet()) {
                Guess g = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("sig", g.sig);
                if (g.ticks != null) {
                    o.addProperty("frames", g.ticks.length);
                    JsonArray per = new JsonArray();
                    for (int t : g.ticks) per.add(t);
                    o.add("ticksPerTexture", per);
                    if (g.family) o.addProperty("ifVariantsBlink", true);
                }
                o.addProperty("at", g.at);
                skins.add(e.getKey(), o);
            }
            root.add("skins", skins);
            return root;
        }
    }
}
