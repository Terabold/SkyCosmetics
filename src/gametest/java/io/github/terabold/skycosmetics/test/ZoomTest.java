package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.zoom.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.*;
import static io.github.terabold.skycosmetics.zoom.ZoomSettings.*;

/** Exercises real input, camera projection, wheel consumption, menus and state restoration. */
public class ZoomTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext ctx) {
        transitions();
        JsonObject before = ctx.computeOnClient(mc -> ZoomSettings.write());
        boolean advancedBefore = ctx.computeOnClient(mc -> io.github.terabold.skycosmetics.gui.MenuState.advanced("zoom"));
        String keyBefore = ctx.computeOnClient(mc -> Zoom.primaryKey.saveString());
        String resetBefore = ctx.computeOnClient(mc -> Zoom.resetKey.saveString());
        String inBefore = ctx.computeOnClient(mc -> Zoom.inKey.saveString());
        String outBefore = ctx.computeOnClient(mc -> Zoom.outKey.saveString());
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            world.getServer().runCommand("time set noon");
            window(ctx, 1280, 720, 2);
            menu(ctx);
            ctx.runOnClient(mc -> {
                ZoomSettings.reset(); enabled = true;
                primary.initial = 4; primary.scrollStep = 1.25;
                primary.inSeconds = primary.outSeconds = primary.scrollSeconds = 0;
                Zoom.inKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_ADD));
                Zoom.resetKey.setKey(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
                Zoom.outKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_KP_SUBTRACT)); KeyMapping.resetMapping();
            });
            ctx.setScreen(() -> null); ctx.waitTicks(8);
            double fov = fov(ctx);
            double sensitivity = ctx.computeOnClient(mc -> mc.options.sensitivity().get());
            int configuredFov = ctx.computeOnClient(mc -> mc.options.fov().get());
            boolean hud = ctx.computeOnClient(mc -> mc.options.hideGui);
            ctx.takeScreenshot("skycosmetics-120-zoom-normal");
            ctx.getInput().holdKey(GLFW.GLFW_KEY_J); ctx.waitTicks(5);
            near(fov(ctx), Zoom.opticalFov(fov, 4), .02, "camera uses optical 4x projection");
            check(ctx.computeOnClient(mc -> Zoom.magnification()) == 4, "hold key activates primary zoom");
            ctx.takeScreenshot("skycosmetics-121-zoom-4x");
            mouse(ctx);
            int slot = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
            ctx.getInput().pressKey(GLFW.GLFW_KEY_KP_ADD); ctx.waitTicks(2);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 5, .0001, "zoom in key");
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE); ctx.waitTicks(2);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 4, .0001, "middle mouse resets zoom");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_KP_SUBTRACT); ctx.waitTicks(2);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 3.2, .0001, "zoom out key");
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE); ctx.waitTicks(2);
            ctx.getInput().scroll(.5); ctx.waitTicks(3);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 4 * Math.sqrt(1.25), .0001, "fractional wheel input");
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) == slot, "zoom wheel does not change the hotbar");
            ctx.runOnClient(mc -> { modifier = Modifier.SHIFT; });
            ctx.getInput().scroll(-1); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) != slot, "modifier-free scrolling passes through when a modifier is required");
            double level = ctx.computeOnClient(mc -> Zoom.magnification());
            ctx.getInput().holdShift(); ctx.getInput().scroll(1); ctx.getInput().releaseShift(); ctx.waitTicks(2);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), level * 1.25, .0001, "modifier allows wheel zoom");
            ctx.runOnClient(mc -> { modifier = Modifier.NONE; primary.scroll = Scroll.LINEAR; primary.invertScroll = true; });
            level = ctx.computeOnClient(mc -> Zoom.magnification());
            ctx.getInput().scroll(1); ctx.waitTicks(2);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), level / 1.25, .0001, "inverted scrolling uses reciprocal optical steps even for an old linear profile");
            ctx.runOnClient(mc -> { primary.scroll = Scroll.EXPONENTIAL; primary.invertScroll = false; });
            ctx.getInput().scroll(100); ctx.waitTicks(3);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 1000, .0001, "wheel clamps to fixed upper limit");
            ctx.getInput().scroll(-100); ctx.waitTicks(3);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 1, .0001, "wheel clamps to lower limit");
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
            near(fov(ctx), fov, .02, "release restores the normal projection");
            ctx.runOnClient(mc -> { primary.cinematic = Cinematic.CUSTOM; primary.outSeconds = .5; });
            ctx.getInput().holdKey(GLFW.GLFW_KEY_J); ctx.waitTicks(2);
            ctx.runOnClient(mc -> Zoom.smooth(12, .05, true));
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.waitTicks(1);
            ctx.runOnClient(mc -> {
                check(!Zoom.cameraActive(), "release stops mouse smoothing before zoom-out finishes");
                near(Zoom.smooth(0, .05, true), 0, 1e-9, "released zoom has no camera velocity tail");
            });
            ctx.getInput().holdKey(GLFW.GLFW_KEY_J); ctx.waitTicks(2);
            ctx.setScreen(() -> new SettingsScreen(null, "zoom")); ctx.waitTicks(2);
            ctx.setScreen(() -> null); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> Zoom.magnification()) == 1, "interrupted held zoom waits for release after a menu");
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.waitTicks(2);
            ctx.runOnClient(mc -> { primary.outSeconds = 0; primary.cinematic = Cinematic.OFF; });
            int normalSlot = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
            ctx.getInput().scroll(-1); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) != normalSlot, "normal scrolling still changes the hotbar");

            ctx.runOnClient(mc -> { primary.mode = Mode.TOGGLE; primary.initial = 5; primary.remember = false; });
            ctx.getInput().pressKey(GLFW.GLFW_KEY_J); ctx.waitTicks(4);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 5, .0001, "toggle stays on after key release");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_J); ctx.waitTicks(4);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 1, .0001, "second toggle turns zoom off");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
            ctx.setScreen(() -> new SettingsScreen(null, "zoom")); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> Zoom.magnification()) == 1, "opening a menu cancels zoom immediately");
            ctx.setScreen(() -> null); ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> Zoom.magnification()) == 1, "closing the menu does not leave a stuck toggle");

            ctx.runOnClient(mc -> { primary.mode = Mode.HOLD; primary.initial = 4; });
            ctx.getInput().holdKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 4, .0001, "holding the key zooms to the starting level");
            ctx.runOnClient(mc -> { primary.overlay = Overlay.VIGNETTE; primary.hideCrosshair = true; primary.hideHand = true; });
            ctx.takeScreenshot("skycosmetics-123-zoom-vignette");
            check(ctx.computeOnClient(mc -> Zoom.hideHand() && Zoom.hideCrosshair()), "hand and crosshair options are active");
            ctx.runOnClient(mc -> { primary.scrollSeconds = 1; primary.scrollCurve = ZoomTransition.Curve.LINEAR; });
            ctx.getInput().scroll(-100); ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> Zoom.amount() > 0 && Zoom.amount() < 1), "overlays fade when scrolling back to 1x instead of snapping");
            ctx.runOnClient(mc -> primary.scrollSeconds = 0);
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.waitTicks(4);
            check(!ctx.computeOnClient(mc -> Zoom.hideHand() || Zoom.hideCrosshair()), "hand and crosshair restore on release");

            ctx.runOnClient(mc -> {
                primary.overlay = Overlay.OFF;
                var legacy = ZoomSettings.write(); legacy.addProperty("spyglass", "REQUIRE_HELD");
                legacy.getAsJsonObject("primary").addProperty("overlay", "SPYGLASS");
                ZoomSettings.read(legacy);
                check(primary.overlay == Overlay.OFF && !ZoomSettings.write().has("spyglass"), "removed spyglass settings migrate harmlessly");
            });
            ctx.getInput().holdKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 4, .0001, "zoom works without carrying a spyglass after legacy migration");
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
            ctx.runOnClient(mc -> {
                var legacy = ZoomSettings.write(); legacy.addProperty("spyglass", "REPLACE"); ZoomSettings.read(legacy);
            });
            world.getServer().runCommand("item replace entity @a weapon.offhand with minecraft:spyglass"); ctx.waitTicks(5);
            ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> mc.player.isScoping()), "vanilla spyglass still works");
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 1, .0001, "using a spyglass never activates custom zoom");
            // The spyglass's black surround can take a few more frames on a busy PC: looked at again before failing.
            int spyglassCorner = corner(ctx, "skycosmetics-127-native-spyglass");
            for (int attempt = 0; attempt < 4 && spyglassCorner != 0; attempt++) {
                ctx.waitTicks(5);
                spyglassCorner = corner(ctx, "skycosmetics-127-native-spyglass");
            }
            check(spyglassCorner == 0, "native spyglass keeps its own overlay");
            // Native spyglass FOV returns gradually; the removed replacement hook used to bypass that modifier.
            ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT); ctx.waitTicks(16);
            near(fov(ctx), fov, .04, "stopping spyglass use restores the view");

            ctx.runOnClient(mc -> { primary.mode = Mode.PERSISTENT; primary.initial = 2; }); ctx.waitTicks(4);
            near(ctx.computeOnClient(mc -> Zoom.magnification()), 2, .0001, "persistent zoom activates without a held key");
            ctx.runOnClient(mc -> enabled = false); ctx.waitTicks(4);
            near(fov(ctx), fov, .02, "disabling restores the camera");
            check(ctx.computeOnClient(mc -> mc.options.sensitivity().get()) == sensitivity, "zoom never rewrites saved sensitivity");
            check(ctx.computeOnClient(mc -> mc.options.fov().get()) == configuredFov, "zoom never rewrites saved FOV");
            check(ctx.computeOnClient(mc -> mc.options.hideGui) == hud, "zoom never rewrites saved HUD visibility");
            settingsValidation(ctx);
        } finally {
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_J); ctx.getInput().releaseKey(GLFW.GLFW_KEY_B);
            ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT); ctx.getInput().releaseShift();
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> { ZoomSettings.read(before);
                io.github.terabold.skycosmetics.gui.MenuState.advanced("zoom", advancedBefore); Settings.save();
                Zoom.primaryKey.setKey(InputConstants.getKey(keyBefore));
                Zoom.resetKey.setKey(InputConstants.getKey(resetBefore)); Zoom.inKey.setKey(InputConstants.getKey(inBefore)); Zoom.outKey.setKey(InputConstants.getKey(outBefore));
                KeyMapping.resetMapping(); mc.options.save(); });
        }
        // Cinematic is Minecraft's own glide: 1 s at the default sensitivity (100%), and the Glide slider scales it.
        double[] glide = ctx.computeOnClient(mc -> {
            double was = mc.options.sensitivity().get();
            mc.options.sensitivity().set(.5);
            double[] out = {Zoom.cinematicSeconds(1), Zoom.cinematicSeconds(2)};
            mc.options.sensitivity().set(was);
            return out;
        });
        check(Math.abs(glide[0] - 1) < 1e-9 && Math.abs(glide[1] - 2) < 1e-9, "cinematic glides as Minecraft's: " + java.util.Arrays.toString(glide));
        System.out.println("[SkyCosmeticsTest] zoom camera, input, menu and transition checks passed");
    }

    private static double fov(ClientGameTestContext ctx) { return ctx.computeOnClient(mc -> (double) mc.gameRenderer.getMainCamera().getFov()); }

    private static void menu(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> io.github.terabold.skycosmetics.gui.MenuState.advanced("zoom", false));
        ctx.setScreen(() -> new SettingsScreen(null, "zoom")); ctx.waitTicks(3);
        ctx.runOnClient(mc -> check(((SettingsScreen) mc.screen).widget("primaryKey") != null, "zoom key is editable inside the mod menu"));
        int[] at = ctx.computeOnClient(mc -> { var s = (SettingsScreen) mc.screen; s.scrollTo("primaryKey"); var w = s.widget("primaryKey"); return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2}; });
        click(ctx, at[0], at[1]); ctx.getInput().pressKey(GLFW.GLFW_KEY_J); ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> Zoom.primaryKey.saveString()).equals("key.keyboard.j"), "menu rebinds the zoom key");
        check(ctx.computeOnClient(mc -> io.github.terabold.skycosmetics.gui.hub.KeyBindButton.keyLabel(Zoom.primaryKey).getString()).equals("J"), "Latin binding label is independent of keyboard layout");
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("resetKey") == null), "advanced keys are hidden initially");
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("advanced") == null
            && ((SettingsScreen)mc.screen).widget("inSeconds") != null), "animation timing is beside the basic animation selector");
        ctx.runOnClient(mc -> {
            var animation = Hub.find("zoom").rows().apply(mc.screen).stream().filter(o -> o.id().equals("style"))
                .map(o -> (io.github.terabold.skycosmetics.hub.Control.Choice<String>)o.control()).findFirst().orElseThrow();
            var camera = primary.cinematic;
            animation.set().accept("Instant"); check(primary.inSeconds == 0 && primary.cinematic == camera, "instant animation does not change camera smoothing");
            ((SettingsScreen)mc.screen).changed();
            check(((SettingsScreen)mc.screen).widget("advanced") == null, "instant animation has no empty timing disclosure");
            animation.set().accept("Linear"); check(primary.inCurve == ZoomTransition.Curve.LINEAR && primary.cinematic == camera, "linear animation does not change camera smoothing");
            animation.set().accept("Eased"); check(primary.inCurve == ZoomTransition.Curve.IN_OUT_SINE && primary.cinematic == camera, "the eased animation does not change camera smoothing");
            animation.set().accept("Smooth"); ((SettingsScreen)mc.screen).changed();
        });
        ctx.takeScreenshot("skycosmetics-zoom-compact");
        ctx.runOnClient(mc -> io.github.terabold.skycosmetics.gui.MenuState.advanced("zoom", true));
        ctx.setScreen(() -> new SettingsScreen(null, "zoom")); ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("resetKey") == null), "legacy expanded preference cannot revive deferred controls");
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("inSeconds") != null), "expanded footer shows only animation timing");
        ctx.runOnClient(mc -> io.github.terabold.skycosmetics.gui.MenuState.advanced("zoom", false));
        ctx.setScreen(() -> new SettingsScreen(null, "zoom")); ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> {
            try { return Files.readString(mc.gameDirectory.toPath().resolve("options.txt")).contains("key_key.skycosmetics.zoom:key.keyboard.j"); }
            catch (Exception e) { throw new RuntimeException(e); }
        }), "menu keybind persists in vanilla options");
        ctx.getInput().setCursorPos(0, 0); ctx.takeScreenshot("skycosmetics-124-zoom-menu");
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("barColor") == null), "cinematic artwork is deferred from the release menu");
        ctx.runOnClient(mc -> { primary.bars = .1; ((SettingsScreen)mc.screen).changed(); });
        check(ctx.computeOnClient(mc -> ((SettingsScreen)mc.screen).widget("barColor") == null), "saved artwork cannot expose deferred controls");
        for (String id : new String[]{"style", "smoothCamera", "sensitivityScale", "hideHand"}) {
            ctx.runOnClient(mc -> check(((SettingsScreen) mc.screen).scrollTo(id), "zoom row exists: " + id));
            ctx.waitTicks(2); ctx.takeScreenshot("skycosmetics-125-zoom-menu-" + id);
        }
        window(ctx, 854, 480, 2); ctx.waitTicks(3);
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).scrollTo("primaryKey")); ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-126-zoom-menu-small");
        window(ctx, 1280, 720, 2);
    }

    private static void settingsValidation(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            JsonObject old = ZoomSettings.write(); old.remove("timingVersion");
            var stock = old.getAsJsonObject("primary");
            stock.addProperty("inSeconds", .2); stock.addProperty("outSeconds", .5);
            stock.addProperty("inCurve", "OUT_CUBIC"); stock.addProperty("outCurve", "OUT_CUBIC");
            ZoomSettings.read(old);
            check(primary.inSeconds == .08 && primary.outSeconds == .08, "old stock timings migrate to faster defaults");
            stock.addProperty("inSeconds", .13); stock.addProperty("outSeconds", .19); ZoomSettings.read(old);
            check(primary.inSeconds == .13 && primary.outSeconds == .19, "individually adjusted timings survive migration");
            JsonObject o = ZoomSettings.write(); JsonObject p = o.getAsJsonObject("primary");
            p.addProperty("minimum", 2000); p.addProperty("maximum", -4); p.addProperty("initial", -1);
            p.addProperty("inSeconds", -100); p.addProperty("outSeconds", Double.NaN); p.addProperty("mode", "unknown");
            ZoomSettings.read(o);
            check(primary.minimum == 1000 && primary.maximum == 1000 && primary.initial == 1000, "malformed bounds normalize safely");
            check(primary.inSeconds == 0 && Double.isFinite(primary.outSeconds), "invalid durations cannot poison camera math");
            ZoomSettings.reset(); JsonObject partial = new JsonObject(), limits = new JsonObject();
            limits.addProperty("minimum", 100); partial.add("primary", limits); ZoomSettings.read(partial);
            check(primary.maximum >= primary.minimum && primary.initial >= primary.minimum, "partial edited settings preserve ordered bounds");
        });
    }

    private static void mouse(ClientGameTestContext ctx) {
        // Compare the actual MouseHandler -> LocalPlayer.turn path, with the same scripted cursor movement.
        ctx.runOnClient(mc -> { primary.sensitivity = Sensitivity.UNCHANGED; primary.cinematic = Cinematic.OFF; });
        ctx.getInput().moveCursor(3, 0); ctx.waitTicks(2);
        float start = ctx.computeOnClient(mc -> mc.player.getYRot());
        ctx.getInput().moveCursor(40, 0); ctx.waitTicks(2);
        double normal = ctx.computeOnClient(mc -> (double) mc.player.getYRot()) - start;
        check(Math.abs(normal) > .01, "scripted mouse input turns the actual camera");
        ctx.runOnClient(mc -> primary.sensitivity = Sensitivity.RELATIVE);
        start = ctx.computeOnClient(mc -> mc.player.getYRot());
        ctx.getInput().moveCursor(40, 0); ctx.waitTicks(2);
        double zoom = ctx.computeOnClient(mc -> (double) mc.player.getYRot()) - start;
        double precision = ctx.computeOnClient(mc -> Zoom.precisionSensitivity(Zoom.magnification()));
        near(zoom / normal, precision, .01, "deeper zoom gently reduces actual mouse input");
        check(Zoom.precisionSensitivity(1) == 1 && Zoom.precisionSensitivity(10) > Zoom.precisionSensitivity(100)
            && Zoom.precisionSensitivity(1000) > .2, "precision reduction is smooth and remains gentler than inverse magnification");
        ctx.runOnClient(mc -> primary.sensitivityScale = .5);
        start = ctx.computeOnClient(mc -> mc.player.getYRot());
        ctx.getInput().moveCursor(40, 0); ctx.waitTicks(2);
        double half = ctx.computeOnClient(mc -> (double)mc.player.getYRot()) - start;
        near(half / normal, .5 * precision, .01, "the multiplier directly adjusts actual angular mouse movement");
        ctx.runOnClient(mc -> primary.sensitivityScale = 1);
        boolean cinematicBefore = ctx.computeOnClient(mc -> mc.options.smoothCamera);
        try {
            ctx.runOnClient(mc -> { primary.sensitivity = Sensitivity.UNCHANGED; mc.options.smoothCamera = true; });
            start = ctx.computeOnClient(mc -> mc.player.getYRot());
            ctx.getInput().moveCursor(40, 0); ctx.waitTicks(2);
            double direct = ctx.computeOnClient(mc -> (double) mc.player.getYRot()) - start;
            near(direct / normal, 1, .01, "Off cinematic mode stays direct even when vanilla cinematic camera is enabled");
        } finally { ctx.runOnClient(mc -> mc.options.smoothCamera = cinematicBefore); }
        ctx.runOnClient(mc -> {
            primary.cinematic = Cinematic.CUSTOM;
            Zoom.reset(); Zoom.update();
            double low = 0; for (int i = 0; i < 30; i++) low += Zoom.smooth(10 / 30.0, 1 / 30.0, true);
            Zoom.reset(); Zoom.update();
            double high = 0; for (int i = 0; i < 240; i++) high += Zoom.smooth(10 / 240.0, 1 / 240.0, true);
            near(low, high, 1e-9, "custom camera smoothing integrates equally at 30 and 240 FPS");
            primary.cinematic = Cinematic.OFF; primary.sensitivity = Sensitivity.ANGULAR_FOV;
            Zoom.reset(); Zoom.update();
        });
    }

    private static void transitions() {
        for (var curve : ZoomTransition.Curve.values()) {
            double previous = 0;
            for (int i = 0; i <= 100; i++) { double value = curve.at(i / 100.0); check(value >= previous && value <= 1, "bounded monotonic easing: " + curve); previous = value; }
            ZoomTransition low = new ZoomTransition(), high = new ZoomTransition();
            low.to(20, 0, 1, curve); high.to(20, 0, 1, curve);
            for (int i = 1; i <= 30; i++) low.value(i / 30.0);
            for (int i = 1; i <= 240; i++) high.value(i / 240.0);
            near(low.value(.7), high.value(.7), 1e-9, "transition is independent of frame rate");
            near(low.value(1), 20, 1e-9, "transition reaches its exact endpoint");
            double mid = low.value(.4); low.to(2, .4, .6, ZoomTransition.Curve.LINEAR);
            near(low.value(.4), mid, 1e-9, "reversal preserves the current magnification");
            near(low.value(1.1), 2, 1e-9, "reversal reaches its new endpoint");
        }
        near(Zoom.opticalFov(90, 2), 53.1301, .0001, "optical magnification matches perspective geometry");
        ZoomTransition release = new ZoomTransition(); release.to(4, 0, 0, ZoomTransition.Curve.INSTANT);
        release.to(1, 1, 2, ZoomTransition.Curve.LINEAR);
        check(release.value(1.1) > 1, "wheel return is still in progress");
        release.to(1, 1.1, 0, ZoomTransition.Curve.LINEAR, true);
        near(release.value(1.1), 1, 1e-9, "release overrides the wheel timing even with an identical target");
    }

    private static int corner(ClientGameTestContext ctx, String name) {
        try (var input = Files.newInputStream(ctx.takeScreenshot(name)); NativeImage image = NativeImage.read(input)) {
            return image.getPixel(5, 5) & 0xFFFFFF;
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static void near(double actual, double expected, double tolerance, String reason) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, reason + ": expected " + expected + ", got " + actual);
    }
}
