package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.Io;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

/**
 * SkyCosmetics' own swing timer for the first-person hand. It watches your swings (a read-only hook on
 * {@code LivingEntity.swing}) and times them itself, at the speed of the pose for the item swinging, so the
 * hand can swing faster, slower or never restart halfway, while the game's real swing, attack timing and what
 * the server sees stay exactly as they were. Nothing here writes to the player.
 *
 * At 1x it is Minecraft's timer to the frame: the same restart rule (a click in the first half of a swing
 * doesn't restart it), the same one-tick delay before the arm moves, the same item duration with Haste and
 * Mining Fatigue, and the same finish of the old arc when a swing restarts. Speeds other than 1x are exact
 * too: a 4x swing on a 6-tick item lasts 1.5 ticks, not a rounded 1 or 2.
 */
public final class HandSwing {
    /** Ticks since the swing started: 0 on the first tick after the click, as Minecraft's swingTime. */
    private static float elapsed;
    /** How many ticks this swing lasts, at its speed. */
    private static float duration = 6;
    /** A swing is on screen (it ends once its arc has fully played). */
    private static boolean running;
    /** A click since the last tick: the swing starts on the next one. */
    private static boolean pending;
    private static InteractionHand pendingHand = InteractionHand.MAIN_HAND;
    private static InteractionHand arm = InteractionHand.MAIN_HAND;
    /** A restart cut the old arc here; the first tick of the new swing plays it out to its end. */
    private static float tailFrom = 1;
    /** Swings seen, for tests. */
    private static int seen;

    private HandSwing() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (RuntimeException e) {
                Io.failed("Timing the hand's swing", e);
                reset();
            }
        });
    }

    /** Back to no swing (world change, failure). */
    public static void reset() {
        running = false;
        pending = false;
        elapsed = 0;
        tailFrom = 1;
    }

    /**
     * The local player swung (called from {@code LivingEntity.swing} before it runs; observe only). Whether the
     * swing restarts follows Minecraft's rule on SkyCosmetics' own timer; with Full Swings, never mid-swing.
     */
    public static void swung(InteractionHand hand) {
        if (!Hand.active() || hand == null) return;
        seen++;
        if (pending) {
            pendingHand = hand;
            return;
        }
        if (running && elapsed < duration) {
            if (HandRender.options() && Hand.fullSwings()) return;
            if (elapsed >= (float) Math.floor(duration / 2)) start(hand);
            return;
        }
        start(hand);
    }

    /** "Test Swing": a swing on screen only. The player doesn't swing, and nothing is sent. */
    public static void test() {
        pending = false;
        running = false;
        start(InteractionHand.MAIN_HAND);
    }

    private static void start(InteractionHand hand) {
        pending = true;
        pendingHand = hand;
    }

    private static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !Hand.active()) {
            if (running || pending) reset();
            return;
        }
        if (mc.isPaused()) return; // the player's own swing stands still too
        if (pending) {
            // The old arc (if any) is played out to its end over this tick, as Minecraft does on a restart.
            tailFrom = running ? Math.clamp(elapsed / duration, 0, 1) : 1;
            pending = false;
            running = true;
            arm = pendingHand;
            elapsed = 0;
            duration = durationFor(p, arm);
            return;
        }
        if (!running) return;
        elapsed++;
        duration = durationFor(p, arm);
        if (elapsed - 1 >= duration) running = false;
    }

    /**
     * Ticks a swing lasts: the swinging item's own duration, Haste shortening it and Mining Fatigue lengthening
     * it as in Minecraft (unless "Ignore Haste & Fatigue"), divided by the pose's speed. Effects are only read.
     */
    static float durationFor(LocalPlayer p, InteractionHand hand) {
        ItemStack held = p.getItemInHand(hand);
        int base = held.getSwingAnimation().duration();
        if (!(HandRender.options() && Hand.ignoreEffects())) {
            if (MobEffectUtil.hasDigSpeed(p)) {
                base -= 1 + MobEffectUtil.getDigSpeedAmplification(p);
            } else {
                MobEffectInstance fatigue = p.getEffect(MobEffects.MINING_FATIGUE);
                if (fatigue != null) base += (1 + fatigue.getAmplifier()) * 2;
            }
        }
        float speed = HandRender.speedFor(held);
        return Math.max(1, base) / Math.max(0.05f, speed);
    }

    /**
     * Swing progress for this frame, 0 to 1 (both are the rest pose). Replaces {@code getAttackAnim} only where the
     * first-person hand reads it.
     */
    public static float progress(float partialTick) {
        if (!running) return 0;
        float x = (elapsed - 1 + partialTick) / duration;
        if (x < 0) return tailFrom + (1 - tailFrom) * Math.clamp(partialTick, 0, 1);
        return x >= 1 ? 0 : x;
    }

    /** The hand the current swing is in, as the player's {@code swingingArm} is for Minecraft's. */
    public static InteractionHand arm() {
        return arm;
    }

    public static boolean running() {
        return running || pending;
    }

    /** For tests: swings seen since start, and the current duration in ticks. */
    public static int seen() {
        return seen;
    }

    public static float duration() {
        return duration;
    }
}
