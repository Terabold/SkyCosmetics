package io.github.terabold.skycosmetics.hand;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.Io;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * What the {@code ItemInHandRenderer} hooks do. Every method is called on the render thread for the first-person
 * hand only, changes nothing but the pose stack it is given (or the value it returns to the renderer), and never
 * throws: a failure is logged and the hand is drawn as Minecraft draws it.
 *
 * Per frame and hand: two reference checks find the cached pose, then at most one translate, one rotation (one
 * reused quaternion), one scale and the scaled swing translate. Nothing is allocated, nothing parsed.
 */
public final class HandRender {
    /** What the current {@code renderArmWithItem} call draws. */
    enum Kind { ITEM, EMPTY, MAP }

    /** The hand being drawn right now; null outside {@code renderArmWithItem} or with nothing to change. */
    private static HandPose pose;
    private static Kind kind = Kind.ITEM;
    /** 1 for the right arm, -1 for the left: the left mirrors X position and Y/Z rotation. */
    private static float side = 1;
    /** The hand pivot's rotation was applied for this hand already. */
    private static boolean handTurned;

    private static final Quaternionf TURN = new Quaternionf();

    /** One cached lookup per hand: the stack it was for and the version of the rules it used. */
    private static final class Slot {
        ItemStack stack;
        int version = -1;
        HandPose pose;
    }

    private static final Slot MAIN = new Slot(), OFF = new Slot(), SWING = new Slot();

    /** Calls drawn with a pose, for tests. */
    private static int drawn;

    private HandRender() {}

    /** The feature is on: every hook checks this first, so with it off the cost is one boolean. */
    public static boolean active() {
        return Hand.enabled();
    }

    // ------------------------------------------------------------- lookup ---

    private static HandPose cached(Slot slot, ItemStack s) {
        int v = Hand.version();
        if (slot.stack != s || slot.version != v) {
            slot.stack = s;
            slot.version = v;
            slot.pose = Hand.resolve(s);
        }
        return slot.pose;
    }

    /** The pose for a hand this frame, the editor's preview first. */
    private static HandPose poseFor(InteractionHand hand, ItemStack s) {
        String preview = Hand.previewKey();
        if (preview != null && hand == InteractionHand.MAIN_HAND) {
            HandPose p = Hand.pose(preview);
            return p == null || p.isVanilla() ? null : p;
        }
        return cached(hand == InteractionHand.MAIN_HAND ? MAIN : OFF, s);
    }

    /** Swing speed for the item swinging (read by {@link HandSwing} once a tick). */
    static float speedFor(ItemStack held) {
        String preview = Hand.previewKey();
        HandPose p;
        if (preview != null) p = Hand.pose(preview);
        else p = cached(SWING, held);
        return p == null ? 1 : p.speed();
    }

    // -------------------------------------------------------------- hooks ---

    /** The stack the editor wants drawn in your main hand instead of the held one (a sample bow...), else {@code s}. */
    public static ItemStack shown(InteractionHand hand, ItemStack s) {
        if (hand != InteractionHand.MAIN_HAND || Hand.previewKey() == null) return s;
        ItemStack sample = Hand.previewStack();
        return sample != null ? sample : s;
    }

    /**
     * Starts one hand: finds its pose and what it draws. Returns the swing progress to draw it with: the pose's
     * None style draws no swing. {@link #end} must follow, in a finally.
     */
    public static float begin(LocalPlayer player, InteractionHand hand, ItemStack s, float attack) {
        pose = null;
        handTurned = false;
        try {
            HandPose p = poseFor(hand, s);
            if (p == null) return attack;
            HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
            side = arm == HumanoidArm.RIGHT ? 1 : -1;
            kind = s.isEmpty() ? Kind.EMPTY : s.has(DataComponents.MAP_ID) ? Kind.MAP : Kind.ITEM;
            pose = p;
            drawn++;
            return p.swing == HandPose.Swing.NONE ? 0 : attack;
        } catch (RuntimeException e) {
            Io.failed("Finding the pose for your hand", e);
            pose = null;
            return attack;
        }
    }

    public static void end() {
        pose = null;
    }

    /** Right after {@code renderArmWithItem}'s pushPose: the position, and the rotation for the View pivot. */
    public static void afterPush(PoseStack ps) {
        HandPose p = pose;
        if (p == null) return;
        try {
            if (p.pivot == HandPose.Pivot.VIEW) turn(ps, p);
            if (p.offset) ps.translate(side * p.get(HandPose.Field.X), p.get(HandPose.Field.Y), p.get(HandPose.Field.Z));
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** End of {@code applyItemArmTransform}: the hand's resting place, before the swing. The Hand pivot turns here. */
    public static void atRest(PoseStack ps) {
        HandPose p = pose;
        if (p == null || kind != Kind.ITEM || p.pivot != HandPose.Pivot.HAND || handTurned) return;
        try {
            handTurned = true;
            turn(ps, p);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Just before the held item is drawn, at its grip: the Item pivot's rotation, then the size. */
    public static void beforeItem(PoseStack ps) {
        HandPose p = pose;
        if (p == null || kind != Kind.ITEM) return;
        try {
            if (p.pivot == HandPose.Pivot.ITEM || p.pivot == HandPose.Pivot.HAND && !handTurned) turn(ps, p);
            if (p.resized) ps.scale(p.scaleX, p.scaleY, p.scaleZ);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** The swing's travel in {@code swingArm} and the map swings: scaled per axis, 0 for In Place. */
    public static float swingX(float x) {
        HandPose p = pose;
        return p == null ? x : x * p.swingDX;
    }

    public static float swingY(float y) {
        HandPose p = pose;
        return p == null ? y : y * p.swingDY;
    }

    public static float swingZ(float z) {
        HandPose p = pose;
        return p == null ? z : z * p.swingDZ;
    }

    public static boolean swingScaled() {
        HandPose p = pose;
        return p != null && p.swingScaled;
    }

    /** The arm translate's three numbers, reused (render thread only). */
    private static final float[] ARM = new float[3];

    /**
     * The arm's first translate in {@code renderPlayerArm}: its resting place plus the swing's travel. Only the
     * travel is scaled, recomputed with Minecraft's numbers from the same swing progress.
     */
    public static float[] armTranslate(float x, float y, float z, float attack, HumanoidArm arm) {
        float[] t = ARM;
        t[0] = x;
        t[1] = y;
        t[2] = z;
        HandPose p = pose;
        if (p == null || !p.swingScaled) return t;
        float invert = arm != HumanoidArm.LEFT ? 1 : -1;
        float sqrt = Mth.sqrt(attack);
        float sx = -0.3F * Mth.sin(sqrt * (float) Math.PI);
        float sy = 0.4F * Mth.sin(sqrt * (float) (Math.PI * 2));
        float sz = -0.4F * Mth.sin(attack * (float) Math.PI);
        t[0] = x + invert * sx * (p.swingDX - 1);
        t[1] = y + sy * (p.swingDY - 1);
        t[2] = z + sz * (p.swingDZ - 1);
        return t;
    }

    /** Right after the arm reaches its place: an empty hand turns and resizes around it. */
    public static void afterArmPlaced(PoseStack ps) {
        HandPose p = pose;
        if (p == null || kind != Kind.EMPTY) return;
        try {
            if (p.pivot != HandPose.Pivot.VIEW) turn(ps, p);
            if (p.resized) ps.scale(p.scaleX, p.scaleY, p.scaleZ);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** A map turns and resizes around its center. */
    public static void beforeMap(PoseStack ps) {
        HandPose p = pose;
        if (p == null || kind != Kind.MAP) return;
        try {
            if (p.pivot != HandPose.Pivot.VIEW) turn(ps, p);
            if (p.resized) ps.scale(p.scaleX, p.scaleY, p.scaleZ);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void turn(PoseStack ps, HandPose p) {
        if (!p.turned) return;
        ps.mulPose(TURN.rotationXYZ(p.radX, side * p.radY, side * p.radZ));
    }

    /** Hand sway: Minecraft's lag angle times the setting. */
    public static float sway(float angle) {
        return angle * Hand.sway();
    }

    private static void fail(RuntimeException e) {
        Io.failed("Drawing your hand's pose", e);
    }

    // -------------------------------------------------------------- equip ---

    /** Selected slot last tick, and whether a switch's own dip is still playing. */
    private static int lastSlot = -1;
    private static boolean switching;

    /**
     * Start of the renderer's tick: notes slot switches. With "Only When Switching Slots" the dip plays from the
     * switch until the new item is up and the cooldown has refilled; outside that window nothing dips.
     */
    public static void tick(ItemStack visibleMain) {
        try {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            int slot = p.getInventory().getSelectedSlot();
            if (slot != lastSlot) {
                if (lastSlot >= 0) switching = true;
                lastSlot = slot;
            } else if (switching && visibleMain == p.getMainHandItem() && p.getItemSwapScale(1) >= 1) {
                switching = false;
            }
        } catch (RuntimeException e) {
            Io.failed("Following your hotbar slot", e);
        }
    }

    /** The renderer may show the new item at once, with no dip. */
    public static boolean instantSwap(boolean vanilla) {
        if (vanilla || !active()) return vanilla;
        return switch (Hand.equip()) {
            case NORMAL -> false;
            case SLOTS -> !switching;
            case OFF -> true;
        };
    }

    /** The dip after an attack or a swap, as the renderer reads it: none outside a slot switch unless Normal. */
    public static float swapScale(float vanilla) {
        if (!active()) return vanilla;
        return switch (Hand.equip()) {
            case NORMAL -> vanilla;
            case SLOTS -> switching ? vanilla : 1;
            case OFF -> 1;
        };
    }

    /** The dip when you use an ability: kept only with Normal. */
    public static boolean keepUseDip() {
        return !active() || Hand.equip() == Hand.Equip.NORMAL;
    }

    // -------------------------------------------------------------- tests ---

    /** Hands drawn with a pose since start. */
    public static int drawn() {
        return drawn;
    }
}
