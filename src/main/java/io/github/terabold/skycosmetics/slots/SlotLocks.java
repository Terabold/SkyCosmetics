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
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Slot locking and binding, per SkyBlock profile.
 *
 * Tap the lock key over one of your slots to lock it: your clicks, shift-clicks, number-key swaps and drops on it
 * are stopped before they leave the game, so a locked item can't be moved, sold or dropped by accident. Hold the key
 * on an inventory slot and let go over a hotbar slot (or the other way round) to bind the two: a shift-click on
 * either then swaps them, exactly as pressing that hotbar number over the inventory slot does. Locking only ever
 * holds back your own actions; binding turns your one shift-click into that one vanilla swap.
 *
 * Kept in {@code config/skycosmetics/slot-locks.json}. Inventory slot numbers: 0-8 hotbar, 9-35 inventory, 36-39
 * armor; the off-hand and the SkyBlock menu slot (8) are never bound.
 */
public final class SlotLocks {
    public static final String SECTION = "slotLocks";

    public enum Icon { PADLOCK, DOT, CORNER, OUTLINE, NONE }

    public enum Place { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

    static final class Bucket {
        final Set<Integer> locked = new LinkedHashSet<>();
        /** Inventory slot -> hotbar slot. */
        final Map<Integer, Integer> binds = new TreeMap<>();
    }

    // Settings; the draw and click paths read them directly.
    static boolean on = true, swapBound = true, blockDrops = true, hotbar = true;
    static Icon icon = Icon.PADLOCK;
    static Place place = Place.TOP_LEFT;
    static int size = 100, opacity = 85, color = 0xFFFFD24A, bindColor = 0xFF4BA3FF;

    private static KeyMapping key;
    private static final Map<String, Bucket> BUCKETS = new HashMap<>();
    private static final Map<String, String> LAST_PROFILE = new HashMap<>();
    private static boolean loaded;

    // The key gesture: where it started, whether a click used it up, and the short flash after a new binding.
    private static boolean held, consumed;
    private static Integer anchor;
    private static int flashFrom = -1, flashTo = -1;
    private static long flashAt;
    /** Set around our own swap so the lock check lets it through. */
    private static boolean ours;

    private static final Mask.Sheet OUTLINE = new Mask.Sheet("slots/lock_outline", 1, 1);

    private SlotLocks() {}

    public static void init(KeyMapping.Category category) {
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skycosmetics.lockSlot", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L, category));
        Settings.register(SECTION, SlotLocks::read, SlotLocks::write);
        Hub.add(new Section(SECTION, Section.FEATURE, Component.literal("Slot Locking"),
            Component.literal("Lock and bind slots"),
            Component.literal("Lock slots so their items can't be moved, sold or dropped by accident, and bind inventory slots to hotbar slots."),
            () -> new ItemStack(Items.TRIPWIRE_HOOK), SlotLocks::rows));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!on || !key.matches(event) || SkyCosmetics.typing(s)) return true;
                if (!held) { held = true; consumed = false; anchor = playerSlot(hovered(acs)); }
                return false;
            });
            ScreenKeyboardEvents.afterKeyRelease(screen).register((s, event) -> {
                if (held && key.matches(event)) finish(acs);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                if (held) { consumed = true; return false; }
                if (!on || !swapBound || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !click.hasShiftDown()
                    || !(s instanceof InventoryScreen) || !acs.getMenu().getCarried().isEmpty()) return true;
                return !swap(acs, hovered(acs));
            });
            ScreenEvents.remove(screen).register(s -> { held = false; anchor = null; });
        });
    }

    // ---------------------------------------------------------------- state ---

    public static boolean isLocked(int slot) {
        Bucket b = bucket(false);
        return b != null && (b.locked.contains(slot) || b.binds.containsKey(slot) || b.binds.containsValue(slot));
    }

    static boolean explicitlyLocked(int slot) {
        Bucket b = bucket(false);
        return b != null && b.locked.contains(slot);
    }

    /** {inventory slot, hotbar slot} of the binding with this end, or null. */
    static int[] bindingOf(int slot) {
        Bucket b = bucket(false);
        if (b == null) return null;
        for (var e : b.binds.entrySet()) if (e.getKey() == slot || e.getValue() == slot) return new int[]{e.getKey(), e.getValue()};
        return null;
    }

    /** Called from the menu's click path: whether this click must not happen. */
    public static boolean blocks(Slot slot, int button, ContainerInput input) {
        if (!on || ours) return false;
        if (slot != null && slot.container instanceof Inventory && isLocked(slot.getContainerSlot())) return true;
        return input == ContainerInput.SWAP && isLocked(button);
    }

    /** Called before Q drops the held item. */
    public static boolean blocksDrop(Player player) {
        return on && blockDrops && isLocked(player.getInventory().getSelectedSlot());
    }

    private static boolean swap(AbstractContainerScreen<?> acs, Slot hovered) {
        if (hovered == null || !(hovered.container instanceof Inventory)) return false;
        int[] b = bindingOf(hovered.getContainerSlot());
        if (b == null) return false;
        Slot source = null;
        for (Slot s : acs.getMenu().slots) if (s.container instanceof Inventory && s.getContainerSlot() == b[0]) source = s;
        if (source == null) return false;
        ours = true;
        try {
            ((AbstractContainerScreenAccessor) acs).skycosmetics$slotClicked(source, source.index, b[1], ContainerInput.SWAP);
        } finally {
            ours = false;
        }
        return true;
    }

    private static void finish(AbstractContainerScreen<?> acs) {
        held = false;
        Integer start = anchor;
        anchor = null;
        if (consumed) return;
        Integer end = playerSlot(hovered(acs));
        if (end == null) end = start;
        if (end == null || end == Inventory.SLOT_OFFHAND) return;
        Bucket b = bucket(true);
        if (acs instanceof InventoryScreen && start != null && bindable(start, end)) {
            int inv = hotbarSlot(start) ? end : start, bar = hotbarSlot(start) ? start : end;
            unbind(b, inv);
            unbind(b, bar);
            b.locked.remove(inv);
            b.locked.remove(bar);
            b.binds.put(inv, bar);
            flashFrom = inv; flashTo = bar; flashAt = System.currentTimeMillis();
            changed(true, "Slots bound");
            return;
        }
        if (unbind(b, end)) { changed(false, "Binding removed"); return; }
        boolean locked = b.locked.add(end);
        if (!locked) b.locked.remove(end);
        changed(locked, locked ? "Slot locked" : "Slot unlocked");
    }

    private static boolean bindable(int a, int b) {
        return a != b && a != 8 && b != 8 && a < 36 && b < 36 && hotbarSlot(a) != hotbarSlot(b);
    }

    static boolean hotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }

    private static boolean unbind(Bucket b, int end) {
        return b.binds.entrySet().removeIf(e -> e.getKey() == end || e.getValue() == end);
    }

    private static void changed(boolean positive, String what) {
        save();
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, positive ? 1f : .25f));
        mc.gui.setOverlayMessage(Component.literal(what), false);
    }

    private static Slot hovered(AbstractContainerScreen<?> acs) {
        return ((AbstractContainerScreenAccessor) acs).skycosmetics$hoveredSlot();
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
        if (!on || icon != Icon.OUTLINE) return;
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
        return on && icon == Icon.OUTLINE && s.container instanceof Inventory && isLocked(s.getContainerSlot());
    }

    /** Over a slot's item, under its count. */
    public static void drawIcon(GuiGraphicsExtractor g, Slot s) {
        if (!on || !(s.container instanceof Inventory)) return;
        try {
            int i = s.getContainerSlot();
            boolean lock = explicitlyLocked(i), bound = !lock && bindingOf(i) != null;
            if (lock || bound) mark(g, s.x, s.y, lock ? color : bindColor);
            if (held && anchor != null && !hotbarSlot(anchor) && hotbarSlot(i) && i != 8) {
                g.fill(s.x, s.y, s.x + 16, s.y + 16, 0x40FFFFFF); // where a binding can end
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing a slot lock", e);
        }
    }

    /** The HUD hotbar: {@code under} the item the outline style, else over it the mark. */
    public static void drawHotbar(GuiGraphicsExtractor g, int x, int y, Player player, ItemStack stack, boolean under) {
        if (!on || !hotbar || player == null || stack.isEmpty() || under != (icon == Icon.OUTLINE)) return;
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
        if (!on || !hotbar || icon != Icon.OUTLINE || p == null) return false;
        int i = hotbarIndex(p, stack);
        return i >= 0 && isLocked(i);
    }

    /** The HUD draws the hotbar's own stacks: which slot holds this one. */
    private static int hotbarIndex(Player player, ItemStack stack) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i) == stack) return i;
        return -1;
    }

    /** The inventory screen: both ends of a binding while you hover one end or hold shift, and a new binding's flash. */
    public static void drawBindings(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!on || !(screen instanceof InventoryScreen)) return;
        try {
            Bucket b = bucket(false);
            if (b == null) return;
            var acc = (AbstractContainerScreenAccessor) screen;
            Slot hov = acc.skycosmetics$hoveredSlot();
            int[] hb = hov == null || !(hov.container instanceof Inventory) ? null : bindingOf(hov.getContainerSlot());
            boolean shift = InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
            g.pose().pushMatrix();
            g.pose().translate(acc.skycosmetics$leftPos(), acc.skycosmetics$topPos());
            for (var e : b.binds.entrySet()) {
                if (hb != null ? hb[0] == e.getKey() : shift) pair(g, screen, e.getKey(), e.getValue(), bindColor);
            }
            long age = System.currentTimeMillis() - flashAt;
            if (flashFrom >= 0 && age < 650) pair(g, screen, flashFrom, flashTo, (int) (230 - age * 230 / 650) << 24 | bindColor & 0xFFFFFF);
            g.pose().popMatrix();
        } catch (RuntimeException e) {
            Io.failed("Drawing slot bindings", e);
        }
    }

    private static void pair(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int a, int b, int c) {
        for (Slot s : screen.getMenu().slots) {
            if (s.container instanceof Inventory && (s.getContainerSlot() == a || s.getContainerSlot() == b)) {
                g.outline(s.x - 1, s.y - 1, 18, 18, c);
            }
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
        r.add(row("on", "Enable slot locking", "Tap the key over one of your slots to lock or unlock it. Hold it on an inventory slot and let go over a hotbar slot to bind them.",
            new Control.Toggle(() -> on, v -> on = v)));
        r.add(row("key", "Lock key", "Over a slot in any menu. Default L.", new Control.Key(() -> key, false)));
        r.add(row("swapBound", "Shift-click swaps bound slots", "In your inventory, shift-clicking either end of a binding swaps the two, like the hotbar number key does.",
            new Control.Toggle(() -> swapBound, v -> swapBound = v)));
        r.add(row("blockDrops", "Stop dropping locked items", "Q does nothing while a locked hotbar slot is selected.",
            new Control.Toggle(() -> blockDrops, v -> blockDrops = v)));
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
        swapBound = Settings.bool(o.get("swapBound"), swapBound);
        blockDrops = Settings.bool(o.get("blockDrops"), blockDrops);
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
        o.addProperty("on", on); o.addProperty("swapBound", swapBound); o.addProperty("blockDrops", blockDrops);
        o.addProperty("hotbar", hotbar); o.addProperty("icon", icon.name()); o.addProperty("place", place.name());
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
                    int i = (int) Settings.num(x, -1, -1, 40);
                    if (i >= 0) bucket.locked.add(i);
                }
                for (var bind : Settings.obj(b.get("binds")).entrySet()) {
                    try {
                        int inv = Integer.parseInt(bind.getKey()), bar = (int) Settings.num(bind.getValue(), -1, -1, 8);
                        if (bindable(inv, bar)) bucket.binds.put(inv, bar);
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
        Io.writeAsync(file(), root);
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
}
