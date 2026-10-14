package io.github.terabold.skycosmetics.slots;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import io.github.terabold.skycosmetics.items.Profiles;
import io.github.terabold.skycosmetics.items.Where;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Slot locking and binding, per SkyBlock profile.
 *
 * Tap the lock key over one of your slots to lock it: your clicks, shift-clicks, number-key swaps and drops on it
 * are stopped before they leave the game, so a locked item can't be moved, sold or dropped by accident.
 *
 * Binding an inventory slot to a hotbar slot works the ways other mods taught people: hold the lock key on one and
 * let go over the other, or press the bind key on one and then on the other. A shift-click (or a plain click, if
 * chosen) on either end then swaps them, exactly as pressing that hotbar number over the inventory slot does. Locking
 * only ever holds back your own actions; binding turns your one click into that one vanilla swap.
 *
 * Kept in {@code config/skycosmetics/slot-locks.json}. Inventory slot numbers: 0-8 hotbar, 9-35 inventory, 36-39
 * armor; the off-hand and the SkyBlock menu slot (8) are never bound.
 */
public final class SlotLocks {
    public static final String SECTION = "slotLocks";

    public enum Icon { PADLOCK, DOT, CORNER, OUTLINE, NONE }

    public enum Place { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

    /** What swaps a bound slot with its hotbar slot. */
    public enum Swap { SHIFT_CLICK, CLICK, OFF }

    /** When bindings are outlined. */
    public enum Show { HOVER, ALWAYS, HIDDEN }

    static final class Bucket {
        final Set<Integer> locked = new LinkedHashSet<>();
        /** Inventory slot -> hotbar slot, oldest first. */
        final Map<Integer, Integer> binds = new LinkedHashMap<>();
    }

    // Settings; the draw and click paths read them directly.
    static boolean on = true, blockDrops = true, dungeonQ = true, hotbar = true, multiBind, everyMenu, links = true, tellStopped = true;
    static Swap swapWith = Swap.SHIFT_CLICK;
    static Show show = Show.HOVER;
    static Icon icon = Icon.PADLOCK;
    static Place place = Place.TOP_LEFT;
    static int size = 100, opacity = 85, color = 0xFFFFD24A, bindColor = 0xFF4BA3FF;

    private static KeyMapping key, bindKey;
    private static final Map<String, Bucket> BUCKETS = new HashMap<>();
    private static final Map<String, String> LAST_PROFILE = new HashMap<>();
    private static boolean loaded;

    // The key gesture: which key is down, where it started and whether a click used it up; the first slot of a
    // two-press binding; the short flash after a new binding; when a stopped action was last mentioned.
    private static KeyMapping held;
    private static boolean consumed;
    private static Integer anchor, pending;
    private static int flashFrom = -1, flashTo = -1;
    private static long flashAt, toldAt;
    /** The mouse button whose press was kept from the menu: its release is kept too, or the menu acts on it. */
    private static int swallowed = -1;
    /** Hotbar slot -> the inventory slot it last swapped with, for a hotbar slot bound to several. */
    private static final Map<Integer, Integer> LAST_SWAP = new HashMap<>();
    /** Set around our own swap so the lock check lets it through. */
    private static boolean ours;

    private static final Mask.Sheet OUTLINE = new Mask.Sheet("slots/lock_outline", 1, 1);

    private SlotLocks() {}

    public static void init(KeyMapping.Category category) {
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycosmetics.lockSlot", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L, category));
        bindKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycosmetics.bindSlot", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, category));
        Settings.register(SECTION, SlotLocks::read, SlotLocks::write);
        Hub.add(new Section(SECTION, Section.FEATURE, Component.literal("Slot Locking"),
            Component.literal("Lock and bind slots"),
            Component.literal("Lock slots so their items can't be moved, sold or dropped by accident, and bind inventory slots to hotbar slots."),
            () -> new ItemStack(Items.TRIPWIRE_HOOK), SlotLocks::rows));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs) || screen instanceof CreativeModeInventoryScreen) return;
            // Either key may be a keyboard key or a mouse button.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!active() || SkyCosmetics.typing(s)) return true;
                KeyMapping k = key.matches(event) ? key : !bindKey.isUnbound() && bindKey.matches(event) ? bindKey : null;
                if (k == null) return true;
                press(acs, k);
                return false;
            });
            ScreenKeyboardEvents.afterKeyRelease(screen).register((s, event) -> {
                if (held != null && held.matches(event)) release(acs);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (held != null) { consumed = true; swallowed = click.button(); return false; }
                KeyMapping k = !active() ? null : key.matchesMouse(click) ? key : !bindKey.isUnbound() && bindKey.matchesMouse(click) ? bindKey : null;
                if (k != null) { press(acs, k); return false; }
                if (!swapClick(acs, click)) return true;
                swallowed = click.button();
                return false;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((s, click) -> {
                if (held != null && held.matchesMouse(click)) { release(acs); return false; }
                if (click.button() != swallowed) return true;
                swallowed = -1;
                return false;
            });
            ScreenEvents.remove(screen).register(s -> { held = null; anchor = null; pending = null; swallowed = -1; });
        });
    }

    // ---------------------------------------------------------------- state ---

    /**
     * Locks and bindings belong to SkyBlock profiles, so they only act on SkyBlock (and in singleplayer, to try them
     * out): another game's items in those slots are left alone. Never in the creative inventory.
     */
    static boolean active() {
        if (!on) return false;
        Minecraft mc = Minecraft.getInstance();
        return !(mc.screen instanceof CreativeModeInventoryScreen) && (Where.skyBlock() || mc.hasSingleplayerServer());
    }

    public static boolean isLocked(int slot) {
        Bucket b = bucket(false);
        return b != null && (b.locked.contains(slot) || b.binds.containsKey(slot) || b.binds.containsValue(slot));
    }

    static boolean explicitlyLocked(int slot) {
        Bucket b = bucket(false);
        return b != null && b.locked.contains(slot);
    }

    /**
     * {inventory slot, hotbar slot} of the binding with this end, or null. A hotbar slot bound to several goes back
     * to the one it last swapped with, else to its newest.
     */
    static int[] bindingOf(int slot) {
        Bucket b = bucket(false);
        if (b == null) return null;
        Integer bar = b.binds.get(slot);
        if (bar != null) return new int[]{slot, bar};
        Integer last = LAST_SWAP.get(slot);
        if (last != null && Integer.valueOf(slot).equals(b.binds.get(last))) return new int[]{last, slot};
        int[] newest = null;
        for (var e : b.binds.entrySet()) if (e.getValue() == slot) newest = new int[]{e.getKey(), slot};
        return newest;
    }

    /** Whether this click must not happen. */
    public static boolean blocks(Slot slot, int button, ContainerInput input) {
        if (!active() || ours) return false;
        if (slot != null && slot.container instanceof Inventory && isLocked(slot.getContainerSlot())) return true;
        if (input == ContainerInput.PICKUP_ALL) return pullsFromLocked();
        return input == ContainerInput.SWAP && isLocked(button);
    }

    /** A double-click gathers every stack like the one on the cursor, locked slots' too: then it doesn't happen. */
    private static boolean pullsFromLocked() {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        ItemStack carried = p.containerMenu.getCarried();
        if (carried.isEmpty()) return false;
        for (Slot s : p.containerMenu.slots) {
            if (s.container instanceof Inventory && s.hasItem() && isLocked(s.getContainerSlot())
                && ItemStack.isSameItemSameComponents(s.getItem(), carried)) return true;
        }
        return false;
    }

    /** Whether Q must not drop the held item. */
    public static boolean blocksDrop(Player player) {
        return active() && blockDrops && !(dungeonQ && Where.dungeon()) && isLocked(player.getInventory().getSelectedSlot());
    }

    /** The menu's click path: {@link #blocks}, saying so on the action bar. */
    public static boolean stopClick(Slot slot, int button, ContainerInput input) {
        boolean stop = blocks(slot, button, input);
        if (stop) told();
        return stop;
    }

    /** Before Q drops the held item: {@link #blocksDrop}, saying so on the action bar. */
    public static boolean stopDrop(Player player) {
        boolean stop = blocksDrop(player);
        if (stop) told();
        return stop;
    }

    private static void told() {
        long now = System.currentTimeMillis();
        if (!tellStopped || now - toldAt < 1500) return;
        toldAt = now;
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setOverlayMessage(Component.literal("Locked slot; ").append(key.getTranslatedKeyMessage()).append(" over it unlocks"), false);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, .25f, .4f));
    }

    /** Bindings are made and used in your inventory, and in every menu when that's on. */
    private static boolean bindsHere(Screen screen) {
        return screen instanceof InventoryScreen || everyMenu && screen instanceof AbstractContainerScreen<?>;
    }

    /** A left click with or without shift, as chosen, on a bound slot: that one swap instead of the click. */
    private static boolean swapClick(AbstractContainerScreen<?> acs, MouseButtonEvent click) {
        if (!active() || swapWith == Swap.OFF || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !bindsHere(acs)
            || !acs.getMenu().getCarried().isEmpty()) return false;
        // The event's own modifiers, or the keyboard's: some input layers send clicks without them.
        boolean shift = click.hasShiftDown() || Minecraft.getInstance().hasShiftDown();
        // The slot under the click itself, as the menu would take it.
        return shift == (swapWith == Swap.SHIFT_CLICK) && swap(acs, ((AbstractContainerScreenAccessor) acs).skycosmetics$slotAt(click.x(), click.y()));
    }

    private static boolean swap(AbstractContainerScreen<?> acs, Slot hovered) {
        Integer at = playerSlot(hovered);
        int[] b = at == null ? null : bindingOf(at);
        if (b == null) return false;
        Slot source = slotOf(acs, b[0]);
        if (source == null) return false;
        LAST_SWAP.put(b[1], b[0]);
        ours = true;
        try {
            ((AbstractContainerScreenAccessor) acs).skycosmetics$slotClicked(source, source.index, b[1], ContainerInput.SWAP);
        } finally {
            ours = false;
        }
        return true;
    }

    /** One of the keys went down over the menu. */
    private static void press(AbstractContainerScreen<?> acs, KeyMapping k) {
        if (held != null) return; // a key repeat, or the other key while one is down
        held = k;
        consumed = false;
        anchor = playerSlot(hovered(acs));
        if (k == key) { pending = null; return; }
        if (pending == null) return;
        // The bind key's second press: bind the two, or on the same slot again remove its binding.
        Integer first = pending, at = anchor;
        pending = null;
        consumed = true;
        if (at == null) { message("Binding cancelled"); return; }
        if (at.equals(first)) {
            if (unbind(bucket(true), at)) changed(false, "Binding removed"); else message("Binding cancelled");
            return;
        }
        if (bindable(first, at) && bindsHere(acs)) bind(first, at);
        else pick(at);
    }

    /** The held key came up. */
    private static void release(AbstractContainerScreen<?> acs) {
        KeyMapping k = held;
        held = null;
        Integer start = anchor;
        anchor = null;
        if (consumed) return;
        Integer end = playerSlot(hovered(acs));
        if (start != null && end != null && !end.equals(start) && bindable(start, end)) {
            // Held on one slot and let go over another.
            if (bindsHere(acs)) bind(start, end); else message("Bind slots in your inventory, or turn on bindings in every menu");
            return;
        }
        if (k == bindKey) { if (start != null) pick(start); return; }
        if (end == null) end = start;
        if (end == null || end == Inventory.SLOT_OFFHAND) return;
        Bucket b = bucket(true);
        if (unbind(b, end)) { changed(false, "Binding removed"); return; }
        boolean locked = b.locked.add(end);
        if (!locked) b.locked.remove(end);
        changed(locked, locked ? "Slot locked" : "Slot unlocked");
    }

    /** The bind key's first press: this slot waits for its partner. */
    private static void pick(int slot) {
        if (!bindableEnd(slot)) { message("This slot can't be bound"); return; }
        pending = slot;
        message(bindingOf(slot) != null ? "Now press it on another slot, or here again to remove the binding"
            : "Now press it on the " + (hotbarSlot(slot) ? "inventory" : "hotbar") + " slot to bind with");
    }

    private static void bind(int a, int b) {
        int inv = hotbarSlot(a) ? b : a, bar = hotbarSlot(a) ? a : b;
        Bucket bk = bucket(true);
        unbind(bk, inv);
        if (!multiBind) unbind(bk, bar);
        bk.locked.remove(inv);
        bk.locked.remove(bar);
        bk.binds.put(inv, bar);
        flashFrom = inv; flashTo = bar; flashAt = System.currentTimeMillis();
        changed(true, "Slots bound");
    }

    private static boolean bindableEnd(int slot) {
        return slot >= 0 && slot < 36 && slot != 8;
    }

    private static boolean bindable(int a, int b) {
        return a != b && bindableEnd(a) && bindableEnd(b) && hotbarSlot(a) != hotbarSlot(b);
    }

    static boolean hotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }

    private static boolean unbind(Bucket b, int end) {
        return b.binds.entrySet().removeIf(e -> e.getKey() == end || e.getValue() == end);
    }

    private static void changed(boolean positive, String what) {
        save();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, positive ? 1f : .25f));
        message(what);
    }

    private static void message(String what) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(what), false);
    }

    private static Slot hovered(AbstractContainerScreen<?> acs) {
        return ((AbstractContainerScreenAccessor) acs).skycosmetics$hoveredSlot();
    }

    /** The menu's slot showing this inventory slot, or null. */
    private static Slot slotOf(AbstractContainerScreen<?> acs, int index) {
        for (Slot s : acs.getMenu().slots) if (s.container instanceof Inventory && s.getContainerSlot() == index && s.isActive()) return s;
        return null;
    }

    private static Integer playerSlot(Slot s) {
        return s != null && s.container instanceof Inventory ? s.getContainerSlot() : null;
    }

    /** Locks are kept per account and SkyBlock profile; until Hypixel names the profile, the last one it named. */
    private static Bucket bucket(boolean create) {
        load();
        var user = Minecraft.getInstance().getUser();
        String account = user == null ? "unknown" : user.getProfileId().toString();
        String profile = Profiles.current();
        if (profile != null && !profile.equals(LAST_PROFILE.get(account))) {
            LAST_PROFILE.put(account, profile);
            save();
        }
        String k = account + "|" + LAST_PROFILE.getOrDefault(account, "*");
        return create ? BUCKETS.computeIfAbsent(k, x -> new Bucket()) : BUCKETS.get(k);
    }

    // ------------------------------------------------------------- drawing ---

    /** Under the items, with the rarity backgrounds: the outline style. */
    public static void drawUnder(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!active() || icon != Icon.OUTLINE) return;
        try {
            for (Slot s : screen.getMenu().slots) {
                if (s.isActive() && s.container instanceof Inventory && isLocked(s.getContainerSlot())) {
                    outline(g, s.x, s.y, explicitlyLocked(s.getContainerSlot()) ? color : bindColor);
                }
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing slot lock outlines", e);
        }
    }

    /** Whether the lock outline takes the place of this slot's rarity outline. */
    static boolean replacesOutline(Slot s) {
        return active() && icon == Icon.OUTLINE && s.container instanceof Inventory && isLocked(s.getContainerSlot());
    }

    /** Over a slot's item, under its count. */
    public static void drawIcon(GuiGraphicsExtractor g, Slot s) {
        if (!active() || !(s.container instanceof Inventory)) return;
        try {
            int i = s.getContainerSlot();
            boolean lock = explicitlyLocked(i), bound = !lock && bindingOf(i) != null;
            if (lock || bound) mark(g, s.x, s.y, lock ? color : bindColor);
            Integer from = binding();
            if (from != null && bindsHere(Minecraft.getInstance().screen)) {
                if (i == from) g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x60000000 | bindColor & 0xFFFFFF); // where it starts
                else if (bindable(from, i)) g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x40FFFFFF);       // where it can end
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing a slot lock", e);
        }
    }

    /** The HUD hotbar: {@code under} the item the outline style, else over it the mark. */
    public static void drawHotbar(GuiGraphicsExtractor g, int x, int y, Player player, ItemStack stack, boolean under) {
        if (!active() || !hotbar || player == null || stack.isEmpty() || under != (icon == Icon.OUTLINE)) return;
        try {
            int i = hotbarIndex(player, stack);
            if (i < 0 || !isLocked(i)) return;
            int c = explicitlyLocked(i) ? color : bindColor;
            if (under) outline(g, x, y, c); else mark(g, x, y, c);
        } catch (RuntimeException e) {
            Io.failed("Drawing a hotbar lock", e);
        }
    }

    /** Whether a hotbar slot's lock outline takes the place of its rarity outline. */
    static boolean replacesHotbarOutline(ItemStack stack) {
        var p = Minecraft.getInstance().player;
        if (!active() || !hotbar || icon != Icon.OUTLINE || p == null) return false;
        int i = hotbarIndex(p, stack);
        return i >= 0 && isLocked(i);
    }

    /** The HUD draws the hotbar's own stacks: which slot holds this one. */
    private static int hotbarIndex(Player player, ItemStack stack) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i) == stack) return i;
        return -1;
    }

    /** The slot a binding being made starts from: held on it and moved away, or picked with the bind key. */
    private static Integer binding() {
        if (pending != null) return pending;
        if (held == null || consumed || anchor == null) return null;
        var screen = Minecraft.getInstance().screen;
        Integer at = screen instanceof AbstractContainerScreen<?> acs ? playerSlot(hovered(acs)) : null;
        return held == bindKey || !anchor.equals(at) ? anchor : null;
    }

    /**
     * Over the menu: the bindings (of the hovered slot, or all while shift is held, or always), a new binding's
     * flash, and a line from the slot a binding is being made from to the cursor.
     */
    public static void drawBindings(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        if (!active() || !bindsHere(screen)) return;
        try {
            Bucket b = bucket(false);
            var acc = (AbstractContainerScreenAccessor) screen;
            int left = acc.skycosmetics$leftPos(), top = acc.skycosmetics$topPos();
            g.pose().pushMatrix();
            try {
                g.pose().translate(left, top);
                if (b != null && show != Show.HIDDEN) {
                    Integer h = playerSlot(acc.skycosmetics$hoveredSlot());
                    boolean all = show == Show.ALWAYS || Minecraft.getInstance().hasShiftDown();
                    for (var e : b.binds.entrySet()) {
                        if (all || e.getKey().equals(h) || e.getValue().equals(h)) pair(g, screen, e.getKey(), e.getValue(), bindColor);
                    }
                }
                long age = System.currentTimeMillis() - flashAt;
                if (flashFrom >= 0 && age < 650) pair(g, screen, flashFrom, flashTo, (int) (230 - age * 230 / 650) << 24 | bindColor & 0xFFFFFF);
                Integer from = binding();
                Slot fs = from == null ? null : slotOf(screen, from);
                if (fs != null) line(g, fs.x + 8, fs.y + 8, mouseX - left, mouseY - top, 0xC0000000 | bindColor & 0xFFFFFF);
            } finally {
                g.pose().popMatrix();
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing slot bindings", e);
        }
    }

    private static void pair(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int a, int b, int c) {
        Slot sa = slotOf(screen, a), sb = slotOf(screen, b);
        if (links && sa != null && sb != null) line(g, sa.x + 8, sa.y + 8, sb.x + 8, sb.y + 8, (int) ((c >>> 24) * .7) << 24 | c & 0xFFFFFF);
        if (sa != null) g.outline(sa.x - 1, sa.y - 1, 18, 18, c);
        if (sb != null) g.outline(sb.x - 1, sb.y - 1, 18, 18, c);
    }

    /** A one-pixel line: a thin rectangle turned to point from one end to the other. */
    private static void line(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int c) {
        float dx = x1 - x0, dy = y1 - y0;
        int len = Math.round((float) Math.sqrt(dx * dx + dy * dy));
        if (len < 2) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x0, y0);
            g.pose().rotate((float) Math.atan2(dy, dx));
            g.pose().translate(0, -.5f);
            g.fill(0, 0, len, 1, c);
        } finally {
            g.pose().popMatrix();
        }
    }

    /** The outline style: the rarity backgrounds' own shape (rounded when they have none), in the lock color. */
    private static void outline(GuiGraphicsExtractor g, int x, int y, int rgb) {
        Mask.Shape shape = RarityBackgrounds.on && RarityBackgrounds.shape.outlined() ? RarityBackgrounds.shape : Mask.Shape.ROUNDED;
        Identifier tex = OUTLINE.ensure(shape.ordinal(), (img, i, x0, y0, n, s) ->
            Mask.paint(img, x0, y0, n, s, new Mask.Style(shape, Mask.Fill.SOLID, 0, true, 0, 100), 4));
        int tint = (int) Math.round((rgb >>> 24) * opacity / 100.0) << 24 | rgb & 0xFFFFFF;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 1, y - 1, 0, 0, Mask.CELL, Mask.CELL, OUTLINE.texWidth(), OUTLINE.texHeight(), tint);
    }

    /** A small mark in the chosen corner: a padlock, a dot or a corner tab. */
    private static void mark(GuiGraphicsExtractor g, int x, int y, int rgb) {
        if (icon == Icon.NONE) return;
        if (icon == Icon.OUTLINE) return; // drawn under the item instead
        int a = (int) Math.round((rgb >>> 24) * opacity / 100.0);
        int c = a << 24 | rgb & 0xFFFFFF, shadow = (int) (a * .7) << 24;
        float scale = size / 100f;
        int w = icon == Icon.PADLOCK ? 7 : icon == Icon.DOT ? 4 : 6, h = icon == Icon.PADLOCK ? 8 : w;
        float sw = w * scale, sh = h * scale;
        float px = switch (place) { case TOP_LEFT, BOTTOM_LEFT -> x; case TOP_RIGHT, BOTTOM_RIGHT -> x + 16 - sw; case CENTER -> x + (16 - sw) / 2; };
        float py = switch (place) { case TOP_LEFT, TOP_RIGHT -> y; case BOTTOM_LEFT, BOTTOM_RIGHT -> y + 16 - sh; case CENTER -> y + (16 - sh) / 2; };
        g.pose().pushMatrix();
        try {
            g.pose().translate(px, py);
            g.pose().scale(scale, scale);
            switch (icon) {
                case PADLOCK -> {
                    rect(g, 2, 0, 3, 1, c); rect(g, 1, 1, 1, 2, c); rect(g, 5, 1, 1, 2, c); // shackle
                    rect(g, 0, 3, 7, 5, c);                                               // body
                    rect(g, 3, 4, 1, 2, shadow);                                          // keyhole
                }
                case DOT -> { rect(g, 1, 0, 2, 4, c); rect(g, 0, 1, 4, 2, c); }
                case CORNER -> {
                    boolean left = place == Place.TOP_LEFT || place == Place.BOTTOM_LEFT || place == Place.CENTER;
                    boolean top = place != Place.BOTTOM_LEFT && place != Place.BOTTOM_RIGHT;
                    for (int r = 0; r < 6; r++) {
                        int len = 6 - r, row = top ? r : 5 - r;
                        rect(g, left ? 0 : 6 - len, row, len, 1, c);
                    }
                }
                default -> { }
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + h, c);
    }

    // ------------------------------------------------------------ settings ---

    private static List<Option> rows(Screen settings) {
        List<Option> r = new ArrayList<>();
        var overlaps = io.github.terabold.skycosmetics.compat.Overlaps.note(SECTION);
        if (overlaps != null) r.add(overlaps);
        r.add(row("on", "Enable slot locking", "Tap the lock key over one of your slots to lock or unlock it; bind an inventory slot to a hotbar slot with either key below. Locks are kept per SkyBlock profile and only act on SkyBlock (and in singleplayer, to try them).",
            new Control.Toggle(() -> on, v -> on = v)));
        r.add(row("key", "Lock key", "Tap over a slot to lock or unlock it. Hold it on one slot and let go over another to bind them. Default L.",
            new Control.Key(() -> key, true)));
        r.add(row("bindKey", "Bind key", "Press over one slot, then over the other, to bind them; press twice on the same slot to remove its binding. Holding and letting go over the other works too. Not set by default.",
            new Control.Key(() -> bindKey, true)));
        r.add(row("blockDrops", "Stop dropping locked items", "Q does nothing while a locked hotbar slot is selected.",
            new Control.Toggle(() -> blockDrops, v -> blockDrops = v)));
        r.add(row("dungeonQ", "Q still works in Dungeons", "In Dungeons Q uses your class Ultimate, so a lock doesn't stop it there.",
            new Control.Toggle(() -> dungeonQ, v -> dungeonQ = v)).visibleWhen(() -> blockDrops));
        r.add(row("tellStopped", "Say when a lock stops something", "A short note on the action bar, at most every 1.5 seconds.",
            new Control.Toggle(() -> tellStopped, v -> tellStopped = v)));
        r.add(Option.header("slotLocks.binding", Component.literal("Bindings")));
        r.add(row("swapWith", "Swap with", "Clicking either end of a binding swaps the two, like that hotbar number key over the inventory slot.",
            new Control.Choice<>(List.of(Swap.values()), v -> Component.literal(switch (v) {
                case SHIFT_CLICK -> "Shift-click"; case CLICK -> "Click"; case OFF -> "Off"; }), () -> swapWith, v -> swapWith = v)));
        r.add(row("multiBind", "Several per hotbar slot", "Bind one hotbar slot to more than one inventory slot. From the hotbar end, a swap goes back where the item came from.",
            new Control.Toggle(() -> multiBind, v -> multiBind = v)));
        r.add(row("everyMenu", "In every menu", "Make and use bindings on your own slots in chests and other menus too, not only in your inventory.",
            new Control.Toggle(() -> everyMenu, v -> everyMenu = v)));
        r.add(row("show", "Show bindings", "On hover: the binding of the slot under the cursor, or every binding while you hold shift.",
            new Control.Choice<>(List.of(Show.values()), v -> Component.literal(v == Show.HOVER ? "On hover" : label(v)), () -> show, v -> show = v)));
        r.add(row("links", "Link line", "A line between the two ends of a binding.", new Control.Toggle(() -> links, v -> links = v))
            .visibleWhen(() -> show != Show.HIDDEN));
        r.add(Option.header("slotLocks.look", Component.literal("Look")));
        r.add(row("icon", "Mark", "Outline replaces the rarity background's outline on locked slots.",
            new Control.Choice<>(List.of(Icon.values()), v -> Component.literal(label(v)), () -> icon, v -> icon = v)));
        r.add(row("place", "Position", "Where the mark sits in the slot.",
            new Control.Choice<>(List.of(Place.values()), v -> Component.literal(label(v)), () -> place, v -> place = v))
            .visibleWhen(() -> icon != Icon.OUTLINE && icon != Icon.NONE));
        r.add(row("size", "Mark size", "", new Control.Slider(() -> size, v -> size = (int) Math.round(v), 50, 200, 10,
            v -> Component.literal(Math.round(v) + "%"))).visibleWhen(() -> icon != Icon.OUTLINE && icon != Icon.NONE));
        r.add(row("opacity", "Opacity", "", new Control.Slider(() -> opacity, v -> opacity = (int) Math.round(v), 10, 100, 5,
            v -> Component.literal(Math.round(v) + "%"))));
        r.add(row("color", "Lock color", "", new Control.Color(() -> color, v -> color = v, false)));
        r.add(row("bindColor", "Binding color", "", new Control.Color(() -> bindColor, v -> bindColor = v, false)));
        r.add(row("hotbar", "Show on the hotbar", "Mark locked hotbar slots on the HUD too.", new Control.Toggle(() -> hotbar, v -> hotbar = v)));
        r.add(Option.of("slotLocks.clear", Component.literal("Clear this profile"), Component.literal("Unlocks and unbinds every slot of the current profile."),
            new Control.Action(Component.literal("Clear"), () -> {
                Bucket b = bucket(false);
                if (b != null) { b.locked.clear(); b.binds.clear(); save(); }
            }, () -> { Bucket b = bucket(false); return b != null && (!b.locked.isEmpty() || !b.binds.isEmpty()); }, Component.literal("Nothing locked"))));
        return r.stream().map(o -> List.of("slotLocks.opacity", "slotLocks.size", "slotLocks.color", "slotLocks.bindColor").contains(o.id()) ? o.compact().beside() : o.compact()).toList();
    }

    private static Option row(String id, String title, String help, Control c) {
        Option o = Option.of(SECTION + "." + id, Component.literal(title), Component.literal(help), c);
        return id.equals("on") ? o : o.enabledWhen(() -> on);
    }

    private static String label(Enum<?> v) {
        String s = v.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void read(JsonElement v) {
        JsonObject o = Settings.obj(v);
        on = Settings.bool(o.get("on"), on);
        // "swapBound" was the on/off before "swapWith".
        swapWith = choice(Swap.values(), Settings.str(o.get("swapWith"), null), Settings.bool(o.get("swapBound"), true) ? swapWith : Swap.OFF);
        blockDrops = Settings.bool(o.get("blockDrops"), blockDrops);
        dungeonQ = Settings.bool(o.get("dungeonQ"), dungeonQ);
        tellStopped = Settings.bool(o.get("tellStopped"), tellStopped);
        multiBind = Settings.bool(o.get("multiBind"), multiBind);
        everyMenu = Settings.bool(o.get("everyMenu"), everyMenu);
        show = choice(Show.values(), Settings.str(o.get("show"), null), show);
        links = Settings.bool(o.get("links"), links);
        hotbar = Settings.bool(o.get("hotbar"), hotbar);
        icon = choice(Icon.values(), Settings.str(o.get("icon"), null), icon);
        place = choice(Place.values(), Settings.str(o.get("place"), null), place);
        size = (int) Settings.num(o.get("size"), size, 50, 200);
        opacity = (int) Settings.num(o.get("opacity"), opacity, 10, 100);
        color = 0xFF000000 | (int) Settings.num(o.get("color"), color, Integer.MIN_VALUE, Integer.MAX_VALUE);
        bindColor = 0xFF000000 | (int) Settings.num(o.get("bindColor"), bindColor, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on); o.addProperty("swapWith", swapWith.name()); o.addProperty("blockDrops", blockDrops);
        o.addProperty("dungeonQ", dungeonQ); o.addProperty("tellStopped", tellStopped); o.addProperty("multiBind", multiBind); o.addProperty("everyMenu", everyMenu);
        o.addProperty("show", show.name()); o.addProperty("links", links); o.addProperty("hotbar", hotbar); o.addProperty("icon", icon.name()); o.addProperty("place", place.name());
        o.addProperty("size", size); o.addProperty("opacity", opacity); o.addProperty("color", color); o.addProperty("bindColor", bindColor);
        return o;
    }

    private static <E extends Enum<E>> E choice(E[] values, String name, E fallback) {
        if (name != null) for (E e : values) if (e.name().equalsIgnoreCase(name)) return e;
        return fallback;
    }

    // ---------------------------------------------------------- the file ---

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(SkyCosmetics.MOD_ID).resolve("slot-locks.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            if (Files.size(f) > 1 << 20) throw new java.io.IOException("too large");
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : Settings.obj(root.get("buckets")).entrySet()) {
                if (BUCKETS.size() >= 256 || !(e.getValue() instanceof JsonObject b)) continue;
                Bucket bucket = new Bucket();
                if (b.get("locked") instanceof JsonArray a) for (JsonElement x : a) {
                    int i = (int) Settings.num(x, -1, -1, 99);
                    if (i >= 0 && i < Inventory.SLOT_OFFHAND) bucket.locked.add(i);
                }
                for (var bind : Settings.obj(b.get("binds")).entrySet()) {
                    try {
                        int inv = Integer.parseInt(bind.getKey()), bar = (int) Settings.num(bind.getValue(), -1, -1, 8);
                        if (!hotbarSlot(inv) && bindable(inv, bar)) bucket.binds.put(inv, bar);
                    } catch (NumberFormatException ignored) { /* not a slot number */ }
                }
                BUCKETS.put(e.getKey(), bucket);
            }
            for (var e : Settings.obj(root.get("lastProfile")).entrySet()) {
                String p = Settings.str(e.getValue(), null);
                if (p != null && p.length() <= 32) LAST_PROFILE.put(e.getKey(), p);
            }
        } catch (java.io.IOException | RuntimeException e) {
            Io.setAside(f, "Could not read slot-locks.json", e);
        }
    }

    private static void save() {
        Io.writeAsync(file(), json());
    }

    /** What the file holds: every profile's locks and bindings, and the last profile Hypixel named per account. */
    private static JsonObject json() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject buckets = new JsonObject();
        for (var e : BUCKETS.entrySet()) {
            if (e.getValue().locked.isEmpty() && e.getValue().binds.isEmpty()) continue;
            JsonObject b = new JsonObject();
            JsonArray locked = new JsonArray();
            e.getValue().locked.forEach(locked::add);
            b.add("locked", locked);
            JsonObject binds = new JsonObject();
            e.getValue().binds.forEach((k, v) -> binds.addProperty(String.valueOf(k), v));
            b.add("binds", binds);
            buckets.add(e.getKey(), b);
        }
        root.add("buckets", buckets);
        JsonObject last = new JsonObject();
        LAST_PROFILE.forEach(last::addProperty);
        root.add("lastProfile", last);
        return root;
    }

    // --------------------------------------------------------------- tests ---

    /** For tests: lock or unlock a slot of the current profile. */
    public static void testLock(int slot, boolean locked) {
        Bucket b = bucket(true);
        if (locked) b.locked.add(slot); else b.locked.remove(slot);
    }

    /** For tests: bind an inventory slot to a hotbar slot (or unbind with -1). */
    public static void testBind(int inventory, int hotbar) {
        Bucket b = bucket(true);
        unbind(b, inventory);
        if (hotbar >= 0 && bindable(inventory, hotbar)) b.binds.put(inventory, hotbar);
    }

    /** For tests: the look. */
    public static void testLook(Icon i, boolean enabled) {
        icon = i;
        on = enabled;
    }

    /** For tests: how bindings swap and where. */
    public static void testBinding(Swap swap, boolean several, boolean anyMenu) {
        swapWith = swap;
        multiBind = several;
        everyMenu = anyMenu;
    }

    /** For tests: the bind key (GLFW key code, or -1 for none). */
    public static void testBindKey(int glfw) {
        bindKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(glfw));
        KeyMapping.resetMapping();
    }

    /** For tests: what slot-locks.json would hold now (loading it first if nothing asked for it yet). */
    public static JsonObject testFile() {
        load();
        return json();
    }

    /** For tests: {inventory slot, hotbar slot} of the binding with this end, or null. */
    public static int[] testPartner(int slot) {
        return bindingOf(slot);
    }
}
