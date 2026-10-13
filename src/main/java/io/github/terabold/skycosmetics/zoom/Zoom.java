package io.github.terabold.skycosmetics.zoom;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import static io.github.terabold.skycosmetics.zoom.ZoomSettings.*;

/** Local camera state. No persistent vanilla options, items or player actions are changed. */
public final class Zoom {
    public static KeyMapping primaryKey, inKey, outKey, resetKey;
    private static final ZoomTransition transition = new ZoomTransition();
    private static final ZoomTransition visibility = new ZoomTransition();
    private static final double[] remembered = {primary.initial};
    private static final boolean[] toggled = new boolean[1];
    private static Object player;
    private static Object level;
    private static boolean waitForRelease;
    private static int selected = -1;
    private static Profile rendering = primary;
    private static double target = 1, magnification = 1, amount, baseFov = 70, zoomFov = 70;
    private static double velocityX, velocityY;
    private static int indicatorValue = -1;
    private static String indicatorText = "";

    private Zoom() {}
    public static double now() { return System.nanoTime() * 1e-9; }

    public static void init(KeyMapping.Category category) {
        primaryKey = key("zoom", GLFW.GLFW_KEY_C, category);
        inKey = key("zoomIn", GLFW.GLFW_KEY_UNKNOWN, category);
        outKey = key("zoomOut", GLFW.GLFW_KEY_UNKNOWN, category);
        resetKey = key("zoomReset", GLFW.GLFW_KEY_UNKNOWN, category);
        Settings.register("zoom", ZoomSettings::read, ZoomSettings::write);
        ZoomSection.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                update();
            } catch (RuntimeException e) { reset(); Io.failed("Updating zoom", e); }
        });
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycosmetics." + name,
            InputConstants.Type.KEYSYM, code, category));
    }

    public static void reset() {
        transition.reset(); visibility.reset(); selected = -1; target = magnification = 1; amount = 0;
        toggled[0] = false; velocityX = velocityY = 0;
    }

    public static void forgetLevels() {
        remembered[0] = primary.initial; reset();
    }

    public static void forgetLevel(Profile profile) {
        remembered[0] = profile.initial; reset();
    }

    private static boolean allowed(Minecraft mc) {
        return enabled && mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null
            && mc.isWindowActive() && !mc.player.isDeadOrDying()
            && (thirdPerson || mc.options.getCameraType().isFirstPerson());
    }

    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (primaryKey == null) return;
        int primaryClicks = clicks(primaryKey);
        int plus = clicks(inKey), minus = clicks(outKey), restore = clicks(resetKey);
        if (player != mc.player || level != mc.level) {
            waitForRelease |= selected >= 0;
            reset(); player = mc.player; level = mc.level;
        }
        if (!allowed(mc)) {
            // Opening a screen clears KeyMapping.isDown before this update; retain the active hold too.
            waitForRelease |= selected >= 0 || primaryKey.isDown();
            reset(); return;
        }
        if (waitForRelease) {
            if (primaryKey.isDown()) { reset(); return; }
            waitForRelease = false;
        }
        if ((primaryClicks & 1) != 0 && primary.mode == Mode.TOGGLE) toggled[0] = !toggled[0];
        int next = active(primary, primaryKey, 0) ? 0 : -1;
        double time = now();
        if (next != selected) {
            Profile previous = rendering;
            selected = next;
            if (next >= 0) {
                rendering = primary;
                target = rendering.bounded(rendering.remember ? remembered[next] : rendering.initial);
                remembered[next] = target;
                transition.to(target, time, rendering.inSeconds, rendering.inCurve, true);
                visibility.to(target > 1 ? Math.E : 1, time, rendering.inSeconds, rendering.inCurve, true);
                sound(mc, true);
            } else {
                velocityX = velocityY = 0;
                target = 1;
                transition.to(1, time, previous.outSeconds, previous.outCurve, true);
                visibility.to(1, time, previous.outSeconds, previous.outCurve, true);
                sound(mc, false);
            }
        }
        if (selected >= 0) {
            if (restore > 0) setTarget(rendering.initial, time);
            else if (plus != minus) adjust((double) plus - minus, time);
        }
        magnification = transition.value(time);
        amount = Math.clamp(Math.log(visibility.value(time)), 0, 1);
        if (magnification <= 1.000001 && selected < 0) velocityX = velocityY = 0;
    }

    private static int clicks(KeyMapping key) {
        int count = 0; while (key.consumeClick()) count++; return count;
    }

    private static void sound(Minecraft mc, boolean entering) {
        if (rendering.sounds) mc.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.UI_BUTTON_CLICK.value(), entering ? 1 : .8f, (float) rendering.soundVolume));
    }

    private static boolean active(Profile p, KeyMapping key, int index) {
        return switch (p.mode) { case HOLD -> key.isDown(); case TOGGLE -> toggled[index]; case PERSISTENT -> true; };
    }

    private static void setTarget(double value, double time) {
        target = rendering.bounded(value); remembered[selected] = target;
        transition.to(target, time, rendering.scrollSeconds, rendering.scrollCurve);
        visibility.to(target > 1 ? Math.E : 1, time, rendering.scrollSeconds, rendering.scrollCurve);
    }

    private static void adjust(double delta, double time) {
        // Equal optical ratios feel consistent at every level; opposite notches undo each other.
        double value = target * Math.pow(1.25, Math.clamp(delta, -100, 100));
        setTarget(value, time);
    }

    public static boolean scroll(double delta) {
        update();
        if (selected < 0 || rendering.scroll == Scroll.OFF || !Double.isFinite(delta) || delta == 0 || !modifierDown()) return false;
        adjust(rendering.invertScroll ? -delta : delta, now()); return true;
    }

    private static boolean modifierDown() {
        var window = Minecraft.getInstance().getWindow();
        return switch (modifier) {
            case NONE -> true;
            case SHIFT -> InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
            case CONTROL -> InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
            case ALT -> InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        };
    }

    public static float worldFov(float original, float dynamicModifier) {
        update();
        baseFov = original;
        if (magnification == 1) { zoomFov = original; return original; }
        if (magnification > 1 && rendering.suppressFov) {
            baseFov = original / (1 + (Math.max(.01, dynamicModifier) - 1) * amount);
        }
        zoomFov = opticalFov(baseFov, magnification);
        return (float) zoomFov;
    }

    public static float opticalFov(double fov, double factor) {
        return (float) Math.clamp(Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fov) / 2) / factor)), .01, 179);
    }

    public static float handFov(float original) { return visible() && rendering.affectHand ? opticalFov(original, magnification) : original; }
    public static boolean visible() { return enabled && magnification > 1.000001; }
    public static boolean hideHud() { return visible() && rendering.hideHud; }
    public static boolean hideCrosshair() { return visible() && rendering.hideCrosshair; }
    public static boolean hideHand() { return visible() && rendering.hideHand; }
    public static boolean cameraActive() { return visible() && selected >= 0 && allowed(Minecraft.getInstance()); }
    public static boolean vanillaCinematic() { return cameraActive() && rendering.cinematic == Cinematic.VANILLA; }
    public static float bobbing() { return visible() ? (float) (1 + (rendering.bobbing - 1) * amount) : 1; }
    public static double magnification() { return magnification; }
    public static double amount() { return amount; }
    public static Profile profile() { return rendering; }

    public static double sensitivity() {
        if (!visible()) return 1;
        if (rendering.sensitivity == Sensitivity.UNCHANGED) return 1;
        // A sublinear reduction adds precision without the steep 1/zoom slowdown.
        return (1 + (rendering.sensitivityScale - 1) * amount) * precisionSensitivity(magnification);
    }

    public static double precisionSensitivity(double zoom) {
        return 1 / Math.pow(Math.max(1, zoom), .22);
    }

    /** Integrates filtered angular velocity exactly over the frame, including its decaying tail. */
    public static double smooth(double delta, double dt, boolean horizontal) {
        if (!cameraActive() || rendering.cinematic != Cinematic.CUSTOM) { velocityX = velocityY = 0; return delta; }
        if (!Double.isFinite(dt) || dt <= 0) return delta;
        dt = Math.min(dt, .25);
        double old = horizontal ? velocityX : velocityY;
        double input = delta / dt, tau = rendering.smoothingSeconds;
        double decay = Math.exp(-dt / tau), next = input + (old - input) * decay;
        if (horizontal) velocityX = next; else velocityY = next;
        return input * dt + (old - input) * tau * (1 - decay);
    }

    public static String indicator() {
        int value = (int) Math.round(magnification * 10);
        if (value != indicatorValue) { indicatorValue = value; indicatorText = (value / 10) + "." + (value % 10) + "x"; }
        return indicatorText;
    }
}
