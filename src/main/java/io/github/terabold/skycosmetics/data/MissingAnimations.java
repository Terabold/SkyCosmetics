package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.mixin.CustomDataAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Records missing frames from incoming item updates only. Never requests updates or changes an item. */
final class MissingAnimations {
    private static final int MAX_TRACKS = 16, MAX_SAMPLES = 2 * Captured.MAX_FRAMES + 2;
    private static final long TICK_NS = 50_000_000L, IDLE_NS = 15_000_000_000L;
    private static final Map<Long, Track> TRACKS = new HashMap<>();
    private static final class Track {
        final String skin, uuid;
        final Component name;
        final long start;
        final long[] at = new long[MAX_SAMPLES];
        final List<String> values = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();
        long last;
        Track(String skin, String uuid, Component name, long now) { this.skin = skin; this.uuid = uuid; this.name = name; start = last = now; }
    }
    private MissingAnimations() {}

    static void reset() { TRACKS.clear(); }
    static void openMenu() { TRACKS.keySet().removeIf(k -> k >>> 56 == 2 && (k >>> 16 & 0xFFFFFFFFL) != 0); }
    static void expire(long now) { TRACKS.values().removeIf(t -> now - t.last > IDLE_NS); }

    static void observe(long slot, ItemStack stack, String value, String hash, long now) {
        if (!Settings.learnSkins || value == null || hash == null) { TRACKS.remove(slot); return; }
        String skin = SkinLearner.wornSkin(stack);
        if (skin == null) { TRACKS.remove(slot); return; }
        var data = stack.get(DataComponents.CUSTOM_DATA);
        String uuid = data == null ? null : ((CustomDataAccessor)(Object)data).skycosmetics$tag().getStringOr("uuid", null);
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        Track t = TRACKS.get(slot);
        if (t == null || !t.skin.equals(skin) || !Objects.equals(t.uuid, uuid) || !Objects.equals(t.name, name)) {
            if (t == null && TRACKS.size() >= MAX_TRACKS) return;
            t = new Track(skin, uuid, name, now); TRACKS.put(slot, t);
        }
        if (!t.hashes.isEmpty() && t.hashes.getLast().equals(hash)) return;
        if (!SkinLearner.mojangTexture(value)) { TRACKS.remove(slot); return; }
        if (t.values.size() >= MAX_SAMPLES || now < t.last || now - t.last > IDLE_NS) {
            TRACKS.remove(slot); return;
        }
        t.at[t.values.size()] = Math.round((now - t.start) / (double) TICK_NS);
        t.values.add(value); t.hashes.add(hash); t.last = now;
        finish(slot, t);
    }

    /** The next server clock update can confirm that the last observed round was free of server lag. */
    static void time() {
        for (var e : List.copyOf(TRACKS.entrySet())) finish(e.getKey(), e.getValue());
    }

    private static void finish(long slot, Track t) {
        if (!Settings.learnSkins) { TRACKS.remove(slot); return; }
        int n = t.values.size();
        if (n < 6 || !t.hashes.getLast().equals(t.hashes.get(1))) return;
        PreviewRecorder.Fit fit = PreviewRecorder.fit(t.at, t.hashes, true);
        if (fit == null || fit.frames() > Captured.MAX_FRAMES || !TimingLearner.clean(t.start, t.last)) return;
        // Two visually identical rounds must also agree on their frame durations.
        for (int i = 1; i <= fit.frames(); i++) {
            long a = t.at[i + 1] - t.at[i], b = t.at[i + fit.frames() + 1] - t.at[i + fit.frames()];
            if (a <= 0 || b <= 0 || Math.abs(a - b) > Math.max(1, Math.min(a, b) / 10)) return;
        }
        SkinEntry current = Repo.get().skin(t.skin);
        SkinEntry root = current != null ? Repo.get().root(current) : Repo.get().skin(RepoParser.parentOf(t.skin, Repo.get().skins));
        if (root == null) { TRACKS.remove(slot); return; }
        String name = current != null ? current.name : root.name + " (Captured style)";
        String[] frames = t.values.subList(fit.start(), fit.start() + fit.frames()).toArray(String[]::new);
        Repo.learn(t.skin, new Captured.Anim(name, root.color, fit.ticks(), fit.ticksPerTexture(), frames, System.currentTimeMillis(), Captured.Evidence.REPEATED_UPDATES),
            Component.literal("Learned the animation of " + name).withColor(root.color & 0xFFFFFF));
        TRACKS.remove(slot);
    }
}
