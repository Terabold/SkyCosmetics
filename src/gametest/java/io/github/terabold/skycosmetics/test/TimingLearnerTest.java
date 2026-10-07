package io.github.terabold.skycosmetics.test;

import com.mojang.datafixers.util.Pair;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.TimingLearner;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Frame timing learned from head updates: fake packets play a known blink pattern (with jitter, a
 * lag spike, a mid-cycle start on a repeated texture, and a texture value wrapped differently than
 * the repo's) through the observation path, and the catalog must end up playing exactly that. A
 * timing that matches the repo's is not saved, still heads are never watched, a timing for the
 * wrong number of frames is ignored, and an unreadable timings.json is set aside without losing
 * what was learned.
 */
public class TimingLearnerTest implements FabricClientGameTest {
    /** 4 frames, the 2nd and 4th the same texture (half-closed eyes); the repo says 51, 2, 4, 2. */
    private static final String BLINK = "PET_SKIN_RABBIT_GARDEN_BUNNY_DAISY";
    /** 3 frames, 10 ticks each in the repo. */
    private static final String UNIFORM = "NECRON_DIAMOND_KNIGHT_BLACK";
    /** 55, 2, 4, 2 in the repo, played as such. */
    private static final String SAME = "PET_SKIN_RABBIT_GARDEN_BUNNY_CITRUS";
    private static final long MS = 1_000_000L;

    private static final long[] NOW = {1_000_000_000_000L};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        deleteTimings();
        reload(ctx);
        Catalog before = Repo.get();
        ctx.runOnClient(mc -> {
            for (String id : List.of(BLINK, UNIFORM, SAME)) {
                SkinEntry e = before.skin(id);
                check(e != null && e.animated() && !e.timed, id + " is an animated repo skin");
            }
            check(Arrays.equals(before.skin(BLINK).frameTicks, new int[]{51, 2, 4, 2}), "the repo times " + BLINK + " per frame");
            TimingLearner.useClock(() -> NOW[0]);
        });

        // A stand's head blinks 40, 2, 6, 2, first seen on a half-closed frame (a texture two frames
        // share), with a 20-tick hitch on one frame of the first round.
        ctx.runOnClient(mc -> {
            String[] frames = before.skin(BLINK).textures;
            play(s -> TimingLearner.onEquipment(new ClientboundSetEquipmentPacket(5001, List.of(Pair.of(EquipmentSlot.HEAD, s)))),
                frames, new int[]{40, 2, 6, 2}, 1, 5, 0);
        });
        ctx.waitFor(mc -> Repo.get().skin(BLINK).timed, 20 * 10);
        ctx.runOnClient(mc -> {
            SkinEntry e = Repo.get().skin(BLINK);
            check(Arrays.equals(e.frameTicks, new int[]{40, 2, 6, 2}), "learned blink timing: " + Arrays.toString(e.frameTicks));
            check(e.cycle() == 50, "cycle " + e.cycle());
            int[] want = new int[50];
            Arrays.fill(want, 40, 42, 1);
            Arrays.fill(want, 42, 48, 2);
            Arrays.fill(want, 48, 50, 3);
            for (int t = 0; t < 150; t++) check(e.frameAt(t) == want[t % 50], "frame at tick " + t + ": " + e.frameAt(t));
            check(TimingLearner.watching() == 0, "a learned head is no longer watched");
        });

        // Your helmet slot flashes 50, 2, 4, with texture values wrapped differently than the repo's.
        ctx.runOnClient(mc -> {
            String[] frames = Arrays.stream(before.skin(UNIFORM).textures).map(TimingLearnerTest::rewrap).toArray(String[]::new);
            play(s -> TimingLearner.onSlot(new ClientboundContainerSetSlotPacket(0, 0, 5, s)), frames, new int[]{50, 2, 4}, 0, 3, -1);
        });
        ctx.waitFor(mc -> Repo.get().skin(UNIFORM).timed, 20 * 10);
        check(ctx.computeOnClient(mc -> Arrays.equals(Repo.get().skin(UNIFORM).frameTicks, new int[]{50, 2, 4})),
            "learned flash timing from rewrapped textures");

        // An item display plays the repo's own timing: confirmed, nothing saved.
        ctx.runOnClient(mc -> {
            String[] frames = before.skin(SAME).textures;
            play(s -> TimingLearner.onEntityData(new ClientboundSetEntityDataPacket(5002,
                List.<SynchedEntityData.DataValue<?>>of(new SynchedEntityData.DataValue<>(23, EntityDataSerializers.ITEM_STACK, s)))),
                frames, new int[]{55, 2, 4, 2}, 0, 3, -1);
            check(TimingLearner.watching() == 0, "a confirmed head is no longer watched");
        });

        // Still heads are never watched, and a head that turns into one is dropped.
        ctx.runOnClient(mc -> {
            Set<String> frames = new HashSet<>();
            for (SkinEntry e : before.animatedTab) frames.addAll(Arrays.asList(e.textures));
            List<SkinEntry> stills = before.skinTab.stream().filter(e -> !e.animated() && !frames.contains(e.textures[0])).limit(2).toList();
            for (int i = 0; i < 6; i++) {
                ItemStack s = head(stills.get(i % 2).textures[0]);
                TimingLearner.onEquipment(new ClientboundSetEquipmentPacket(5003, List.of(Pair.of(EquipmentSlot.HEAD, s))));
                NOW[0] += 40 * MS;
            }
            check(TimingLearner.watching() == 0, "still heads are not watched");
            String[] anim = untimed();
            TimingLearner.onEquipment(new ClientboundSetEquipmentPacket(5004, List.of(Pair.of(EquipmentSlot.HEAD, head(anim[0])))));
            check(TimingLearner.watching() == 1, "an animated head is watched");
            TimingLearner.onEquipment(new ClientboundSetEquipmentPacket(5004, List.of(Pair.of(EquipmentSlot.HEAD, ItemStack.EMPTY))));
            check(TimingLearner.watching() == 0, "a removed head is dropped");
        });
        ctx.waitTicks(20); // the repo thread's batch window
        ctx.runOnClient(mc -> {
            Catalog after = Repo.get();
            check(!after.skin(SAME).timed, "a timing equal to the repo's is not saved");
            String[] blink = before.skin(BLINK).textures, flash = before.skin(UNIFORM).textures;
            for (Map.Entry<String, SkinEntry> e : before.skins.entrySet()) {
                String[] t = e.getValue().textures;
                if (Arrays.equals(t, blink) || Arrays.equals(t, flash)) continue; // learned (or the same animation)
                SkinEntry now = after.skins.get(e.getKey());
                check(now != null && !now.timed && Arrays.equals(now.frameTicks, e.getValue().frameTicks), "untouched: " + e.getKey());
            }
        });
        String saved = read(timings());
        check(saved.contains(BLINK) && saved.contains(UNIFORM) && !saved.contains(SAME), "timings.json holds what was learned: " + saved);
        System.out.println("[SkyCosmeticsTest] learned timings: " + saved.replaceAll("\\s+", " "));

        brokenFile(ctx);

        // The real packet hook, in a world: a head on an entity is watched until it goes.
        ctx.runOnClient(mc -> TimingLearner.useClock(null));
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                String[] anim = untimed();
                mc.getConnection().handleSetEquipment(new ClientboundSetEquipmentPacket(424242, List.of(Pair.of(EquipmentSlot.HEAD, head(anim[0])))));
                check(TimingLearner.watching() == 1, "the equipment hook feeds the learner");
                mc.getConnection().handleSetEquipment(new ClientboundSetEquipmentPacket(424242, List.of(Pair.of(EquipmentSlot.HEAD, ItemStack.EMPTY))));
                check(TimingLearner.watching() == 0, "and drops the head when it goes");
                System.out.println("[SkyCosmeticsTest] " + TimingLearner.status());
            });
        }

        deleteTimings();
        reload(ctx);
        check(ctx.computeOnClient(mc -> !Repo.get().skin(BLINK).timed
            && Arrays.equals(Repo.get().skin(BLINK).frameTicks, new int[]{51, 2, 4, 2})), "without timings.json the repo timing is back");
        System.out.println("[SkyCosmeticsTest] timing learner checks passed");
    }

    /**
     * Plays {@code rounds} rounds of {@code frames} with {@code ticks} per frame through {@code send}, starting at frame
     * {@code start}, each update up to 12 ms late or early; the second update of round {@code spike} stays 20 ticks longer.
     */
    private static void play(Consumer<ItemStack> send, String[] frames, int[] ticks, int start, int rounds, int spike) {
        ItemStack[] heads = Arrays.stream(frames).map(TimingLearnerTest::head).toArray(ItemStack[]::new);
        long t = NOW[0];
        int n = frames.length;
        for (int i = 0; i <= rounds * n; i++) {
            int f = (start + i) % n;
            NOW[0] = t + (i % 3 - 1) * 12 * MS;
            send.accept(heads[f]);
            t += (ticks[f] + (i == spike * n + 1 ? 20 : 0)) * 50 * MS;
        }
        NOW[0] = t + 1000 * MS;
    }

    /** An unreadable timings.json is set aside and what was learned stays; a timing for another frame count is ignored. */
    private static void brokenFile(ClientGameTestContext ctx) {
        write(timings(), "{\"skins\": {oops");
        reload(ctx);
        List<Path> aside = brokenTimings();
        check(aside.size() == 1 && read(aside.getFirst()).contains("oops"), "the unreadable file is set aside: " + aside);
        check(ctx.computeOnClient(mc -> Repo.get().skin(BLINK).timed && Repo.get().skin(UNIFORM).timed), "learned timings are kept");
        check(read(timings()).contains(BLINK), "and saved again");

        write(timings(), "{\"skins\": {\"" + UNIFORM + "\": {\"frames\": 2, \"ticksPerTexture\": [5, 5]},"
            + " \"" + BLINK + "\": {\"frames\": 4, \"ticksPerTexture\": [0, 99999, 4, 2]},"
            + " \"" + SAME + "\": {\"frames\": 4, \"ticksPerTexture\": [30, 3, 3, 3]}}}");
        reload(ctx);
        ctx.runOnClient(mc -> {
            Catalog c = Repo.get();
            check(!c.skin(UNIFORM).timed && !c.skin(BLINK).timed, "a timing for another frame count, or out of range, is ignored");
            check(c.skin(SAME).timed && Arrays.equals(c.skin(SAME).frameTicks, new int[]{30, 3, 3, 3}), "a saved timing is read back");
        });
        aside.forEach(TimingLearnerTest::delete);
        System.out.println("[SkyCosmeticsTest] broken timings.json checks passed");
    }

    // ------------------------------------------------------------ helpers ---

    /** Frames of an animation none of the checks above learned or confirmed. */
    private static String[] untimed() {
        return Repo.get().animatedTab.stream().filter(e -> !e.timed && e.textures.length > 2 && !List.of(BLINK, UNIFORM, SAME).contains(e.id)
            && !e.id.startsWith("PET_SKIN_RABBIT_GARDEN_BUNNY") && !e.id.startsWith("NECRON_DIAMOND_KNIGHT")).findFirst().orElseThrow().textures;
    }

    private static ItemStack head(String value) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, Textures.profile(value));
        return s;
    }

    /** The same skin texture in a texture value the way another server might wrap it. */
    private static String rewrap(String value) {
        String json = new String(Base64.getDecoder().decode(value.replace("=", "")), StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(("{\"timestamp\":1700000000000,\"profileName\":\"x\","
            + json.trim().substring(1)).getBytes(StandardCharsets.UTF_8));
    }

    /** Forces a reload (captured.json, timings.json and the repo) and waits for it. */
    private static void reload(ClientGameTestContext ctx) {
        AtomicBoolean done = new AtomicBoolean();
        Repo.reload(() -> done.set(true));
        ctx.waitFor(mc -> done.get(), 20 * 120);
        ctx.waitTicks(2);
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics");
    }

    private static Path timings() {
        return dir().resolve("timings.json");
    }

    private static List<Path> brokenTimings() {
        try (Stream<Path> s = Files.list(dir())) {
            return s.filter(p -> p.getFileName().toString().startsWith("timings.broken-")).toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void deleteTimings() {
        delete(timings());
        brokenTimings().forEach(TimingLearnerTest::delete);
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path p, String s) {
        try {
            Files.writeString(p, s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
