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

    /**
     * A mark, drawn by hand here. Besides this mod's own, the looks players know from other mods, redrawn to their
     * shapes and colors (none of their files is used): NEU's and SkyblockAddons' gold padlock over the slot,
     * Firmament's small red lock, SkyBlock Enhancements' corner padlock, Skyblocker's dashed frame and NEU's stitched
     * frame for bound slots. The tinted ones take the color picked here.
     */
    public enum Icon {
        PADLOCK, NEU_PADLOCK, SBA_PADLOCK, FIRMAMENT_LOCK, SBE_LOCK, DOT, CORNER, OUTLINE, BOX, DASHED, STITCHED, TINT, NONE;

        /** Drawn at the chosen corner and size. */
        boolean small() { return this == PADLOCK || this == DOT || this == CORNER || this == SBE_LOCK; }

        /** Drawn in the color picked here; the others keep their own colors. */
        boolean tinted() { return this != NEU_PADLOCK && this != SBA_PADLOCK && this != FIRMAMENT_LOCK && this != SBE_LOCK && this != NONE; }
    }

    static final List<Icon> LOCK_MARKS = List.of(Icon.PADLOCK, Icon.NEU_PADLOCK, Icon.SBA_PADLOCK, Icon.FIRMAMENT_LOCK, Icon.SBE_LOCK,
        Icon.DOT, Icon.CORNER, Icon.OUTLINE, Icon.BOX, Icon.DASHED, Icon.TINT, Icon.NONE);
    static final List<Icon> BOUND_MARKS = List.of(Icon.OUTLINE, Icon.BOX, Icon.STITCHED, Icon.DASHED, Icon.TINT, Icon.DOT, Icon.CORNER, Icon.NONE);

    /** Where the lines between bound slots start: the outlines' edges, or the slots' centers. */
    public enum LineFrom { EDGES, CENTRES }

    public enum Place { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

    /** What swaps a bound slot with its hotbar slot. */
    public enum Swap { SHIFT_CLICK, CLICK, CTRL_CLICK, OFF }

    /** When the lines between bound slots show. */
    public enum Show { ALWAYS, HOVER, HIDDEN }

    /** Where a lock that stopped something says so. */
    public enum Tell { CHAT, ACTION_BAR, OFF }

    static final class Bucket {
        final Set<Integer> locked = new LinkedHashSet<>();
        /** Inventory slot -> hotbar slot, oldest first. */
        final Map<Integer, Integer> binds = new LinkedHashMap<>();
    }

    // Settings; the draw and click paths read them directly.
    static boolean on = true, blockDrops = true, dungeonQ = true, hotbar = true, multiBind, everyMenu, boundLocked = true, redHover = true;
    static LineFrom lineFrom = LineFrom.EDGES;
    static Swap swapWith = Swap.SHIFT_CLICK;
    static Show show = Show.ALWAYS;
    static Tell tell = Tell.CHAT;
    /** The mark of a locked slot, and of a bound one. */
    static Icon icon = Icon.PADLOCK, bindIcon = Icon.OUTLINE;
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
    /** With the key held, the slot clicked first: the next click on its partner binds the two. */
    private static Integer clickFrom;
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
        io.github.terabold.skycosmetics.gui.hub.KeyBindButton.mayShare(key, bindKey); // one key may both lock and bind
        io.github.terabold.skycosmetics.gui.hub.KeyBindButton.onlyInMenus(key);       // L and Advancements never both act
        io.github.terabold.skycosmetics.gui.hub.KeyBindButton.onlyInMenus(bindKey);
        Settings.register(SECTION, SlotLocks::read, SlotLocks::write);
        Hub.add(new Section(SECTION, Section.FEATURE, Component.literal("Slot locking"),
            Component.literal("Lock and bind slots"),
            Component.literal("Lock slots so their items can't be moved, sold or dropped by accident, and bind inventory slots to hotbar slots."),
            () -> new ItemStack(Items.TRIPWIRE_HOOK), SlotLocks::rows));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs) || screen instanceof CreativeModeInventoryScreen) return;
            // Either key may be a keyboard key or a mouse button.
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!active() || SkyCosmetics.typing(s)) return true;
                KeyMapping k = key.matches(event) ? key : !bindKey.isUnbound() && bindKey.matches(event) ? bindKey : null;
                if (k != null) press(acs, k);
                return true; // never kept from the menu: another mod's slot lock on the same key still gets it
            });
            ScreenKeyboardEvents.afterKeyRelease(screen).register((s, event) -> {
                if (held != null && held.matches(event)) release(acs);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (held != null) { heldClick(acs, click); consumed = true; swallowed = click.button(); return false; }
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
            ScreenEvents.remove(screen).register(s -> { held = null; anchor = null; pending = null; clickFrom = null; swallowed = -1; });
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
        return b != null && (b.locked.contains(slot) || boundLocked && (b.binds.containsKey(slot) || b.binds.containsValue(slot)));
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
        return lockedPulledFrom() >= 0;
    }

    /** The locked inventory slot a double-click would gather from, or -1. */
    private static int lockedPulledFrom() {
        var p = Minecraft.getInstance().player;
        if (p == null) return -1;
        ItemStack carried = p.containerMenu.getCarried();
        if (carried.isEmpty()) return -1;
        for (Slot s : p.containerMenu.slots) {
            if (s.container instanceof Inventory && s.hasItem() && isLocked(s.getContainerSlot())
                && ItemStack.isSameItemSameComponents(s.getItem(), carried)) return s.getContainerSlot();
        }
        return -1;
    }

    /** Whether Q must not drop the held item. */
    public static boolean blocksDrop(Player player) {
        return active() && blockDrops && !(dungeonQ && Where.dungeon()) && isLocked(player.getInventory().getSelectedSlot());
    }

    /** The menu's click path: {@link #blocks}, saying so. */
    public static boolean stopClick(Slot slot, int button, ContainerInput input) {
        boolean stop = blocks(slot, button, input);
        if (stop) {
            var p = Minecraft.getInstance().player;
            int at = slot != null && slot.container instanceof Inventory && isLocked(slot.getContainerSlot()) ? slot.getContainerSlot()
                : input == ContainerInput.PICKUP_ALL ? lockedPulledFrom() : button;
            told(p == null || at < 0 || at >= p.getInventory().getContainerSize() ? ItemStack.EMPTY : p.getInventory().getItem(at), at);
        }
        return stop;
    }

    /** Before Q drops the held item: {@link #blocksDrop}, saying so. */
    public static boolean stopDrop(Player player) {
        boolean stop = blocksDrop(player);
        if (stop) told(player.getInventory().getSelectedItem(), player.getInventory().getSelectedSlot());
        return stop;
    }

    /** "Protected your item: Hyperion." in chat as Firmament says it, the how-to-unlock under the mouse; at most every 3 s. */
    private static void told(ItemStack item, int slot) {
        long now = System.currentTimeMillis();
        if (tell == Tell.OFF || now - toldAt < (tell == Tell.CHAT ? 3000 : 1500)) return;
        toldAt = now;
        Minecraft mc = Minecraft.getInstance();
        boolean bound = !explicitlyLocked(slot) && bindingOf(slot) != null;
        Component name = item == null || item.isEmpty() ? Component.literal("this slot") : item.getStyledHoverName();
        Component how = Component.literal(bound
            ? "This slot is bound to a hotbar slot, which locks it. Tap " : "This slot is locked. Tap ")
            .append(key.getTranslatedKeyMessage())
            .append(bound ? " over it to remove the binding, or turn off \"Bound slots are locked\" in Slot locking." : " over it to unlock it.");
        if (tell == Tell.CHAT && mc.player != null) {
            mc.player.sendSystemMessage(SkyCosmetics.prefix().append(Component.literal("Protected your item: ")
                .withStyle(net.minecraft.ChatFormatting.RED).append(name.copy()).append(Component.literal(". "))
                .append(Component.literal("Hover for more info.").withStyle(net.minecraft.ChatFormatting.GRAY))
                .withStyle(s -> s.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(how)))));
        } else {
            mc.gui.setOverlayMessage(Component.literal("Protected your item: ").append(name.copy()), false);
        }
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
        Minecraft mc = Minecraft.getInstance();
        boolean shift = click.hasShiftDown() || mc.hasShiftDown(), ctrl = click.hasControlDown() || mc.hasControlDown();
        boolean wanted = switch (swapWith) {
            case SHIFT_CLICK -> shift && !ctrl;
            case CLICK -> !shift && !ctrl;
            case CTRL_CLICK -> ctrl && !shift;
            case OFF -> false;
        };
        // The slot under the click itself, as the menu would take it.
        return wanted && swap(acs, ((AbstractContainerScreenAccessor) acs).skycosmetics$slotAt(click.x(), click.y()));
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
        if (start != null && end != null && !end.equals(start)) {
            // Held on one slot and let go over another: bind them, or say why not; never a lock on either.
            if (!bindable(start, end)) refuse("Bind one inventory slot to one hotbar slot (not the SkyBlock menu)");
            else if (bindsHere(acs)) bind(start, end);
            else refuse("Bind slots in your inventory, or turn on bindings in every menu");
            return;
        }
        if (k == bindKey) { if (start != null) pick(start); return; }
        if (start == null || end == null || end == Inventory.SLOT_OFFHAND) return; // let go off the slots: nothing
        Bucket b = bucket(true);
        if (unbind(b, end)) { changed(false, "Binding removed"); return; }
        boolean locked = b.locked.add(end);
        if (!locked) b.locked.remove(end);
        changed(locked, locked ? "Slot locked" : "Slot unlocked");
    }

    /**
     * A click with the key held: left on one slot and then on its partner binds the two (from the slot the key went
     * down on, too); right on a bound slot removes its binding.
     */
    private static void heldClick(AbstractContainerScreen<?> acs, MouseButtonEvent click) {
        Integer at = playerSlot(((AbstractContainerScreenAccessor) acs).skycosmetics$slotAt(click.x(), click.y()));
        if (at == null) return;
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (unbind(bucket(true), at)) changed(false, "Binding removed");
            clickFrom = null;
            return;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        Integer from = clickFrom != null ? clickFrom : anchor;
        if (from != null && !from.equals(at) && bindable(from, at)) {
            if (bindsHere(acs)) bind(from, at); else refuse("Bind slots in your inventory, or turn on bindings in every menu");
            clickFrom = null;
        } else {
            clickFrom = at;
        }
    }

    private static void refuse(String why) {
        message(why);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), .5f, .4f));
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

    /**
     * Locks are kept per account and SkyBlock profile (until Hypixel names the profile, the last one it named), with
     * a set of their own in the Rift and in the Safari, whose inventories are their own.
     */
    private static Bucket bucket(boolean create) {
        load();
        var user = Minecraft.getInstance().getUser();
        java.util.UUID id = user == null ? null : user.getProfileId();
        if (id != keyUser || keyAccount == null) {
            keyUser = id;
            keyAccount = id == null ? "unknown" : id.toString();
        }
        String account = keyAccount;
        String profile = Profiles.current();
        if (profile != null && !profile.equals(LAST_PROFILE.get(account))) {
            LAST_PROFILE.put(account, profile);
            save();
        }
        // Out of SkyBlock (singleplayer, to try it) with no profile named: a set of its own, never a real profile's.
        String last = profile == null && !Where.skyBlock() ? "singleplayer" : LAST_PROFILE.getOrDefault(account, "*");
        Where.Island island = Where.island();
        if (!last.equals(keyLast) || island != keyIsland || !account.equals(keyFor)) {
            keyFor = account;
            keyLast = last;
            keyIsland = island;
            bucketKey = account + "|" + last + switch (island) {
                case RIFT -> "|rift";
                case SAFARI -> "|safari";
                case MAIN -> "";
            };
        }
        return create ? BUCKETS.computeIfAbsent(bucketKey, x -> new Bucket()) : BUCKETS.get(bucketKey);
    }

    /** The last bucket key and what it was made of. */
    private static java.util.UUID keyUser;
    private static String keyAccount, keyFor, keyLast, bucketKey;
    private static Where.Island keyIsland;

    // ------------------------------------------------------------- drawing ---

    /** The mark of an inventory slot: a locked slot's, a bound slot's, or null. */
    private static Icon markOf(int slot) {
        if (explicitlyLocked(slot)) return icon;
        return bindingOf(slot) != null ? bindIcon : null;
    }

    private static int colorOf(int slot) {
        return explicitlyLocked(slot) ? color : bindColor;
    }

    /** Under the items, with the rarity backgrounds: the outline marks. */
    public static void drawUnder(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!active() || icon != Icon.OUTLINE && bindIcon != Icon.OUTLINE) return;
        try {
            for (Slot s : screen.getMenu().slots) {
                if (s.isActive() && s.container instanceof Inventory && markOf(s.getContainerSlot()) == Icon.OUTLINE) {
                    outline(g, s.x, s.y, colorOf(s.getContainerSlot()));
                }
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing slot lock outlines", e);
        }
    }

    /** Whether the lock outline takes the place of this slot's rarity outline. */
    static boolean replacesOutline(Slot s) {
        return active() && s.container instanceof Inventory && markOf(s.getContainerSlot()) == Icon.OUTLINE;
    }

    /** Over a slot's item, under its count. */
    public static void drawIcon(GuiGraphicsExtractor g, Slot s) {
        if (!active() || !(s.container instanceof Inventory)) return;
        try {
            int i = s.getContainerSlot();
            Icon m = markOf(i);
            var screen = Minecraft.getInstance().screen;
            boolean under = screen instanceof AbstractContainerScreen<?> acs && hovered(acs) == s;
            if (redHover && under && isLocked(i)) g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x50FF5555); // as NEU shows a locked slot
            if (m != null) mark(g, s.x, s.y, colorOf(i), m);
            Integer from = binding();
            if (from != null && bindsHere(screen)) {
                // Where it starts and the slot it would end on show the bound look itself, as the binding will.
                if (i == from) emphasis(g, s.x, s.y, 0xFF000000 | bindColor);
                else if (bindable(from, i)) {
                    if (under) emphasis(g, s.x, s.y, 0xFF000000 | bindColor);
                    else g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x40FFFFFF);                              // where it can end, as NEU shows it
                } else if (under) g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x50FF3030);                      // where it can't
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing a slot lock", e);
        }
    }

    /** The HUD hotbar: {@code under} the item the outline style, else over it the mark. */
    public static void drawHotbar(GuiGraphicsExtractor g, int x, int y, Player player, ItemStack stack, boolean under) {
        if (!active() || !hotbar || player == null || stack.isEmpty()) return;
        try {
            int i = hotbarIndex(player, stack);
            Icon m = i < 0 ? null : markOf(i);
            if (m == null || under != (m == Icon.OUTLINE)) return;
            if (under) outline(g, x, y, colorOf(i)); else mark(g, x, y, colorOf(i), m);
        } catch (RuntimeException e) {
            Io.failed("Drawing a hotbar lock", e);
        }
    }

    /** Whether a hotbar slot's lock outline takes the place of its rarity outline. */
    static boolean replacesHotbarOutline(ItemStack stack) {
        var p = Minecraft.getInstance().player;
        if (!active() || !hotbar || p == null) return false;
        int i = hotbarIndex(p, stack);
        return i >= 0 && markOf(i) == Icon.OUTLINE;
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
        if (held != null && clickFrom != null) return clickFrom;
        if (held == null || consumed || anchor == null) return null;
        var screen = Minecraft.getInstance().screen;
        Integer at = screen instanceof AbstractContainerScreen<?> acs ? playerSlot(hovered(acs)) : null;
        return held == bindKey || !anchor.equals(at) ? anchor : null;
    }

    /**
     * Over the menu: the lines between bound slots (always, or for the hovered slot and all while shift is held),
     * the hovered binding framed, a new binding's flash, and a line from the slot a binding is being made from to
     * the cursor. Lines run from one slot's outline edge to the other's.
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
                    int faint = (int) ((bindColor >>> 24) * .55) << 24 | bindColor & 0xFFFFFF;
                    for (var e : b.binds.entrySet()) {
                        boolean hovered = e.getKey().equals(h) || e.getValue().equals(h);
                        if (all || hovered) pair(g, screen, e.getKey(), e.getValue(), hovered ? bindColor : faint, hovered);
                    }
                }
                long age = System.currentTimeMillis() - flashAt;
                if (flashFrom >= 0 && age < 650) pair(g, screen, flashFrom, flashTo, (int) (230 - age * 230 / 650) << 24 | bindColor & 0xFFFFFF, true);
                Integer from = binding();
                Slot fs = from == null ? null : slotOf(screen, from);
                if (fs != null) {
                    float[] at = anchor(fs, mouseX - left, mouseY - top);
                    line(g, at[0], at[1], mouseX - left, mouseY - top, 0xC0000000 | bindColor & 0xFFFFFF);
                }
            } finally {
                g.pose().popMatrix();
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing slot bindings", e);
        }
    }

    private static void pair(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int a, int b, int c, boolean framed) {
        Slot sa = slotOf(screen, a), sb = slotOf(screen, b);
        if (sa != null && sb != null) {
            float[] from = anchor(sa, sb.x + 8, sb.y + 8), to = anchor(sb, sa.x + 8, sa.y + 8);
            line(g, from[0], from[1], to[0], to[1], c);
        }
        if (!framed) return;
        if (sa != null) emphasis(g, sa.x, sa.y, c);
        if (sb != null) emphasis(g, sb.x, sb.y, c);
    }

    /**
     * A bound slot picked out (hovered, being bound, just bound): the bound look again at {@code argb}'s own
     * opacity, so it stands out in the same design; None and Tint get a frame, having no edge of their own.
     */
    private static void emphasis(GuiGraphicsExtractor g, int x, int y, int argb) {
        switch (bindIcon) {
            case OUTLINE -> outlineAt(g, x, y, argb);
            case NONE, TINT -> g.outline(x - 1, y - 1, 18, 18, argb);
            default -> markAt(g, x, y, argb, bindIcon);
        }
    }

    /**
     * Where a line leaves a slot toward a point, as Firmament draws it: the middle of the outline's bottom edge when
     * the point is lower, of its top edge when higher, of a side when level; or the slot's centre.
     */
    private static float[] anchor(Slot s, float tx, float ty) {
        float cx = s.x + 8, cy = s.y + 8;
        if (lineFrom == LineFrom.CENTRES) return new float[]{cx, cy};
        if (Math.abs(ty - cy) < 9) return new float[]{tx > cx ? s.x + 16 : s.x - 1, cy};
        return new float[]{cx, ty > cy ? s.y + 16 : s.y - 1};
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
        outlineAt(g, x, y, (int) Math.round((rgb >>> 24) * opacity / 100.0) << 24 | rgb & 0xFFFFFF);
    }

    private static void outlineAt(GuiGraphicsExtractor g, int x, int y, int argb) {
        Mask.Shape shape = RarityBackgrounds.on && RarityBackgrounds.shape.outlined() ? RarityBackgrounds.shape : Mask.Shape.ROUNDED;
        Identifier tex = OUTLINE.ensure(shape.ordinal(), (img, i, x0, y0, n, s) ->
            Mask.paint(img, x0, y0, n, s, new Mask.Style(shape, Mask.Fill.SOLID, 0, true, 0, 100), 4));
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 1, y - 1, 0, 0, Mask.CELL, Mask.CELL, OUTLINE.texWidth(), OUTLINE.texHeight(), argb);
    }

    // Pixel art, 16 columns a row unless noted; each letter a color of the mark's palette, '.' nothing.
    private static final String[] NEU_LOCK = {
        "................", "......OOOO......", ".....OYYYYO.....", "....OYYMMYYO....", "....OYS..SMO....", "....OYS..SMO....",
        "....OYS..SMO....", "...OOOOOOOOOO...", "...OYYYYMMMSD...", "...OYYYYMMMSD...", "...OYYYKKMMSD...", "...OYYMMKMSSD...",
        "...OYMMMKSSSD...", "...OMMSSSSSSD...", "...DDDDDDDDDD...", "................"};
    private static final Map<Character, Integer> NEU_GOLD = Map.of('Y', 0xF2EE00, 'M', 0xD0CD00, 'S', 0xAA9908, 'O', 0x8E7100, 'D', 0x715405, 'K', 0x000000);
    private static final Map<Character, Integer> SBA_OLIVE = Map.of('Y', 0xD6C358, 'M', 0xA09242, 'S', 0x6E6530, 'O', 0x453F1C, 'D', 0x453F1C, 'K', 0x2A2611);
    private static final String[] FIRMAMENT = {".RRRR.", ".R..R.", ".R..R.", "RRRRRR", "RRRRRR", "RRRRRR"};
    private static final String[] SBE = {"..KKK..", ".KBWBK.", ".KB.BK.", "KKKKKKK", "KGHGGGK", "KGGKGGK", "KAAAOAK", "KKKKKKK"};
    private static final Map<Character, Integer> SBE_COLORS = Map.of('K', 0x121116, 'B', 0x647591, 'W', 0x93A1BB, 'G', 0xF7CC25,
        'H', 0xFFE956, 'A', 0xFFA314, 'O', 0xED7514, 'R', 0xFF0000);

    /** A mark over the slot's item: a padlock, a dot, a corner, a frame or a tint, in the chosen corner when it's small. */
    private static void mark(GuiGraphicsExtractor g, int x, int y, int rgb, Icon icon) {
        markAt(g, x, y, (int) Math.round((rgb >>> 24) * opacity / 100.0) << 24 | rgb & 0xFFFFFF, icon);
    }

    private static void markAt(GuiGraphicsExtractor g, int x, int y, int c, Icon icon) {
        int a = c >>> 24, rgb = c & 0xFFFFFF;
        switch (icon) {
            case NONE, OUTLINE -> { }                                         // nothing, or drawn under the item
            case NEU_PADLOCK -> pixels(g, x, y, NEU_LOCK, NEU_GOLD, 0x80); // the whole slot at half opacity
            case SBA_PADLOCK -> pixels(g, x, y, NEU_LOCK, SBA_OLIVE, 0x66);
            case FIRMAMENT_LOCK -> pixels(g, x + 1, y + 1, FIRMAMENT, SBE_COLORS, 0xFF);
            case BOX -> g.outline(x, y, 17, 17, c);                          // over the item's edge, as Firmament draws it
            case TINT -> g.fill(x, y, x + 16, y + 16, (int) (a * .4) << 24 | rgb & 0xFFFFFF);
            case DASHED -> {
                for (int i = 0; i < 16; i++) {
                    if (i % 3 == 2) continue; // two on, one off
                    g.fill(x + i, y, x + i + 1, y + 1, c); g.fill(x + i, y + 15, x + i + 1, y + 16, c);
                    g.fill(x, y + i, x + 1, y + i + 1, c); g.fill(x + 15, y + i, x + 16, y + i + 1, c);
                }
            }
            case STITCHED -> rope(g, x, y, c);
            default -> small(g, x, y, c, icon);
        }
    }

    /**
     * NEU's kind of bound-slot frame: a rope weaving between the slot's edge and one pixel in, in {@code c} and a
     * shade darker (NEU's own is two blues).
     */
    private static void rope(GuiGraphicsExtractor g, int x, int y, int c) {
        int rgb = c & 0xFFFFFF, a = c & 0xFF000000;
        int light = a | rgb, dark = a | (int) ((rgb >> 16 & 0xFF) * .78f) << 16 | (int) ((rgb >> 8 & 0xFF) * .78f) << 8 | (int) ((rgb & 0xFF) * .78f);
        for (int i = 1; i < 15; i++) {
            int in = (i / 2) % 2, col = i % 2 == 0 ? dark : light; // in and out every two pixels
            g.fill(x + i, y + in, x + i + 1, y + in + 1, col);
            g.fill(x + i, y + 15 - in, x + i + 1, y + 16 - in, col);
            g.fill(x + in, y + i, x + in + 1, y + i + 1, col);
            g.fill(x + 15 - in, y + i, x + 16 - in, y + i + 1, col);
        }
    }

    /** Rows of pixel art at x, y, each run of one color a single fill; {@code alpha} over the whole mark. */
    private static void pixels(GuiGraphicsExtractor g, int x, int y, String[] rows, Map<Character, Integer> palette, int alpha) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int i = 0; i < row.length(); ) {
                char ch = row.charAt(i);
                int j = i + 1;
                while (j < row.length() && row.charAt(j) == ch) j++;
                Integer rgb = palette.get(ch);
                if (rgb != null) g.fill(x + i, y + r, x + j, y + r + 1, alpha << 24 | rgb);
                i = j;
            }
        }
    }

    /** The small marks, at the chosen corner and size. */
    private static void small(GuiGraphicsExtractor g, int x, int y, int c, Icon icon) {
        int shadow = (int) ((c >>> 24) * .7) << 24;
        float scale = size / 100f;
        int w = switch (icon) { case PADLOCK -> 7; case DOT -> 4; case SBE_LOCK -> 7; default -> 6; };
        int h = switch (icon) { case PADLOCK, SBE_LOCK -> 8; case DOT -> 4; default -> 6; };
        Place at = place;
        float sw = w * scale, sh = h * scale;
        float px = switch (at) { case TOP_LEFT, BOTTOM_LEFT -> x; case TOP_RIGHT, BOTTOM_RIGHT -> x + 16 - sw; case CENTER -> x + (16 - sw) / 2; };
        float py = switch (at) { case TOP_LEFT, TOP_RIGHT -> y; case BOTTOM_LEFT, BOTTOM_RIGHT -> y + 16 - sh; case CENTER -> y + (16 - sh) / 2; };
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
                case SBE_LOCK -> pixels(g, 0, 0, SBE, SBE_COLORS, 0xFF);
                case DOT -> { rect(g, 1, 0, 2, 4, c); rect(g, 0, 1, 4, 2, c); }
                case CORNER -> {
                    boolean left = at == Place.TOP_LEFT || at == Place.BOTTOM_LEFT || at == Place.CENTER;
                    boolean top = at != Place.BOTTOM_LEFT && at != Place.BOTTOM_RIGHT;
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
        r.add(row("on", "Enable slot locking", "Lock slots so their items can't be moved, sold or dropped by accident, and bind inventory slots to hotbar slots to swap them in one click. Kept per SkyBlock profile, with a set of its own in the Rift and the Safari; acts only on SkyBlock (and in singleplayer, to try it).",
            new Control.Toggle(() -> on, v -> on = v)));

        r.add(Option.header("slotLocks.keys", Component.literal("Keys")));
        r.add(row("key", "Lock key", "Tap over a slot to lock or unlock it. Hold it on one slot and let go over another to bind the two. Default L.",
            new Control.Key(() -> key, true)));
        r.add(row("bindKey", "Bind key", "The lock key binds too (hold and let go over the other slot). Pick a key of its own to press it over one slot and then the other; twice on one slot removes its binding.",
            new Control.Key(() -> bindKey, true, Component.literal("Same as lock key"))));

        r.add(Option.header("slotLocks.locked", Component.literal("Locked slots")));
        r.add(picker("icon", "Mark", "Outline takes the place of the rarity background's outline. The NEU, SkyblockAddons, Firmament and SkyBlock Enhancements looks keep their own colors.",
            LOCK_MARKS, () -> icon, v -> icon = v, () -> color));
        r.add(row("color", "Color", "", new Control.Color(() -> color, v -> color = v, false)));
        r.add(row("blockDrops", "Stop dropping locked items", "Q does nothing while a locked hotbar slot is selected.",
            new Control.Toggle(() -> blockDrops, v -> blockDrops = v)));
        r.add(row("dungeonQ", "Q still works in Dungeons", "In Dungeons Q uses your class Ultimate, so a lock doesn't stop it there.",
            new Control.Toggle(() -> dungeonQ, v -> dungeonQ = v)).visibleWhen(() -> blockDrops));
        r.add(row("redHover", "Red under the mouse", "A locked slot turns red while the mouse is over it, as in NEU.",
            new Control.Toggle(() -> redHover, v -> redHover = v)));
        r.add(row("tell", "When a lock stops something", "Say so in chat (at most every 3 seconds) or on the action bar.",
            new Control.Choice<>(List.of(Tell.values()), v -> Component.literal(switch (v) {
                case CHAT -> "Chat"; case ACTION_BAR -> "Action bar"; case OFF -> "Off"; }), () -> tell, v -> tell = v)));

        r.add(Option.header("slotLocks.bound", Component.literal("Bound slots")));
        r.add(picker("bindIcon", "Mark", "Every one takes the color below. Outline takes the place of the rarity background's outline; Box is Firmament's; the rope is NEU's.",
            BOUND_MARKS, () -> bindIcon, v -> bindIcon = v, () -> bindColor));
        r.add(row("bindColor", "Color", "", new Control.Color(() -> bindColor, v -> bindColor = v, false)));
        r.add(row("swapWith", "Swap with", "Clicking either end of a binding swaps the two, like that hotbar number key over the inventory slot.",
            new Control.Choice<>(List.of(Swap.values()), v -> Component.literal(switch (v) {
                case SHIFT_CLICK -> "Shift-click"; case CLICK -> "Click"; case CTRL_CLICK -> "Ctrl-click"; case OFF -> "Off"; }),
                () -> swapWith, v -> swapWith = v)));
        r.add(row("show", "Lines between them", "From one slot's outline to the other's: always, or for the slot under the mouse (and all while you hold shift).",
            new Control.Choice<>(List.of(Show.values()), v -> Component.literal(switch (v) {
                case ALWAYS -> "Always"; case HOVER -> "On hover"; case HIDDEN -> "Hidden"; }), () -> show, v -> show = v)));
        r.add(row("lineFrom", "Lines start at", "The middle of the outlines' edges (the bottom of one, the top of the other, as in Firmament), or the slots' centers.",
            new Control.Choice<>(List.of(LineFrom.values()), v -> Component.literal(v == LineFrom.EDGES ? "Edges" : "Centers"),
                () -> lineFrom, v -> lineFrom = v)).visibleWhen(() -> show != Show.HIDDEN));
        r.add(row("boundLocked", "Bound slots are locked", "Off: a bound slot moves like any other, and only the swap click swaps it (Firmament's and NEU's way).",
            new Control.Toggle(() -> boundLocked, v -> boundLocked = v)));
        r.add(row("multiBind", "Several per hotbar slot", "Bind one hotbar slot to more than one inventory slot. From the hotbar end, a swap goes back where the item came from.",
            new Control.Toggle(() -> multiBind, v -> multiBind = v)));
        r.add(row("everyMenu", "In every menu", "Make and use bindings on your own slots in chests and other menus too, not only in your inventory.",
            new Control.Toggle(() -> everyMenu, v -> everyMenu = v)));

        r.add(Option.header("slotLocks.marks", Component.literal("Marks")));
        r.add(row("place", "Position", "Where a padlock, dot or corner sits in the slot.",
            new Control.Choice<>(List.of(Place.values()), v -> Component.literal(label(v)), () -> place, v -> place = v))
            .visibleWhen(SlotLocks::smallMarks));
        r.add(row("size", "Size", "", new Control.Slider(() -> size, v -> size = (int) Math.round(v), 50, 200, 10,
            v -> Component.literal(Math.round(v) + "%"))).visibleWhen(SlotLocks::smallMarks));
        r.add(row("opacity", "Opacity", "", new Control.Slider(() -> opacity, v -> opacity = (int) Math.round(v), 10, 100, 5,
            v -> Component.literal(Math.round(v) + "%"))));
        r.add(row("hotbar", "Show on the hotbar", "Mark locked and bound hotbar slots on the HUD too.", new Control.Toggle(() -> hotbar, v -> hotbar = v)));
        r.add(Option.of("slotLocks.clear", Component.literal("Clear this profile"), Component.literal("Unlocks and unbinds every slot of the current profile."),
            new Control.Action(Component.literal("Clear"), () -> {
                Bucket b = bucket(false);
                if (b != null) { b.locked.clear(); b.binds.clear(); save(); }
            }, () -> { Bucket b = bucket(false); return b != null && (!b.locked.isEmpty() || !b.binds.isEmpty()); }, Component.literal("Nothing locked"))));
        return r.stream().map(o -> List.of("slotLocks.opacity", "slotLocks.size", "slotLocks.color", "slotLocks.bindColor").contains(o.id()) ? o.compact().beside() : o.compact()).toList();
    }

    /** Whether a mark drawn at a corner is in use, so position and size matter. */
    private static boolean smallMarks() {
        return icon.small() || bindIcon.small();
    }

    /** A mark picked by its look: every choice drawn in a slot, in the color it will have. */
    private static Option picker(String id, String title, String help, List<Icon> marks, java.util.function.Supplier<Icon> get,
                                 java.util.function.Consumer<Icon> set, java.util.function.IntSupplier tint) {
        return row(id, title, help, new Control.Custom((host, width) -> new io.github.terabold.skycosmetics.gui.hub.PreviewPicker<>(
            Component.literal(title), width, marks, v -> Component.literal(markName(v)), get, set,
            (g, v, x, y) -> preview(g, v, x, y, tint.getAsInt()), host)));
    }

    private static String markName(Icon v) {
        return switch (v) {
            case PADLOCK -> "Padlock"; case NEU_PADLOCK -> "NEU padlock"; case SBA_PADLOCK -> "SkyblockAddons padlock";
            case FIRMAMENT_LOCK -> "Firmament lock"; case SBE_LOCK -> "SkyBlock Enhancements lock"; case DOT -> "Dot";
            case CORNER -> "Corner"; case OUTLINE -> "Outline (rarity background's shape)"; case BOX -> "Box (Firmament)";
            case DASHED -> "Dashed frame (Skyblocker)"; case STITCHED -> "Rope (NEU)"; case TINT -> "Tint"; case NONE -> "None";
        };
    }

    private static void preview(GuiGraphicsExtractor g, Icon v, int x, int y, int rgb) {
        switch (v) {
            case OUTLINE -> outline(g, x, y, rgb);
            case NONE -> g.fill(x + 4, y + 7, x + 12, y + 9, 0x60FFFFFF); // a dash: nothing drawn
            default -> mark(g, x, y, rgb, v);
        }
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
        // "tellStopped" was the on/off before "tell".
        tell = choice(Tell.values(), Settings.str(o.get("tell"), null), Settings.bool(o.get("tellStopped"), true) ? tell : Tell.OFF);
        multiBind = Settings.bool(o.get("multiBind"), multiBind);
        everyMenu = Settings.bool(o.get("everyMenu"), everyMenu);
        boundLocked = Settings.bool(o.get("boundLocked"), boundLocked);
        redHover = Settings.bool(o.get("redHover"), redHover);
        lineFrom = choice(LineFrom.values(), Settings.str(o.get("lineFrom"), null), lineFrom);
        // The lines' own choice; the old "show" (hover by default) is left behind on purpose.
        show = choice(Show.values(), Settings.str(o.get("linkLines"), null), show);
        hotbar = Settings.bool(o.get("hotbar"), hotbar);
        icon = choice(Icon.values(), Settings.str(o.get("icon"), null), icon);
        bindIcon = choice(Icon.values(), Settings.str(o.get("bindIcon"), null), bindIcon);
        place = choice(Place.values(), Settings.str(o.get("place"), null), place);
        size = (int) Settings.num(o.get("size"), size, 50, 200);
        opacity = (int) Settings.num(o.get("opacity"), opacity, 10, 100);
        color = 0xFF000000 | (int) Settings.num(o.get("color"), color, Integer.MIN_VALUE, Integer.MAX_VALUE);
        bindColor = 0xFF000000 | (int) Settings.num(o.get("bindColor"), bindColor, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on); o.addProperty("swapWith", swapWith.name()); o.addProperty("blockDrops", blockDrops);
        o.addProperty("dungeonQ", dungeonQ); o.addProperty("tell", tell.name()); o.addProperty("multiBind", multiBind); o.addProperty("everyMenu", everyMenu);
        o.addProperty("linkLines", show.name()); o.addProperty("hotbar", hotbar);
        o.addProperty("boundLocked", boundLocked); o.addProperty("redHover", redHover); o.addProperty("lineFrom", lineFrom.name());
        o.addProperty("icon", icon.name()); o.addProperty("bindIcon", bindIcon.name()); o.addProperty("place", place.name());
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

    /** For tests: the marks of locked and of bound slots. */
    public static void testMarks(Icon locked, Icon bound) {
        icon = locked;
        bindIcon = bound;
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
