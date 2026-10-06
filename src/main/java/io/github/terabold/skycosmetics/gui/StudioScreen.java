package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Favorites;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The studio, in three columns.
 *
 * Left: your items, most relevant first - what you wear, your summoned pet,
 * what you hold and carry, then everything stored (wardrobe, ender chest,
 * backpacks...) on this profile. Middle: the tab you are editing with. Right:
 * the live preview right beside it - the item's full tooltip as the game shows
 * it (your new name included), your player model, what is applied, and
 * Reset / Done.
 *
 * Every change applies and saves at once. In narrow windows the preview folds
 * into the left column; in tiny ones the studio says so instead of breaking.
 */
public class StudioScreen extends Screen {
    private enum Tab {
        SKINS("Skins", "Skins"), DYES("Dyes", "Dyes"), STYLE("Name & Glint", "Name"), SAVED("Saved", "Saved");

        final String label;
        final String shortLabel;

        Tab(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    private enum SkinFilter {
        HELMET("Helmet", "Helmet skins and their color variants", "Search helmet skins...  e.g. knight, animated"),
        PET("Pet", "Pet skins", "Search pet skins...  e.g. dragon, animated"),
        ORB("Orb", "Power orb skins", "Search power orb skins...  e.g. moon"),
        ALL("All", "Every head in SkyBlock: skins, items, minions...", "Search every head...  e.g. celestial"),
        PASTE("Paste", "Any texture: a skin URL or a skull's Value", null);

        final String label;
        final String help;
        final String hint;

        SkinFilter(String label, String help, String hint) {
            this.label = label;
            this.help = help;
            this.hint = hint;
        }
    }

    /**
     * One line of the left list: a section header, or an item you can pick. {@code role} is where it is
     * listed ("worn:HEAD", "pet", "inv:3", "uuid:..." for a stored item); {@code key} is the item itself,
     * "uuid:..." when it has one, so the pick follows the item when a pet swap or a shuffle moves it.
     */
    private record Row(String header, String key, String role, Supplier<ItemStack> stack, String sub) {
        static Row item(String role, Supplier<ItemStack> stack, String sub) {
            return new Row(null, role, role, stack, sub);
        }

        boolean isHeader() {
            return header != null;
        }
    }

    private static final int PAD = 8;
    private static final int ROW_H = 20;
    private static final int HEADER_H = 12;
    /** Cards are at least this wide (they share the grid's width) and this tall (taller with three-line names). */
    private static final int CARD_W = 54;
    private static final int CARD_H = 46;
    /** New textures the grid may ask for per game tick: first frames, animation frames. */
    private static final int FIRSTS_PER_TICK = 12, FRAMES_PER_TICK = 3;
    /** Ticks after a scroll before the grid fetches animation frames again. */
    private static final int STILL_TICKS = 8;
    private static final float SMALL = 0.75f;
    private static final int SAVED_H = 24;
    private static final int SUMMARY_H = 11;
    /** The roomy Name & Glint tab needs a middle column this wide, stops growing here, and its picker is this tall. */
    private static final int ROOMY_MIN = 230, ROOMY_W = 420, PICKER_H = 84;
    /** The "Name" title row over the name box; Reset name sits at its right end. */
    private static final int NAME_HEAD = 14;
    /** Search boxes stop growing here; wider ones only look empty at GUI scale 1. */
    private static final int SEARCH_W = 420;

    private static final int BG = 0xE0101014;
    private static final int PANEL = 0xF01B1B22;
    private static final int LINE = 0xFF34343F;
    private static final int TEXT = 0xFFE8E8EE;
    private static final int MUTED = 0xFF8C8C9A;
    private static final int ACCENT = 0xFFD58CFF;
    private static final int GOLD = 0xFFFFC94A;

    private static final List<Component> ITEMS_HELP = List.of(
        Component.literal("Where My Items come from").withStyle(ChatFormatting.AQUA),
        bullet("Always: ", "armor, held item, pet, inventory"),
        bullet("Open once: ", "Wardrobe, Equipment, Pets,"),
        Component.literal("   Ender Chest, Backpacks, Personal Vault").withStyle(ChatFormatting.GRAY),
        bullet("Search: ", "names and item ids"),
        bullet("Right-click an item: ", "forget it"));
    /** The same while Stored Items is off: nothing comes from menus, so there is nothing to forget. */
    private static final List<Component> ITEMS_HELP_STORED_OFF = List.of(
        Component.literal("Where My Items come from").withStyle(ChatFormatting.AQUA),
        bullet("Always: ", "armor, held item, pet, inventory"),
        Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.translatable("skycosmetics.studio.itemsHelp.storedOff").withStyle(ChatFormatting.GRAY)),
        bullet("Search: ", "names and item ids"));
    /** Room for a gradient: every letter carries its own {@code &#RRGGBB} (plus its styles again). */
    private static final int MAX_NAME = 512;

    private static final ChatFormatting[] COLOURS = {
        ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY,
        ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
        ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE};
    private static final String[][] STYLES = {{"l", "Bold", "Makes the letters thick"}, {"o", "Italic", "Slants the letters"},
        {"n", "Underline", "Draws a line under the letters"}, {"m", "Strike", "Draws a line through the letters"},
        {"k", "Magic", "Scrambles the letters"},
        {"r", "Plain", "Takes color and style off the letters"}};
    /** Minecraft's own glint purple: stored only to beat an "every item" colour, null otherwise. */
    private static final String PURPLE = "#9D5CFF";
    private static final String[][] GLINT_COLOURS = {{"Purple (Minecraft's)", null}, {"Red", "#FF3B3B"},
        {"Orange", "#FF8A1F"}, {"Gold", "#FFC21A"}, {"Yellow", "#FFF23B"}, {"Lime", "#9BFF3B"}, {"Green", "#2FD14A"},
        {"Aqua", "#3BF0FF"}, {"Blue", "#3B6BFF"}, {"Pink", "#FF7AC8"}, {"Magenta", "#FF3BF0"}, {"White", "#FFFFFF"}};

    /** A name gradient preset: its colours from the first letter to the last (0xRRGGBB). */
    private record Gradient(String name, int... stops) {}

    private static final List<Gradient> GRADIENTS = List.of(
        new Gradient("Rainbow", 0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF),
        new Gradient("Fire", 0xFFF06A, 0xFF8A1F, 0xE0201B), new Gradient("Ocean", 0x55FFFF, 0x2F6BFF),
        new Gradient("Sunset", 0xFF5FA8, 0xFFB13B), new Gradient("Toxic", 0xEFFF3B, 0x1FC93F),
        new Gradient("Royal", 0x9D5CFF, 0xFF6BE6), new Gradient("Ice", 0xFFFFFF, 0x55D6FF),
        new Gradient("Gold", 0xFFF4A3, 0xFFAA00, 0xB86B00));

    private final Screen parent;
    private final ItemStack focus;

    // Left list
    private final List<Row> rows = new ArrayList<>();
    private String selectedKey;
    /** The picked row. Kept while a search hides it, so filtering never changes what you edit. */
    private Row selected;
    private ItemStack identStack;
    private Cosmetics.Ident identCache;
    /**
     * The item the middle column was built for, and its Hypixel name. Edits always go to it, also the
     * ones still waiting when the picked row starts showing another item.
     */
    private Cosmetics.Ident widgetsFor;
    private String widgetsName;
    /** A typed name, saved once typing pauses (not one file write per keystroke). */
    private String pendingName;
    private long pendingNameAt;
    private int listScroll;
    private String itemQuery = "";
    private int builtFor = -1;
    private boolean byType;
    private Button itemsInfo, settings;
    /** While the open key is unbound, the Settings button is outlined until it is clicked. */
    private boolean keyTip;

    // Middle
    private Tab tab = Tab.SKINS;
    private SkinFilter skinFilter = SkinFilter.ALL;
    private String query = "";
    private int scroll;
    private String status = "";
    private int statusColor = MUTED;
    private long statusAt;
    /** The footer line as drawn last frame. */
    private String footShown = "";
    private List<SkinEntry> skinResults = List.of();
    private List<DyeEntry> dyeResults = List.of();
    private Catalog shownCatalog;
    private final Map<String, String[]> nameLines = new HashMap<>();
    private final Map<String, Integer> lastFrame = new HashMap<>();
    /** What the skins grid cost last frame (heads drawn, texture checks) and every texture it asked for. */
    private int gridItems, gridChecks;
    private final Set<String> asked = new HashSet<>();

    private EditBox texBox;
    private NameBox nameBox;
    /** What the item is called without a name of this scope's own: the "every item" name, else Hypixel's. */
    private String nameBaseline;
    private Button nameInfo, resetName, speedReset, strengthReset, colourReset;
    /** The glint colour square in use, outlined; null for a custom colour. */
    private Button glintPick;
    private final Map<String, Button> glintSquares = new HashMap<>();
    private int nameTop, glintTop, glintIconX, glintIconY, glintIcon;
    /** Name & Glint at a comfortable size, with the name's colour picker open; off in small windows. */
    private boolean roomy;
    /** That always-open picker, or null (then "Custom color..." opens the pop-up). */
    private ColorPicker namePicker;
    private List<FormattedCharSequence> pickerNote = List.of();
    private int[] pickerNoteAt = {0, 0};
    /** The name and selection a picker drag started from, and what it made of them: the drag recolours the same letters. */
    private String pickBase, pickShown;
    private int pickFrom, pickTo, pickSelA, pickSelB;
    /** What the picker's colour was last set from: the name, and where in it. */
    private String syncedValue;
    private int syncedAt;
    /** Name & Glint widgets and where they sit unscrolled: the tab scrolls when the window is too short for it. */
    private final Map<AbstractWidget, Integer> styleY = new HashMap<>();
    private int styleScroll, styleMax;
    private boolean codesShown;
    /** The colour pop-up over the middle column, or null. It gets every click and key first. */
    private ColorPopup popup;
    /** Tab, item and scope the pop-up was opened for; it is dropped when any of them changes. */
    private String popupOwner;
    /** A dye chosen in a picker, applied once the mouse rests (no file write per drag step). */
    private String pendingDye;
    private long pendingAt;
    /** A glint speed or colour being dragged, saved the same way. */
    private UnaryOperator<Looks.Look> pendingGlint;
    private long pendingGlintAt;

    // Right
    private final TooltipPanel tooltip = new TooltipPanel();
    /** The custom name in the summary, clipped once per name and width rather than every frame. */
    private String summaryNameRaw;
    private int summaryNameWidth;
    private FormattedCharSequence summaryName;

    // Layout, recomputed in init().
    private boolean compact, slim, tooSmall;
    private int leftW, prevW, midX, midW, prevX;
    private int gridY, gridH, cols, cardW = CARD_W, cardH = CARD_H;
    /** Card names: text scale, most lines, line pitch in font units. */
    private float labelScale = SMALL;
    private int labelLines = 2, labelStep = 10;
    /** This tick's texture budget, and the tick of the last grid scroll. */
    private long budgetTick = -1, scrolledAt = -100;
    private int firstBudget, frameBudget;
    private int listTop, listBottom;

    /**
     * @param parent screen to return to (a menu stays open underneath)
     * @param focus  item to start on, e.g. the one hovered when K was pressed; may be empty
     */
    public StudioScreen(Screen parent, ItemStack focus) {
        super(Component.literal("SkyCosmetics"));
        this.parent = parent;
        this.focus = focus == null || focus.isEmpty() ? ItemStack.EMPTY : focus.copy();
    }

    private static Component bullet(String lead, String rest) {
        return Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(lead).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(rest).withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------- items ---

    private Player player() {
        return minecraft != null ? minecraft.player : net.minecraft.client.Minecraft.getInstance().player;
    }

    private Supplier<ItemStack> worn(EquipmentSlot slot) {
        return () -> {
            Player p = player();
            return p == null ? ItemStack.EMPTY : p.getItemBySlot(slot);
        };
    }

    private Supplier<ItemStack> invSlot(int i) {
        return () -> {
            Player p = player();
            return p == null ? ItemStack.EMPTY : p.getInventory().getItem(i);
        };
    }

    private static boolean usable(ItemStack s) {
        return !s.isEmpty() && Cosmetics.identify(s) != null;
    }

    /** Picked, Wearing, Pet, Held, Inventory, then stored items by category - each item once. */
    private void buildRows() {
        builtFor = OwnedItems.version();
        rows.clear();
        Set<String> shown = new HashSet<>();
        String q = itemQuery.toLowerCase(Locale.ROOT).trim();

        // Opened from a menu: the hovered item first. Else (held item, helmet) it is picked where it is listed.
        section("Hovered", fromMenu() && usable(focus) ? List.of(Row.item("focus", () -> focus, "")) : List.of(), shown, q);

        String[] names = {"Helmet", "Chestplate", "Leggings", "Boots"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        List<Row> wearing = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            Supplier<ItemStack> s = worn(slots[i]);
            if (usable(s.get())) wearing.add(Row.item("worn:" + slots[i].name(), s, names[i]));
        }
        section("Wearing", wearing, shown, q);

        List<Row> pet = usable(PetTracker.currentPetItem())
            ? List.of(Row.item("pet", PetTracker::currentPetItem, "Summoned")) : List.of();
        section("Pet", pet, shown, q);

        Player p = player();
        int held = p == null ? -1 : p.getInventory().getSelectedSlot();
        List<Row> hand = p != null && usable(p.getInventory().getItem(held))
            ? List.of(Row.item("inv:" + held, invSlot(held), "Main hand")) : List.of();
        section("Held", hand, shown, q);

        List<Row> inv = new ArrayList<>();
        if (p != null) {
            for (int i = 0; i < 36; i++) {
                if (i == held) continue;
                ItemStack s = p.getInventory().getItem(i);
                if (OwnedItems.skinnable(s)) inv.add(Row.item("inv:" + i, invSlot(i), i < 9 ? "Hotbar" : "Inventory"));
            }
        }
        section("Inventory", inv, shown, q);

        OwnedItems.Category cat = null;
        List<Row> group = new ArrayList<>();
        for (OwnedItems.Owned o : OwnedItems.list()) {
            if (o.category != cat) {
                if (cat != null) section(cat.label, group, shown, q);
                group = new ArrayList<>();
                cat = o.category;
            }
            if (!o.stack().isEmpty()) group.add(Row.item("uuid:" + o.uuid, o::stack, o.source));
        }
        if (cat != null) section(cat.label, group, shown, q);
        pickedFirst(shown, q);

        Row match = null, sameRole = null, first = null;
        for (Row r : rows) {
            if (r.isHeader()) continue;
            if (first == null) first = r;
            if (r.key().equals(selectedKey)) match = r;
            if (selected != null && r.role().equals(selected.role())) sameRole = r;
        }
        if (match != null) {
            selected = match; // same item, fresh supplier
        } else if (selected == null || ident() == null || !Objects.equals(ident(), widgetsFor)) {
            // The item is gone (sold, another pet summoned...): what its slot holds now, else the first item.
            // The caller then rebuilds the middle column, after saving what was waiting to the old item.
            selected = sameRole != null ? sameRole : first;
            selectedKey = selected == null ? null : keyOf(selected);
            identStack = null;
        }
        // else: the search hides the picked item; keep editing it.
    }

    /** Opened for an item none of the sections lists (not in a menu): it goes first, under "Picked". */
    private void pickedFirst(Set<String> shown, String q) {
        if (fromMenu() || !usable(focus)) return;
        Cosmetics.Ident id = Cosmetics.identify(focus);
        if (id.uuid() != null ? shown.contains(id.uuid())
            : rows.stream().anyMatch(r -> !r.isHeader() && ItemStack.isSameItemSameComponents(r.stack().get(), focus))) {
            return;
        }
        if (!matches(focus, id, q)) return;
        rows.add(0, new Row("Picked", null, null, null, null));
        rows.add(1, new Row(null, id.uuid() != null ? "uuid:" + id.uuid() : "focus", "focus", () -> focus, ""));
    }

    /** Opened with the open key over an item in a menu (the menu stays open underneath). */
    private boolean fromMenu() {
        return parent instanceof AbstractContainerScreen<?>;
    }

    /** The key of the row to start on: the item the studio was opened for. */
    private String focusKey() {
        Cosmetics.Ident id = usable(focus) ? Cosmetics.identify(focus) : null;
        if (id == null) return null;
        return id.uuid() != null ? "uuid:" + id.uuid() : null;
    }

    /** Starts on the item opened for when it has no uuid: its own row, else the listed item that is the same. */
    private void pickLike(ItemStack s) {
        if (s.isEmpty()) return;
        for (Row r : rows) {
            if (!r.isHeader() && r.role().equals("focus")) {
                selected = r;
                selectedKey = keyOf(r);
                identStack = null;
                return;
            }
        }
        for (Row r : rows) {
            if (!r.isHeader() && ItemStack.isSameItemSameComponents(r.stack().get(), s)) {
                selected = r;
                selectedKey = keyOf(r);
                identStack = null;
                return;
            }
        }
    }

    /** Adds a header and the rows that match the search and were not listed above. */
    private void section(String title, List<Row> candidates, Set<String> shown, String q) {
        List<Row> keep = new ArrayList<>();
        for (Row r : candidates) {
            ItemStack s = r.stack().get();
            Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
            if (id == null) continue;
            if (id.uuid() != null && !shown.add(id.uuid())) continue;
            if (matches(s, id, q)) keep.add(id.uuid() == null ? r : new Row(null, "uuid:" + id.uuid(), r.role(), r.stack(), r.sub()));
        }
        if (keep.isEmpty()) return;
        rows.add(new Row(title, null, null, null, null));
        rows.addAll(keep);
    }

    /** The search finds your name for the item, Hypixel's name, or its SkyBlock id ("necron" or "NECRON_HEAD"). */
    private static boolean matches(ItemStack s, Cosmetics.Ident id, String q) {
        if (q.isEmpty()) return true;
        String type = id.type().toLowerCase(Locale.ROOT);
        return s.getStyledHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
            || Cosmetics.originalName(s).getString().toLowerCase(Locale.ROOT).contains(q)
            || type.contains(q) || type.replace('_', ' ').contains(q);
    }

    /** The item a row shows now: "uuid:..." when it has one, else where it is listed. */
    private static String keyOf(Row r) {
        ItemStack s = r.stack().get();
        Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
        return id != null && id.uuid() != null ? "uuid:" + id.uuid() : r.role();
    }

    private ItemStack target() {
        return selected == null ? ItemStack.EMPTY : selected.stack().get();
    }

    /** Identified once per stack (a pet's petInfo is JSON), not several times per frame. */
    private Cosmetics.Ident ident() {
        ItemStack s = target();
        if (s != identStack) {
            identStack = s;
            identCache = s.isEmpty() ? null : Cosmetics.identify(s);
        }
        return identCache;
    }

    private void resetScope() {
        Cosmetics.Ident id = ident();
        byType = id != null && id.uuid() == null;
    }

    /**
     * Opens the tab that fits the item: pet skins for pets, dyes for body
     * armour, skins otherwise - and sets the Skins filter to what the item can
     * wear (pet, orb or helmet skins; every head for anything else).
     */
    private void pickTabFor(ItemStack s) {
        Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
        if (id == null) return;
        skinFilter = defaultFilter(s, id);
        if (tab == Tab.STYLE || tab == Tab.SAVED) return; // keep the user's explicit choice
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        boolean body = eq != null && (eq.slot() == EquipmentSlot.CHEST || eq.slot() == EquipmentSlot.LEGS
                                     || eq.slot() == EquipmentSlot.FEET);
        tab = body && skinFilter != SkinFilter.PET ? Tab.DYES : Tab.SKINS;
        query = "";
        scroll = 0;
    }

    private SkinFilter defaultFilter(ItemStack s, Cosmetics.Ident id) {
        if (id.type().startsWith("PET:")) return SkinFilter.PET;
        OwnedItems.Category cat = OwnedItems.categorize(s, id);
        if (cat == OwnedItems.Category.DEPLOYABLE) return SkinFilter.ORB;
        if (cat == OwnedItems.Category.HELMET || wornOnHead(id)) return SkinFilter.HELMET;
        return SkinFilter.ALL;
    }

    /** The helmet you wear counts as one even when its lore has no rarity line yet. */
    private boolean wornOnHead(Cosmetics.Ident id) {
        ItemStack head = worn(EquipmentSlot.HEAD).get();
        Cosmetics.Ident h = head.isEmpty() ? null : Cosmetics.identify(head);
        return h != null && id.uuid() != null && id.uuid().equals(h.uuid());
    }

    private String key(Cosmetics.Ident id) {
        return byType ? id.type() : id.uuid();
    }

    /** The look saved for the picked item at the current scope (not merged). */
    private Looks.Look look() {
        Cosmetics.Ident id = widgetsFor;
        if (id == null) return Looks.Look.NONE;
        Looks.Look l = byType ? Looks.byType(id.type()) : Looks.byUuid(id.uuid());
        return l == null ? Looks.Look.NONE : l;
    }

    /** The "every item of this type" look still applying while editing "only this item". */
    private Looks.Look inherited() {
        Cosmetics.Ident id = widgetsFor;
        if (id == null || byType) return Looks.Look.NONE;
        Looks.Look t = Looks.byType(id.type());
        return t == null ? Looks.Look.NONE : t;
    }

    /** Applies and saves at once, to the item the middle column was built for. */
    private void edit(UnaryOperator<Looks.Look> change) {
        Cosmetics.Ident id = widgetsFor;
        if (id == null) return;
        Looks.Look next = change.apply(look());
        String label = byType ? "Every " + Repo.get().typeName(id.type()) : widgetsName;
        Looks.Look l = next.empty() ? null : next.withLabel(label);
        if (byType) Looks.put(true, id.type(), l);
        else Looks.putItem(id.uuid(), id.type(), l); // its type sorts it in Saved, even once the item is forgotten
    }

    // ------------------------------------------------------------ layout ---

    @Override
    protected void init() {
        tooSmall = width < 330 || height < 200;
        if (tooSmall) {
            popup = null;
            addRenderableWidget(new Button.Builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 40, height / 2 + 14, 80, 20).build());
            return;
        }
        compact = width < 480;
        slim = compact && height < 320;
        leftW = Math.max(140, Math.min(200, (int) (width * 0.24)));
        prevW = compact ? 0 : width >= 640 ? Math.clamp(width / 4, 200, 240) : 130;
        prevX = width - PAD - prevW;
        midX = PAD * 2 + leftW;
        midW = (prevW > 0 ? prevX - PAD : width - PAD) - midX;
        if (builtFor < 0) {
            selectedKey = focusKey();
            boolean byUuid = selectedKey != null;
            buildRows();
            if (!byUuid) pickLike(focus);
            resetScope();
            pickTabFor(target());
        }
        widgetsFor = ident();
        widgetsName = widgetsFor == null ? null : Cosmetics.originalName(target()).getString();

        initLeft();
        initMiddle();
        initPreview();
        refilter();
    }

    private void initLeft() {
        int x = PAD + 4, w = leftW - 8;
        int sw = font.width("Settings") + 14;
        settings = addRenderableWidget(new Button.Builder(Component.literal("Settings"), b -> {
            flushPending();
            if (!Settings.keyTipShown) {
                Settings.keyTipShown = true;
                Settings.save();
            }
            // Opened from the settings: back to that same screen rather than stack another one.
            minecraft.setScreen(parent instanceof SettingsScreen ? parent : new SettingsScreen(this, Hub.STUDIO));
        }).bounds(PAD + leftW - 4 - sw, PAD + 3, sw, 20).build());
        keyTip = !Settings.keyTipShown && SkyCosmetics.openKey() != null && SkyCosmetics.openKey().isUnbound();
        settings.setTooltip(Tooltip.create(Component.translatable(keyTip ? "skycosmetics.studio.settings.keyTip"
            : "skycosmetics.studio.settings.tooltip")));

        EditBox items = new EditBox(font, x, PAD + 28, w - 18, 14, Component.literal("Search my items"));
        items.setHint(Component.literal("Search my items...").withStyle(ChatFormatting.DARK_GRAY));
        items.setMaxLength(48);
        items.setValue(itemQuery);
        items.setResponder(v -> {
            itemQuery = v;
            listScroll = 0;
            buildRows();
        });
        addRenderableWidget(items);
        itemsInfo = info(x + w - 15, PAD + 27);

        listTop = PAD + 48;
        // Short narrow windows: only the item's icon beside the scope switch; its tooltip has the summary.
        listBottom = height - PAD - (!compact ? 4 : slim ? 48 : 98);
        if (compact) actionButtons(x, height - PAD - 44, w, slim ? 24 : 0);
    }

    /** Scope switch (after {@code indent}) above Reset / Done, in the preview column (or the left one when compact). */
    private void actionButtons(int x, int y, int w, int indent) {
        Cosmetics.Ident id = ident();
        Button scope = addRenderableWidget(new Button.Builder(scopeLabel(id), b -> toggleScope())
            .bounds(x + indent, y, w - indent, 20).build());
        scope.active = id != null && id.uuid() != null;
        scope.setTooltip(Tooltip.create(Component.literal(
            "This Item Only: just the piece you picked.\nEvery item of this type: all of them, e.g. every Necron's Chestplate.")));
        int half = (w - 4) / 2;
        Button reset = addRenderableWidget(new Button.Builder(Component.literal("Reset").withStyle(ChatFormatting.RED),
            b -> resetItem()).bounds(x, y + 24, half, 20).build());
        reset.active = id != null;
        reset.setTooltip(Tooltip.create(Component.literal("Put Hypixel's look back on this item")));
        addRenderableWidget(new Button.Builder(Component.literal("Done").withStyle(ChatFormatting.GREEN), b -> onClose())
            .bounds(x + half + 4, y + 24, half, 20).build());
    }

    private void initPreview() {
        if (prevW > 0) actionButtons(prevX + 4, height - PAD - 48, prevW - 8, 0);
    }

    private void initMiddle() {
        nameBox = null;
        nameInfo = null;
        glintPick = null;
        glintSquares.clear();
        styleY.clear();
        styleMax = 0;
        texBox = null;
        if (popup != null && !popupOwnerKey().equals(popupOwner)) popup = null; // no stale editor keeps input
        int y = PAD;
        Tab[] tabs = Tab.values();
        int tw = Math.min(96, (midW - (tabs.length - 1) * 2) / tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            String label = font.width(t.label) + 8 <= tw ? t.label : t.shortLabel;
            Button b = addRenderableWidget(new Button.Builder(Component.literal(label), btn -> switchTab(t))
                .bounds(midX + i * (tw + 2), y, tw, 18).build());
            b.active = t != tab;
        }
        y += 22;

        switch (tab) {
            case SKINS -> {
                y = chips(y);
                if (skinFilter == SkinFilter.PASTE) {
                    int w = Math.min(midW - 96, 360);
                    texBox = new EditBox(font, midX, y + 12, w, 16, Component.literal("Texture"));
                    texBox.setHint(Component.literal("base64 value, textures.minecraft.net URL, or hash")
                        .withStyle(ChatFormatting.DARK_GRAY));
                    texBox.setMaxLength(4096);
                    addRenderableWidget(texBox);
                    addRenderableWidget(new Button.Builder(Component.literal("Use texture"), b -> useTexture())
                        .bounds(midX + w + 4, y + 10, 90, 20).build());
                    setInitialFocus(texBox);
                    y += 36;
                } else {
                    y = search(y, skinFilter.hint, Math.min(midW, SEARCH_W));
                }
            }
            case DYES -> {
                int bw = font.width("Custom dye...") + 12, sw = Math.min(midW - bw - 4, SEARCH_W);
                y = search(y, "Search dyes...  e.g. aurora, warden", sw);
                Button custom = addRenderableWidget(new Button.Builder(Component.literal("Custom dye..."),
                    b -> openDyePopup()).bounds(midX + sw + 4, y - 22, bw, 16).build());
                custom.active = ident() != null;
                custom.setTooltip(Tooltip.create(Component.literal("Any color, or your own animated dye")));
            }
            case STYLE -> {
                int first = children().size();
                roomy = midW >= ROOMY_MIN;
                int end = initStyle(y);
                if (roomy && end > styleBottom()) {
                    // Too short for the big layout: the compact one, which scrolls if it must.
                    for (var c : List.copyOf(children().subList(first, children().size()))) removeWidget(c);
                    roomy = false;
                    end = initStyle(y);
                }
                y = end;
                scrollStyle(first, y);
            }
            default -> { }
        }
        gridY = y;
        gridH = height - PAD - 14 - gridY;
        cols = Math.max(1, midW / CARD_W);
        cardW = midW / cols; // the cards share the width: wider cards, fewer cut names
        int gs = Math.max(1, (int) Math.round(minecraft.getWindow().getGuiScale()));
        labelScale = gs >= 3 ? (gs - 1f) / gs : SMALL;
        labelLines = gs >= 2 ? 3 : 2;
        labelStep = gs >= 2 ? 9 : 10;
        cardH = gs >= 3 ? 48 : gs == 2 ? 50 : CARD_H;
        nameLines.clear();
        placePopup();
    }

    private int search(int y, String hint, int w) {
        EditBox search = new EditBox(font, midX, y, w, 16, Component.literal("Search"));
        search.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            scroll = 0;
            refilter();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        return y + 22;
    }

    /** The Skins filters as small toggle buttons; the active one is shown pressed. Wraps in narrow windows. */
    private int chips(int y) {
        int cx = midX, cy = y;
        for (SkinFilter f : SkinFilter.values()) {
            int w = font.width(f.label) + 12;
            if (cx + w > midX + midW) {
                cx = midX;
                cy += 16;
            }
            Button chip = addRenderableWidget(new Button.Builder(Component.literal(f.label), b -> {
                skinFilter = f;
                scroll = 0;
                rebuildWidgets();
            }).bounds(cx, cy, w, 14).build());
            chip.active = f != skinFilter;
            chip.setTooltip(Tooltip.create(Component.literal(f.help)));
            cx += w + 2;
        }
        return cy + 18;
    }

    /** The custom dye pop-up: starts from the dye the item shows now, applies every change live. */
    private void openDyePopup() {
        if (ident() == null) return;
        String current = look().dye() != null ? look().dye() : inherited().dye();
        openPopup(new ColorPopup(Component.literal("Custom dye"), current, true, this::queueDye).onClose(this::flushPending));
    }

    /** Shows a colour pop-up over the middle column until it is closed or the tab, item or scope changes. */
    private void openPopup(ColorPopup p) {
        popup = p;
        popupOwner = popupOwnerKey();
        placePopup();
    }

    private String popupOwnerKey() {
        return tab + "|" + widgetsFor + "|" + byType;
    }

    /** Over the middle column; in narrow windows it may reach over the item list to stay usable. */
    private void placePopup() {
        if (popup == null) return;
        int aw = Math.max(midW, Math.min(320, width - 2 * PAD));
        int ax = Math.max(PAD, midX + midW - aw);
        popup.place(ax, PAD, aw, height - 2 * PAD);
    }

    private void queueDye(String id) {
        pendingDye = id;
        pendingAt = Util.getMillis();
    }

    /**
     * Name & Glint. The name box starts with the name the item has now; the
     * colour and style buttons format the selected letters (or insert at the
     * cursor) and leave the keyboard in the box. Then the enchant glint:
     * on/off, speed, strength and colour beside a live icon, each with a reset.
     *
     * Where the window has room, everything is bigger and the name's colour
     * picker stays open under the style buttons. Small windows get the compact
     * layout, which scrolls if it must, with a pop-up for the colour.
     */
    private int initStyle(int y0) {
        nameBaseline = null;
        nameBox = null;
        nameInfo = null;
        namePicker = null;
        glintPick = null;
        glintSquares.clear();
        if (ident() == null) return y0;
        Looks.Look l = look();
        int right = midX + Math.min(midW, roomy ? ROOMY_W : 340);
        int rowH = roomy ? 18 : 14, boxH = roomy ? 20 : 16, infoW = roomy ? 18 : 14;
        nameTop = y0;
        String typeName = inherited().name();
        nameBaseline = (typeName != null ? typeName : Names.toCodes(Cosmetics.originalName(target()))).trim();
        // Reset name sits on the "Name" title row, right-aligned over the box it resets.
        int rw = font.width("Reset name") + 12;
        resetName = addRenderableWidget(Button.builder(Component.literal("Reset name"), b -> resetName())
            .bounds(right - rw, y0, rw, NAME_HEAD - 1).build());
        nameBox = new NameBox(font, midX, y0 + NAME_HEAD, right - midX - infoW - 4, boxH);
        nameBox.setHint(Component.literal("Empty: Hypixel's name").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setMaxLength(MAX_NAME);
        nameBox.setValue(pendingName != null ? pendingName : l.name() != null ? l.name() : nameBaseline);
        nameBox.setResponder(v -> {
            pendingName = v;
            pendingNameAt = Util.getMillis();
            resetName.active = true;
        });
        addRenderableWidget(nameBox);
        nameInfo = info(right - infoW, y0 + NAME_HEAD, infoW, boxH);

        int[] pos = {midX, y0 + NAME_HEAD + boxH + (roomy ? 5 : 4)};
        int sw = roomy ? Math.clamp((right - midX + 1) / COLOURS.length - 1, 14, 22) : 14;
        for (ChatFormatting f : COLOURS) {
            Button b = place(pos, right, 1, swatch(Component.literal("■").withStyle(f), sw, rowH, f.getColor(), true,
                () -> applyCode("&" + f.getChar())));
            b.setTooltip(Tooltip.create(Component.literal(CodesTooltip.title(f)).withStyle(f)
                .append(Component.literal("  &" + f.getChar()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\nColors the selected letters, or the text after the cursor")
                    .withStyle(ChatFormatting.GRAY))));
        }
        pos = new int[]{midX, pos[1] + rowH + (roomy ? 4 : 2)};
        int extra = 0; // roomy: the style buttons share the row's width
        if (roomy) {
            int natural = (STYLES.length - 1) * 2;
            for (String[] st : STYLES) natural += font.width(st[1]) + 10;
            extra = Math.max(0, (right - midX - natural) / STYLES.length);
        }
        for (String[] st : STYLES) {
            Button b = place(pos, right, 2, keepFocus(Component.literal(st[1]), font.width(st[1]) + 10 + extra, rowH,
                () -> applyCode("&" + st[0])));
            b.setTooltip(Tooltip.create(Component.literal(st[2]).append(Component.literal("  &" + st[0])
                .withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal(st[0].equals("r") ? ""
                : "\nLetters that have it already lose it").withStyle(ChatFormatting.GRAY))));
        }
        pos = new int[]{midX, pos[1] + rowH + (roomy ? 6 : 2)};
        if (roomy) {
            // The colour picker, open: it colours the letters the box has selected, or the whole name.
            // The gradients sit beside it, so the tab is no taller than without them.
            int pw = Math.min(200, right - midX - 92), rx = midX + pw + 10;
            namePicker = new ColorPicker(midX, pos[1], pw, PICKER_H);
            namePicker.setOnChange(this::pickNameColour);
            namePicker.setOnCommit(rgb -> pickBase = null);
            pickBase = null;
            syncedValue = null;
            int noteY = gradients(rx, pos[1], right - rx, rowH) + 5;
            List<FormattedCharSequence> note =
                font.split(Component.literal("Any color for the selected letters, or the whole name"), right - rx);
            pickerNote = noteY + note.size() * 10 - 2 <= pos[1] + PICKER_H ? note : List.of(); // whole, or not at all
            pickerNoteAt = new int[]{rx, noteY};
            pos[1] += PICKER_H;
        } else {
            pos[1] = gradients(midX, pos[1], right - midX, 16) + 2;
            place(pos, right, 2, keepFocus(Component.literal("Custom color..."), font.width("Custom color...") + 12, 16,
                this::openNameColour)).setTooltip(Tooltip.create(Component.literal("Any color for the selected letters")));
            pos[1] += 16;
        }
        resetName.active = l.name() != null || pendingName != null;
        resetName.setTooltip(Tooltip.create(Component.literal(typeName != null
            ? "Back to the name every " + Repo.get().typeName(ident().type()) + " has" : "Back to Hypixel's name")));
        return initGlint(pos[1] + (roomy ? 10 : 8), right);
    }

    private int initGlint(int y, int right) {
        Looks.Look l = look(), t = inherited();
        glintTop = y;
        glintIcon = roomy ? 52 : 36;
        glintIconX = midX;
        glintIconY = y + 12;
        int rowH = roomy ? 18 : 16, step = rowH + (roomy ? 3 : 4);
        int cx = midX + glintIcon + 4, cw = Math.min(right - cx - 16, roomy ? 240 : 150);
        boolean on = glintOn();
        Button toggle = addRenderableWidget(Button.builder(Component.literal("Enchant glint: " + (on ? "On" : "Off")),
            b -> toggleGlint()).bounds(cx, glintIconY, cw, rowH).build());
        toggle.setTooltip(Tooltip.create(Component.literal("Click to turn the shimmer " + (on ? "off" : "on"))));
        reset(cx + cw + 2, glintIconY, rowH, l.glint() != null, "Back to " + (t.glint() != null
            ? "the look for every item" : "Hypixel's") + " (" + (glintDefault() ? "on" : "off") + ")", () -> {
                edit(look -> look.withGlint(null));
                rebuildWidgets();
            });

        Float speed = l.glintSpeed() != null ? l.glintSpeed() : t.glintSpeed();
        SpeedSlider slider = addRenderableWidget(SpeedSlider.speed(cx, glintIconY + step, cw, rowH, speed, s -> {
            queueGlint(look -> look.withGlintSpeed(s));
            speedReset.active = true;
        }, this::flushPending));
        slider.setTooltip(Tooltip.create(Component.literal("How fast the shimmer moves: 0.1x to 4x")));
        speedReset = reset(cx + cw + 2, glintIconY + step, rowH, l.glintSpeed() != null, "Back to Minecraft's own speed",
            () -> {
                pendingGlint = null;
                edit(look -> look.withGlintSpeed(null));
                rebuildWidgets();
            });

        Float strength = l.glintStrength() != null ? l.glintStrength() : t.glintStrength();
        SpeedSlider bright = addRenderableWidget(SpeedSlider.strength(cx, glintIconY + 2 * step, cw, rowH, strength, s -> {
            queueGlint(look -> look.withGlintStrength(s));
            strengthReset.active = true;
        }, this::flushPending));
        bright.setTooltip(Tooltip.create(Component.literal("How bright the shimmer is: 0.25x to 3x")));
        strengthReset = reset(cx + cw + 2, glintIconY + 2 * step, rowH, l.glintStrength() != null,
            "Back to the glint's own strength", () -> {
                pendingGlint = null;
                edit(look -> look.withGlintStrength(null));
                rebuildWidgets();
            });

        String colour = l.glintColor() != null ? l.glintColor() : t.glintColor();
        int sq = roomy ? 18 : 14;
        int[] pos = {midX, Math.max(glintIconY + 2 * step + rowH, glintIconY + glintIcon) + 4};
        for (String[] c : GLINT_COLOURS) {
            int rgb = Integer.parseInt((c[1] != null ? c[1] : PURPLE).substring(1), 16);
            // Purple is "no colour", except over an "every item" colour, which only an explicit one beats.
            String value = c[1] == null && t.glintColor() != null ? PURPLE : c[1];
            Button b = place(pos, right, 1, swatch(Component.literal("■").withColor(rgb), sq, sq, rgb, false, () -> {
                pendingGlint = null;
                edit(look -> look.withGlintColor(value));
                rebuildWidgets();
            }));
            b.setTooltip(Tooltip.create(Component.literal(c[0]).withColor(rgb)));
            glintSquares.put(c[0], b);
            if (Objects.equals(colour, c[1]) || c[1] == null && PURPLE.equals(colour)) glintPick = b;
        }
        pos[0] += 1; // the usual 2 px before a button
        place(pos, right, 2, Button.builder(Component.literal("Custom color..."), b -> openGlintColour())
            .size(font.width("Custom color...") + 12, sq).build())
            .setTooltip(Tooltip.create(Component.literal("Any color for the shimmer")));
        Button undo = undoButton(b -> {
            pendingGlint = null;
            edit(look -> look.withGlintColor(null));
            rebuildWidgets();
        });
        undo.setHeight(sq);
        colourReset = place(pos, right, 2, undo);
        colourReset.active = l.glintColor() != null;
        colourReset.setTooltip(Tooltip.create(Component.literal("Back to Minecraft's purple")));
        return pos[1] + sq;
    }

    /** In windows too short for the tab, it scrolls with the wheel; rows out of view are hidden. */
    private void scrollStyle(int first, int contentBottom) {
        styleMax = Math.max(0, contentBottom - styleBottom());
        styleScroll = Math.clamp(styleScroll, 0, styleMax);
        for (var c : children().subList(first, children().size())) {
            if (c instanceof AbstractWidget w) styleY.put(w, w.getY());
        }
        placeStyle();
    }

    private void placeStyle() {
        styleY.forEach((w, y) -> {
            w.setY(y - styleScroll);
            w.visible = w.getY() >= PAD + 22 && w.getBottom() <= styleBottom();
        });
    }

    private int styleBottom() {
        return height - PAD - 12;
    }

    /** Puts {@code b} at pos, left to right, wrapping before {@code right}; a row is as tall as its buttons. */
    private Button place(int[] pos, int right, int gap, Button b) {
        if (pos[0] + b.getWidth() > right && pos[0] > midX) {
            pos[0] = midX;
            pos[1] += b.getHeight() + 2;
        }
        b.setPosition(pos[0], pos[1]);
        pos[0] += b.getWidth() + gap;
        return addRenderableWidget(b);
    }

    /** A button that leaves the keyboard where it was, so the name box keeps its focus and shows the selection. */
    private static Button keepFocus(Component label, int w, int h, Runnable action) {
        return new Button.Plain(0, 0, w, h, label, b -> action.run(), Supplier::get) {
            @Override
            public boolean shouldTakeFocusAfterInteraction() {
                return false;
            }
        };
    }

    /**
     * A colour button. In the roomy layout the colour fills it; else its label, a coloured ■, shows it.
     * {@code keepFocus}: the name box keeps the keyboard and its selection.
     */
    private Button swatch(Component label, int w, int h, int rgb, boolean keepFocus, Runnable action) {
        boolean fill = roomy;
        return new Button.Plain(0, 0, w, h, label, b -> action.run(), Supplier::get) {
            @Override
            public boolean shouldTakeFocusAfterInteraction() {
                return !keepFocus;
            }

            @Override
            protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
                if (!fill) {
                    super.extractContents(g, mouseX, mouseY, delta);
                    return;
                }
                extractDefaultSprite(g);
                g.fill(getX() + 3, getY() + 3, getRight() - 3, getBottom() - 3, 0xFF000000);
                g.fill(getX() + 4, getY() + 4, getRight() - 4, getBottom() - 4, 0xFF000000 | rgb);
            }
        };
    }

    /** A small ↺ that puts one glint setting back to its default. */
    private Button reset(int x, int y, int h, boolean active, String tip, Runnable action) {
        Button b = addRenderableWidget(undoButton(btn -> action.run()));
        b.setRectangle(14, h, x, y);
        b.active = active;
        b.setTooltip(Tooltip.create(Component.literal(tip)));
        return b;
    }

    /** The font only has a tiny ↺, so it is drawn at twice the size. */
    private Button undoButton(Button.OnPress onPress) {
        return new Button.Plain(0, 0, 14, 14, Component.literal("↺"), onPress, Supplier::get) {
            @Override
            protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
                extractDefaultSprite(g);
                g.pose().pushMatrix();
                g.pose().translate(getX() + getWidth() / 2f, getY() + getHeight() / 2f);
                g.pose().scale(2, 2);
                g.centeredText(font, getMessage(), 0, -5, active ? 0xFFFFFFFF : 0xFFA0A0A0);
                g.pose().popMatrix();
            }
        };
    }

    /** Glint without this scope's own choice: the "every item" look's, else whether Hypixel's item shimmers. */
    private boolean glintDefault() {
        String g = inherited().glint();
        return g != null ? g.equals("on") : target().hasFoil();
    }

    private boolean glintOn() {
        String g = look().glint();
        return g != null ? g.equals("on") : glintDefault();
    }

    /** Flips the glint; landing back on the default stores nothing. */
    private void toggleGlint() {
        boolean want = !glintOn();
        edit(l -> l.withGlint(want == glintDefault() ? null : want ? "on" : "off"));
        rebuildWidgets();
    }

    private void queueGlint(UnaryOperator<Looks.Look> change) {
        pendingGlint = change;
        pendingGlintAt = Util.getMillis();
    }

    private void openGlintColour() {
        flushPending();
        String current = look().glintColor() != null ? look().glintColor() : inherited().glintColor();
        openPopup(new ColorPopup(Component.literal("Glint color"), current != null ? current : PURPLE, false, hex -> {
            queueGlint(l -> l.withGlintColor(hex));
            colourReset.active = true;
            glintPick = null;
        }).onClose(this::flushPending));
    }

    /** "Custom color..." for the name: the pop-up's colour goes on the letters selected when it opened, live. */
    private void openNameColour() {
        if (nameBox == null) return;
        String base = nameBox.getValue();
        int from = nameBox.selectionStart(), to = nameBox.selectionEnd();
        String start = Names.colourAt(base, from);
        openPopup(new ColorPopup(Component.literal("Name color"), start != null ? start : "#FFFFFF", false, hex -> {
            if (nameBox != null) setName(Names.format(base, from, to, "&" + hex), true);
        }).onClose(this::flushPending));
    }

    /**
     * The open picker's colour, live: on the selected letters while the box has the keyboard (at the cursor
     * with none selected), else on the whole name. A drag recolours the same letters from where it started,
     * so codes never pile up; a colour code right before the cursor is replaced rather than added to.
     */
    private void pickNameColour(int rgb) {
        if (nameBox == null) return;
        String v = nameBox.getValue();
        boolean focused = nameBox.isFocused();
        int a = nameBox.selectionStart(), b = nameBox.selectionEnd();
        if (pickBase == null || !v.equals(pickShown) || focused && (a != pickSelA || b != pickSelB)) {
            pickBase = v;
            pickFrom = focused ? a : 0;
            pickTo = focused ? b : v.length();
            for (int n = 8; focused && a == b && n >= 2; n -= 6) {
                int p = a - n;
                if (p >= 0 && Names.codeAt(v, p) == n && Names.codeColour(v, p) >= 0) {
                    pickBase = v.substring(0, p) + v.substring(a);
                    pickFrom = pickTo = p;
                    break;
                }
            }
        }
        if (!setName(Names.format(pickBase, pickFrom, pickTo, "&" + ColorPicker.hex(rgb)), focused)) return;
        pickShown = nameBox.getValue();
        pickSelA = nameBox.selectionStart();
        pickSelB = nameBox.selectionEnd();
    }

    /** The open picker shows the colour of the letters it would change, until the user changes them. */
    private void syncPicker() {
        if (pickBase != null || namePicker.isEditing()) return;
        String v = nameBox.getValue();
        int at = nameBox.isFocused() ? nameBox.selectionStart() : 0;
        if (v == syncedValue && at == syncedAt) return;
        syncedValue = v;
        syncedAt = at;
        String c = Names.colourAt(v, at);
        namePicker.setRgb(c != null ? Integer.parseInt(c.substring(1), 16) : 0xFFFFFF);
    }

    /** Colours or styles the selected letters, or inserts the code at the cursor when nothing is selected. */
    private void applyCode(String code) {
        if (nameBox != null) {
            setName(Names.format(nameBox.getValue(), nameBox.selectionStart(), nameBox.selectionEnd(), code), true);
        }
    }

    /** A gradient on the selected letters while the box has the keyboard, else on the whole name. */
    private void applyGradient(int[] stops) {
        if (nameBox == null) return;
        String v = nameBox.getValue();
        boolean focused = nameBox.isFocused();
        int a = nameBox.selectionStart(), b = nameBox.selectionEnd();
        boolean some = focused && a != b;
        setName(Names.gradient(v, some ? a : 0, some ? b : v.length(), stops), focused);
    }

    /** The gradient presets from (x, y) in as few rows {@code w} wide as fit; returns the bottom of the last row. */
    private int gradients(int x, int y, int w, int h) {
        int perRow = Math.clamp((w + 2) / 20, 1, GRADIENTS.size());
        int gw = (w + 2) / perRow - 2;
        for (int i = 0; i < GRADIENTS.size(); i++) {
            Button b = gradientSwatch(GRADIENTS.get(i), gw, h);
            b.setPosition(x + i % perRow * (gw + 2), y + i / perRow * (h + 2));
            addRenderableWidget(b);
        }
        return y + (GRADIENTS.size() + perRow - 1) / perRow * (h + 2) - 2;
    }

    /** A gradient button filled with its colours; the name box keeps the keyboard and its selection. */
    private Button gradientSwatch(Gradient gr, int w, int h) {
        Button b = new Button.Plain(0, 0, w, h, Component.literal(gr.name()), btn -> applyGradient(gr.stops()),
            Supplier::get) {
            @Override
            public boolean shouldTakeFocusAfterInteraction() {
                return false;
            }

            @Override
            protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
                extractDefaultSprite(g);
                int x0 = getX() + 4, x1 = getRight() - 4;
                g.fill(x0 - 1, getY() + 3, x1 + 1, getBottom() - 3, 0xFF000000);
                for (int x = x0; x < x1; x++) {
                    int rgb = Names.mix(gr.stops(), (x - x0) / (float) Math.max(1, x1 - x0 - 1));
                    g.fill(x, getY() + 4, x + 1, getBottom() - 4, 0xFF000000 | rgb);
                }
            }
        };
        String name = gr.name();
        b.setTooltip(Tooltip.create(Names.parse(Names.gradient(name, 0, name.length(), gr.stops()).text())
            .append(Component.literal("\nGradient for the selection, or the whole name").withStyle(ChatFormatting.GRAY))));
        return b;
    }

    /** Shows an edited name with the same letters selected; {@code focus} gives the box the keyboard. */
    private boolean setName(Names.Edit e, boolean focus) {
        if (e.text().length() > MAX_NAME) {
            flash("The name is too long for more codes", 0xFFFF6666);
            return false;
        }
        nameBox.setValue(e.text());
        nameBox.select(e.from(), e.to());
        if (focus) setFocused(nameBox);
        return true;
    }

    /** Takes this scope's name off; the box shows the name the item has again. */
    private void resetName() {
        pendingName = null;
        edit(l -> l.withName(null));
        rebuildWidgets();
    }

    /** Saves what is still waiting, before the selection, scope or screen changes. */
    private void flushPending() {
        if (pendingName != null) {
            applyName(pendingName);
            pendingName = null;
        }
        if (pendingDye != null) {
            setDye(pendingDye);
            pendingDye = null;
        }
        if (pendingGlint != null) {
            edit(pendingGlint);
            pendingGlint = null;
        }
    }

    /** An empty box, or the name the item has anyway, stores no name of its own. */
    private void applyName(String v) {
        String n = v.trim();
        edit(l -> l.withName(n.isEmpty() || n.equals(nameBaseline) ? null : n));
    }

    /** A small "i" button; the screen draws its tooltip when it is hovered. */
    private Button info(int x, int y) {
        return info(x, y, 14, 16);
    }

    private Button info(int x, int y, int w, int h) {
        return addRenderableWidget(new Button.Builder(Component.literal("i").withStyle(ChatFormatting.AQUA), btn -> { })
            .bounds(x, y, w, h).build());
    }

    private Component scopeLabel(Cosmetics.Ident id) {
        if (id == null) return Component.literal("Pick an item");
        return byType ? Component.literal("Every " + Repo.get().typeName(id.type()))
            : Component.literal("This Item Only");
    }

    private void switchTab(Tab t) {
        flushPending();
        tab = t;
        scroll = 0;
        styleScroll = 0;
        query = ""; // a "knight" search means nothing on the Dyes tab
        rebuildWidgets();
    }

    private void select(Row r) {
        String key = keyOf(r);
        if (r == selected && key.equals(selectedKey)) return;
        selected = r;
        selectedKey = key;
        pickedChanged();
    }

    /** Another item is picked, or the picked row holds another one now: start over on it. */
    private void pickedChanged() {
        flushPending(); // still the old item's
        popup = null;
        identStack = null;
        styleScroll = 0;
        resetScope();
        pickTabFor(target());
        tooltip.resetScroll();
        rebuildWidgets();
    }

    private void refilter() {
        Catalog c = Repo.get();
        shownCatalog = c;
        List<SkinEntry> base = switch (skinFilter) {
            case HELMET -> c.helmetSkinTab;
            case PET -> c.petSkinTab;
            case ORB -> c.orbSkinTab;
            case ALL -> c.headTab;
            case PASTE -> List.of();
        };
        // Favorites first; starring a card re-sorts at once.
        skinResults = tab == Tab.SKINS ? Favorites.first(Catalog.filter(base, query), e -> Favorites.skin(e.id)) : List.of();
        dyeResults = tab == Tab.DYES ? Favorites.first(Catalog.filterDyes(c.dyeTab, query), d -> Favorites.dye(d.id)) : List.of();
    }

    // ----------------------------------------------------------- actions ---

    private void setSkin(String id) {
        Textures.preload(Repo.get().skin(id));
        edit(l -> l.withSkin(id));
    }

    private void setDye(String id) {
        edit(l -> l.withDye(id));
    }

    private void resetItem() {
        Cosmetics.Ident id = widgetsFor;
        if (id == null) return;
        pendingName = null;
        pendingDye = null;
        pendingGlint = null;
        Looks.put(byType, key(id), null);
        flash(inherited().empty() ? "Back to Hypixel's look"
            : "Cleared - the look for every " + Repo.get().typeName(id.type()) + " still applies", 0xFFFFAA66);
        rebuildWidgets();
    }

    private void toggleScope() {
        Cosmetics.Ident id = ident();
        if (id == null || id.uuid() == null) return;
        flushPending();
        byType = !byType;
        rebuildWidgets();
    }

    private void useTexture() {
        String tex = Textures.normalise(texBox.getValue());
        if (tex == null) {
            flash("Not a Minecraft skin texture", 0xFFFF6666);
            return;
        }
        setSkin(Textures.CUSTOM_PREFIX + tex);
        flash("Custom texture applied (downloading if new)", 0xFF66DD88);
    }

    /** A message in the footer for a few seconds; then the footer shows its count again. */
    private void flash(String msg, int color) {
        status = msg;
        statusColor = color;
        statusAt = Util.getMillis();
    }

    @Override
    public void onClose() {
        flushPending();
        var mc = this.minecraft;
        if (parent instanceof AbstractContainerScreen<?> acs) {
            if (mc.player != null && mc.player.containerMenu == acs.getMenu()) {
                mc.setScreen(parent);
                return;
            }
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
                return;
            }
            mc.setScreen(null);
            return;
        }
        mc.setScreen(parent);
    }

    /** Any screen change, not only Esc or Done: a menu Hypixel opens or closes, a warp, a disconnect. */
    @Override
    public void removed() {
        if (namePicker != null) namePicker.stopEditing(); // applies a half-typed hex value
        if (popup != null) {
            ColorPopup p = popup;
            popup = null;
            p.close(); // applies a half-typed hex value; its close action saves
        }
        flushPending();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ render ---

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, BG);
        if (tooSmall) return;
        panel(g, PAD, PAD, leftW, height - PAD * 2);
        if (prevW > 0) panel(g, prevX, PAD, prevW, height - PAD * 2);
        if (tab == Tab.SKINS && skinFilter != SkinFilter.PASTE || tab == Tab.DYES || tab == Tab.SAVED) {
            panel(g, midX - 2, gridY - 2, midW + 4, gridH + 4);
        }
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (tooSmall) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            g.centeredText(font, "This window is too small for SkyCosmetics.", width / 2, height / 2 - 14, TEXT);
            g.centeredText(font, "Make it bigger or lower the GUI scale.", width / 2, height / 2 - 2, MUTED);
            return;
        }
        if (shownCatalog != Repo.get()) {
            refilter(); // repo finished (re)loading while open
            nameLines.clear();
        }
        if (builtFor != OwnedItems.version() || !Objects.equals(ident(), widgetsFor)) {
            buildRows(); // finds the picked item again if it only moved
            if (!Objects.equals(ident(), widgetsFor)) pickedChanged();
        }
        if (pendingDye != null && Util.getMillis() - pendingAt > 150) {
            setDye(pendingDye);
            pendingDye = null;
        }
        if (pendingName != null && Util.getMillis() - pendingNameAt > 300) {
            applyName(pendingName);
            pendingName = null;
        }
        if (pendingGlint != null && Util.getMillis() - pendingGlintAt > 150) {
            edit(pendingGlint);
            pendingGlint = null;
        }
        if (popup != null && popup.isClosed()) popup = null;
        // Under an open pop-up nothing is hovered: no highlights, no tooltips.
        int mx = popup != null ? -10000 : mouseX, my = popup != null ? -10000 : mouseY;
        super.extractRenderState(g, mx, my, delta);
        long tick = Util.getMillis() / 50;
        if (tick != budgetTick) {
            budgetTick = tick;
            firstBudget = FIRSTS_PER_TICK;
            frameBudget = FRAMES_PER_TICK;
        }
        summaryShown = false;
        modelScale = petSize = 0;
        if (keyTip) g.outline(settings.getX() - 1, settings.getY() - 1, settings.getWidth() + 2, settings.getHeight() + 2, ACCENT);

        g.text(font, Component.literal("SkyCosmetics").withStyle(ChatFormatting.BOLD), PAD + 6, PAD + 9, ACCENT);
        drawList(g, mx, my);
        if (prevW > 0) drawPreview(g, mx, my, mouseX, mouseY, tick);
        else drawCompactPreview(g, mx, my, tick);

        switch (tab) {
            case DYES -> drawDyes(g, mx, my, tick);
            case STYLE -> drawStyle(g, mx, my);
            case SAVED -> drawSaved(g, mx, my, tick);
            default -> {
                if (skinFilter == SkinFilter.PASTE) drawPaste(g);
                else drawSkins(g, mx, my, tick);
            }
        }

        String foot;
        Catalog c = Repo.get();
        if (c.skins.isEmpty()) foot = Repo.loading() ? "Loading skins..." : "No skin data yet - retrying";
        else foot = switch (tab) {
            case DYES -> Names.count(dyeResults.size(), "dye") + dyeNote();
            case SKINS -> skinFilter == SkinFilter.PASTE ? "" : Names.count(skinResults.size(), "skin");
            default -> "";
        };
        // One footer line: a fresh message, else the count. A clipped message shows whole on hover.
        boolean fresh = !status.isEmpty() && Util.getMillis() - statusAt < 4000;
        int fy = height - PAD - 9;
        footShown = clip(fresh ? status : foot, midW);
        g.text(font, footShown, midX, fy, fresh ? statusColor : MUTED);
        if (fresh && font.width(status) > midW && mx >= midX && mx < midX + midW && my >= fy - 1 && my < fy + 9) {
            g.setTooltipForNextFrame(font, Component.literal(status), mx, my);
        }
        if (itemsInfo != null && itemsInfo.isHovered()) g.setComponentTooltipForNextFrame(font, Settings.storedItems ? ITEMS_HELP : ITEMS_HELP_STORED_OFF, mx, my);
        codesShown = nameInfo != null && nameInfo.isHovered();
        if (codesShown) {
            g.nextStratum();
            g.tooltip(font, CodesTooltip.lines(height), mx, my, DefaultTooltipPositioner.INSTANCE, null);
        }
        if (popup != null) popup.render(g, mouseX, mouseY);
    }

    /** Only leather takes dye: other armour is drawn as the leather piece for its slot, heads not at all. */
    private String dyeNote() {
        ItemStack s = target();
        if (s.isEmpty()) return "";
        if (s.get(DataComponents.EQUIPPABLE) == null || s.is(Items.PLAYER_HEAD)) return " - armor only";
        return s.getItem().toString().contains("leather") ? "" : " - shown as leather";
    }

    // -------------------------------------------------------------- left ---

    private int rowsHeight() {
        int h = 0;
        for (Row r : rows) h += r.isHeader() ? HEADER_H : ROW_H;
        return h;
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = PAD + 4, w = leftW - 8;
        if (rows.isEmpty()) {
            g.textWithWordWrap(font, Component.literal("No items yet. Wear or hold a SkyBlock item, or open your "
                + "wardrobe, pets or ender chest once."), x + 2, listTop + 4, w - 4, MUTED);
            return;
        }
        int maxScroll = Math.max(0, rowsHeight() - (listBottom - listTop));
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        g.enableScissor(x, listTop, x + w, listBottom);
        int y = listTop - listScroll;
        Row hovered = null;
        for (Row r : rows) {
            int h = r.isHeader() ? HEADER_H : ROW_H;
            if (y + h >= listTop && y < listBottom) {
                if (r.isHeader()) {
                    g.text(font, r.header(), x + 2, y + 2, ACCENT);
                } else {
                    boolean sel = r == selected;
                    boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                                   && mouseY >= listTop && mouseY < listBottom;
                    if (sel) g.fill(x, y, x + w, y + h, 0x50FFC94A);
                    else if (over) g.fill(x, y, x + w, y + h, 0x30FFFFFF);
                    ItemStack s = r.stack().get();
                    g.item(s, x + 2, y + 2);
                    g.text(font, clip(s.getStyledHoverName().getString(), w - 24), x + 21, y + 2, TEXT);
                    if (!r.sub().isEmpty()) small(g, r.sub(), x + 21, y + 12, MUTED);
                    if (over) hovered = r;
                }
            }
            y += h;
        }
        g.disableScissor();
        if (rowsHeight() > listBottom - listTop) {
            int track = listBottom - listTop;
            int thumb = Math.max(12, track * track / rowsHeight());
            int ty = listTop + (track - thumb) * listScroll / Math.max(1, maxScroll);
            g.fill(x + w - 2, ty, x + w, ty + thumb, 0x80FFFFFF);
        }
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(hovered.stack().get().getStyledHoverName());
            if (!hovered.sub().isEmpty()) tip.add(Component.literal(hovered.sub()).withStyle(ChatFormatting.GRAY));
            if (hovered.role().startsWith("uuid:")) {
                tip.add(Component.literal("Right-click to remove from this list").withStyle(ChatFormatting.DARK_GRAY));
            }
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    private void small(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(SMALL, SMALL);
        g.text(font, text, 0, 0, color);
        g.pose().popMatrix();
    }

    // ----------------------------------------------------------- preview ---

    /** Bottom of the preview column's free space: the summary and the buttons sit under it. */
    private int summaryTop() {
        return height - PAD - 48 - 4 - 4 * SUMMARY_H;
    }

    /**
     * Right column: the item's full tooltip (icon, styled name, lore), the
     * player model in the space left, what is applied, then the buttons. The
     * model gets at least a third of the room; a longer tooltip scrolls.
     */
    private void drawPreview(GuiGraphicsExtractor g, int mouseX, int mouseY, int modelX, int modelY, long tick) {
        int x = prevX + 6, w = prevW - 12;
        if (ident() == null) {
            g.textWithWordWrap(font, Component.literal("Pick an item on the left"), x, PAD + 8, w, MUTED);
            return;
        }
        int top = PAD + 6, summaryTop = summaryTop();
        int room = summaryTop - 6 - top;
        int used = tooltip.render(g, font, target(), x, top, w, room - Math.max(64, room * 2 / 7));
        int modelTop = top + used + 4, modelBottom = summaryTop - 6;
        if (minecraft.player != null && modelBottom - modelTop >= 30) drawModel(g, modelTop, modelBottom, w, modelX, modelY);
        drawSummary(g, x, summaryTop, x + w, mouseX, mouseY, tick);
    }

    /**
     * The player model, standing at the bottom of its box with headroom above for hats and other cosmetics
     * (the box clips what reaches past it). A picked pet floats beside the shoulder, wearing its look: only
     * its item, drawn here, so it shows whatever the world or a "hide pets" setting does.
     */
    private void drawModel(GuiGraphicsExtractor g, int top, int bottom, int w, int mouseX, int mouseY) {
        int h = bottom - top;
        // As big as the room allows, but narrow enough to stay inside the column while it turns to the mouse.
        int scale = Math.max(12, Math.min(w * 2 / 5, (h - 6) * 5 / 12));
        float lift = (h / 2f - 3) / scale - 0.9f; // feet 3 px above the bottom, 0.6 blocks free above the head
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, prevX + 4, top, prevX + prevW - 4, bottom,
            scale, lift, mouseX, mouseY, minecraft.player);
        modelY0 = top;
        modelY1 = bottom;
        modelScale = scale;
        Cosmetics.Ident id = ident();
        if (id == null || !id.type().startsWith("PET:")) return;
        // Whole multiples of the item size stay crisp; the bob is snapped to screen pixels.
        int size = scale >= 40 ? 48 : 32;
        int gs = Math.max(1, (int) Math.round(minecraft.getWindow().getGuiScale()));
        float bob = Math.round((float) Math.sin(Util.getMillis() / 500.0) * 2 * gs) / (float) gs;
        int px = Math.min(prevX + prevW / 2 + scale / 2, prevX + prevW - 6 - size);
        int py = Math.max(top + 3, bottom - 3 - scale * 17 / 10 - size / 2);
        g.pose().pushMatrix();
        g.pose().translate(px, py + bob);
        g.pose().scale(size / 16f, size / 16f);
        g.item(target(), 0, 0);
        g.pose().popMatrix();
        petX = px;
        petY = py;
        petSize = size;
    }

    /**
     * Narrow windows: the item (hover for its tooltip) and the summary at the bottom of the left column.
     * Short ones keep the list long: only the item beside the scope switch, with the summary in its tooltip.
     */
    private void drawCompactPreview(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        if (ident() == null) return;
        if (slim) {
            bigItem(g, target(), PAD + 4, height - PAD - 44, 20, mouseX, mouseY, () -> summaryLines(tick));
            return;
        }
        int x = PAD + 6, y = listBottom + 4;
        bigItem(g, target(), x, y, 24, mouseX, mouseY, null);
        drawSummary(g, x + 28, y, PAD + leftW - 6, mouseX, mouseY, tick);
    }

    /** The item at {@code size}; on hover its full tooltip (new name, real lore), then {@code more} lines if any. */
    private void bigItem(GuiGraphicsExtractor g, ItemStack s, int x, int y, int size, int mouseX, int mouseY,
                         Supplier<List<Component>> more) {
        g.fill(x, y, x + size, y + size, 0x40000000);
        float scale = (size - 4) / 16f;
        g.pose().pushMatrix();
        g.pose().translate(x + 2, y + 2);
        g.pose().scale(scale, scale);
        g.item(s, 0, 0);
        g.pose().popMatrix();
        if (mouseX < x || mouseX >= x + size || mouseY < y || mouseY >= y + size) return;
        if (more == null) {
            g.setTooltipForNextFrame(font, s, mouseX, mouseY);
            return;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, s));
        lines.add(Component.empty());
        lines.addAll(more.get());
        g.setTooltipForNextFrame(font, lines, s.getTooltipImage(), mouseX, mouseY);
    }

    /** Where the model was drawn last frame, and the pet beside it; a size of 0 when it was not. */
    private int modelY0, modelY1, modelScale, petX, petY, petSize;

    /** Skin, Dye, Name and Glint lines, each with its own clear [x]; "(all)" marks a look inherited from the whole type. */
    private int summaryY, summaryRight;
    /** Drawn last frame: in short narrow windows it is not, and its old place must not take clicks. */
    private boolean summaryShown;

    private void drawSummary(GuiGraphicsExtractor g, int x, int y, int right, int mouseX, int mouseY, long tick) {
        summaryShown = true;
        summaryY = y;
        summaryRight = right;
        Looks.Look l = look();
        Looks.Look t = inherited();

        Value skin = skinValue(l, t);
        line(g, x, y, "Skin", skin.text(), skin.color(), skin.own(), right, mouseX, mouseY);
        y += SUMMARY_H;

        DyeEntry dye = shownDye(l, t);
        Value dv = dyeValue(l, t, dye);
        if (dye != null) g.fill(x + 30, y, x + 37, y + 7, 0xFF000000 | dye.rgbAt(tick, 0));
        line(g, x, y, "Dye", (dye != null ? "   " : "") + dv.text(), dv.color(), dv.own(), right, mouseX, mouseY);
        y += SUMMARY_H;

        String name = l.name() != null ? l.name() : t.name();
        g.text(font, "Name", x, y, MUTED);
        if (name == null) {
            g.text(font, "Hypixel's", x + 30, y, MUTED);
        } else {
            g.text(font, styledName(name + all(l.name(), t.name()), right - x - 42), x + 30, y, TEXT);
        }
        clearMark(g, y, l.name() != null, right, mouseX, mouseY);
        y += SUMMARY_H;

        Value glint = glintValue(l, t);
        line(g, x, y, "Glint", glint.text(), glint.color(), glint.own(), right, mouseX, mouseY);
    }

    /** The same lines for a tooltip, built only while it shows. */
    private List<Component> summaryLines(long tick) {
        Looks.Look l = look(), t = inherited();
        DyeEntry dye = shownDye(l, t);
        Value skin = skinValue(l, t), dv = dyeValue(l, t, dye), glint = glintValue(l, t);
        String name = l.name() != null ? l.name() : t.name();
        MutableComponent dyeLine = label("Dye");
        if (dye != null) dyeLine.append(Component.literal("■ ").withColor(dye.rgbAt(tick, 0)));
        return List.of(label("Skin").append(Component.literal(skin.text()).withColor(skin.color() & 0xFFFFFF)),
            dyeLine.append(Component.literal(dv.text()).withColor(dv.color() & 0xFFFFFF)),
            label("Name").append(name == null ? Component.literal("Hypixel's").withColor(MUTED & 0xFFFFFF)
                : Names.parse(name + all(l.name(), t.name()))),
            label("Glint").append(Component.literal(glint.text()).withColor(glint.color() & 0xFFFFFF)));
    }

    private static MutableComponent label(String label) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY);
    }

    /** One summary value: its text, colour, and whether this scope set it (then it has an [x]). */
    private record Value(String text, int color, boolean own) {}

    private static Value skinValue(Looks.Look l, Looks.Look t) {
        String id = l.skin() != null ? l.skin() : t.skin();
        SkinEntry e = Repo.get().skin(id);
        return new Value((e != null ? e.name : id != null ? "Unknown" : "Hypixel's") + all(l.skin(), t.skin()),
            e != null ? e.color : MUTED, l.skin() != null);
    }

    private static DyeEntry shownDye(Looks.Look l, Looks.Look t) {
        return Repo.get().dye(l.dye() != null ? l.dye() : t.dye());
    }

    private static Value dyeValue(Looks.Look l, Looks.Look t, DyeEntry dye) {
        String id = l.dye() != null ? l.dye() : t.dye();
        return new Value((dye != null ? dye.name : id != null ? "Unknown" : "Hypixel's") + all(l.dye(), t.dye()),
            dye != null ? dye.nameColor : MUTED, l.dye() != null);
    }

    private static Value glintValue(Looks.Look l, Looks.Look t) {
        String glint = l.glint() != null ? l.glint() : t.glint();
        String colour = l.glintColor() != null ? l.glintColor() : t.glintColor();
        Float speed = l.glintSpeed() != null ? l.glintSpeed() : t.glintSpeed();
        Float strength = l.glintStrength() != null ? l.glintStrength() : t.glintStrength();
        StringBuilder text = new StringBuilder(glint == null ? "Hypixel's" : "on".equals(glint) ? "On" : "Off");
        if (colour != null) text.append("  ").append(colour);
        if (speed != null) text.append("  speed ").append(SpeedSlider.format(speed));
        if (strength != null) text.append("  strength ").append(SpeedSlider.format(strength));
        boolean own = l.glint() != null || l.glintColor() != null || l.glintSpeed() != null || l.glintStrength() != null;
        boolean any = glint != null || colour != null || speed != null || strength != null;
        return new Value(text + (!own && any ? " (all)" : ""), any ? TEXT : MUTED, own);
    }

    private static String all(String own, String type) {
        return own == null && type != null ? " (all)" : "";
    }

    /** The custom name in its own colours and styles, cut to fit; rebuilt only when it or the width changes. */
    private FormattedCharSequence styledName(String raw, int px) {
        if (!raw.equals(summaryNameRaw) || px != summaryNameWidth) {
            summaryNameRaw = raw;
            summaryNameWidth = px;
            summaryName = NameBox.fit(font, Names.parse(raw), px);
        }
        return summaryName;
    }

    private void line(GuiGraphicsExtractor g, int x, int y, String label, String value, int color, boolean clearable,
                      int right, int mouseX, int mouseY) {
        g.text(font, label, x, y, MUTED);
        String shown = clip(value, right - x - 42);
        g.text(font, shown, x + 30, y, color);
        clearMark(g, y, clearable, right, mouseX, mouseY);
        if (shown.length() != value.length() && mouseX >= x + 30 && mouseX < right - 10 && mouseY >= y - 1 && mouseY < y + 9) {
            g.setTooltipForNextFrame(font, Component.literal(value), mouseX, mouseY); // cut: the whole value on hover
        }
    }

    private void clearMark(GuiGraphicsExtractor g, int y, boolean clearable, int right, int mouseX, int mouseY) {
        if (!clearable) return;
        int cx = right - 8;
        boolean over = mouseX >= cx && mouseX < cx + 8 && mouseY >= y - 1 && mouseY < y + 9;
        g.text(font, "x", cx + 1, y, over ? 0xFFFF6666 : 0xFF9A5A5A);
        if (over) g.setTooltipForNextFrame(font, Component.literal("Remove"), mouseX, mouseY);
    }

    // ------------------------------------------------------------- cards ---

    /**
     * A card's name, wrapped on words into at most {@link #labelLines} lines at card width; only a last line
     * that still does not fit is cut. Every card on the Skins tab is a skin, so the word is left off
     * ("Knight Skin (Aurora)" reads "Knight (Aurora)"); the tooltip has the full name. Computed once per entry.
     */
    private String[] lines(String id, String name) {
        String[] cached = nameLines.get(id); // not computeIfAbsent: its lambda would be a new object per card per frame
        if (cached != null) return cached;
        String label = name.replace(" Skin (", " (");
        if (label.endsWith(" Skin") && label.length() > 5) label = label.substring(0, label.length() - 5);
        int max = (int) ((cardW - 4) / labelScale);
        String[] words = label.split(" ");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < words.length && out.size() < labelLines) {
            StringBuilder b = new StringBuilder(words[i++]);
            while (i < words.length && font.width(b + " " + words[i]) <= max) b.append(' ').append(words[i++]);
            out.add(b.toString());
        }
        if (i < words.length) {
            out.set(out.size() - 1, out.getLast() + " " + String.join(" ", Arrays.copyOfRange(words, i, words.length)));
        }
        out.replaceAll(l -> clip(l, max));
        String[] lines = out.toArray(String[]::new);
        nameLines.put(id, lines);
        return lines;
    }

    /** Under the icon; at GUI scale 3 and up one step smaller, which keeps it crisp. Centred on a whole pixel. */
    private void drawCardName(GuiGraphicsExtractor g, int cx, int cy, String id, String name, int color) {
        String[] l = lines(id, name);
        g.pose().pushMatrix();
        g.pose().translate(cx + cardW / 2, cy + (labelLines > 2 ? 29 : 30));
        g.pose().scale(labelScale, labelScale);
        for (int i = 0; i < l.length; i++) g.centeredText(font, l[i], 0, i * labelStep, color);
        g.pose().popMatrix();
    }

    private void drawIcon(GuiGraphicsExtractor g, ItemStack icon, int cx, int cy) {
        g.pose().pushMatrix();
        g.pose().translate(cx + (cardW - 24) / 2, cy + 3);
        g.pose().scale(1.5f, 1.5f);
        g.item(icon, 0, 0);
        g.pose().popMatrix();
    }

    private void cardBackground(GuiGraphicsExtractor g, int cx, int cy, boolean current, boolean hover) {
        if (current) {
            g.fill(cx + 1, cy + 1, cx + cardW - 1, cy + cardH - 1, 0x40FFC94A);
            g.outline(cx + 1, cy + 1, cardW - 2, cardH - 2, GOLD);
        } else if (hover) {
            g.fill(cx + 1, cy + 1, cx + cardW - 1, cy + cardH - 1, 0x30FFFFFF);
        }
    }

    /** A small "play" triangle in a card's top-right corner: animated (gray: frames not known yet). */
    private static void animatedBadge(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 4; i++) g.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
    }

    /**
     * The grid shows many animated skins, so it asks for textures on a budget: per game tick a few new first
     * frames (the cards in view, then the next rows), and fewer animation frames, only while the grid is still.
     * A frame is asked for when its card shows it, never every frame of every skin at once.
     */
    private void drawSkins(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        List<SkinEntry> list = skinResults;
        int visible = gridH / cardH;
        clampScroll(list.size());
        SkinEntry hovered = null;
        String current = look().skin();
        int start = scroll * cols;
        int end = Math.min(list.size(), start + (visible + 1) * cols);
        boolean still = tick - scrolledAt > STILL_TICKS;
        gridItems = gridChecks = 0;
        for (int i = start; i < end; i++) ask(list.get(i).textures[0]); // every card's first frame before any animation
        g.enableScissor(midX, gridY, midX + midW, gridY + gridH);
        for (int i = start; i < end; i++) {
            SkinEntry e = list.get(i);
            int cx = midX + (i % cols) * cardW;
            int cy = gridY + (i / cols - scroll) * cardH;
            boolean hover = inCard(mouseX, mouseY, cx, cy);
            cardBackground(g, cx, cy, e.id.equals(current), hover);
            int frame = readyFrame(e, tick, still);
            if (frame >= 0) {
                drawIcon(g, e.icon(frame), cx, cy);
                gridItems++;
            }
            drawCardName(g, cx, cy, e.id, e.name, e.color);
            if (e.animated() || e.missingFrames) animatedBadge(g, cx + cardW - 8, cy + 3, e.animated() ? ACCENT : MUTED);
            boolean fav = Favorites.skin(e.id);
            if (fav) star(g, cx, cy);
            int ly = fav ? cy + 12 : cy + 3; // under the star
            if (e.learned) g.fill(cx + 3, ly, cx + 6, ly + 3, 0xFF66DD88);
            if (hover) hovered = e;
        }
        g.disableScissor();
        // Warm the next rows with what the budget leaves, so scrolling down rarely shows empty cards.
        for (int i = end; i < Math.min(list.size(), end + cols * 2); i++) ask(list.get(i).textures[0]);
        if (list.isEmpty() && !Repo.get().skins.isEmpty()) {
            g.centeredText(font, "Nothing matches \"" + query + "\"", midX + midW / 2, gridY + 20, MUTED);
        }
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name).withColor(hovered.color & 0xFFFFFF));
            if (hovered.animated()) {
                tip.add(Component.literal("Animated: " + hovered.textures.length + " frames").withStyle(ChatFormatting.LIGHT_PURPLE));
            } else if (hovered.missingFrames) {
                tip.add(Component.literal("Animated on Hypixel; frames not known yet").withStyle(ChatFormatting.GRAY));
            }
            if (hovered.learned) tip.add(Component.literal("Learned in game").withStyle(ChatFormatting.GREEN));
            tip.add(Component.literal(hovered.id.equals(current) ? "Click to remove" : "Click to apply")
                .withStyle(ChatFormatting.YELLOW));
            tip.add(favoriteHint(Favorites.skin(hovered.id)));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    /**
     * Never show a frame that has not downloaded yet (that would be a Steve head): the wanted frame once it
     * is ready, else the last one this card showed, else the first; -1 while even that one loads. Animation
     * frames are only fetched when {@code animate}.
     */
    private int readyFrame(SkinEntry e, long tick, boolean animate) {
        Integer last = lastFrame.get(e.id); // a card that showed a frame had its first one ready then
        if (last == null && !ready(e.textures[0], true)) return -1;
        if (!e.animated()) return 0;
        int want = animate ? e.frameAt(tick) : -1;
        if (want == 0 || want > 0 && ready(e.textures[want], false)) {
            lastFrame.put(e.id, want);
            return want;
        }
        return last != null ? last : 0;
    }

    /** Whether a texture is loaded; one not asked for yet is only asked for within this tick's budget. */
    private boolean ready(String texture, boolean first) {
        gridChecks++;
        if (!asked.contains(texture)) {
            if (first ? firstBudget <= 0 : frameBudget <= 0) return false;
            if (first) firstBudget--;
            else frameBudget--;
            asked.add(texture);
        }
        return Textures.ready(texture);
    }

    /** Starts loading a first frame if the budget allows; nothing more. */
    private void ask(String texture) {
        if (firstBudget > 0 && !asked.contains(texture)) ready(texture, true);
    }

    private void drawDyes(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        List<DyeEntry> list = dyeResults;
        int visible = gridH / cardH;
        clampScroll(list.size());
        DyeEntry hovered = null;
        String current = look().dye();
        g.enableScissor(midX, gridY, midX + midW, gridY + gridH);
        int start = scroll * cols;
        int end = Math.min(list.size(), start + (visible + 1) * cols);
        for (int i = start; i < end; i++) {
            DyeEntry d = list.get(i);
            int cx = midX + (i % cols) * cardW;
            int cy = gridY + (i / cols - scroll) * cardH;
            boolean hover = inCard(mouseX, mouseY, cx, cy);
            int rgb = 0xFF000000 | d.rgbAt(tick, 0);
            cardBackground(g, cx, cy, d.id.equals(current), hover);
            ItemStack icon = d.icon();
            if (icon != null) {
                drawIcon(g, icon, cx, cy);
            } else {
                g.fill(cx + (cardW - 20) / 2, cy + 5, cx + (cardW + 20) / 2, cy + 25, rgb);
                g.outline(cx + (cardW - 20) / 2, cy + 5, 20, 20, 0xFF000000);
            }
            g.fill(cx + 8, cy + 27, cx + cardW - 8, cy + 29, rgb); // live colour, animated dyes cycle here
            drawCardName(g, cx, cy + 1, d.id, d.name.replace(" Dye", ""), d.nameColor);
            if (Favorites.dye(d.id)) star(g, cx, cy);
            if (hover) hovered = d;
        }
        g.disableScissor();
        if (hovered != null) {
            int rgb = hovered.rgbAt(tick, 0);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name).withColor(hovered.nameColor & 0xFFFFFF));
            tip.add(Component.literal(String.format(Locale.ROOT, "#%06X", rgb)).withColor(rgb));
            if (hovered.animated()) {
                tip.add(Component.literal("Animated: " + hovered.colors.length + " colors, ripples boots to helmet")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            tip.add(Component.literal(hovered.id.equals(current) ? "Click to remove" : "Click to apply")
                .withStyle(ChatFormatting.YELLOW));
            tip.add(favoriteHint(Favorites.dye(hovered.id)));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    /** A gold star in a card's top-left corner: a favorite, listed first. */
    private void star(GuiGraphicsExtractor g, int cx, int cy) {
        g.text(font, "★", cx + 3, cy + 2, GOLD);
    }

    /** The favorite line of a card's tooltip: the key in yellow, the star in gold, the effect in gray. */
    private static Component favoriteHint(boolean favorite) {
        if (favorite) {
            return Component.literal("★ Favorite").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Right-click").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" to remove").withStyle(ChatFormatting.GRAY));
        }
        return Component.literal("Right-click").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" to favorite ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("★").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" (listed first)").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Says what a right-click on a card did, and moves the card to its place in the list at once. */
    private boolean starred(boolean on, String name) {
        refilter();
        flash(on ? "★ " + name + " is a favorite: listed first" : name + " is no longer a favorite", on ? GOLD : MUTED);
        return true;
    }

    private void drawPaste(GuiGraphicsExtractor g) {
        g.textWithWordWrap(font, Component.literal("Paste a texture from minecraft-heads.com, a skin URL, or any "
            + "skull's Value. Works on any item, not only helmets."), midX, gridY + 4, Math.min(midW, 360), MUTED);
    }

    private void drawStyle(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (ident() == null) {
            g.text(font, "Pick an item on the left", midX, gridY + 6, MUTED);
            return;
        }
        int top = PAD + 22, bottom = styleBottom(), dy = styleScroll;
        boolean inView = mouseY >= top && mouseY < bottom;
        g.enableScissor(midX, top, midX + midW, bottom);
        g.text(font, "Name", midX, nameTop + 3 - dy, TEXT);
        g.text(font, "Enchant glint", midX, glintTop - dy, TEXT);
        // Live: the glint shows here. 3x in the roomy layout, 2x in the compact one.
        bigItem(g, target(), glintIconX, glintIconY - dy, glintIcon, inView ? mouseX : -1, mouseY, null);
        if (glintPick != null && glintPick.visible) {
            g.outline(glintPick.getX() - 1, glintPick.getY() - 1, glintPick.getWidth() + 2, glintPick.getHeight() + 2,
                0xFFFFFFFF);
        }
        g.disableScissor();
        if (namePicker != null) { // roomy: never scrolled
            syncPicker();
            namePicker.render(g, mouseX, mouseY);
            for (int i = 0; i < pickerNote.size(); i++) {
                g.text(font, pickerNote.get(i), pickerNoteAt[0], pickerNoteAt[1] + i * 10, MUTED, false);
            }
        }
        if (styleMax > 0) {
            int track = bottom - top, thumb = Math.max(12, track * track / (track + styleMax));
            int ty = top + (track - thumb) * styleScroll / styleMax;
            g.fill(midX + midW + 3, top, midX + midW + 5, bottom, 0x30FFFFFF);
            g.fill(midX + midW + 3, ty, midX + midW + 5, ty + thumb, 0x80FFFFFF);
        }
    }

    private List<Map.Entry<String, Looks.Look>> savedRows() {
        List<Map.Entry<String, Looks.Look>> out = new ArrayList<>();
        for (var e : Looks.uuidLooks().entrySet()) out.add(Map.entry("I" + e.getKey(), e.getValue()));
        for (var e : Looks.typeLooks().entrySet()) out.add(Map.entry("T" + e.getKey(), e.getValue()));
        return out;
    }

    private void drawSaved(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        List<Map.Entry<String, Looks.Look>> saved = savedRows();
        int visible = gridH / SAVED_H;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, saved.size() - visible)));
        if (saved.isEmpty()) {
            g.centeredText(font, "Nothing changed yet", midX + midW / 2, gridY + 20, MUTED);
            return;
        }
        Catalog c = Repo.get();
        g.enableScissor(midX, gridY, midX + midW, gridY + gridH);
        for (int i = scroll; i < Math.min(saved.size(), scroll + visible + 1); i++) {
            var r = saved.get(i);
            Looks.Look l = r.getValue();
            int y = gridY + (i - scroll) * SAVED_H;
            boolean type = r.getKey().charAt(0) == 'T';
            String label = l.label() != null ? l.label() : r.getKey().substring(1);
            SkinEntry s = c.skin(l.skin());
            DyeEntry d = c.dye(l.dye());
            int frame = s != null ? readyFrame(s, tick, true) : -1;
            if (frame >= 0) g.item(s.icon(frame), midX + 2, y + 3);
            else if (s == null && d != null) g.fill(midX + 4, y + 5, midX + 16, y + 17, 0xFF000000 | d.rgbAt(tick, 0));
            g.text(font, clip(label, midW - 90), midX + 24, y + 3, TEXT);
            List<String> parts = new ArrayList<>();
            parts.add(type ? "Every item of this type" : "This Item Only");
            if (l.skin() != null) parts.add(s != null ? s.name : "?");
            if (l.dye() != null) parts.add(d != null ? d.name : "custom dye");
            if (l.name() != null) parts.add("renamed");
            if (l.glint() != null) parts.add("glint " + l.glint());
            g.text(font, clip(String.join("  ·  ", parts), midW - 90), midX + 24, y + 13, MUTED);
            int bx = midX + midW - 52;
            boolean hover = mouseX >= bx && mouseX < bx + 48 && mouseY >= y + 4 && mouseY < y + 18;
            g.fill(bx, y + 4, bx + 48, y + 18, hover ? 0xFF803040 : 0xFF3A2228);
            g.centeredText(font, "Remove", bx + 24, y + 7, 0xFFFFD0D0);
        }
        g.disableScissor();
    }

    private boolean inCard(int mx, int my, int cx, int cy) {
        return mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH && my >= gridY && my < gridY + gridH;
    }

    private void clampScroll(int size) {
        int visible = Math.max(1, gridH / cardH);
        int total = (size + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - visible)));
    }

    private String clip(String s, int px) {
        if (px <= 0) return "";
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("..."))) + "...";
    }

    // ------------------------------------------------------------- input ---

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (popup != null) {
            popup.mouseClicked(mx, my, event.button());
            if (popup.isClosed()) popup = null;
            return true;
        }
        // Before the widgets: a click elsewhere also ends typing in its hex box. The name box keeps its selection.
        if (namePicker != null && namePicker.mouseClicked(mx, my, event.button())) return true;
        if (super.mouseClicked(event, doubleClick)) return true;
        if (tooSmall) return false;
        if (nameBox != null && getFocused() == nameBox) setFocused(null); // a click elsewhere shows the styled name
        if (mx < midX) return clickLeft(mx, my, event.button());
        if (clickSummary(mx, my)) return true;
        if (mx >= midX + midW || my < gridY || my >= gridY + gridH) return false;

        if (tab == Tab.SAVED) {
            List<Map.Entry<String, Looks.Look>> saved = savedRows();
            int i = scroll + (int) ((my - gridY) / SAVED_H);
            int bx = midX + midW - 52;
            int rowY = gridY + (i - scroll) * SAVED_H;
            if (i < saved.size() && mx >= bx && mx < bx + 48 && my >= rowY + 4 && my < rowY + 18) {
                String k = saved.get(i).getKey();
                Looks.put(k.charAt(0) == 'T', k.substring(1), null);
                flash("Removed", 0xFFFFAA66);
                return true;
            }
            return false;
        }
        if (ident() == null) return false;
        boolean grid = tab == Tab.SKINS && skinFilter != SkinFilter.PASTE || tab == Tab.DYES;
        if (!grid) return false;

        int col = (int) ((mx - midX) / cardW);
        if (col >= cols) return false;
        int i = (scroll + (int) ((my - gridY) / cardH)) * cols + col;
        if (tab == Tab.DYES) {
            if (i < 0 || i >= dyeResults.size()) return false;
            DyeEntry d = dyeResults.get(i);
            if (event.button() == 1) return starred(Favorites.toggleDye(d.id), d.name);
            pendingDye = null;
            setDye(d.id.equals(look().dye()) ? null : d.id);
        } else {
            if (i < 0 || i >= skinResults.size()) return false;
            SkinEntry e = skinResults.get(i);
            if (event.button() == 1) return starred(Favorites.toggleSkin(e.id), e.name);
            setSkin(e.id.equals(look().skin()) ? null : e.id);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (popup != null) return popup.mouseDragged(event.x(), event.y(), event.button());
        if (namePicker != null && namePicker.mouseDragged(event.x(), event.y(), event.button())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (popup != null) {
            popup.mouseReleased(event.x(), event.y(), event.button());
            super.mouseReleased(event); // ends the screen's own drag state; a release never presses a button
            return true;
        }
        if (namePicker != null && namePicker.mouseReleased(event.x(), event.y(), event.button())) {
            pickBase = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (popup != null) {
            popup.keyPressed(event);
            if (popup.isClosed()) popup = null;
            return true;
        }
        if (namePicker != null && namePicker.isEditing()) return namePicker.keyPressed(event);
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (popup != null) return popup.charTyped(event);
        if (namePicker != null && namePicker.isEditing()) return namePicker.charTyped(event);
        return super.charTyped(event);
    }

    /** The item list: left-click picks, right-click forgets. */
    private boolean clickLeft(double mx, double my, int button) {
        int x = PAD + 4, w = leftW - 8;
        if (mx >= x && mx < x + w && my >= listTop && my < listBottom) {
            int y = listTop - listScroll;
            for (Row r : rows) {
                int h = r.isHeader() ? HEADER_H : ROW_H;
                if (my >= y && my < y + h) {
                    if (r.isHeader()) return true;
                    if (button == 1 && r.role().startsWith("uuid:")) {
                        OwnedItems.forget(r.role().substring(5));
                        rebuildWidgets();
                    } else {
                        select(r);
                    }
                    return true;
                }
                y += h;
            }
            return true;
        }
        return clickSummary(mx, my);
    }

    /** The [x] at the end of the Skin, Dye, Name and Glint lines. */
    private boolean clickSummary(double mx, double my) {
        if (!summaryShown || ident() == null) return false;
        int cx = summaryRight - 8;
        if (mx < cx || mx >= cx + 8 || my < summaryY - 1) return false;
        int line = (int) ((my - summaryY + 1) / SUMMARY_H);
        Looks.Look l = look();
        switch (line) {
            case 0 -> {
                if (l.skin() == null) return false;
                setSkin(null);
            }
            case 1 -> {
                if (l.dye() == null) return false;
                pendingDye = null;
                setDye(null);
            }
            case 2 -> {
                if (l.name() == null) return false;
                pendingName = null;
                edit(look -> look.withName(null));
                rebuildWidgets(); // the name box shows the name it had
            }
            case 3 -> {
                if (l.glint() == null && l.glintColor() == null && l.glintSpeed() == null && l.glintStrength() == null) {
                    return false;
                }
                edit(look -> look.withGlint(null).withGlintColor(null).withGlintSpeed(null).withGlintStrength(null));
                rebuildWidgets();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (tooSmall) return false;
        if (popup != null) return true;
        if (prevW > 0 && ident() != null && tooltip.mouseScrolled(mx, my, dy)) return true;
        if (tab == Tab.STYLE && styleMax > 0 && mx >= midX && mx < midX + midW) {
            styleScroll = Math.clamp(styleScroll - (long) Math.signum(dy) * 20, 0, styleMax);
            placeStyle();
            return true;
        }
        int step = minecraft.hasShiftDown() ? 5 : 1;
        if (mx >= midX && mx < midX + midW && my >= gridY && my < gridY + gridH) {
            scroll = Math.max(0, scroll - (int) Math.signum(dy) * step);
            scrolledAt = Util.getMillis() / 50;
            return true;
        }
        if (mx < midX && my >= listTop && my < listBottom) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(dy) * ROW_H * step);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    // -------------------------------------------------------- test hooks ---

    /** The item list in order: "# Header" lines and where each item is listed ("worn:HEAD", "inv:0", "uuid:..."). */
    public List<String> listedRows() {
        List<String> out = new ArrayList<>();
        for (Row r : rows) out.add(r.isHeader() ? "# " + r.header() : r.role());
        return out;
    }

    public String skinFilterLabel() {
        return skinFilter.label;
    }

    /** The open colour pop-up, or null. */
    public ColorPopup popup() {
        return popup;
    }

    public TooltipPanel preview() {
        return tooltip;
    }

    /** The name box on the Name & Glint tab, or null. */
    public NameBox nameBox() {
        return nameBox;
    }

    /** A glint colour square by its name ("Red", "Purple (Minecraft's)"...), or null. */
    public Button glintSquare(String name) {
        return glintSquares.get(name);
    }

    /** The name's always-open colour picker (the roomy Name & Glint layout), or null. */
    public ColorPicker namePicker() {
        return namePicker;
    }

    /** Whether the name's codes tooltip was drawn last frame. */
    public boolean codesShown() {
        return codesShown;
    }

    /** Picks the item listed at {@code role} ("pet", "inv:3", "worn:HEAD"...), as a click on its row does. */
    public void pick(String role) {
        for (Row r : List.copyOf(rows)) if (!r.isHeader() && r.role().equals(role)) select(r);
    }

    /** Where the picked item is listed, or null. */
    public String pickedRole() {
        return selected == null ? null : selected.role();
    }

    /** The footer line drawn last frame: a fresh message, else the count. */
    public String footer() {
        return footShown;
    }

    /** Whether the Settings button is outlined with the open-key tip. */
    public boolean keyTipShown() {
        return keyTip;
    }

    /** Height of the item list. */
    public int listRoom() {
        return listBottom - listTop;
    }

    /** Whether the summary was drawn last frame, and where line {@code i}'s [x] was: x, y. */
    public boolean summaryShown() {
        return summaryShown;
    }

    /** Card names laid out so far at this size: how many end cut ("..."), and how many there are. */
    public int[] labelsCut() {
        int cut = 0;
        for (String[] l : nameLines.values()) if (l[l.length - 1].endsWith("...")) cut++;
        return new int[]{cut, nameLines.size()};
    }

    /** The skins grid last frame: heads drawn, texture checks; and how many textures it asked for since it opened. */
    public int[] gridCost() {
        return new int[]{gridItems, gridChecks, asked.size()};
    }

    /** The player model's box last frame: top, bottom, scale; null when it was not drawn. */
    public int[] modelBox() {
        return modelScale == 0 ? null : new int[]{modelY0, modelY1, modelScale};
    }

    /** The pet drawn beside the model last frame: x, y, size; null when none was. */
    public int[] petBox() {
        return petSize == 0 ? null : new int[]{petX, petY, petSize};
    }

    public int[] summaryClearAt(int i) {
        return new int[]{summaryRight - 4, summaryY + i * SUMMARY_H + 3};
    }
}
