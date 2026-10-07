package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Learns how long each frame of an animated skin really stays on screen, from what Hypixel already sends.
 *
 * The NEU repo times about a hundred animations frame by frame; most others have one tick count for every
 * frame, so a blink or a flash plays as long as the rest. Hypixel animates a skin by sending the head again
 * with the next frame, so every update of a head showing a frame of a known animation is stamped with the
 * time it arrived: your armor and inventory slots, menu slots, the equipment of players, stands and mobs,
 * and item displays (every place a pet or orb head shows too). Once one head has gone round its frames at
 * least twice in a row, and in most rounds each frame lasted within a tick (or 10%) of the median of all
 * rounds, those medians are the timing. It goes to timings.json and replaces the repo's for that skin
 * everywhere; each skin is measured once per game session, so a wrong timing heals itself next time.
 * Rounds during server lag (time updates falling behind the clock) never count. A blink's open frame may
 * last a different time each round, so a frame much longer than all others only has to be within half of
 * its median, and then three rounds must agree.
 *
 * A head is followed per slot or entity while the same item shows there (its name and SkyBlock uuid):
 * another item in the slot, even with a frame of the same skin, starts over, and so does every menu slot
 * when a new menu opens (menus reuse their slot numbers and, in time, their ids).
 *
 * Frames that repeat a texture are handled by matching the order seen against the skin's frame order;
 * frames in a row with the same texture are timed together, since nothing changes on screen between them.
 *
 * Read only: nothing here sends a packet, clicks, or changes a slot. Main thread only, except the index
 * of known frames, which the repo thread builds with each catalog. An update of a head that shows no frame
 * of an animation costs a few map lookups; any other item, a null check.
 */
public final class TimingLearner {
    private static final long TICK_NS = 50_000_000L;
    /** Rounds that must agree before a timing is trusted. */
    private static final int MIN_ROUNDS = 2;
    /** Rounds that must agree when the open frame of a blink varied between them. */
    private static final int MIN_ROUNDS_VARIED = 3;
    /** A frame at least this long and 4 times as long as any other is the open frame of a blink. */
    private static final double LONG_TICKS = 20;
    /** Rounds kept per head while they disagree; older ones make room. */
    private static final int KEEP_ROUNDS = 6;
    private static final int MAX_TRACKS = 64;
    private static final long IDLE_NS = 60_000_000_000L;
    /** Time updates more than this many ticks behind the clock: the server lagged. */
    private static final double LAG_TICKS = 3;
    private static final int MAX_LAGS = 16;
    /** Real texture values are a few hundred characters; longer ones are not looked at. */
    private static final int MAX_VALUE = 2048;
    private static final int MAX_SEEN = 2048;

    /** "Learn Animation Timing" in the studio settings. */
    public static boolean enabled = true;

    /** One animation as a cycle of runs: frame {@code first[k]} starts run k, which covers {@code len[k]} frames. */
    record Anim(SkinEntry skin, String[] runs, int[] first, int[] len) {}

    /** Texture hash of every frame of every animation in one catalog, and which animations show it. */
    private record Index(Map<String, String> byValue, Map<String, Anim[]> byHash, int animations) {}

    private static volatile Index index;
    /** Catalog skins wearing a timing learned in game. */
    private static volatile int timedSkins;

    private static final Map<Long, Track> TRACKS = new HashMap<>();
    /** Texture values that are not catalog frames -> their hash, or "" if they have none. */
    private static final Map<String, String> SEEN = new HashMap<>();
    /** Skins measured this session (learned, or found to match what we have). */
    private static final Set<String> SETTLED = new HashSet<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static int learned;

    private static LongSupplier clock = System::nanoTime;
    private static ClientLevel level;
    private static int ticks;

    // Server lag, from time updates: the last one, and recent stretches where the server fell behind.
    private static boolean hasTime;
    private static long timeAt;
    private static long timeGame;
    private static final long[] LAG_FROM = new long[MAX_LAGS];
    private static final long[] LAG_TO = new long[MAX_LAGS];
    private static int lags;
    private static int lagSlot;
    /** Nothing before this is known to be lag-free: a world change, or a lag stretch that fell out of the ring. */
    private static long lagHorizon = Long.MIN_VALUE;

    private TimingLearner() {}

    static void init() {
        Settings.register("learnTiming", v -> enabled = Settings.bool(v, enabled), () -> new JsonPrimitive(enabled));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != level || !enabled && !TRACKS.isEmpty()) {
                level = mc.level;
                TRACKS.clear(); // entity ids and container slots mean something else now
                resetLag();
            }
            if (++ticks % 100 != 0 || TRACKS.isEmpty()) return;
            long now = clock.getAsLong();
            TRACKS.values().removeIf(t -> now - t.at[t.n - 1] > IDLE_NS);
        });
    }

    // ------------------------------------------------------------ packets ---
    // From ClientPacketListenerMixin, on the main thread, after the packet was applied.

    public static void onEquipment(ClientboundSetEquipmentPacket p) {
        if (!enabled) return;
        long now = clock.getAsLong();
        for (Pair<EquipmentSlot, ItemStack> s : p.getSlots()) observe(key(1, p.getEntity(), s.getFirst().ordinal()), s.getSecond(), now);
    }

    public static void onSlot(ClientboundContainerSetSlotPacket p) {
        if (enabled) observe(key(2, p.getContainerId(), p.getSlot()), p.getItem(), clock.getAsLong());
    }

    /** A new menu: what its slots show has nothing to do with what any menu slot showed before. */
    public static void onOpenScreen(ClientboundOpenScreenPacket p) {
        if (!TRACKS.isEmpty()) TRACKS.keySet().removeIf(k -> k >>> 56 == 2 && (k >>> 16 & 0xFFFFFFFFL) != 0);
    }

    public static void onContent(ClientboundContainerSetContentPacket p) {
        if (!enabled) return;
        long now = clock.getAsLong();
        List<ItemStack> items = p.items();
        for (int i = 0; i < items.size(); i++) observe(key(2, p.containerId(), i), items.get(i), now);
    }

    public static void onInventory(ClientboundSetPlayerInventoryPacket p) {
        if (enabled) observe(key(3, 0, p.slot()), p.contents(), clock.getAsLong());
    }

    /** Item displays, item frames and dropped items carry their item as entity data. */
    public static void onEntityData(ClientboundSetEntityDataPacket p) {
        if (!enabled) return;
        long now = -1;
        for (SynchedEntityData.DataValue<?> v : p.packedItems()) {
            if (!(v.value() instanceof ItemStack s)) continue;
            if (now < 0) now = clock.getAsLong();
            observe(key(4, p.id(), v.id()), s, now);
        }
    }

    /**
     * About once a second: when the server's clock moved fewer ticks than the real one since the last update, it
     * lagged in between. A clock that stands still (or goes back) tells nothing.
     */
    public static void onTime(ClientboundSetTimePacket p) {
        long now = clock.getAsLong();
        long game = p.gameTime();
        if (hasTime && game > timeGame && (now - timeAt) / (double) TICK_NS - (game - timeGame) > LAG_TICKS) lag(timeAt, now);
        hasTime = true;
        timeAt = now;
        timeGame = game;
    }

    private static long key(int kind, int owner, int slot) {
        return (long) kind << 56 | (owner & 0xFFFFFFFFL) << 16 | slot & 0xFFFF;
    }

    // ---------------------------------------------------------- observing ---

    /** {@code stack} is now at {@code key}; {@code now} on the {@link #clock}. */
    private static void observe(long key, ItemStack stack, long now) {
        Index idx = index;
        if (idx == null) return;
        String value = stack.isEmpty() ? null : texture(stack);
        String hash = value == null ? null : hashOf(idx, value);
        Anim[] anims = hash == null ? null : idx.byHash.get(hash);
        Track t = TRACKS.isEmpty() ? null : TRACKS.get(key);
        if (anims == null) {
            if (t != null) TRACKS.remove(key); // something else is shown there now
            return;
        }
        Who who = who(stack);
        if (t != null && t.who.equals(who)) {
            if (hash.equals(t.tex[t.n - 1])) return; // sent again: same frame
            if (t.add(hash, now)) {
                if (t.check()) TRACKS.remove(key);
                return;
            }
        } else if (t == null && TRACKS.size() >= MAX_TRACKS) {
            return;
        }
        Track fresh = Track.start(anims, hash, now, who); // another item, or not the next frame: start over
        if (fresh != null) TRACKS.put(key, fresh);
        else if (t != null) TRACKS.remove(key);
    }

    /** Which item shows a head, beyond its texture: menus put another item with the same skin in a slot. */
    private record Who(Component name, String uuid) {}

    private static Who who(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        String uuid = data == null ? null : ((CustomDataAccessor) (Object) data).skycosmetics$tag().getStringOr("uuid", null);
        return new Who(s.get(DataComponents.CUSTOM_NAME), uuid);
    }

    private static String texture(ItemStack s) {
        GameProfile p = SkinLearner.profile(s);
        return p == null ? null : SkinLearner.texture(p);
    }

    private static String hashOf(Index idx, String value) {
        String h = idx.byValue.get(value);
        if (h != null) return h;
        if (value.length() > MAX_VALUE) return null;
        h = SEEN.get(value);
        if (h == null) {
            if (SEEN.size() >= MAX_SEEN) SEEN.clear();
            String d = hash(value);
            h = d == null ? "" : d;
            SEEN.put(value, h);
        }
        return h.isEmpty() ? null : h;
    }

    /**
     * The skin texture's hash in a head's texture value ({@code textures.minecraft.net/texture/<hex>}), lower
     * case, or null. Hypixel's value for a frame may wrap the same texture differently than the repo's.
     */
    static String hash(String value) {
        if (value == null || value.length() > MAX_VALUE) return null;
        try {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '=') end--; // padding is optional, and some values get it wrong
            String json = new String(Base64.getDecoder().decode(value.substring(0, end)), StandardCharsets.UTF_8);
            int at = json.indexOf("/texture/", Math.max(0, json.indexOf("\"SKIN\"")));
            if (at < 0) return null;
            int from = at + 9;
            int to = from;
            while (to < json.length() && Character.digit(json.charAt(to), 16) >= 0) to++;
            return to - from >= 32 ? json.substring(from, to).toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What one slot or entity has shown since its head became a frame of a known animation. */
    private static final class Track {
        /** Animations the frames seen so far fit, each with the run the first frame may have been. */
        final List<Anim> anims = new ArrayList<>(2);
        final List<BitSet> offsets = new ArrayList<>(2);
        final Who who;
        String[] tex = new String[16];
        long[] at = new long[16];
        int n;

        private Track(Who who) {
            this.who = who;
        }

        /** Null when every animation showing {@code hash} was measured this session already. */
        static Track start(Anim[] anims, String hash, long now, Who who) {
            boolean open = false;
            for (Anim a : anims) open |= !SETTLED.contains(a.skin.id);
            if (!open) return null;
            Track t = new Track(who);
            for (Anim a : anims) {
                if (SETTLED.contains(a.skin.id)) continue;
                BitSet o = new BitSet(a.runs.length);
                for (int k = 0; k < a.runs.length; k++) if (a.runs[k].equals(hash)) o.set(k);
                t.anims.add(a);
                t.offsets.add(o);
            }
            t.tex[0] = hash;
            t.at[0] = now;
            t.n = 1;
            return t;
        }

        /** Appends the next frame; false if no animation shows it next. */
        boolean add(String hash, long now) {
            for (int i = anims.size() - 1; i >= 0; i--) {
                Anim a = anims.get(i);
                BitSet o = offsets.get(i);
                int r = a.runs.length;
                for (int k = o.nextSetBit(0); k >= 0; k = o.nextSetBit(k + 1)) if (!a.runs[(k + n) % r].equals(hash)) o.clear(k);
                if (o.isEmpty() || SETTLED.contains(a.skin.id)) {
                    anims.remove(i);
                    offsets.remove(i);
                }
            }
            if (anims.isEmpty()) return false;
            int r = anims.getFirst().runs.length;
            boolean sameR = true;
            for (Anim a : anims) sameR &= a.runs.length == r;
            if (n >= 2 + KEEP_ROUNDS * r) {
                if (!sameR) return false;
                // Drop the oldest round: positions move by a whole cycle, so every offset still holds.
                System.arraycopy(tex, r, tex, 0, n - r);
                System.arraycopy(at, r, at, 0, n - r);
                n -= r;
            }
            if (n == tex.length) {
                tex = Arrays.copyOf(tex, n * 2);
                at = Arrays.copyOf(at, n * 2);
            }
            tex[n] = hash;
            at[n] = now;
            n++;
            return true;
        }

        /** Learns every animation whose rounds agree; true once none is left to learn. */
        boolean check() {
            for (int i = anims.size() - 1; i >= 0; i--) {
                Anim a = anims.get(i);
                int r = a.runs.length;
                if (n < 2 + MIN_ROUNDS * r) continue;
                int[] runTicks = fit(at, n, r, offsets.get(i).nextSetBit(0), TimingLearner::clean);
                if (runTicks == null) continue;
                learn(a, runTicks);
                anims.remove(i);
                offsets.remove(i);
            }
            return anims.isEmpty();
        }
    }

    // ------------------------------------------------------------ fitting ---

    /** Whether nothing between two clock times may have been server lag. */
    interface Clean {
        boolean clean(long from, long to);
    }

    /**
     * Ticks per run from a head's frame changes: {@code at[0..n)} are the times frames appeared, the first
     * ({@code at[0]}, maybe just when the head came into view) as run {@code offset}, each next one as the next
     * run. Whole rounds of {@code r} frame durations are compared from the second frame on; a round must be
     * {@code clean} (no server lag), and of those, at least two and more than half must have every frame within
     * a tick or 10% of that frame's median. A frame of {@link #LONG_TICKS} or more and 4 times as long as any
     * other (a blink's open eyes) only has to be within half of its median; if it varied by more than 10%,
     * three rounds must agree. The medians of those rounds, rounded, are the result; null while there is no
     * such agreement, or a frame lasts over a minute. Pure, for the gametest too.
     */
    static int[] fit(long[] at, int n, int r, int offset, Clean clean) {
        int rounds = (n - 2) / r;
        if (rounds < MIN_ROUNDS || offset < 0) return null;
        double[][] d = new double[rounds][r];
        boolean[] use = new boolean[rounds];
        int usable = 0;
        for (int c = 0; c < rounds; c++) {
            int j0 = 1 + c * r;
            use[c] = clean.clean(at[j0], at[j0 + r]);
            if (use[c]) usable++;
            for (int q = 0; q < r; q++) d[c][(offset + j0 + q) % r] = (at[j0 + q + 1] - at[j0 + q]) / (double) TICK_NS;
        }
        if (usable < MIN_ROUNDS) return null;
        double[] med = new double[r];
        for (int k = 0; k < r; k++) med[k] = median(d, use, k);
        int open = 0;
        for (int k = 1; k < r; k++) if (med[k] > med[open]) open = k;
        double next = 0;
        for (int k = 0; k < r; k++) if (k != open) next = Math.max(next, med[k]);
        if (r < 2 || med[open] < LONG_TICKS || med[open] < next * 4) open = -1;
        boolean[] good = new boolean[rounds];
        int agree = 0;
        boolean varied = false;
        for (int c = 0; c < rounds; c++) {
            if (!use[c]) continue;
            boolean ok = true;
            for (int k = 0; k < r && ok; k++) ok = Math.abs(d[c][k] - med[k]) <= (k == open ? med[k] / 2 : Math.max(1.0, med[k] / 10));
            good[c] = ok;
            if (!ok) continue;
            agree++;
            varied |= open >= 0 && Math.abs(d[c][open] - med[open]) > Math.max(1.0, med[open] / 10);
        }
        if (agree < (varied ? MIN_ROUNDS_VARIED : MIN_ROUNDS) || agree * 2 <= usable) return null;
        int[] ticks = new int[r];
        for (int k = 0; k < r; k++) {
            double m = median(d, good, k);
            if (m > Timings.MAX_TICKS) return null;
            ticks[k] = (int) Math.max(1, Math.round(m));
        }
        return ticks;
    }

    private static double median(double[][] d, boolean[] use, int k) {
        double[] v = new double[d.length];
        int m = 0;
        for (int c = 0; c < d.length; c++) if (use[c]) v[m++] = d[c][k];
        Arrays.sort(v, 0, m);
        return m % 2 == 1 ? v[m / 2] : (v[m / 2 - 1] + v[m / 2]) / 2;
    }

    /** True if the server was not lagging between {@code from} and {@code to}, as far as time updates tell. */
    private static boolean clean(long from, long to) {
        if (from < lagHorizon) return false;
        for (int i = 0; i < lags; i++) if (LAG_FROM[i] < to && LAG_TO[i] > from) return false;
        // Lag in the last second shows only with the next time update. A server that sends none: trust the clock.
        return !hasTime || to <= timeAt;
    }

    private static void lag(long from, long to) {
        if (lags == MAX_LAGS) lagHorizon = Math.max(lagHorizon, LAG_TO[lagSlot]);
        else lags++;
        LAG_FROM[lagSlot] = from;
        LAG_TO[lagSlot] = to;
        lagSlot = (lagSlot + 1) % MAX_LAGS;
    }

    private static void resetLag() {
        hasTime = false;
        lags = 0;
        lagSlot = 0;
        lagHorizon = clock.getAsLong();
    }

    // ----------------------------------------------------------- learning ---

    /** {@code runTicks} measured for {@code a}: saved unless they match what the skin plays already. */
    private static void learn(Anim a, int[] runTicks) {
        SkinEntry s = a.skin;
        SETTLED.add(s.id);
        int[] ticks = frames(a, runTicks);
        // An estimate that turns out right is saved all the same: from then on it is measured, not estimated.
        if (close(ticks, s.frameTicks) && s.timing != SkinEntry.Timing.GUESSED) {
            SkyCosmetics.LOG.debug("Animation timing of {} confirmed: {}", s.id, Arrays.toString(s.frameTicks));
            return;
        }
        learned++;
        if (RECENT.size() == 3) RECENT.removeFirst();
        RECENT.addLast(s.name + " " + String.join("/", Arrays.stream(ticks).mapToObj(String::valueOf).toList()));
        SkyCosmetics.LOG.info("Learned the animation timing of {}: {} ticks per frame, was {}", s.id,
            Arrays.toString(ticks), Arrays.toString(s.frameTicks));
        Component news = !Looks.usedSkins().contains(s.id) ? null
            : Component.literal("Learned the animation timing of ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(s.name).withColor(s.color & 0xFFFFFF));
        Repo.learnTiming(s.id, s.textures, ticks, news);
    }

    /** Run ticks -> frame ticks: a run of several frames with one texture is split evenly, the rest on its first. */
    static int[] frames(Anim a, int[] runTicks) {
        int[] out = new int[a.skin.textures.length];
        for (int k = 0; k < runTicks.length; k++) {
            int len = a.len[k];
            int each = Math.max(1, runTicks[k] / len);
            for (int q = 1; q < len; q++) out[(a.first[k] + q) % out.length] = each;
            out[a.first[k]] = Math.max(1, runTicks[k] - each * (len - 1));
        }
        return out;
    }

    /** Within a tick (or 10%) per frame and a tick (or 5%) per cycle: the same timing, give or take measuring. */
    static boolean close(int[] a, int[] b) {
        if (a.length != b.length) return false;
        long sa = 0, sb = 0;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > Math.max(1, b[i] / 10)) return false;
            sa += a[i];
            sb += b[i];
        }
        return Math.abs(sa - sb) <= Math.max(1, sb / 20);
    }

    // -------------------------------------------------------------- index ---

    /** Indexes the frames of every animation in {@code c}. Repo thread, with each new catalog. */
    static void index(Catalog c) {
        Map<String, String> byValue = new HashMap<>();
        Map<String, String> canon = new HashMap<>();
        Map<String, List<Anim>> byHash = new HashMap<>();
        int timed = 0;
        int count = 0;
        for (SkinEntry e : c.skins.values()) {
            if (e.timing == SkinEntry.Timing.LEARNED) timed++;
            if (!e.animated()) continue;
            String[] hashes = new String[e.textures.length];
            for (int i = 0; i < hashes.length; i++) {
                String v = e.textures[i];
                String h = byValue.get(v);
                if (h == null && (h = hash(v)) != null) {
                    h = canon.computeIfAbsent(h, k -> k);
                    byValue.put(v, h);
                }
                hashes[i] = h;
            }
            Anim a = anim(e, hashes);
            if (a == null) continue;
            count++;
            for (String h : new LinkedHashSet<>(Arrays.asList(a.runs))) byHash.computeIfAbsent(h, k -> new ArrayList<>(1)).add(a);
        }
        Map<String, Anim[]> arrays = new HashMap<>(byHash.size() * 2);
        byHash.forEach((h, l) -> arrays.put(h, l.toArray(Anim[]::new)));
        index = new Index(byValue, arrays, count);
        timedSkins = timed;
    }

    /** {@code e}'s frames as runs, or null if a frame has no texture hash or every frame looks the same. */
    static Anim anim(SkinEntry e, String[] hashes) {
        int n = hashes.length;
        for (String h : hashes) if (h == null) return null;
        int start = -1;
        for (int i = 0; i < n && start < 0; i++) if (!hashes[i].equals(hashes[(i + n - 1) % n])) start = i;
        if (start < 0) return null;
        List<String> runs = new ArrayList<>();
        List<Integer> first = new ArrayList<>();
        List<Integer> len = new ArrayList<>();
        for (int i = 0; i < n; ) {
            int f = (start + i) % n;
            int l = 1;
            while (i + l < n && hashes[(start + i + l) % n].equals(hashes[f])) l++;
            runs.add(hashes[f]);
            first.add(f);
            len.add(l);
            i += l;
        }
        return new Anim(e, runs.toArray(String[]::new), first.stream().mapToInt(Integer::intValue).toArray(),
            len.stream().mapToInt(Integer::intValue).toArray());
    }

    // -------------------------------------------------------------- debug ---

    /** One line for {@code /skycosmetics debug}. */
    public static String status() {
        Index idx = index;
        StringBuilder b = new StringBuilder("Animation timing: ").append(enabled ? "learning" : "off").append("; ")
            .append("timing learned in game for ").append(Names.count(timedSkins, "skin"));
        if (learned > 0) b.append(" (this session: ").append(String.join(", ", RECENT)).append(learned > RECENT.size() ? ", ..." : "").append(')');
        b.append("; watching ").append(Names.count(TRACKS.size(), "head"));
        if (idx != null) b.append(" (").append(idx.animations).append(" animations known)");
        return b.toString();
    }

    /** Catalog skins wearing a timing learned in game. */
    public static int timedSkins() {
        return timedSkins;
    }

    /** Heads being watched right now. */
    public static int watching() {
        return TRACKS.size();
    }

    /** For tests: the clock packets are stamped with, in nanoseconds ({@link System#nanoTime} unless set). */
    public static void useClock(LongSupplier c) {
        clock = c == null ? System::nanoTime : c;
        TRACKS.clear();
        resetLag();
    }
}
