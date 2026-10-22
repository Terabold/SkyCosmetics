package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.PreviewRecorderTest;
import io.github.terabold.skycosmetics.data.RemoteTest;
import io.github.terabold.skycosmetics.data.RepoBoundsTest;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The whole run, one test after another. A failed test no longer stops the run: it is recorded, the screen is
 * closed and the settings are put back as they were before it, and the next test starts. At the end
 * {@code report.html} (beside the screenshots) lists every test and check, and the run fails if any test did.
 * {@code -Ponly=Name,Other} runs only the tests whose class names contain one of those words.
 */
public final class AllTests implements FabricClientGameTest {
    private static final List<Supplier<FabricClientGameTest>> ORDER = List.of(
        CorruptStartTest::new, // first: it checks the start from broken files
        ReportTest::new,
        TextRulesTest::new,
        MenuMemoryTest::new,
        RepoBoundsTest::new,
        NametagTest::new,
        ModelTest::new,
        ViewmodelTest::new,
        MenuPolishTest::new,
        ZoomTest::new,
        PolishTest::new,
        SkyCosmeticsClientTest::new,
        SettingsClientTest::new,
        MenuClientTest::new,
        GlintRenderTest::new,
        DataSafetyTest::new,
        StudioTabsClientTest::new,
        ThemeTest::new,
        TimingLearnerTest::new,
        SlotsTest::new,
        SlotLocksTest::new,
        VariantsTest::new,
        PreviewRecorderTest::new,
        RemoteTest::new,
        UpdateTest::new,
        TooltipTest::new);

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path dir = FabricLoader.getInstance().getGameDir();
        Path shots = dir.resolve("screenshots");
        String only = System.getProperty("skycosmetics.only", "").trim();
        List<String> wanted = only.isEmpty() ? List.of() : List.of(only.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        for (Supplier<FabricClientGameTest> make : ORDER) {
            FabricClientGameTest test = make.get();
            String name = test.getClass().getSimpleName();
            if (!wanted.isEmpty() && wanted.stream().noneMatch(w -> name.toLowerCase(Locale.ROOT).contains(w))) continue;
            Set<String> before = list(shots);
            JsonObject settings = ctx.computeOnClient(mc -> Settings.snapshot());
            int[] window = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
            System.out.println("[SkyCosmeticsTest] === " + name + " ===");
            Report.begin(name);
            long start = System.nanoTime();
            Throwable error = null;
            try {
                test.runTest(ctx);
            } catch (Throwable e) {
                error = e;
                System.out.println("[SkyCosmeticsTest] FAILED " + name + ": " + e);
                e.printStackTrace(System.out);
                cleanUp(ctx, settings, window);
            }
            List<String> taken = new ArrayList<>(list(shots));
            taken.removeAll(before);
            taken.sort(null);
            Report.end((System.nanoTime() - start) / 1_000_000, error, taken);
        }
        sentNothing();
        String summary = Report.summary();
        try {
            Path report = Report.write(dir);
            System.out.println("[SkyCosmeticsTest] REPORT: " + summary + ". Open " + report.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[SkyCosmeticsTest] REPORT: " + summary + " (report.html could not be written: " + e + ")");
        }
        if (Report.failed() > 0) throw new AssertionError("[SkyCosmeticsTest] " + summary + ": see report.html");
    }

    /**
     * The Hypixel rule, over the whole run: the mod never sends chat or a command (its own commands, such as
     * /skycosmetics debug pet, run in the client). Every packet type the client sent is listed in the report.
     */
    private static void sentNothing() {
        Report.begin("Nothing sent to the server");
        Throwable error = null;
        try {
            for (String type : List.of("ServerboundChatPacket", "ServerboundChatCommandPacket", "ServerboundChatCommandSignedPacket",
                    "ServerboundSignUpdatePacket", "ServerboundEditBookPacket", "ServerboundRenameItemPacket")) {
                Report.check(PacketLog.count(type) == 0, "no " + type + " in the whole run");
            }
            PacketLog.all().forEach((type, n) -> Report.check(true, "sent " + type + " x" + n));
            System.out.println("[SkyCosmeticsTest] packets the client sent: " + PacketLog.all());
        } catch (Throwable e) {
            error = e;
        }
        Report.end(0, error, List.of());
    }

    /** After a failed test: no screen left open, and the settings, window size and GUI scale as they were before it. */
    private static void cleanUp(ClientGameTestContext ctx, JsonObject settings, int[] window) {
        try {
            ctx.setScreen(() -> null);
            ctx.getInput().resizeWindow(window[0], window[1]);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(window[2]);
                mc.resizeGui();
            });
            ctx.runOnClient(mc -> {
                mc.options.hideGui = false;
                Settings.restore(settings);
                Settings.save();
            });
            ctx.waitTicks(2);
        } catch (Throwable e) {
            System.out.println("[SkyCosmeticsTest] clean-up after a failed test failed too: " + e);
        }
    }

    private static Set<String> list(Path dir) {
        if (!Files.isDirectory(dir)) return new HashSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            Set<String> out = new HashSet<>();
            s.forEach(p -> out.add(p.getFileName().toString()));
            return out;
        } catch (IOException e) {
            return new HashSet<>();
        }
    }
}
