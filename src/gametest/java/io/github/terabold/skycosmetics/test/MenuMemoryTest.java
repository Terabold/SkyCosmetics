package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.MenuState;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Reopen the actual menu, reload disk preferences, and hold the disk worker behind a burst of edits. */
public final class MenuMemoryTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext ctx) {
        JsonObject before = ctx.computeOnClient(mc -> MenuState.write());
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            StudioLayoutTest.window(ctx, 1280, 720, 2);
            ctx.runOnClient(mc -> { MenuState.advanced("zoom", false); MenuState.advanced("nametags", false); });
            ctx.setScreen(() -> new SettingsScreen(null, "zoom"));
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> MenuState.advanced("zoom", true));
            check(ctx.computeOnClient(mc -> MenuState.advanced("zoom") && !MenuState.advanced("nametags")), "disclosure preferences stay independent");
            ctx.setScreen(() -> null);
            ctx.setScreen(() -> SettingsScreen.create(null));
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).sectionId().equals("zoom")
                && ((SettingsScreen) mc.screen).widget("resetKey") == null
                && ((SettingsScreen) mc.screen).widget("advanced") == null), "reopen restores the section without reviving removed timing disclosure");
            ctx.setScreen(() -> new SettingsScreen(null, "nametags")); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).sectionId().equals("nametags")), "explicit links override remembered navigation");
            ctx.setScreen(() -> null);
            Path settings = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/settings.json");
            ctx.waitFor(mc -> saved(settings), 200);
            ctx.runOnClient(mc -> {
                JsonObject reset = new JsonObject(); reset.addProperty("section", "studio");
                JsonObject advanced = new JsonObject(); advanced.addProperty("zoom", false); advanced.addProperty("nametags", true);
                reset.add("advanced", advanced); MenuState.read(reset);
                Settings.load();
                check(MenuState.section().equals("nametags") && MenuState.advanced("zoom")
                    && !MenuState.advanced("nametags"), "disk reload restores navigation and both disclosure states");
                JsonObject future = MenuState.write(); future.addProperty("section", "future-section");
                future.addProperty("future", 42); future.getAsJsonObject("advanced").addProperty("future", true);
                future.getAsJsonObject("advanced").addProperty("zoom", "wrong type"); MenuState.read(future);
                check(MenuState.section().equals("studio") && MenuState.advanced("zoom"), "unknown sections fall back and malformed values keep safe state");
                check(MenuState.write().get("future").getAsInt() == 42
                    && MenuState.write().getAsJsonObject("advanced").get("future").getAsBoolean(), "future fields survive saves");
            });
            coalescing(ctx);
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> { MenuState.read(before); Settings.save(); });
        }
        System.out.println("[SkyCosmeticsTest] persistent menu preferences and coalesced writes passed");
    }

    private static boolean saved(Path file) {
        try {
            var menu = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("menu");
            return menu.get("section").getAsString().equals("nametags") && menu.getAsJsonObject("advanced").get("zoom").getAsBoolean();
        } catch (Exception e) { return false; }
    }

    private static void coalescing(ClientGameTestContext ctx) {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Path dir = FabricLoader.getInstance().getGameDir().resolve("test-menu-memory");
        Path first = dir.resolve("first.json"), second = dir.resolve("second.json");
        try {
            var field = Io.class.getDeclaredField("WRITER"); field.setAccessible(true);
            ExecutorService writer = (ExecutorService) field.get(null);
            writer.submit(() -> { held.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            check(held.await(10, TimeUnit.SECONDS), "disk worker held");
            for (int i = 0; i < 1000; i++) {
                JsonObject o = new JsonObject(); o.addProperty("value", i); Io.writeAsync(first, o);
            }
            JsonObject o = new JsonObject(); o.addProperty("value", 123); Io.writeAsync(second, o);
            var pendingField = Io.class.getDeclaredField("PENDING"); pendingField.setAccessible(true);
            Map<?, ?> pending = (Map<?, ?>) pendingField.get(null);
            synchronized (pending) { check(pending.containsKey(first) && pending.containsKey(second)
                && ((JsonObject) pending.get(first)).get("value").getAsInt() == 999, "only the newest snapshot is pending per file"); }
            release.countDown();
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
            check(JsonParser.parseString(Files.readString(first)).getAsJsonObject().get("value").getAsInt() == 999
                && JsonParser.parseString(Files.readString(second)).getAsJsonObject().get("value").getAsInt() == 123,
                "latest edits for both files reach disk");
        } catch (Exception e) { throw new AssertionError("coalesced writes", e); }
        finally {
            release.countDown();
            try { Files.deleteIfExists(first); Files.deleteIfExists(second); Files.deleteIfExists(dir); }
            catch (Exception e) { throw new AssertionError("fixture cleanup", e); }
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
