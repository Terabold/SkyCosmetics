package io.github.terabold.skycosmetics.test;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.BlinkGuesser;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.data.TimingLearner;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blink timing without the repo's help, and learning in menus. Synthetic skins: eyes that close (a blink) get a
 * long open frame and short closed ones; a color cycle, a moving dot, and five frames do not. Then real files:
 * blink frames written where Minecraft caches skins are found and estimated for a learned skin the repo would
 * time evenly, never for one timed per frame; the sources layer learned over shipped over estimated over repo,
 * and an estimate is dropped when the frames change. Last, menu slots: a skin is learned from a slot, an open
 * frame that varies per round is learned by its median, another item in the slot starts over, and a new menu
 * forgets every menu slot but the player's inventory.
 */
final class BlinkTimingTest {
    private static final String BLINK = "SKYCOSMETICS_TEST_BLINK";
    private static final String CYCLE = "SKYCOSMETICS_TEST_CYCLE";
    private static final String MEASURED = "SKYCOSMETICS_TEST_MEASURED";
    private static final int SKIN = 0xFFE0B080;
    private static final int LID = 0xFFC89868;
    private static final long MS = 1_000_000L;
    private static final long[] NOW = {2_000_000_000_000L};

    private BlinkTimingTest() {}

    static void run(ClientGameTestContext ctx) {
        pictures();
        files(ctx);
        menus(ctx);
        System.out.println("[SkyCosmeticsTest] blink timing checks passed");
    }

    // ----------------------------------------------------------- pictures ---

    private static void pictures() {
        int[] blink = BlinkGuesser.guess(heads(face(Eyes.OPEN), face(Eyes.HALF), face(Eyes.CLOSED)));
        check(Arrays.equals(blink, new int[]{49, 2, 4}), "a blink: long open, short half and closed: " + Arrays.toString(blink));
        int[] four = BlinkGuesser.guess(heads(face(Eyes.HALF), face(Eyes.CLOSED), face(Eyes.HALF), face(Eyes.OPEN)));
        check(Arrays.equals(four, new int[]{2, 4, 2, 49}), "a four-frame blink starting half closed: " + Arrays.toString(four));
        int[] two = BlinkGuesser.guess(heads(face(Eyes.CLOSED), face(Eyes.OPEN)));
        check(Arrays.equals(two, new int[]{4, 49}), "a two-frame blink: " + Arrays.toString(two));

        check(BlinkGuesser.guess(heads(fill(0xFFFF4040), fill(0xFF40FF40), fill(0xFF4040FF))) == null, "a color cycle is no blink");
        check(BlinkGuesser.guess(heads(dot(0), dot(2), dot(4))) == null, "a moving dot is no blink");
        check(BlinkGuesser.guess(heads(face(Eyes.OPEN), face(Eyes.HALF), face(Eyes.CLOSED), face(Eyes.HALF), face(Eyes.OPEN))) == null,
            "five frames are no blink");
        check(BlinkGuesser.guess(heads(face(Eyes.OPEN), face(Eyes.OPEN))) == null, "frames that look the same are no blink");

        // A 64x32 skin: an outer area with no see-through pixel is not drawn (Minecraft's rule), one with any is.
        int[] legacy = new int[64 * 32];
        Arrays.fill(legacy, SKIN);
        for (int y = 0; y < 32; y++) for (int x = 32; x < 64; x++) legacy[y * 64 + x] = 0xFFFF0000;
        int[] head = BlinkGuesser.head(legacy, 64, 32);
        check(head != null && head[9 * 32 + 10] == (SKIN & 0xFFFFFF) && head[0] == -1, "an opaque 64x32 outer layer is ignored");
        legacy[20 * 64 + 60] = 0;
        check(BlinkGuesser.head(legacy, 64, 32)[9 * 32 + 10] == 0xFF0000, "a 64x32 outer layer with a hole shows");
        check(BlinkGuesser.head(new int[128 * 128], 128, 128) == null, "other sizes are no skins");
    }

    private enum Eyes { OPEN, HALF, CLOSED }

    /** A 64x64 skin with a plain head and two eyes on the front face (columns 8-15, rows 8-15). */
    private static int[] face(Eyes eyes) {
        int[] px = fill(SKIN);
        for (int eye : new int[]{9, 13}) {
            switch (eyes) {
                case OPEN -> {
                    set(px, eye, 11, 0xFFFFFFFF);
                    set(px, eye + 1, 11, 0xFFFFFFFF);
                    set(px, eye, 12, 0xFFFFFFFF);
                    set(px, eye + 1, 12, 0xFF101010);
                }
                case HALF -> {
                    set(px, eye, 12, 0xFFFFFFFF);
                    set(px, eye + 1, 12, 0xFF101010);
                }
                case CLOSED -> {
                    set(px, eye, 12, LID);
                    set(px, eye + 1, 12, LID);
                }
            }
        }
        return px;
    }

    private static int[] dot(int x) {
        int[] px = fill(SKIN);
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) set(px, 9 + x + dx, 11 + dy, 0xFF101010);
        return px;
    }

    /** Head and body one color, the outer layer see-through. */
    private static int[] fill(int argb) {
        int[] px = new int[64 * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) px[y * 64 + x] = y < 16 && x >= 32 ? 0 : argb;
        return px;
    }

    private static void set(int[] px, int x, int y, int argb) {
        px[y * 64 + x] = argb;
    }

    private static int[][] heads(int[]... skins) {
        return Arrays.stream(skins).map(s -> BlinkGuesser.head(s, 64, 64)).toArray(int[][]::new);
    }

    // -------------------------------------------------------------- files ---

    private static void files(ClientGameTestContext ctx) {
        Path captured = dir().resolve("captured.json"), timings = dir().resolve("timings.json"), estimated = dir().resolve("estimated-timings.json");
        String hadCaptured = readOrNull(captured), hadTimings = readOrNull(timings), hadEstimated = readOrNull(estimated);
        List<Path> written = new ArrayList<>();
        try {
            String[] blink = values(0, 3), cycle = values(10, 3), measured = values(20, 3);
            write(captured, capturedJson(blink, cycle, measured));
            delete(timings);
            delete(estimated);
            Repo.useBundledTimings("{\"skins\": {}}");
            reload(ctx);
            ctx.runOnClient(mc -> {
                SkinEntry e = Repo.get().skin(BLINK);
                check(e != null && e.timing == SkinEntry.Timing.REPO && !e.perFrame && Arrays.equals(e.frameTicks, new int[]{10, 10, 10}),
                    "the test blink plays the even timing first");
                check(Repo.get().skin(MEASURED).perFrame, "ticksPerTexture marks a skin timed per frame");
                check(BlinkGuesser.skinFile(blink[0]) != null, "the skin folder is known");
            });

            // The frames land in Minecraft's skin cache, as a download would leave them.
            ctx.runOnClient(mc -> {
                written.addAll(writeSkins(blink, face(Eyes.CLOSED), face(Eyes.OPEN), face(Eyes.HALF)));
                written.addAll(writeSkins(cycle, fill(0xFFFF4040), fill(0xFF40FF40), fill(0xFF4040FF)));
                written.addAll(writeSkins(measured, face(Eyes.OPEN), face(Eyes.HALF), face(Eyes.CLOSED)));
                BlinkGuesser.nudge();
            });
            ctx.waitFor(mc -> Repo.get().skin(BLINK).timing == SkinEntry.Timing.GUESSED, 20 * 15);
            ctx.waitFor(mc -> readOrNull(estimated) != null && readOrNull(estimated).contains(CYCLE), 20 * 10);
            ctx.runOnClient(mc -> {
                SkinEntry e = Repo.get().skin(BLINK);
                check(Arrays.equals(e.frameTicks, new int[]{4, 49, 2}), "estimated blink: " + Arrays.toString(e.frameTicks));
                check(e.cycle() == 55 && e.frameAt(0) == 0 && e.frameAt(4) == 1 && e.frameAt(52) == 1 && e.frameAt(53) == 2,
                    "plays the estimate");
                SkinEntry c = Repo.get().skin(CYCLE);
                check(c.timing == SkinEntry.Timing.REPO && Arrays.equals(c.frameTicks, new int[]{10, 10, 10}), "a color cycle keeps its timing");
                SkinEntry m = Repo.get().skin(MEASURED);
                check(m.timing == SkinEntry.Timing.REPO && Arrays.equals(m.frameTicks, new int[]{10, 10, 10}), "a skin timed per frame is not estimated");
                System.out.println("[SkyCosmeticsTest] " + BlinkGuesser.status());
            });
            String saved = read(estimated);
            check(saved.contains(BLINK) && saved.contains("49") && saved.contains(CYCLE) && !saved.contains(MEASURED),
                "estimated-timings.json holds both answers: " + saved);

            // Shipped beats estimated; learned beats shipped.
            Repo.useBundledTimings("{\"skins\": {\"" + BLINK + "\": {\"frames\": 3, \"ticksPerTexture\": [30, 5, 5]},"
                + " \"" + MEASURED + "\": {\"frames\": 3, \"ticksPerTexture\": [7, 8, 9]}}}");
            reload(ctx);
            expect(ctx, BLINK, SkinEntry.Timing.BUNDLED, 30, 5, 5);
            expect(ctx, MEASURED, SkinEntry.Timing.BUNDLED, 7, 8, 9);
            write(timings, "{\"skins\": {\"" + BLINK + "\": {\"frames\": 3, \"ticksPerTexture\": [40, 3, 3]}}}");
            reload(ctx);
            expect(ctx, BLINK, SkinEntry.Timing.LEARNED, 40, 3, 3);
            delete(timings);
            reload(ctx);
            expect(ctx, BLINK, SkinEntry.Timing.BUNDLED, 30, 5, 5);

            // Without the pictures the saved estimate still holds; with other frames it no longer does.
            written.forEach(BlinkTimingTest::delete);
            Repo.useBundledTimings("{\"skins\": {}}");
            reload(ctx);
            expect(ctx, BLINK, SkinEntry.Timing.GUESSED, 4, 49, 2);
            write(captured, capturedJson(new String[]{blink[0], blink[1], values(30, 1)[0]}, cycle, measured));
            reload(ctx);
            expect(ctx, BLINK, SkinEntry.Timing.REPO, 10, 10, 10);
        } finally {
            written.forEach(BlinkTimingTest::delete);
            restore(captured, hadCaptured);
            restore(timings, hadTimings);
            restore(estimated, hadEstimated);
            Repo.useBundledTimings(null);
            reload(ctx);
        }
        check(ctx.computeOnClient(mc -> Repo.get().skin(BLINK) == null), "the test skins are gone again");
        System.out.println("[SkyCosmeticsTest] estimated and layered timing checks passed");
    }

    private static void expect(ClientGameTestContext ctx, String id, SkinEntry.Timing source, int... ticks) {
        ctx.runOnClient(mc -> {
            SkinEntry e = Repo.get().skin(id);
            check(e != null && e.timing == source && Arrays.equals(e.frameTicks, ticks),
                id + " plays " + source + " " + Arrays.toString(ticks) + ": " + (e == null ? null : e.timing + " " + Arrays.toString(e.frameTicks)));
        });
    }

    /** {@code n} texture values with made-up skin hashes, numbered from {@code from}. */
    private static String[] values(int from, int n) {
        String[] v = new String[n];
        for (int i = 0; i < n; i++) {
            String hash = String.format("5ca1ab1e%056x", from + i + 1);
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
            v[i] = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        return v;
    }

    private static String capturedJson(String[] blink, String[] cycle, String[] measured) {
        return "{\"version\": 1, \"skins\": {}, \"animated\": {"
            + "\"" + BLINK + "\": {\"ticks\": 10, \"textures\": " + array(blink) + ", \"name\": \"Test Blink\"},"
            + "\"" + CYCLE + "\": {\"ticks\": 10, \"textures\": " + array(cycle) + ", \"name\": \"Test Cycle\"},"
            + "\"" + MEASURED + "\": {\"ticks\": 10, \"ticksPerTexture\": [10, 10, 10], \"textures\": " + array(measured) + "}}}";
    }

    private static String array(String[] v) {
        return "[\"" + String.join("\", \"", v) + "\"]";
    }

    private static List<Path> writeSkins(String[] values, int[]... skins) {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Path p = BlinkGuesser.skinFile(values[i]);
            try (NativeImage img = new NativeImage(64, 64, true)) {
                for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) img.setPixel(x, y, skins[i][y * 64 + x]);
                Files.createDirectories(p.getParent());
                img.writeToFile(p);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            out.add(p);
        }
        return out;
    }

    // -------------------------------------------------------------- menus ---

    private static void menus(ClientGameTestContext ctx) {
        String slotSkin = "PET_SKIN_JELLYFISH_CUTE_PINK", variedSkin = "PET_SKIN_SHEEP_MONSTER_TOXIC", swapSkin = "PET_SKIN_FROG_BIOME_SWAMP";
        Path timings = dir().resolve("timings.json");
        String hadTimings = readOrNull(timings);
        try {
            ctx.runOnClient(mc -> {
                for (String id : List.of(slotSkin, variedSkin, swapSkin)) {
                    SkinEntry e = Repo.get().skin(id);
                    check(e != null && e.animated() && e.timing != SkinEntry.Timing.LEARNED, id + " is an animated skin not learned yet");
                }
                TimingLearner.useClock(() -> NOW[0]);

                // A pet menu slot animates 50, 2, 4.
                String[] frames = Repo.get().skin(slotSkin).textures;
                play(slot(7, 13), frames, new int[][]{{50, 2, 4}, {50, 2, 4}, {50, 2, 4}, {50, 2, 4}}, "Pet A", null);
            });
            ctx.waitFor(mc -> Repo.get().skin(slotSkin).timing == SkinEntry.Timing.LEARNED, 20 * 10);
            expect(ctx, slotSkin, SkinEntry.Timing.LEARNED, 50, 2, 4);

            // The open frame lasts 40 to 61 ticks, a different time each round: learned by its median.
            ctx.runOnClient(mc -> play(slot(8, 20), Repo.get().skin(variedSkin).textures,
                new int[][]{{58, 2, 4}, {40, 2, 4}, {61, 2, 4}, {47, 2, 4}, {52, 2, 4}, {44, 2, 4}}, "Pet B", null));
            ctx.waitFor(mc -> Repo.get().skin(variedSkin).timing == SkinEntry.Timing.LEARNED, 20 * 10);
            ctx.runOnClient(mc -> {
                int[] t = Repo.get().skin(variedSkin).frameTicks;
                check(t[0] >= 44 && t[0] <= 58 && t[1] == 2 && t[2] == 4, "a varying open frame is learned by its median: " + Arrays.toString(t));
            });

            // Two pets with the same skin take turns in a slot, each showing the next frame: never learned.
            ctx.runOnClient(mc -> {
                play(slot(8, 30), Repo.get().skin(swapSkin).textures, new int[][]{{50, 4}, {50, 4}, {50, 4}, {50, 4}}, "Pet C", "Pet D");
                check(TimingLearner.watching() == 1, "the slot is watched, started over each time");
            });
            ctx.waitTicks(20);
            check(ctx.computeOnClient(mc -> Repo.get().skin(swapSkin).timing != SkinEntry.Timing.LEARNED),
                "frames of different items in one slot are not timed together");

            // A new menu forgets what menu slots showed, but not the player's inventory.
            ctx.runOnClient(mc -> {
                String[] frames = Repo.get().skin(swapSkin).textures;
                slot(0, 36).accept(head(frames[0], "Helmet"));
                check(TimingLearner.watching() == 2, "a menu slot and an inventory slot are watched");
                TimingLearner.onOpenScreen(new ClientboundOpenScreenPacket(9, MenuType.GENERIC_9x6, Component.literal("Pets")));
                check(TimingLearner.watching() == 1, "a new menu drops the menu slot only");
                slot(0, 36).accept(ItemStack.EMPTY);
                check(TimingLearner.watching() == 0, "an emptied slot is dropped");
            });
        } finally {
            ctx.runOnClient(mc -> TimingLearner.useClock(null));
            restore(timings, hadTimings);
            reload(ctx);
        }
        System.out.println("[SkyCosmeticsTest] menu timing checks passed");
    }

    private interface Send {
        void accept(ItemStack s);
    }

    private static Send slot(int container, int slot) {
        return s -> TimingLearner.onSlot(new ClientboundContainerSetSlotPacket(container, 0, slot, s));
    }

    /**
     * Plays one round per row of {@code rounds} (ticks per frame) through {@code send}, then the first frame once
     * more; the items are named {@code name}, every other update {@code other} instead when it is not null.
     */
    private static void play(Send send, String[] frames, int[][] rounds, String name, String other) {
        long t = NOW[0];
        int i = 0;
        for (int[] round : rounds) {
            for (int f = 0; f < frames.length; f++, i++) {
                NOW[0] = t;
                send.accept(head(frames[f], other != null && i % 2 == 1 ? other : name));
                t += round[f] * 50 * MS;
            }
        }
        NOW[0] = t;
        send.accept(head(frames[0], other != null && i % 2 == 1 ? other : name));
        NOW[0] = t + 1000 * MS;
    }

    private static ItemStack head(String value, String name) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponents.PROFILE, Textures.profile(value));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    // ------------------------------------------------------------ helpers ---

    private static void reload(ClientGameTestContext ctx) {
        AtomicBoolean done = new AtomicBoolean();
        Repo.reload(() -> done.set(true));
        ctx.waitFor(mc -> done.get(), 20 * 120);
        ctx.waitTicks(2);
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics");
    }

    private static String readOrNull(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            return null;
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
            Files.createDirectories(p.getParent());
            Files.writeString(p, s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void restore(Path p, String content) {
        if (content == null) delete(p);
        else write(p, content);
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
