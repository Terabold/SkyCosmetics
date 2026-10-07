package io.github.terabold.skycosmetics;

import io.github.terabold.skycosmetics.data.SkinEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Puts a skin on one head Hypixel shows in the world (your pet, your deployed
 * orb) without ever flashing Steve: a frame is only used once
 * {@link Textures#ready} says it is loaded, the last good frame is held while
 * the next one downloads, and until any frame is ready Hypixel's head stays.
 * One instance per target; it remembers which entity and slot are locked, and
 * the copy is rebuilt only when the frame or the server's stack changes, never
 * per render call.
 */
public final class HeadSwap {
    /** Hypixel's resource pack may point a head's item_model elsewhere; the copy must still draw a head. */
    private static final Identifier HEAD_MODEL = Identifier.withDefaultNamespace("player_head");
    /** Where a head is looked for on an entity: the usual place first. */
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.BODY, EquipmentSlot.SADDLE};

    /** A head in the world: the equipment slot holding it (null: an item display's item) and the stack. */
    public record Head(EquipmentSlot slot, ItemStack stack) {}

    private int id = -1;
    private EquipmentSlot slot;

    private ItemStack src;
    private SkinEntry skin;
    private int frame = -1;
    private ItemStack out;

    /** Entity id of the locked head, or -1. */
    public int id() {
        return id;
    }

    public EquipmentSlot slot() {
        return slot;
    }

    /** Lock {@code e}'s head in {@code slot}; re-locking the same entity only updates the slot. */
    public void lock(Entity e, EquipmentSlot slot) {
        if (e.getId() != id) release();
        id = e.getId();
        this.slot = slot;
    }

    public void unlock() {
        id = -1;
        slot = null;
        release();
    }

    /**
     * What to draw instead of {@code stack} on {@code entity}, or null. From an
     * entity renderer's equipment read ({@code from} = the slot read) the locked
     * slot matches; from an item model ({@code from} null: held items, an item
     * display's item) the stack must be the one in the locked slot. Every other
     * entity costs an id compare. Our own copy handed to a second hook (a worn
     * head the renderer then draws as an item) comes back as it is, never
     * restyled again by its server data.
     */
    public ItemStack head(SkinEntry e, Entity entity, EquipmentSlot from, ItemStack stack) {
        if (entity.getId() != id || e == null || stack.isEmpty()) return null;
        if (stack == out) return out;
        if (from != null ? from != slot : stack != stackIn(entity, slot)) return null;
        return swap(e, stack);
    }

    /** {@code stack} wearing {@code e}'s current frame, or null while no frame is loaded yet. */
    private ItemStack swap(SkinEntry e, ItemStack stack) {
        Textures.keepWarm(e);
        int want = e.frameAt(Util.getMillis() / 50);
        int f;
        if (Textures.ready(e.textures[want])) {
            f = want;
        } else if (skin == e && frame >= 0 && frame < e.textures.length && Textures.ready(e.textures[frame])) {
            f = frame; // hold the last good frame rather than flash Steve
        } else {
            return null; // still downloading: show Hypixel's head
        }
        if (out != null && src == stack && skin == e && frame == f) return out;

        // Whatever item Hypixel used, the copy is a real player head: worn on a head it takes
        // the skull path, anywhere else the player_head model draws the profile.
        ItemStack o = stack.is(Items.PLAYER_HEAD) ? stack.copy() : stack.transmuteCopy(Items.PLAYER_HEAD);
        o.set(DataComponents.PROFILE, Textures.profile(e.textures[f]));
        o.set(DataComponents.ITEM_MODEL, HEAD_MODEL);
        out = o;
        src = stack;
        skin = e;
        frame = f;
        return o;
    }

    /** Drop the copy (the target changed); the held frame survives so a re-lock doesn't flash. */
    public void release() {
        out = null;
        src = null;
    }

    /**
     * The head {@code e} shows, or null. A head is any stack with a skin profile
     * (or a player head), in any equipment slot of an entity that {@link #canShow}
     * one, or an item display's item: Hypixel is not tied to one of these, and
     * the slot is remembered so only that one is swapped.
     */
    public static Head headOf(Entity e) {
        if (!canShow(e)) return null;
        if (!(e instanceof LivingEntity l)) {
            ItemStack s = stackIn(e, null);
            return isHead(s) ? new Head(null, s) : null;
        }
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = l.getItemBySlot(slot);
            if (isHead(s)) return new Head(slot, s);
        }
        return null;
    }

    /**
     * Entities that may carry a head Hypixel shows: item displays and every
     * living entity (armour stands, mobs, mannequins) but real players, whose
     * heads are theirs. Hypixel's NPCs are fake players with a version 2 UUID
     * (real ones have version 4), so those count. Dropped items never do: a
     * mob drop near you is no pet or orb.
     */
    public static boolean canShow(Entity e) {
        return e instanceof Display.ItemDisplay
            || e instanceof LivingEntity && (!(e instanceof Player) || e.getUUID().version() == 2);
    }

    /** The stack in {@code slot} of {@code e} (null: an item display's or item entity's item), else EMPTY. */
    public static ItemStack stackIn(Entity e, EquipmentSlot slot) {
        if (slot == null) {
            if (e instanceof ItemEntity i) return i.getItem();
            SlotAccess a = e instanceof Display.ItemDisplay d ? d.getSlot(0) : null;
            return a == null ? ItemStack.EMPTY : a.get();
        }
        return e instanceof LivingEntity l ? l.getItemBySlot(slot) : ItemStack.EMPTY;
    }

    private static boolean isHead(ItemStack s) {
        return !s.isEmpty() && (s.is(Items.PLAYER_HEAD) || s.get(DataComponents.PROFILE) != null);
    }
}
