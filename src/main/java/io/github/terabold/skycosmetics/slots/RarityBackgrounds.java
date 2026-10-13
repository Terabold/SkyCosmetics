package io.github.terabold.skycosmetics.slots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Rarity Backgrounds: the slot behind each item colored by its rarity, in the inventory, in menus, on the armor
 * slots and in the hotbar.
 *
 * Per frame a menu costs one pass over its slots: each stack's rarity is read once and kept on the stack
 * ({@link ItemFacts}), the menu's title is matched once per menu, and every background is one quad of a shared
 * mask texture ({@link Mask}) tinted with the rarity's color, so a whole menu is one batch. Everything is drawn
 * before the hovered slot's highlight and the items, so nothing of vanilla or another mod is covered.
 */
public final class RarityBackgrounds {
    public static final String SECTION = "rarityBackgrounds";
    public static final int MIN_OPACITY = 10, MAX_OPACITY = 100;

    /** Named color sets; editing a color switches to CUSTOM, starting from the set shown. */
    public enum Colors {
        HYPIXEL(Rarity.COMMON.hypixel, Rarity.UNCOMMON.hypixel, Rarity.RARE.hypixel, Rarity.EPIC.hypixel,
            Rarity.LEGENDARY.hypixel, Rarity.MYTHIC.hypixel, Rarity.DIVINE.hypixel, Rarity.SPECIAL.hypixel,
            Rarity.VERY_SPECIAL.hypixel, Rarity.ULTIMATE.hypixel, Rarity.ADMIN.hypixel),
        SOFT(0xD9D9D9, 0x8FE3A0, 0x8FA6FF, 0xC78BF0, 0xFFC56E, 0xFF9CEB, 0x8DEFFF, 0xFF8F8F, 0xFF8FB4, 0xD96C6C, 0xC77070),
        DEEP(0x9C9C9C, 0x1E9E3A, 0x2A47D6, 0x7A22B0, 0xE08600, 0xC93AC9, 0x1BA9BD, 0xD13434, 0xC2185B, 0x8C0000, 0x7A0A0A),
        CUSTOM;

        final int[] rgb;

        Colors(int... rgb) {
            this.rgb = rgb;
        }

        public Component label() {
            return Component.translatable("skycosmetics.rarityBackgrounds.colors." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** What the Wardrobe, Equipment and Loadouts menus show. */
    public enum OwnMenus {
        ALL, NO_HEADS, NONE;

        public Component label() {
            return Component.translatable("skycosmetics.rarityBackgrounds.ownMenus." + name().toLowerCase(Locale.ROOT));
        }
    }

    // Settings; plain fields the draw loop reads directly.
    static boolean on = false;
    static Mask.Shape shape = Mask.Shape.ROUNDED;
    static Mask.Fill fill = Mask.Fill.SOLID;
    static int opacity = 55;
    static int outlineOpacity = 66;
    static boolean outline = true;
    static int gradientMinimum = 20, markerSize = 6, markerColor = 0xFFFFFFFF;
    enum MarkerCorner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }
    static MarkerCorner markerCorner = MarkerCorner.TOP_RIGHT;
    static Colors colors = Colors.HYPIXEL;
    static final int[] custom = Colors.HYPIXEL.rgb.clone();
    static boolean inventory = true, menus = true, armor = true, hotbar = true;
    static boolean common = true, pets = true, skyblockOnly = false, recombMark = false;
    static OwnMenus ownMenus = OwnMenus.ALL;
    static OwnMenus wardrobeMenus = OwnMenus.ALL, loadoutMenus = OwnMenus.ALL, equipmentMenus = OwnMenus.ALL;
    static boolean equipment = true;
    /** The outline opacity follows the fill (x1.2) until it is set on its own. */
    static boolean outlineLinked = true;

    /** The ARGB tint of each rarity in the colors in use; rebuilt when they change. */
    private static final int[] TINT = new int[Rarity.values().length];

    /** The texture every background is drawn from: cell 0 the style, cell 1 the recombobulated mark. */
    private static final Mask.Sheet MAIN = new Mask.Sheet("slots/rarity_backgrounds", 2, 1);
    /** The menu whose title was matched last (weakly: a closed menu is never kept alive), and its rule. */
    private static WeakReference<Screen> ruleScreen = new WeakReference<>(null);
    private static OwnMenus rule = OwnMenus.ALL;
    private static boolean loadouts;
    /** Backgrounds drawn since start, for tests. */
    static long drawn;

    static {
        colorsChanged();
    }

    private RarityBackgrounds() {}

    public static void init() {
        Settings.register(SECTION, RarityBackgrounds::read, RarityBackgrounds::write);
        ItemDebug.register();
        Hub.add(new Section(SECTION, Section.FEATURE, Component.translatable("skycosmetics.rarityBackgrounds"),
            Component.translatable("skycosmetics.rarityBackgrounds.sub"),
            Component.translatable("skycosmetics.rarityBackgrounds.tooltip"),
            () -> new ItemStack(Items.GLOW_ITEM_FRAME), RarityBackgrounds::rows));
    }

    // ------------------------------------------------------------- drawing ---

    /** Every slot of an open menu, once per frame, under the hover highlight. Never throws. */
    public static void drawSlots(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        ItemDebug.seen(screen);
        if (!on || !(inventory || menus || armor)) return;
        try {
            if (ruleScreen.get() != screen) {
                String source = OwnedItems.menuSource(screen.getTitle().getString());
                loadouts = "Loadouts".equals(source);
                rule = source == null ? OwnMenus.ALL : switch (source) {
                    case "Wardrobe" -> wardrobeMenus;
                    case "Loadouts" -> loadoutMenus;
                    case "Equipment wardrobe", "Equipment" -> equipmentMenus;
                    default -> OwnMenus.ALL;
                };
                ruleScreen = new WeakReference<>(screen); // a menu's title never changes while it is open
            }
            Identifier tex = texture();
            for (Slot slot : screen.getMenu().slots) {
                if (!slot.isActive()) continue;
                ItemStack s = slot.getItem();
                if (s.isEmpty()) continue;
                if (SlotArea.own(slot)) {
                    if (!(SlotArea.armor(slot) ? armor : inventory)) continue;
                } else if (!menus || rule == OwnMenus.NONE || rule == OwnMenus.NO_HEADS && s.is(Items.PLAYER_HEAD)
                    && !ItemFacts.of(s).pet() && (!loadouts || slot.getContainerSlot() % 9 >= 5)) {
                    continue;
                }
                int index = slot.getContainerSlot();
                boolean loadoutButton = loadouts && !SlotArea.own(slot) && index >= 14 && index <= 43
                    && index % 9 >= 5 && index % 9 <= 7;
                draw(g, tex, slot.x, slot.y, s, loadoutButton);
            }
            // Seal the background batch before any mod's per-slot icons are extracted.
            g.nextStratum();
        } catch (RuntimeException e) {
            Io.failed("Drawing rarity backgrounds", e);
        }
    }

    /** A HUD hotbar or off-hand slot. Never throws. */
    public static void drawHotbar(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        if (!on || !hotbar || stack.isEmpty()) return;
        try {
            draw(g, texture(), x, y, stack);
        } catch (RuntimeException e) {
            Io.failed("Drawing a hotbar rarity background", e);
        }
    }

    /**
     * A slot another mod draws through the menu's own slot path without it being one of the menu's slots: the
     * equipment and pet panels of Skyblocker, SkyblockAddons and any mod built the same way.
     */
    public static void drawEquipmentSlot(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, Slot slot) {
        if (!on || !equipment) return;
        try {
            var slots = screen.getMenu().slots;
            if (slot.index >= 0 && slot.index < slots.size() && slots.get(slot.index) == slot || !slot.isActive()) return;
            var stack = slot.getItem(); if (stack.isEmpty()) return;
            draw(g, texture(), slot.x, slot.y, stack);
            g.nextStratum();
        } catch (RuntimeException e) { Io.failed("Drawing an equipment-slot rarity background", e); }
    }

    /** Only known equipment positions, never arbitrary fake items such as buttons or catalogues. */
    public static void drawExtraInventoryItem(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        if (!on || !equipment || stack.isEmpty() || SlotArea.forced != SlotArea.OTHER
            || !(net.minecraft.client.Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
        try {
            if (!SlotArea.adjacentEquipment(x, y) || tintOf(ItemFacts.of(stack)) == 0) return;
            draw(g, texture(), x, y, stack);
            g.nextStratum();
        } catch (RuntimeException e) { Io.failed("Drawing an equipment rarity background", e); }
    }

    private static void draw(GuiGraphicsExtractor g, Identifier tex, int x, int y, ItemStack s) {
        draw(g, tex, x, y, s, false);
    }

    private static void draw(GuiGraphicsExtractor g, Identifier tex, int x, int y, ItemStack s, boolean loadoutButton) {
        ItemFacts f = ItemFacts.of(s);
        // Saved setups are menu buttons, not individual items. Only their known Loadouts slots use name color.
        int tint = loadoutButton && f.rarity() == null && f.loadoutColor() != null
            ? previewTint(f.loadoutColor()) : tintOf(f);
        if (tint == 0) return;
        drawTint(g, tex, x, y, tint, recombMark && f.recombobulated());
    }

    /** One background at a slot's item position (x, y), in an ARGB tint. */
    static void drawTint(GuiGraphicsExtractor g, Identifier tex, int x, int y, int tint, boolean mark) {
        int w = MAIN.texWidth(), h = MAIN.texHeight();
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 1, y - 1, 0, 0, Mask.CELL, Mask.CELL, w, h, tint);
        if (mark) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(x + 8, y + 8);
                g.pose().scale(markerCorner == MarkerCorner.TOP_LEFT || markerCorner == MarkerCorner.BOTTOM_LEFT ? -1 : 1,
                    markerCorner == MarkerCorner.BOTTOM_LEFT || markerCorner == MarkerCorner.BOTTOM_RIGHT ? -1 : 1);
                g.blit(RenderPipelines.GUI_TEXTURED, tex, -9, -9, Mask.CELL, 0, Mask.CELL, Mask.CELL, w, h, markerColor);
            } finally { g.pose().popMatrix(); }
        }
        drawn++;
    }

    /** The tint for an item, or 0 for none: no rarity line, or left out by the settings. */
    static int tintOf(ItemFacts f) {
        Rarity r = f.rarity();
        if (r == null || r == Rarity.COMMON && !common || f.pet() && !pets || skyblockOnly && !f.skyblock()) return 0;
        return TINT[r.ordinal()];
    }

    /** The tint the settings' preview shows for a rarity; 0 when that rarity gets none. */
    static int previewTint(Rarity r) {
        return r == Rarity.COMMON && !common ? 0 : TINT[r.ordinal()];
    }

    /** The mask texture for the current style and GUI scale, repainted only when one of them changed. */
    static Identifier texture() {
        return MAIN.ensure(styleKey(), (img, i, x0, y0, n, s) -> {
            if (i == 0) Mask.paint(img, x0, y0, n, s, style(), 4);
            else Mask.paintMark(img, x0, y0, n, s, markerSize);
        });
    }

    /** The outline opacity that goes with the fill: a little stronger, so the edge reads. */
    static int linkedOutline() {
        return Math.min(100, Math.round(opacity * 1.2f));
    }

    public static Mask.Style style() {
        return new Mask.Style(shape, fill, opacity, outline, gradientMinimum, outlineOpacity);
    }

    /** Everything the masks are painted from, in one number: a mask is repainted when it changes. */
    static long styleKey() {
        return shape.ordinal() | (long) fill.ordinal() << 8 | (long) opacity << 16 | (outline ? 1L : 0L) << 32
            | (long) gradientMinimum << 40 | (long) markerSize << 48 | (long) outlineOpacity << 56;
    }

    /** A rarity's current color (RGB). */
    public static int color(Rarity r) {
        return TINT[r.ordinal()] & 0xFFFFFF;
    }

    /** The color a rarity has in the named set in use; Hypixel's when the set is Custom. */
    public static int presetColor(Rarity r) {
        return (colors == Colors.CUSTOM ? Colors.HYPIXEL : colors).rgb[r.ordinal()];
    }

    /** A rarity's color edited: the set becomes Custom, starting from the set shown. */
    static void setColor(Rarity r, int rgb) {
        if (colors != Colors.CUSTOM) {
            System.arraycopy(colors.rgb, 0, custom, 0, custom.length);
            colors = Colors.CUSTOM;
        }
        custom[r.ordinal()] = rgb & 0xFFFFFF;
        colorsChanged();
    }

    /** Recomputes the tints; the masks are white, so a color change never repaints them. */
    static void colorsChanged() {
        int[] rgb = colors == Colors.CUSTOM ? custom : colors.rgb;
        for (int i = 0; i < TINT.length; i++) TINT[i] = 0xFF000000 | rgb[i];
    }

    // ------------------------------------------------------- test hooks ---

    public static boolean on() {
        return on;
    }

    /** For tests: the style, as the settings would set it. */
    public static void set(boolean on, Mask.Shape shape, Mask.Fill fill, int opacity, boolean outline) {
        RarityBackgrounds.on = on;
        RarityBackgrounds.shape = shape;
        RarityBackgrounds.fill = fill;
        RarityBackgrounds.opacity = Math.clamp(opacity, MIN_OPACITY, MAX_OPACITY);
        RarityBackgrounds.outline = outline;
    }

    /** For tests: where and on what backgrounds show. */
    public static void where(boolean inventory, boolean menus, boolean armor, boolean hotbar, OwnMenus ownMenus,
                             boolean recombMark) {
        RarityBackgrounds.inventory = inventory;
        RarityBackgrounds.menus = menus;
        RarityBackgrounds.armor = armor;
        RarityBackgrounds.hotbar = hotbar;
        RarityBackgrounds.ownMenus = ownMenus;
        wardrobeMenus = loadoutMenus = equipmentMenus = ownMenus;
        RarityBackgrounds.recombMark = recombMark;
        ruleScreen = new WeakReference<>(null);
    }

    /** For tests: which items get one. */
    public static void which(boolean common, boolean pets, boolean skyblockOnly) {
        RarityBackgrounds.common = common;
        RarityBackgrounds.pets = pets;
        RarityBackgrounds.skyblockOnly = skyblockOnly;
    }

    /** Every setting back to its default, custom colors included. */
    public static void reset() {
        set(false, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, true);
        outlineOpacity = 66; outlineLinked = true;
        gradientMinimum = 20; markerSize = 6; markerColor = 0xFFFFFFFF; markerCorner = MarkerCorner.TOP_RIGHT;
        where(true, true, true, true, OwnMenus.ALL, false);
        which(true, true, false);
        equipment = true;
        System.arraycopy(Colors.HYPIXEL.rgb, 0, custom, 0, custom.length);
        colors(Colors.HYPIXEL);
    }

    /** For tests: a named color set. */
    public static void colors(Colors set) {
        colors = set;
        colorsChanged();
    }

    public static Colors colorSet() {
        return colors;
    }

    public static long drawn() {
        return drawn;
    }

    /** The texture backgrounds are drawn from, for tests counting them in a frame. */
    public static Identifier textureId() {
        return MAIN.id;
    }

    // ------------------------------------------------------------- settings ---

    private static void read(JsonElement v) {
        JsonObject o = Settings.obj(v);
        on = Settings.bool(o.get("on"), on);
        shape = choice(o, "shape", Mask.Shape.values(), shape);
        fill = choice(o, "fill", Mask.Fill.values(), fill);
        opacity = (int) Math.round(Settings.num(o.get("opacity"), opacity, MIN_OPACITY, MAX_OPACITY));
        outline = Settings.bool(o.get("outline"), outline);
        outlineOpacity = (int) Settings.num(o.get("outlineOpacity"), linkedOutline(), 0, 100);
        outlineLinked = Settings.bool(o.get("outlineLinked"), outlineOpacity == linkedOutline());
        if (outlineLinked) outlineOpacity = linkedOutline();
        gradientMinimum = (int) Settings.num(o.get("gradientMinimum"), gradientMinimum, 0, 100);
        markerSize = (int) Settings.num(o.get("markerSize"), markerSize, 2, 8);
        markerColor = (int) Settings.num(o.get("markerColor"), markerColor, Integer.MIN_VALUE, Integer.MAX_VALUE);
        String corner = Settings.str(o.get("markerCorner"), markerCorner.name());
        for (var value : MarkerCorner.values()) if (value.name().equals(corner)) markerCorner = value;
        colors = choice(o, "colors", Colors.values(), colors);
        JsonObject c = Settings.obj(o.get("custom"));
        for (Rarity r : Rarity.values()) {
            String hex = Settings.str(c.get(r.name()), null);
            Integer rgb = hex == null ? null : parseHex(hex);
            if (rgb != null) custom[r.ordinal()] = rgb;
        }
        inventory = Settings.bool(o.get("inventory"), inventory);
        menus = Settings.bool(o.get("menus"), menus);
        armor = Settings.bool(o.get("armor"), armor);
        hotbar = Settings.bool(o.get("hotbar"), hotbar);
        common = Settings.bool(o.get("common"), common);
        pets = Settings.bool(o.get("pets"), pets);
        skyblockOnly = Settings.bool(o.get("skyblockOnly"), skyblockOnly);
        recombMark = Settings.bool(o.get("recombMark"), recombMark);
        ownMenus = choice(o, "gearMenus", OwnMenus.values(), ownMenus);
        wardrobeMenus = choice(o, "wardrobeMenus", OwnMenus.values(), ownMenus);
        loadoutMenus = choice(o, "loadoutMenus", OwnMenus.values(), ownMenus);
        equipmentMenus = choice(o, "equipmentMenus", OwnMenus.values(), ownMenus);
        equipment = Settings.bool(o.get("equipment"), equipment);
        ruleScreen = new WeakReference<>(null);
        colorsChanged();
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on);
        o.addProperty("shape", shape.name());
        o.addProperty("fill", fill.name());
        o.addProperty("opacity", opacity);
        o.addProperty("outline", outline);
        o.addProperty("outlineOpacity", outlineOpacity);
        o.addProperty("outlineLinked", outlineLinked);
        o.addProperty("gradientMinimum", gradientMinimum); o.addProperty("markerSize", markerSize);
        o.addProperty("markerColor", markerColor); o.addProperty("markerCorner", markerCorner.name());
        o.addProperty("colors", colors.name());
        JsonObject c = new JsonObject();
        for (Rarity r : Rarity.values()) c.addProperty(r.name(), String.format(Locale.ROOT, "#%06X", custom[r.ordinal()]));
        o.add("custom", c);
        o.addProperty("inventory", inventory);
        o.addProperty("menus", menus);
        o.addProperty("armor", armor);
        o.addProperty("hotbar", hotbar);
        o.addProperty("common", common);
        o.addProperty("pets", pets);
        o.addProperty("skyblockOnly", skyblockOnly);
        o.addProperty("recombMark", recombMark);
        o.addProperty("gearMenus", ownMenus.name());
        o.addProperty("wardrobeMenus", wardrobeMenus.name());
        o.addProperty("loadoutMenus", loadoutMenus.name());
        o.addProperty("equipmentMenus", equipmentMenus.name());
        o.addProperty("equipment", equipment);
        return o;
    }

    private static <E extends Enum<E>> E choice(JsonObject o, String key, E[] values, E current) {
        String name = Settings.str(o.get(key), null);
        if (name != null) for (E e : values) if (e.name().equalsIgnoreCase(name)) return e;
        return current;
    }

    /** "#RRGGBB" or "RRGGBB"; null if malformed. */
    static Integer parseHex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return null;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        BooleanSupplier isOn = () -> on;
        rows.add(toggle("on", () -> on, v -> on = v));
        rows.add(Option.of("rarityBackgrounds.preview", Component.empty(), Component.empty(),
            new Control.Custom((host, w) -> new SlotPreview(w, SlotPreview.Kind.RARITIES))).enabledWhen(isOn));

        rows.add(Option.header("rarityBackgrounds.groupStyle", Component.translatable("skycosmetics.rarityBackgrounds.group.style")));
        rows.add(option("shape", new Control.Custom((host, w) -> new StylePicker<>(w, host, Mask.Shape.values(),
            Mask.Shape::label, () -> shape, v -> shape = v, StylePicker.Sheet.SHAPES)))
            .search(Component.translatable("skycosmetics.rarityBackgrounds.shape.search").getString()).enabledWhen(isOn));
        rows.add(option("fill", new Control.Custom((host, w) -> new StylePicker<>(w, host, Mask.Fill.values(),
            Mask.Fill::label, () -> fill, v -> fill = v, StylePicker.Sheet.FILLS)))
            .search(Component.translatable("skycosmetics.rarityBackgrounds.fill.search").getString()).enabledWhen(isOn));
        rows.add(option("opacity", new Control.Slider(() -> opacity, v -> { opacity = (int) Math.round(v); if (outlineLinked) outlineOpacity = linkedOutline(); },
            MIN_OPACITY, MAX_OPACITY, 5, v -> Component.literal(Math.round(v) + "%"))).enabledWhen(isOn));
        rows.add(toggle("outline", () -> outline, v -> outline = v).enabledWhen(() -> on && shape.outlined()));
        rows.add(Option.of("rarityBackgrounds.outlineOpacity", Component.literal("Outline opacity"), Component.empty(),
            new Control.Slider(() -> outlineOpacity, v -> { outlineOpacity = (int) Math.round(v); outlineLinked = outlineOpacity == linkedOutline(); }, 0, 100, 1,
                v -> Component.literal(Math.round(v) + "%"))).enabledWhen(isOn).visibleWhen(() -> outline && shape.outlined()));
        rows.add(Option.of("rarityBackgrounds.gradientMinimum", Component.literal("Gradient minimum opacity"),
            Component.literal("Opacity at the faint end, relative to the main opacity."),
            new Control.Slider(() -> gradientMinimum, v -> gradientMinimum = (int) v, 0, 100, 5, v -> Component.literal(Math.round(v) + "%")))
            .enabledWhen(isOn).visibleWhen(() -> fill != Mask.Fill.SOLID && fill != Mask.Fill.BEVELED));

        rows.add(Option.header("rarityBackgrounds.groupColors", Component.translatable("skycosmetics.rarityBackgrounds.group.colors")));
        // Custom keeps your own colors; editing a swatch while a named set shows starts them from that set.
        rows.add(option("colors", choice(List.of(Colors.values()), Colors::label, () -> colors, v -> colors = v))
            .enabledWhen(isOn));
        rows.add(option("swatches", new Control.Custom((host, w) -> new RaritySwatches(w, host))).enabledWhen(isOn));

        rows.add(Option.header("rarityBackgrounds.groupWhere", Component.translatable("skycosmetics.rarityBackgrounds.group.where")));
        rows.add(toggle("inventory", () -> inventory, v -> inventory = v).enabledWhen(isOn));
        rows.add(toggle("armor", () -> armor, v -> armor = v).enabledWhen(isOn));
        rows.add(toggle("hotbar", () -> hotbar, v -> hotbar = v).enabledWhen(isOn));
        rows.add(toggle("menus", () -> menus, v -> menus = v).enabledWhen(isOn));
        rows.add(toggle("equipment", () -> equipment, v -> equipment = v).enabledWhen(isOn));
        rows.add(menuRule("wardrobeMenus", () -> wardrobeMenus, v -> wardrobeMenus = v));
        rows.add(menuRule("loadoutMenus", () -> loadoutMenus, v -> loadoutMenus = v));
        rows.add(menuRule("equipmentMenus", () -> equipmentMenus, v -> equipmentMenus = v));

        rows.add(Option.header("rarityBackgrounds.groupWhich", Component.translatable("skycosmetics.rarityBackgrounds.group.which")));
        rows.add(toggle("common", () -> common, v -> common = v).enabledWhen(isOn));
        rows.add(toggle("pets", () -> pets, v -> pets = v).enabledWhen(isOn));
        rows.add(toggle("skyblockOnly", () -> skyblockOnly, v -> skyblockOnly = v).enabledWhen(isOn));
        rows.add(toggle("recombMark", () -> recombMark, v -> recombMark = v)
            .search(Component.translatable("skycosmetics.rarityBackgrounds.recombMark.search").getString()).enabledWhen(isOn));
        rows.add(Option.of("rarityBackgrounds.markerCorner", Component.literal("Marker position"), Component.empty(),
            new Control.Choice<>(List.of(MarkerCorner.values()), c -> Component.literal(switch (c) {
                case TOP_RIGHT -> "Top right"; case TOP_LEFT -> "Top left"; case BOTTOM_RIGHT -> "Bottom right"; case BOTTOM_LEFT -> "Bottom left";
            }), () -> markerCorner, v -> markerCorner = v)).visibleWhen(() -> recombMark).enabledWhen(isOn));
        rows.add(Option.of("rarityBackgrounds.markerColor", Component.literal("Marker color"), Component.empty(),
            new Control.Color(() -> markerColor, v -> markerColor = v, true)).visibleWhen(() -> recombMark).enabledWhen(isOn));
        rows.add(Option.of("rarityBackgrounds.markerSize", Component.literal("Marker size"), Component.empty(),
            new Control.Slider(() -> markerSize, v -> markerSize = (int) v, 2, 8, 1, v -> Component.literal(Math.round(v) + " px")))
            .visibleWhen(() -> recombMark).enabledWhen(isOn));
        return rows.stream().map(o -> {
            Option compact = o.compact();
            if (List.of("rarityBackgrounds.inventory", "rarityBackgrounds.armor", "rarityBackgrounds.hotbar", "rarityBackgrounds.menus").contains(o.id())) compact = compact.beside();
            return compact.visibleWhen(() -> o.visible().getAsBoolean() && switch(o.id()) {
                case "rarityBackgrounds.outline" -> shape.outlined();
                case "rarityBackgrounds.wardrobeMenus", "rarityBackgrounds.loadoutMenus", "rarityBackgrounds.equipmentMenus" -> menus;
                default -> true;
            });
        }).toList();
    }

    private static Option menuRule(String id, Supplier<OwnMenus> get, Consumer<OwnMenus> set) {
        return option(id, choice(List.of(OwnMenus.values()), OwnMenus::label, get, v -> {
            set.accept(v); ruleScreen = new WeakReference<>(null);
        })).enabledWhen(() -> on && menus).beside();
    }

    private static <T> Control choice(List<T> values, Function<T, Component> label, Supplier<T> get, Consumer<T> set) {
        return new Control.Choice<>(values, label, get, v -> {
            set.accept(v);
            colorsChanged();
        });
    }

    /** A row named by {@code skycosmetics.rarityBackgrounds.<id>} and its {@code .tooltip}. */
    private static Option option(String id, Control control) {
        String key = "skycosmetics.rarityBackgrounds." + id;
        return Option.of("rarityBackgrounds." + id, Component.translatable(key), Component.translatable(key + ".tooltip"), control);
    }

    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        return option(id, new Control.Toggle(get, set));
    }
}
