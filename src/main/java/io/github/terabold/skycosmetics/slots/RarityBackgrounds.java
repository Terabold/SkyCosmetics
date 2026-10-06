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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Rarity Backgrounds: the slot behind each item colored by its rarity, in the inventory, in menus, on the armor
 * slots and in the hotbar.
 *
 * Per frame a menu costs one pass over its slots: each stack's rarity is read once and kept on the stack
 * ({@link ItemFacts}), the menu's title is matched once per menu, and every background is one quad of a shared
 * mask texture ({@link Mask}) tinted with the rarity's color. Everything is drawn before the hovered slot's
 * highlight and the items, so nothing of vanilla or another mod is covered.
 */
public final class RarityBackgrounds {
    public static final String SECTION = "rarityBackgrounds";

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

    /** What the Wardrobe, Loadouts and Equipment menus show. */
    public enum OwnMenus {
        ALL, NO_HEADS, NONE;

        public Component label() {
            return Component.translatable("skycosmetics.rarityBackgrounds.ownMenus." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Hypixel menus of your own gear, as {@link OwnedItems#menuSource} names them. */
    private static final Set<String> GEAR_MENUS = Set.of("Wardrobe", "Equipment wardrobe", "Loadouts", "Equipment");

    // Settings; every one but the colors is plain and read by the draw loop directly.
    static boolean on = false;
    static Mask.Shape shape = Mask.Shape.ROUNDED;
    static Mask.Fill fill = Mask.Fill.SOLID;
    static int opacity = 55;
    static boolean outline = false;
    static Colors colors = Colors.HYPIXEL;
    static final int[] custom = Colors.HYPIXEL.rgb.clone();
    static boolean inventory = true, menus = true, armor = true, hotbar = true;
    static boolean common = true, pets = true, skyblockOnly = false, recombMark = false;
    static OwnMenus ownMenus = OwnMenus.ALL;

    /** The ARGB tint of each rarity in the colors in use; rebuilt when they change. */
    private static final int[] TINT = new int[Rarity.values().length];
    /** Bumped on every style change: the mask is repainted when it differs from the one painted. */
    private static long styleKey;

    /** The texture every background is drawn from: cell 0 the style, cell 1 the recombobulated mark. */
    private static final Mask.Sheet MAIN = new Mask.Sheet("slots/rarity_backgrounds", 2, 1);
    /** The menu whose title was matched last, and its rule. */
    private static AbstractContainerScreen<?> ruleScreen;
    private static OwnMenus rule = OwnMenus.ALL;
    /** Backgrounds drawn since start, for tests. */
    static long drawn;

    static {
        changed();
    }

    private RarityBackgrounds() {}

    public static void init() {
        Settings.register(SECTION, RarityBackgrounds::read, RarityBackgrounds::write);
        Hub.add(new Section(SECTION, Section.FEATURE, Component.translatable("skycosmetics.rarityBackgrounds"),
            Component.translatable("skycosmetics.rarityBackgrounds.sub"),
            Component.translatable("skycosmetics.rarityBackgrounds.tooltip"),
            () -> new ItemStack(Items.GLOW_ITEM_FRAME), RarityBackgrounds::rows));
    }

    // ------------------------------------------------------------- drawing ---

    /** Every slot of an open menu, once per frame, under the hover highlight. Never throws. */
    public static void drawSlots(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!on || !(inventory || menus || armor)) return;
        try {
            if (screen != ruleScreen) {
                ruleScreen = screen; // a menu's title never changes while it is open
                rule = GEAR_MENUS.contains(OwnedItems.menuSource(screen.getTitle().getString())) ? ownMenus : OwnMenus.ALL;
            }
            Identifier tex = texture();
            for (Slot slot : screen.getMenu().slots) {
                if (!slot.isActive()) continue;
                ItemStack s = slot.getItem();
                if (s.isEmpty()) continue;
                if (SlotArea.own(slot)) {
                    if (!(SlotArea.armor(slot) ? armor : inventory)) continue;
                } else if (!menus || rule == OwnMenus.NONE || rule == OwnMenus.NO_HEADS && s.is(Items.PLAYER_HEAD)) {
                    continue;
                }
                draw(g, tex, slot.x, slot.y, s);
            }
        } catch (RuntimeException e) {
            Io.failed("Drawing rarity backgrounds", e);
        }
    }

    /** A HUD hotbar slot. Never throws. */
    public static void drawHotbar(GuiGraphicsExtractor g, int x, int y, ItemStack stack) {
        if (!on || !hotbar || stack.isEmpty()) return;
        try {
            draw(g, texture(), x, y, stack);
        } catch (RuntimeException e) {
            Io.failed("Drawing a hotbar rarity background", e);
        }
    }

    private static void draw(GuiGraphicsExtractor g, Identifier tex, int x, int y, ItemStack s) {
        ItemFacts f = ItemFacts.of(s);
        int tint = tintOf(f);
        if (tint == 0) return;
        drawTint(g, tex, x, y, tint, recombMark && f.recombobulated());
    }

    /** One background at a slot's item position (x, y), in an ARGB tint. */
    static void drawTint(GuiGraphicsExtractor g, Identifier tex, int x, int y, int tint, boolean mark) {
        int w = MAIN.texWidth(), h = MAIN.texHeight();
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 1, y - 1, 0, 0, Mask.CELL, Mask.CELL, w, h, tint);
        if (mark) g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 1, y - 1, Mask.CELL, 0, Mask.CELL, Mask.CELL, w, h, 0xFFFFFFFF);
        drawn++;
    }

    /** The tint for an item, or 0 for none: no rarity line, or left out by the settings. */
    static int tintOf(ItemFacts f) {
        Rarity r = f.rarity();
        if (r == null || r == Rarity.COMMON && !common || f.pet() && !pets || skyblockOnly && !f.skyblock()) return 0;
        return TINT[r.ordinal()];
    }

    /** The mask texture for the current style and GUI scale, repainted only when one of them changed. */
    static Identifier texture() {
        return MAIN.ensure(styleKey, (img, i, x0, y0, n, s) -> {
            if (i == 0) Mask.paint(img, x0, y0, n, s, style(), 4);
            else Mask.paintMark(img, x0, y0, n, s);
        });
    }

    static Mask.Style style() {
        return new Mask.Style(shape, fill, opacity, outline);
    }

    /** A rarity's current color (RGB). */
    static int color(Rarity r) {
        return TINT[r.ordinal()] & 0xFFFFFF;
    }

    /** The color a rarity has before any edit: from the set in use, Hypixel's when the set is Custom. */
    static int presetColor(Rarity r) {
        return (colors == Colors.CUSTOM ? Colors.HYPIXEL : colors).rgb[r.ordinal()];
    }

    /** A rarity's color edited: the set becomes Custom, starting from the set shown. */
    static void setColor(Rarity r, int rgb) {
        if (colors != Colors.CUSTOM) {
            System.arraycopy(colors.rgb, 0, custom, 0, custom.length);
            colors = Colors.CUSTOM;
        }
        custom[r.ordinal()] = rgb & 0xFFFFFF;
        changed();
    }

    /** Recomputes the tints and marks the mask for repainting. Call after any setting changes. */
    static void changed() {
        int[] rgb = colors == Colors.CUSTOM ? custom : colors.rgb;
        for (int i = 0; i < TINT.length; i++) TINT[i] = 0xFF000000 | rgb[i];
        styleKey++;
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
        RarityBackgrounds.opacity = Math.clamp(opacity, 10, 100);
        RarityBackgrounds.outline = outline;
        changed();
    }

    /** For tests: where and on what backgrounds show. */
    public static void where(boolean inventory, boolean menus, boolean armor, boolean hotbar, OwnMenus ownMenus,
                             boolean recombMark) {
        RarityBackgrounds.inventory = inventory;
        RarityBackgrounds.menus = menus;
        RarityBackgrounds.armor = armor;
        RarityBackgrounds.hotbar = hotbar;
        RarityBackgrounds.ownMenus = ownMenus;
        RarityBackgrounds.recombMark = recombMark;
        ruleScreen = null;
    }

    public static long drawn() {
        return drawn;
    }

    // ------------------------------------------------------------- settings ---

    private static void read(JsonElement v) {
        JsonObject o = Settings.obj(v);
        on = Settings.bool(o.get("on"), on);
        shape = choice(o, "shape", Mask.Shape.values(), shape);
        fill = choice(o, "fill", Mask.Fill.values(), fill);
        opacity = (int) Math.round(Settings.num(o.get("opacity"), opacity, 10, 100));
        outline = Settings.bool(o.get("outline"), outline);
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
        ruleScreen = null;
        changed();
    }

    private static JsonElement write() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on);
        o.addProperty("shape", shape.name());
        o.addProperty("fill", fill.name());
        o.addProperty("opacity", opacity);
        o.addProperty("outline", outline);
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
        rows.add(Option.of("rarityPreview", Component.empty(), Component.empty(),
            new Control.Custom((host, w) -> new SlotPreview(w, SlotPreview.Kind.RARITIES))).enabledWhen(isOn));

        rows.add(Option.header("rarityStyle", Component.translatable("skycosmetics.rarityBackgrounds.group.style")));
        rows.add(option("shape", new Control.Custom((host, w) -> new StylePicker<>(w, host, Mask.Shape.values(),
            Mask.Shape::label, () -> shape, v -> shape = v, StylePicker.Sheet.SHAPES)))
            .search(Component.translatable("skycosmetics.rarityBackgrounds.shape.search").getString()).enabledWhen(isOn));
        rows.add(option("fill", new Control.Custom((host, w) -> new StylePicker<>(w, host, Mask.Fill.values(),
            Mask.Fill::label, () -> fill, v -> fill = v, StylePicker.Sheet.FILLS)))
            .search(Component.translatable("skycosmetics.rarityBackgrounds.fill.search").getString()).enabledWhen(isOn));
        rows.add(option("opacity", new Control.Slider(() -> opacity, v -> {
            opacity = (int) Math.round(v);
            changed();
        }, 10, 100, 5, v -> Component.literal(Math.round(v) + "%"))).enabledWhen(isOn));
        rows.add(toggle("outline", () -> outline, v -> outline = v).enabledWhen(isOn));

        rows.add(Option.header("rarityColors", Component.translatable("skycosmetics.rarityBackgrounds.group.colors")));
        rows.add(option("colors", choice(List.of(Colors.values()), Colors::label, () -> colors, v -> {
            if (v == Colors.CUSTOM && colors != Colors.CUSTOM) System.arraycopy(colors.rgb, 0, custom, 0, custom.length);
            colors = v;
        })).enabledWhen(isOn));
        rows.add(option("swatches", new Control.Custom((host, w) -> new RaritySwatches(w, host))).enabledWhen(isOn));

        rows.add(Option.header("rarityWhere", Component.translatable("skycosmetics.rarityBackgrounds.group.where")));
        rows.add(toggle("inventory", () -> inventory, v -> inventory = v).enabledWhen(isOn));
        rows.add(toggle("menus", () -> menus, v -> menus = v).enabledWhen(isOn));
        rows.add(toggle("armor", () -> armor, v -> armor = v).enabledWhen(isOn));
        rows.add(toggle("hotbar", () -> hotbar, v -> hotbar = v).enabledWhen(isOn));
        rows.add(option("gearMenus", choice(List.of(OwnMenus.values()), OwnMenus::label, () -> ownMenus, v -> {
            ownMenus = v;
            ruleScreen = null;
        })).search(Component.translatable("skycosmetics.rarityBackgrounds.gearMenus.search").getString()).enabledWhen(isOn));

        rows.add(Option.header("rarityWhich", Component.translatable("skycosmetics.rarityBackgrounds.group.which")));
        rows.add(toggle("common", () -> common, v -> common = v).enabledWhen(isOn));
        rows.add(toggle("pets", () -> pets, v -> pets = v).enabledWhen(isOn));
        rows.add(toggle("skyblockOnly", () -> skyblockOnly, v -> skyblockOnly = v).enabledWhen(isOn));
        rows.add(toggle("recombMark", () -> recombMark, v -> recombMark = v).enabledWhen(isOn));
        return rows;
    }

    private static <T> Control choice(List<T> values, Function<T, Component> label, java.util.function.Supplier<T> get,
                                      Consumer<T> set) {
        return new Control.Choice<>(values, label, get, v -> {
            set.accept(v);
            changed();
        });
    }

    /** A row named by {@code skycosmetics.rarityBackgrounds.<id>} and its {@code .tooltip}. */
    private static Option option(String id, Control control) {
        String key = "skycosmetics.rarityBackgrounds." + id;
        return Option.of("rarityBackgrounds." + id, Component.translatable(key), Component.translatable(key + ".tooltip"), control);
    }

    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        return option(id, new Control.Toggle(get, v -> {
            set.accept(v);
            changed();
        }));
    }
}
