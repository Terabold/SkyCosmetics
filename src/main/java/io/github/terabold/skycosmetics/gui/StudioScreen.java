package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Favorites;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.Textures;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.ChipButton;
import io.github.terabold.skycosmetics.gui.hub.IconButton;
import io.github.terabold.skycosmetics.gui.hub.SearchBox;
import io.github.terabold.skycosmetics.gui.hub.SwatchButton;
import io.github.terabold.skycosmetics.gui.hub.SwitchButton;
import io.github.terabold.skycosmetics.gui.hub.TabButton;
import io.github.terabold.skycosmetics.gui.hub.TextField;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.items.OwnedItems;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
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
 *
 * Drawn in the settings' theme: rounded panels, and the themed widgets of
 * {@code gui.hub} in the player's accent color.
 */
public class StudioScreen extends Screen {
    private enum Tab {
        SKINS("Skins", "Skins"), DYES("Dyes", "Dyes"), STYLE("Name & Glint", "Name"), SAVED("Saved", "Saved"),
        OTHER("Other Mods", "Mods");

        final String label;
        final String shortLabel;

        Tab(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    private enum SkinFilter {
        HELMET("Helmet", "Helmet skins and their color variants", "Search: knight, animated…"),
        PET("Pet", "Pet skins", "Search: dragon, animated…"),
        ORB("Orb", "Power orb skins", "Search: moon…"),
        ALL("All", "Every head in SkyBlock: skins, items, minions…", "Search: celestial…"),
        PASTE("Paste", "Paste any head texture", null);

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
    /**
     * New textures the grid may ask for per game tick at least: first frames, animation frames. While fewer than
     * {@link #MAX_IN_FLIGHT} downloads run, it may ask for up to that many more (animation frames only once every
     * card in view asked for its first), so animations start at once on a fast PC and connection, and a slow one
     * still gets them a few at a time.
     */
    private static final int FIRSTS_PER_TICK = 12, FRAMES_PER_TICK = 3, MAX_IN_FLIGHT = 24;
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
    private static final Component TITLE = Component.literal("SkyCosmetics").withStyle(ChatFormatting.BOLD);
    /** The title where the bold one would run under the Settings button (a narrow left column). */
    private static final Component TITLE_PLAIN = Component.literal("SkyCosmetics");
    private static final int TEXT = Theme.TEXT, MUTED = Theme.MUTED, GOLD = Theme.GOLD;

    private static final List<Component> ITEMS_HELP = List.of(
        Component.literal("Where My Items come from").withStyle(ChatFormatting.AQUA),
        bullet("Always: ", "armor, held item, pet, inventory"),
        bullet("Open once: ", "Wardrobe, Equipment, Pets,"),
        Component.literal("   Ender Chest, Backpacks, Personal Vault").withStyle(ChatFormatting.GRAY),
        bullet("Search: ", "name or item ID"),
        bullet("Right-click an item: ", "forget it"));
    /** The same while Stored Items is off: nothing comes from menus, so there is nothing to forget. */
    private static final List<Component> ITEMS_HELP_STORED_OFF = List.of(
        Component.literal("Where My Items come from").withStyle(ChatFormatting.AQUA),
        bullet("Always: ", "armor, held item, pet, inventory"),
        Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.translatable("skycosmetics.studio.itemsHelp.storedOff").withStyle(ChatFormatting.GRAY)),
        bullet("Search: ", "name or item ID"));
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
    private static final String[][] GLINT_COLOURS = {{"Purple (default)", null}, {"Red", "#FF3B3B"},
        {"Orange", "#FF8A1F"}, {"Gold", "#FFC21A"}, {"Yellow", "#FFF23B"}, {"Lime", "#9BFF3B"}, {"Green", "#2FD14A"},
        {"Aqua", "#3BF0FF"}, {"Blue", "#3B6BFF"}, {"Pink", "#FF7AC8"}, {"Magenta", "#FF3BF0"}, {"White", "#FFFFFF"}};

    /** A name gradient preset: its colours from the first letter to the last (0xRRGGBB). */
    private record Gradient(String name, int... stops) {}

    /** How the chroma button is filled: the rainbow it moves through. */
    private static final int[] CHROMA_STOPS = {0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF};

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
    private Component title = TITLE;
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
    /** The open tab's button, where its accent bar is drawn now (gliding after a switch), and the content's fade-in. */
    private TabButton openTab;
    private float tabBarX = -1, tabBarW;
    private long tabBarAt = -1;
    private final Anim tabIn = new Anim(120, 1);
    /** What the item list, the grids and Other Mods' note have under the mouse: their tooltips wait for a rest. */
    private final Tips.Hover listHover = new Tips.Hover(), gridHover = new Tips.Hover(), noteHover = new Tips.Hover();
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
    /** Skins whose every frame was asked for, so the prefetch skips them. */
    private final Set<String> prefetched = new HashSet<>();
    /** The tick each animated card first showed a frame, and first moved (tests: how soon animations start). */
    private final Map<String, Long> shownAt = new HashMap<>(), movedAt = new HashMap<>();
    /** The cards in view last frame; the most textures asked for in one tick since the studio opened. */
    private int gridStart, gridEnd, askedThisTick, mostPerTick;
    /** Every card in view asked for its first frame last frame: animation frames may use the spare budget. */
    private boolean firstsDone;

    private TextField texBox;
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
    /** The colour or gradient pop-up over the middle column, or null. It gets every click and key first. */
    private StudioPopup popup;
    /** The player's saved gradients as drawn, for their right-click; a preset was saved while the builder was open. */
    private final Map<Button, int[]> customGradients = new HashMap<>();
    private boolean presetsChanged;
    /** Tab, item and scope the pop-up was opened for; it is dropped when any of them changes. */
    private String popupOwner;
    /** A dye chosen in a picker, applied once the mouse rests (no file write per drag step). */
    private String pendingDye;
    private long pendingAt;
    /** A glint speed or colour being dragged, saved the same way. */
    private UnaryOperator<Looks.Look> pendingGlint;
    private long pendingGlintAt;

    // Saved, Other Mods
    private SavedTab savedTab;
    private OtherModsTab otherTab;
    /** Bumped whenever the item list is built again: Saved's Edit links ask it. */
    private int rowsVersion;
    /** The last removal, undone by the footer's Undo while it shows. */
    private Runnable undo;
    private long undoAt = Long.MIN_VALUE;
    private Button undoButton;
    private static final long UNDO_MS = 8000;

    // Right
    private final TooltipPanel tooltip = new TooltipPanel();
    /** Other mods' changes on the picked item, as chips over the summary; what they were built from. */
    private final List<OtherChip> chips = new ArrayList<>();
    private List<OtherLooks.Change> chipsFor = List.of();
    private int chipRows;
    /** The custom name in the summary, clipped once per name and width rather than every frame. */
    private String summaryNameRaw;
    private int summaryNameWidth;
    private long summaryNameTick;
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
    /** Where Other Mods' one-line note is drawn, and the note: which mods it lists. */
    private int otherNoteY;
    private String otherNote = "";

    /**
     * @param parent screen to return to (a menu stays open underneath)
     * @param focus  item to start on, e.g. the one hovered when K was pressed; may be empty
     */
    public StudioScreen(Screen parent, ItemStack focus) {
        super(Component.literal("SkyCosmetics"));
        GradientPresets.init();
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
        rowsVersion++;
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
        if (tab == Tab.STYLE || tab == Tab.SAVED || tab == Tab.OTHER) return; // keep the user's explicit choice
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
            Button done = addRenderableWidget(new ActionButton(Component.literal("Done"), 80, 20, ActionButton.Kind.PRIMARY,
                b -> onClose()));
            done.setPosition(width / 2 - 40, height / 2 + 14);
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
        if (savedTab == null) {
            savedTab = new SavedTab(font, new SavedHost());
            otherTab = new OtherModsTab(font, new OtherHost());
        }
        if (tab == Tab.OTHER && !OtherLooks.present()) tab = Tab.SAVED;
        chips.clear();
        chipRows = 0;
        chipsFor = changesFor(widgetsFor);
        chipsVersion = OtherLooks.version();

        initLeft();
        initMiddle();
        initPreview();
        int uw = font.width("Undo") + 10;
        undoButton = addRenderableWidget(new ActionButton(Component.literal("Undo"), uw, 11, ActionButton.Kind.SECONDARY,
            b -> runUndo()));
        undoButton.setPosition(midX + midW - uw, height - PAD - 11);
        Tips.set(undoButton, Component.literal("Puts back the last removal · Ctrl+Z"));
        undoButton.visible = undoShown();
        refilter();
    }

    // ------------------------------------------------------- other mods ---

    private int chipsVersion;

    /** Other mods' changes on this item: on it by UUID, then on every item of its type. */
    private static List<OtherLooks.Change> changesFor(Cosmetics.Ident id) {
        if (id == null || !OtherLooks.present()) return List.of();
        List<OtherLooks.Change> own = id.uuid() != null ? OtherLooks.of(id.uuid()) : List.of();
        List<OtherLooks.Change> type = OtherLooks.of("id:" + id.type());
        if (type.isEmpty()) return own;
        List<OtherLooks.Change> all = new ArrayList<>(own);
        all.addAll(type);
        return all;
    }

    /** Same changes as shown: compared by mod, item, kind and value. */
    private static boolean sameChanges(List<OtherLooks.Change> a, List<OtherLooks.Change> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            OtherLooks.Change x = a.get(i), y = b.get(i);
            if (x.source() != y.source() || !x.item().equals(y.item()) || x.kind() != y.kind() || !x.value().equals(y.value())
                || x.live() != y.live()) return false;
        }
        return true;
    }

    /**
     * The chips for {@link #chipsFor}, left to right from {@code x} in rows {@code w} wide that end at {@code bottom},
     * at most {@code maxRows} rows (then a "+N" button opens Other Mods); returns how many rows they take.
     */
    private int layChips(int x, int w, int bottom, int maxRows) {
        if (chipsFor.isEmpty()) return 0;
        int step = OtherChip.HEIGHT + 2;
        List<List<OtherLooks.Change>> lines = new ArrayList<>();
        List<OtherLooks.Change> line = new ArrayList<>();
        int used = 0, shown = 0;
        for (OtherLooks.Change c : chipsFor) {
            int cw = Math.min(OtherChip.width(font, c), w);
            if (!line.isEmpty() && used + 2 + cw > w) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
                if (lines.size() == maxRows) break;
            }
            used += (line.isEmpty() ? 0 : 2) + cw;
            line.add(c);
            shown++;
        }
        if (!line.isEmpty() && lines.size() < maxRows) lines.add(line);
        int hidden = chipsFor.size() - shown;
        int top = bottom - lines.size() * step;
        for (int r = 0; r < lines.size(); r++) {
            int cx = x;
            List<OtherLooks.Change> row = lines.get(r);
            boolean lastRow = r == lines.size() - 1;
            int moreW = hidden > 0 && lastRow ? font.width("+" + (hidden + row.size())) + 8 : 0;
            for (int i = 0; i < row.size(); i++) {
                OtherLooks.Change c = row.get(i);
                int room = w - (cx - x) - (moreW > 0 ? moreW + 2 : 0);
                int cw = Math.min(OtherChip.width(font, c), room);
                if (cw < 30) {
                    hidden++;
                    continue;
                }
                OtherChip chip = addRenderableWidget(new OtherChip(font, cx, top + r * step, c, this::removeOther, this::moveOther));
                chip.setWidth(cw);
                chips.add(chip);
                cx += cw + 2;
            }
            if (hidden > 0 && lastRow) {
                String more = "+" + hidden;
                Button b = addRenderableWidget(new ActionButton(Component.literal(more), font.width(more) + 8, OtherChip.HEIGHT,
                    ActionButton.Kind.SECONDARY, btn -> switchTab(Tab.OTHER)));
                b.setPosition(cx, top + r * step);
                Tips.set(b, Component.translatable("skycosmetics.chip.more"));
            }
        }
        return lines.size();
    }

    private void removeOther(OtherLooks.Change c) {
        if (OtherLooks.remove(c)) {
            offerUndo(Component.translatable("skycosmetics.other.removedHere", c.source().name(),
                c.kind().label().getString().toLowerCase(Locale.ROOT)).getString(), () -> OtherLooks.restore(c));
        } else {
            flash(Component.translatable("skycosmetics.other.kept", c.source().name()).getString(), 0xFFFF6666);
        }
        rebuildWidgets();
    }

    private void moveOther(OtherLooks.Change c) {
        flushPending();
        Cosmetics.Ident id = widgetsFor;
        String label = c.uuid() == null ? "Every " + Repo.get().typeName(c.type()) : widgetsName; // as edit() names them
        Looks.Look before = OtherLooks.take(c, id != null ? id.type() : null, label);
        if (before == null) {
            flash(Component.translatable("skycosmetics.other.kept", c.source().name()).getString(), 0xFFFF6666);
            return;
        }
        boolean type = c.uuid() == null;
        String key = type ? c.type() : c.uuid();
        String itemType = id != null ? id.type() : null;
        offerUndo(Component.translatable("skycosmetics.other.movedHere", c.kind().label().getString().toLowerCase(Locale.ROOT))
            .getString(), () -> {
                Looks.Look back = before.empty() ? null : before;
                if (type) Looks.put(true, key, back);
                else Looks.putItem(key, itemType, back);
                OtherLooks.restore(c);
            });
        rebuildWidgets();
    }

    // -------------------------------------------------------------- undo ---

    /** Says what was removed, and keeps how to put it back for the footer's Undo. */
    private void offerUndo(String message, Runnable put) {
        flash(message, 0xFFFFAA66);
        undo = put;
        undoAt = Util.getMillis();
        if (undoButton != null) undoButton.visible = true;
    }

    private boolean undoShown() {
        return undo != null && Util.getMillis() - undoAt < UNDO_MS;
    }

    private void runUndo() {
        Runnable u = undo;
        undo = null;
        if (u == null) return;
        try {
            u.run();
            flash(Component.translatable("skycosmetics.studio.undone").getString(), 0xFF66DD88);
        } catch (RuntimeException e) {
            io.github.terabold.skycosmetics.Io.failed("Undoing a removal", e);
        }
        rebuildWidgets();
    }

    /** Saved asks the studio for textures, the footer and the item list. */
    private final class SavedHost implements SavedTab.Host {
        @Override
        public int readyFrame(SkinEntry e, long tick) {
            return StudioScreen.this.readyFrame(e, tick, true);
        }

        @Override
        public void removed(String message, Runnable undo) {
            offerUndo(message, undo);
        }

        @Override
        public boolean canEdit(boolean type, String key) {
            return rowFor(type, key) != null;
        }

        @Override
        public void edit(boolean type, String key) {
            editLook(type, key);
        }

        @Override
        public String query() {
            return listQuery();
        }

        @Override
        public int editVersion() {
            return rowsVersion;
        }

        @Override
        public ItemStack listed(String uuid) {
            return listedItem(uuid);
        }
    }

    private final class OtherHost implements OtherModsTab.Host {
        @Override
        public void removed(String message, Runnable undo) {
            offerUndo(message, undo);
        }

        @Override
        public void failed(String message) {
            flash(message, 0xFFFF6666);
        }

        @Override
        public String query() {
            return listQuery();
        }

        @Override
        public ItemStack listed(String uuid) {
            return listedItem(uuid);
        }

        @Override
        public int listVersion() {
            return rowsVersion;
        }
    }

    /** Items the left list shows by UUID, as of its last build: Saved and Other Mods name items My Items doesn't keep. */
    private Map<String, ItemStack> listedByUuid = Map.of();
    private int listedFor = -1;

    private ItemStack listedItem(String uuid) {
        if (listedFor != rowsVersion) {
            listedFor = rowsVersion;
            Map<String, ItemStack> m = new HashMap<>();
            for (Row r : rows) {
                if (!r.isHeader() && r.key().startsWith("uuid:")) m.putIfAbsent(r.key().substring(5), r.stack().get());
            }
            listedByUuid = m;
        }
        ItemStack s = uuid == null ? null : listedByUuid.get(uuid);
        return s == null ? ItemStack.EMPTY : s;
    }

    /** The search on Saved and Other Mods, as the lists match it. */
    private String listQuery() {
        return query.toLowerCase(Locale.ROOT).trim();
    }

    /** The row of My Items a saved look is for: its item, or (type) any item of that type; null if none is listed. */
    private Row rowFor(boolean type, String key) {
        for (Row r : rows) {
            if (r.isHeader()) continue;
            if (!type) {
                if (r.key().equals("uuid:" + key)) return r;
                continue;
            }
            ItemStack s = r.stack().get();
            Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
            if (id != null && key.equals(id.type())) return r;
        }
        return null;
    }

    /** Saved's "Edit": picks the look's item in My Items, on the tab that fits it, at the look's scope. */
    private void editLook(boolean type, String key) {
        Row r = rowFor(type, key);
        if (r == null && !itemQuery.isEmpty()) { // the list's search hides it
            itemQuery = "";
            buildRows();
            r = rowFor(type, key);
        }
        if (r == null) return;
        flushPending();
        selected = r;
        selectedKey = keyOf(r);
        popup = null;
        identStack = null;
        styleScroll = 0;
        resetScope();
        if (type && ident() != null && ident().uuid() != null) byType = true;
        tab = Tab.SKINS; // so the item picks its tab
        query = "";
        scroll = 0;
        pickTabFor(target());
        tooltip.resetScroll();
        rebuildWidgets();
    }

    private void initLeft() {
        int x = PAD + 4, w = leftW - 8;
        int sw = font.width("Settings") + 14;
        settings = addRenderableWidget(new ActionButton(Component.literal("Settings"), sw, 20, ActionButton.Kind.SECONDARY, b -> {
            flushPending();
            if (!Settings.keyTipShown) {
                Settings.keyTipShown = true;
                Settings.save();
            }
            // Opened from the settings: back to that same screen rather than stack another one.
            minecraft.setScreen(parent instanceof SettingsScreen ? parent : new SettingsScreen(this, Hub.STUDIO));
        }));
        settings.setPosition(PAD + leftW - 4 - sw, PAD + 3);
        title = font.width(TITLE) <= settings.getX() - 4 - (PAD + 6) ? TITLE : TITLE_PLAIN;
        keyTip = !Settings.keyTipShown && SkyCosmetics.openKey() != null && SkyCosmetics.openKey().isUnbound();
        Tips.set(settings, Component.translatable(keyTip ? "skycosmetics.studio.settings.keyTip"
            : "skycosmetics.studio.settings.tooltip"));

        SearchBox items = new SearchBox(font, w - 18, 14, Component.literal("Search My Items"), Component.literal("Search My Items…"));
        items.setPosition(x, PAD + 28);
        items.setMaxLength(48);
        items.setValue(itemQuery);
        items.setResponder(v -> {
            itemQuery = v;
            listScroll = 0;
            buildRows();
        });
        addRenderableWidget(items);
        itemsInfo = info(x + w - 15, PAD + 28, 14, 14);

        listTop = PAD + 48;
        // Short narrow windows: only the item's icon beside the scope switch; its tooltip has the summary.
        listBottom = height - PAD - (!compact ? 4 : slim ? 48 : 98);
        if (compact) actionButtons(x, height - PAD - 44, w, slim ? 24 : 0);
        if (compact && !slim) {
            chipRows = layChips(PAD + 6, leftW - 12, listBottom, 2);
            listBottom -= chipRows * (OtherChip.HEIGHT + 2) + (chipRows > 0 ? 2 : 0);
        }
    }

    /** Scope switch (after {@code indent}) above Reset / Done, in the preview column (or the left one when compact). */
    private void actionButtons(int x, int y, int w, int indent) {
        Cosmetics.Ident id = ident();
        Button scope = addRenderableWidget(new ActionButton(scopeLabel(id), w - indent, 20, ActionButton.Kind.SECONDARY,
            b -> toggleScope()));
        scope.setPosition(x + indent, y);
        scope.active = id != null && id.uuid() != null;
        String type = id != null ? Repo.get().typeName(id.type()) : null;
        Tips.set(scope, Component.literal(type != null
            ? "Click to switch: this item only, or every " + type : "Click to switch: this item only, or every item of its type")
            .append(Component.literal("\nA look for every item of a type shows only on your own items")
                .withStyle(ChatFormatting.GRAY)));
        int half = (w - 4) / 2;
        Button reset = addRenderableWidget(new ActionButton(Component.literal("Reset"), half, 20, ActionButton.Kind.DANGER,
            b -> resetItem()));
        reset.setPosition(x, y + 24);
        reset.active = id != null;
        Tips.set(reset, Component.literal(byType && type != null ? "Reset every " + type + " to original"
            : "Reset to original"));
        addRenderableWidget(new ActionButton(Component.literal("Done"), half, 20, ActionButton.Kind.PRIMARY,
            b -> onClose())).setPosition(x + half + 4, y + 24);
    }

    private void initPreview() {
        if (prevW <= 0) return;
        actionButtons(prevX + 4, height - PAD - 48, prevW - 8, 0);
        chipRows = layChips(prevX + 6, prevW - 12, summaryTop() - 3, 3);
    }

    /** Bottom of the preview's tooltip and model: the summary, and the chips over it, sit under it. */
    private int previewBottom() {
        return summaryTop() - (chipRows > 0 ? chipRows * (OtherChip.HEIGHT + 2) + 3 : 0);
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
        Tab[] tabs = OtherLooks.present() ? Tab.values() : Arrays.copyOf(Tab.values(), Tab.values().length - 1);
        int tw = Math.min(96, (midW - (tabs.length - 1) * 2) / tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            String label = font.width(t.label) + 8 <= tw ? t.label : t.shortLabel;
            TabButton b = addRenderableWidget(new TabButton(Component.literal(label), tw, 18, t == tab, btn -> switchTab(t)).slidingBar());
            b.setPosition(midX + i * (tw + 2), y);
            if (t == tab) openTab = b;
        }
        y += 22;

        switch (tab) {
            case SKINS -> {
                y = chips(y);
                if (skinFilter == SkinFilter.PASTE) {
                    int w = Math.min(midW - 96, 360);
                    texBox = new TextField(font, midX, y + 12, w, 16, Component.literal("Texture"));
                    texBox.setHint(Component.literal("Value, skin URL or hash").withColor(Theme.DIM & 0xFFFFFF));
                    texBox.setMaxLength(4096);
                    addRenderableWidget(texBox);
                    addRenderableWidget(new ActionButton(Component.literal("Apply"), 90, 20, ActionButton.Kind.PRIMARY,
                        b -> useTexture())).setPosition(midX + w + 4, y + 10);
                    setInitialFocus(texBox);
                    y += 36;
                } else {
                    y = search(y, skinFilter.hint, Math.min(midW, SEARCH_W));
                }
            }
            case DYES -> {
                int bw = font.width("Custom Dye…") + 12, sw = Math.min(midW - bw - 4, SEARCH_W);
                y = search(y, "Search: aurora, warden…", sw);
                Button custom = addRenderableWidget(new ActionButton(Component.literal("Custom Dye…"), bw, 16,
                    ActionButton.Kind.SECONDARY, b -> openDyePopup()));
                custom.setPosition(midX + sw + 4, y - 22);
                custom.active = ident() != null;
                Tips.set(custom, Component.literal("Any color, or your own animated dye"));
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
            case SAVED -> y = search(y, "Search: necron, aurora, glint…", Math.min(midW, SEARCH_W));
            case OTHER -> {
                otherNoteY = y;
                otherNote = otherNote();
                y = search(y + 12, "Search: skyblocker, dye, necron…", Math.min(midW, SEARCH_W));
            }
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
        SearchBox search = new SearchBox(font, w, 16, Component.literal("Search"), Component.literal(hint));
        search.setPosition(midX, y);
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            scroll = 0;
            if (savedTab != null) {
                savedTab.resetScroll();
                otherTab.resetScroll();
            }
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
            Button chip = addRenderableWidget(new ChipButton(Component.literal(f.label), w, 14, f == skinFilter, b -> {
                skinFilter = f;
                scroll = 0;
                rebuildWidgets();
            }));
            chip.setPosition(cx, cy);
            Tips.set(chip, Component.literal(f.help));
            cx += w + 2;
        }
        return cy + 18;
    }

    /** The custom dye pop-up: starts from the dye the item shows now, applies every change live. */
    private void openDyePopup() {
        if (ident() == null) return;
        String current = look().dye() != null ? look().dye() : inherited().dye();
        openPopup(new ColorPopup(Component.literal("Custom Dye"), current, true, this::queueDye).onClose(this::flushPending));
    }

    /** Shows a pop-up over the middle column until it is closed or the tab, item or scope changes. */
    private void openPopup(StudioPopup p) {
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
        int rw = font.width("Reset Name") + 12;
        resetName = addRenderableWidget(new ActionButton(Component.literal("Reset Name"), rw, NAME_HEAD - 1,
            ActionButton.Kind.SECONDARY, b -> resetName()));
        resetName.setPosition(right - rw, y0);
        nameBox = new NameBox(font, midX, y0 + NAME_HEAD, right - midX - infoW - 4, boxH);
        nameBox.setHint(Component.literal("Empty = original name").withColor(Theme.DIM & 0xFFFFFF));
        nameBox.setMaxLength(MAX_NAME);
        // A name saved with a code on every letter (an older gradient) shows as one gradient code.
        nameBox.setValue(pendingName != null ? pendingName : l.name() != null ? Names.compact(l.name()) : nameBaseline);
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
            Tips.set(b, CodesTooltip.colourName(f)
                .append(Component.literal("  &" + f.getChar()).withStyle(ChatFormatting.DARK_GRAY)));
        }
        pos = new int[]{midX, pos[1] + rowH + (roomy ? 4 : 2)};
        int extra = 0; // roomy: the style buttons share the row's width
        if (roomy) {
            int natural = (STYLES.length - 1) * 2;
            for (String[] st : STYLES) natural += font.width(styleLabel(st)) + 10;
            extra = Math.max(0, (right - midX - natural) / STYLES.length);
        }
        for (String[] st : STYLES) {
            Component label = styleLabel(st);
            Button b = place(pos, right, 2, ((ActionButton) keepFocus(label, font.width(label) + 10 + extra, rowH,
                () -> applyCode("&" + st[0]))).showStyle());
            // Bold, Italic... show what they do on themselves; Magic and Plain say it.
            if (st[0].equals("k") || st[0].equals("r")) {
                Tips.set(b, Component.literal(st[2]).append(Component.literal("  &" + st[0]).withStyle(ChatFormatting.DARK_GRAY)));
            }
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
                font.split(Component.literal("Any color for the selection, or the whole name"), right - rx);
            pickerNote = noteY + note.size() * 10 - 2 <= pos[1] + PICKER_H ? note : List.of(); // whole, or not at all
            pickerNoteAt = new int[]{rx, noteY};
            pos[1] += PICKER_H;
        } else {
            pos[1] = gradients(midX, pos[1], right - midX, 16) + 2;
            place(pos, right, 2, keepFocus(Component.literal("Custom Color…"), font.width("Custom Color…") + 12, 16,
                this::openNameColour));
            pos[1] += 16;
        }
        resetName.active = l.name() != null || pendingName != null;
        Tips.set(resetName, Component.literal(typeName != null
            ? "Reset to the name for every " + Repo.get().typeName(ident().type()) : "Reset to the original name"));
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
        Button toggle = addRenderableWidget(new SwitchButton(Component.literal("Glint: " + (on ? "On" : "Off")),
            Component.literal("Glint"), on, cw, rowH, b -> toggleGlint()));
        toggle.setPosition(cx, glintIconY);
        reset(cx + cw + 2, glintIconY, rowH, l.glint() != null, (t.glint() != null
            ? "Reset to every " + Repo.get().typeName(widgetsFor.type()) : "Reset to original")
            + " (" + (glintDefault() ? "On" : "Off") + ")", () -> {
                edit(look -> look.withGlint(null));
                rebuildWidgets();
            });

        Float speed = l.glintSpeed() != null ? l.glintSpeed() : t.glintSpeed();
        SpeedSlider slider = addRenderableWidget(SpeedSlider.speed(cx, glintIconY + step, cw, rowH, speed, s -> {
            queueGlint(look -> look.withGlintSpeed(s));
            speedReset.active = true;
        }, this::flushPending));
        speedReset = reset(cx + cw + 2, glintIconY + step, rowH, l.glintSpeed() != null, "Reset to default (1.0x)",
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
        strengthReset = reset(cx + cw + 2, glintIconY + 2 * step, rowH, l.glintStrength() != null,
            "Reset to default (1.0x)", () -> {
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
            Tips.set(b, Component.literal(c[0]).withColor(rgb));
            glintSquares.put(c[0], b);
            if (Objects.equals(colour, c[1]) || c[1] == null && PURPLE.equals(colour)) {
                glintPick = b;
                ((SwatchButton) b).chosen(true);
            }
        }
        pos[0] += 1; // the usual 2 px before a button
        place(pos, right, 2, new ActionButton(Component.literal("Custom Color…"), font.width("Custom Color…") + 12, sq,
            ActionButton.Kind.SECONDARY, b -> openGlintColour()));
        Button undo = undoButton(b -> {
            pendingGlint = null;
            edit(look -> look.withGlintColor(null));
            rebuildWidgets();
        });
        undo.setHeight(sq);
        colourReset = place(pos, right, 2, undo);
        colourReset.active = l.glintColor() != null;
        Tips.set(colourReset, Component.literal("Reset to default (purple)"));
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
        return new ActionButton(label, w, h, ActionButton.Kind.SECONDARY, b -> action.run()).keepFocus();
    }

    /** A style button's label, in its own style ("Bold" in bold); Magic and Plain stay plain, so they read. */
    private static Component styleLabel(String[] style) {
        ChatFormatting f = ChatFormatting.getByCode(style[0].charAt(0));
        boolean shows = f != null && f != ChatFormatting.OBFUSCATED && f != ChatFormatting.RESET;
        return shows ? Component.literal(style[1]).withStyle(f) : Component.literal(style[1]);
    }

    /**
     * A colour button: the colour fills it. Its message, a coloured ■, names the colour for tooltips and tests.
     * {@code keepFocus}: the name box keeps the keyboard and its selection.
     */
    private Button swatch(Component label, int w, int h, int rgb, boolean keepFocus, Runnable action) {
        SwatchButton b = new SwatchButton(label, w, h, new int[]{rgb}, btn -> action.run());
        if (keepFocus) b.keepFocus();
        return b;
    }

    /** A small ↺ that puts one glint setting back to its default. */
    private Button reset(int x, int y, int h, boolean active, String tip, Runnable action) {
        Button b = addRenderableWidget(undoButton(btn -> action.run()));
        b.setRectangle(14, h, x, y);
        b.active = active;
        Tips.set(b, Component.literal(tip));
        return b;
    }

    /** The font only has a tiny ↺, so it is drawn at twice the size. */
    private Button undoButton(Button.OnPress onPress) {
        return new IconButton(Component.literal("↺"), 14, 14, 2, false, onPress);
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
        openPopup(new ColorPopup(Component.literal("Glint Color"), current != null ? current : PURPLE, false, hex -> {
            queueGlint(l -> l.withGlintColor(hex));
            colourReset.active = true;
            glintPick = null;
        }).onClose(this::flushPending));
    }

    /** "Custom Color…" for the name: the pop-up's colour goes on the letters selected when it opened, live. */
    private void openNameColour() {
        if (nameBox == null) return;
        String base = nameBox.getValue();
        int from = nameBox.selectionStart(), to = nameBox.selectionEnd();
        String start = Names.colourAt(base, from);
        openPopup(new ColorPopup(Component.literal("Name Color"), start != null ? start : "#FFFFFF", false, hex -> {
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
        int[] r = nameRange();
        setName(Names.gradient(nameBox.getValue(), r[0], r[1], stops), nameBox.isFocused());
    }

    /** Chroma, the same way. */
    private void applyChroma() {
        if (nameBox == null) return;
        int[] r = nameRange();
        setName(Names.format(nameBox.getValue(), r[0], r[1], Names.chroma()), nameBox.isFocused());
    }

    /** The selected letters while the box has the keyboard and some are selected, else the whole name. */
    private int[] nameRange() {
        int a = nameBox.selectionStart(), b = nameBox.selectionEnd();
        return nameBox.isFocused() && a != b ? new int[]{a, b} : new int[]{0, nameBox.getValue().length()};
    }

    /**
     * The gradient presets from (x, y) in as few rows {@code w} wide as fit: the built-in ones, chroma, the
     * player's own and + to make one. Returns the bottom of the last row.
     */
    private int gradients(int x, int y, int w, int h) {
        customGradients.clear();
        List<int[]> own = GradientPresets.list();
        int n = GRADIENTS.size() + 1 + own.size() + 1;
        int perRow = Math.clamp((w + 2) / 20, 1, n);
        int gw = (w + 2) / perRow - 2;
        List<Button> all = new ArrayList<>();
        for (Gradient gr : GRADIENTS) all.add(gradientSwatch(gr.name(), gr.stops(), gw, h, null));
        Button chroma = new SwatchButton(Component.literal("Chroma"), gw, h, CHROMA_STOPS, btn -> applyChroma()).keepFocus();
        Tips.set(chroma, Names.parse(Names.chroma() + "Chroma").append(Component.literal(" · a moving rainbow")
            .withStyle(ChatFormatting.GRAY)));
        all.add(chroma);
        for (int[] st : own) {
            Button b = gradientSwatch("Your Gradient", st, gw, h, "Right-click to remove");
            customGradients.put(b, st);
            all.add(b);
        }
        Button make = new IconButton(Component.literal("+"), gw, h, 1, true, btn -> openGradientBuilder()).keepFocus();
        Tips.set(make, Component.literal("Make your own gradient"));
        all.add(make);
        for (int i = 0; i < all.size(); i++) {
            Button b = all.get(i);
            b.setPosition(x + i % perRow * (gw + 2), y + i / perRow * (h + 2));
            addRenderableWidget(b);
        }
        return y + (all.size() + perRow - 1) / perRow * (h + 2) - 2;
    }

    /** A gradient button filled with its colours; the name box keeps the keyboard and its selection. */
    private Button gradientSwatch(String name, int[] stops, int w, int h, String hint) {
        Button b = new SwatchButton(Component.literal(name), w, h, stops, btn -> applyGradient(stops)).keepFocus();
        MutableComponent tip = Names.parse(Names.gradient(name, 0, name.length(), stops).text());
        if (hint != null) tip.append(Component.literal("\n" + hint).withStyle(ChatFormatting.GRAY));
        Tips.set(b, tip);
        return b;
    }

    /**
     * The gradient builder, starting from the gradient the selected letters (or the name) already have. Apply
     * goes to the letters selected now, or the whole name; a saved preset shows up once it closes.
     */
    private void openGradientBuilder() {
        if (nameBox == null) return;
        boolean focused = nameBox.isFocused();
        int[] start = Names.gradientAt(nameBox.getValue(), nameRange()[0]);
        openPopup(new GradientPopup(new GradientPopup.Host() {
            @Override
            public String name() {
                return nameBox != null ? nameBox.getValue() : "";
            }

            @Override
            public int selectionStart() {
                return nameBox != null && focused ? nameBox.selectionStart() : 0;
            }

            @Override
            public int selectionEnd() {
                return nameBox != null && focused ? nameBox.selectionEnd() : 0;
            }

            @Override
            public void apply(int[] stops, boolean whole) {
                if (nameBox == null) return;
                String v = nameBox.getValue();
                int a = whole ? 0 : nameBox.selectionStart(), b = whole ? v.length() : nameBox.selectionEnd();
                setName(Names.gradient(v, a, b, stops), focused);
            }

            @Override
            public boolean save(int[] stops) {
                boolean added = GradientPresets.add(stops);
                presetsChanged |= added;
                return added;
            }
        }, start).onClose(() -> {
            flushPending();
            if (presetsChanged) {
                presetsChanged = false;
                rebuildWidgets();
            }
        }));
    }

    /** Shows an edited name with the same letters selected; {@code focus} gives the box the keyboard. */
    private boolean setName(Names.Edit e, boolean focus) {
        if (e.text().length() > MAX_NAME) {
            flash("Name too long for more codes", 0xFFFF6666);
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
    private Button info(int x, int y, int w, int h) {
        Button b = addRenderableWidget(new IconButton(Component.literal("i"), w, h, 1, true, btn -> { }));
        b.setPosition(x, y);
        return b;
    }

    private Component scopeLabel(Cosmetics.Ident id) {
        if (id == null) return Component.literal("Pick an item");
        return byType ? Component.literal("Every " + Repo.get().typeName(id.type()))
            : Component.literal("This Item Only");
    }

    private void switchTab(Tab t) {
        flushPending();
        tab = t;
        tabIn.set(0);
        scroll = 0;
        if (savedTab != null) {
            savedTab.resetScroll();
            otherTab.resetScroll();
        }
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
        flash(inherited().empty() ? "Reset to original"
            : "Reset. The look for every " + Repo.get().typeName(id.type()) + " still applies.", 0xFFFFAA66);
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
            flash("Not a skin texture: paste a Value, URL or hash", 0xFFFF6666);
            return;
        }
        setSkin(Textures.CUSTOM_PREFIX + tex);
        flash("Texture applied", 0xFF66DD88);
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

    /**
     * The name box is built again with the rest; while it has the keyboard it keeps it, and its selection, so
     * a pop-up working on the selected letters still finds them after a resize or a refresh.
     */
    @Override
    protected void rebuildWidgets() {
        NameBox old = nameBox != null && getFocused() == nameBox ? nameBox : null;
        int from = old != null ? old.selectionStart() : 0, to = old != null ? old.selectionEnd() : 0;
        super.rebuildWidgets();
        if (old != null && nameBox != null && nameBox.visible && nameBox.getValue().equals(old.getValue())) {
            setFocused(nameBox);
            nameBox.select(from, to);
        }
    }

    /** Any screen change, not only Esc or Done: a menu Hypixel opens or closes, a warp, a disconnect. */
    @Override
    public void removed() {
        if (namePicker != null) namePicker.stopEditing(); // applies a half-typed hex value
        if (popup != null) {
            StudioPopup p = popup;
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
        extractBlurredBackground(g); // as the settings do: the world and the hotbar stay out of the way
        g.fill(0, 0, width, height, BG);
        if (tooSmall) return;
        panel(g, PAD, PAD, leftW, height - PAD * 2);
        if (prevW > 0) panel(g, prevX, PAD, prevW, height - PAD * 2);
        if (tab == Tab.SKINS && skinFilter != SkinFilter.PASTE || tab == Tab.DYES || tab == Tab.SAVED || tab == Tab.OTHER) {
            panel(g, midX - 2, gridY - 2, midW + 4, gridH + 4);
        }
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Shapes.round(g, x, y, w, h, Theme.RADIUS, Theme.BODY);
        Shapes.frame(g, x, y, w, h, Theme.RADIUS, Theme.LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Anim.frame();
        if (tooSmall) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            g.centeredText(font, "Window too small", width / 2, height / 2 - 14, TEXT);
            g.centeredText(font, "Make it bigger or lower GUI Scale.", width / 2, height / 2 - 2, MUTED);
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
        OtherLooks.refresh(false); // cheap: one stamp per mod, at most twice a second; nothing without other mods
        if (OtherLooks.version() != chipsVersion) {
            chipsVersion = OtherLooks.version();
            if (!sameChanges(changesFor(widgetsFor), chipsFor) || tab == Tab.OTHER && !OtherLooks.present()) rebuildWidgets();
        }
        if (undoButton != null) undoButton.visible = undoShown();
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
            // Few downloads running: the grid may ask for more this tick (see drawSkins).
            int room = Math.clamp(MAX_IN_FLIGHT - Textures.inFlight(), 0, MAX_IN_FLIGHT);
            firstBudget = Math.max(FIRSTS_PER_TICK, room);
            frameBudget = Math.max(FRAMES_PER_TICK, firstsDone ? room : 0);
            askedThisTick = 0;
        }
        summaryShown = false;
        modelScale = petSize = 0;
        if (keyTip) { // a ring that breathes, until Settings is clicked
            float pulse = 0.55f + 0.45f * (float) Math.sin(Util.getMillis() / 220.0);
            Shapes.frame(g, settings.getX() - 2, settings.getY() - 2, settings.getWidth() + 4, settings.getHeight() + 4,
                Theme.SMALL_RADIUS + 3, Theme.fade(Theme.ACCENT, pulse));
        }

        g.text(font, title, PAD + 6, PAD + 9, Theme.ACCENT, false);
        drawList(g, mx, my);
        if (prevW > 0) drawPreview(g, mx, my, mouseX, mouseY, tick);
        else drawCompactPreview(g, mx, my, tick);

        switch (tab) {
            case DYES -> drawDyes(g, mx, my, tick);
            case STYLE -> drawStyle(g, mx, my);
            case SAVED -> drawSaved(g, mx, my, tick);
            case OTHER -> drawOther(g, mx, my, tick);
            default -> {
                if (skinFilter == SkinFilter.PASTE) drawPaste(g);
                else drawSkins(g, mx, my, tick);
            }
        }
        tabSwitch(g);

        String foot;
        Catalog c = Repo.get();
        if (c.skins.isEmpty()) foot = Repo.loading() ? "Loading skins…" : "No skin data yet. Retrying…";
        else foot = switch (tab) {
            case DYES -> Names.count(dyeResults.size(), "dye") + dyeNote();
            case SKINS -> skinFilter == SkinFilter.PASTE ? "" : Names.count(skinResults.size(), "skin");
            case SAVED -> Names.count(savedTab.shown(), "look");
            case OTHER -> {
                int[] n = otherTab.shown();
                yield Names.count(n[1], "change") + " · " + Names.count(n[0], "item");
            }
            default -> "";
        };
        // One footer line: a fresh message, else the count. A clipped message shows whole on hover.
        boolean fresh = !status.isEmpty() && Util.getMillis() - statusAt < (undoShown() ? UNDO_MS : 4000);
        int fy = height - PAD - 9;
        int footW = midW - (undoButton != null && undoButton.visible ? undoButton.getWidth() + 4 : 0);
        footShown = clip(fresh ? status : foot, footW);
        g.text(font, footShown, midX, fy, fresh ? statusColor : MUTED);
        if (fresh && font.width(status) > footW && mx >= midX && mx < midX + footW && my >= fy - 1 && my < fy + 9) {
            Tips.show(g, font, List.of(Component.literal(status)), Tips.beside(midX, fy - 1, midX + footW, fy + 9, midX, fy,
                Tips.Side.ABOVE), mx, my); // the whole message, over the line it was cut from
        }
        // The (i)s are there for their help: at once, beside their column, clear of what is used with them.
        if (itemsInfo != null && itemsInfo.isHovered()) {
            Tips.show(g, font, Settings.storedItems ? ITEMS_HELP : ITEMS_HELP_STORED_OFF, columnTip(itemsInfo), mx, my);
        }
        codesShown = nameInfo != null && nameInfo.isHovered();
        if (codesShown) {
            g.nextStratum();
            g.tooltip(font, CodesTooltip.lines(height), mx, my, columnTip(nameInfo), null);
        }
        if (popup == null) Tips.widgets(g, font, children(), mx, my, this::columnTip);
        if (popup != null) popup.render(g, mouseX, mouseY);
    }

    /** The column a widget sits in, {x0, y0, x1, y1}: its tooltip keeps clear of it. */
    private int[] columnOf(int x) {
        if (x < midX - 2) return new int[]{PAD, PAD, PAD + leftW, height - PAD};
        if (prevW > 0 && x >= prevX) return new int[]{prevX, PAD, prevX + prevW, height - PAD};
        return new int[]{midX - 2, PAD, midX + midW + 4, height - PAD};
    }

    /** Beside the widget's column, level with the widget. */
    private ClientTooltipPositioner columnTip(AbstractWidget w) {
        int[] c = columnOf(w.getX());
        return Tips.beside(c[0], c[1], c[2], c[3], w.getX(), w.getY());
    }

    /**
     * A tab switch, on the same clock and easing as the settings' section switch: the accent bar glides to the
     * open tab, and the new tab's content fades in from the panel.
     */
    private void tabSwitch(GuiGraphicsExtractor g) {
        if (openTab != null && openTab.visible) {
            long now = Anim.now(), dt = tabBarAt < 0 ? Long.MAX_VALUE : now - tabBarAt;
            tabBarAt = now;
            float tx = openTab.getX() + 6, tw = openTab.getWidth() - 12;
            boolean jump = tabBarX < 0 || dt > 250 || Math.abs(tabBarX - tx) > midW;
            tabBarX = jump ? tx : Anim.glide(tabBarX, tx, dt);
            tabBarW = jump ? tw : Anim.glide(tabBarW, tw, dt);
            if (Math.abs(tabBarX - tx) < 0.3f) tabBarX = tx;
            Shapes.round(g, Math.round(tabBarX), openTab.getY() + openTab.getHeight() - 3, Math.round(tabBarW), 2, 1, Theme.ACCENT);
        }
        float in = tabIn.to(1);
        if (in < 1) {
            g.nextStratum(); // over the content's items and text too
            g.fill(midX - 2, PAD + 22, midX + midW + 4, height - PAD - 11, Theme.fade(Theme.BODY, 1 - in));
        }
    }

    /** Only leather takes dye: other armour is drawn as the leather piece for its slot, heads not at all. */
    private String dyeNote() {
        ItemStack s = target();
        if (s.isEmpty()) return "";
        if (s.get(DataComponents.EQUIPPABLE) == null || s.is(Items.PLAYER_HEAD)) return " · this item can't be dyed";
        return s.getItem().toString().contains("leather") ? "" : " · shown as leather armor";
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
            g.textWithWordWrap(font, Component.literal("No items yet. Hold or wear a SkyBlock item, or open your "
                + "Wardrobe, Pets or Ender Chest."), x + 2, listTop + 4, w - 4, MUTED);
            return;
        }
        int maxScroll = Math.max(0, rowsHeight() - (listBottom - listTop));
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        g.enableScissor(x, listTop, x + w, listBottom);
        int y = listTop - listScroll;
        Row hovered = null;
        int hoveredY = 0;
        for (Row r : rows) {
            int h = r.isHeader() ? HEADER_H : ROW_H;
            if (y + h >= listTop && y < listBottom) {
                if (r.isHeader()) {
                    g.text(font, r.header(), x + 2, y + 2, Theme.ACCENT, false);
                } else {
                    boolean sel = r == selected;
                    boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                                   && mouseY >= listTop && mouseY < listBottom;
                    if (sel) {
                        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.ACCENT_BG);
                        Shapes.round(g, x, y + 4, 2, h - 8, 1, Theme.ACCENT);
                    } else if (over) {
                        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.SURFACE);
                    }
                    ItemStack s = r.stack().get();
                    g.item(s, x + 2, y + 2);
                    g.text(font, clip(s.getStyledHoverName().getString(), w - 24), x + 21, y + 2, TEXT);
                    if (!r.sub().isEmpty()) small(g, r.sub(), x + 21, y + 12, MUTED);
                    if (over) {
                        hovered = r;
                        hoveredY = y;
                    }
                }
            }
            y += h;
        }
        g.disableScissor();
        if (rowsHeight() > listBottom - listTop) {
            int track = listBottom - listTop;
            int thumb = Math.max(12, track * track / rowsHeight());
            int ty = listTop + (track - thumb) * listScroll / Math.max(1, maxScroll);
            boolean overBar = mouseX >= x + w - 6 && mouseX < x + w && mouseY >= listTop && mouseY < listBottom;
            Shapes.round(g, x + w - 2, ty, 2, thumb, 1, overBar ? Theme.ACCENT : Theme.DIM);
        }
        // Beside the list after a rest, level with the row: never over the rows around it.
        if (listHover.settled(hovered == null ? null : hovered.key())) {
            List<Component> tip = new ArrayList<>();
            tip.add(hovered.stack().get().getStyledHoverName());
            if (!hovered.sub().isEmpty()) tip.add(Component.literal(hovered.sub()).withStyle(ChatFormatting.GRAY));
            if (hovered.role().startsWith("uuid:")) {
                tip.add(Component.literal("Right-click to forget").withStyle(ChatFormatting.DARK_GRAY));
            }
            Tips.show(g, font, tip, Tips.beside(PAD, listTop, PAD + leftW, listBottom, x, hoveredY), mouseX, mouseY);
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
        int top = PAD + 6, summaryTop = summaryTop(), bottom = previewBottom();
        int room = bottom - 6 - top;
        int used = tooltip.render(g, font, target(), x, top, w, room - Math.max(64, room * 2 / 7));
        int modelTop = top + used + 4, modelBottom = bottom - 6;
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
        int x = PAD + 6, y = height - PAD - 94; // under the list (and the chips)
        bigItem(g, target(), x, y, 24, mouseX, mouseY, null);
        drawSummary(g, x + 28, y, PAD + leftW - 6, mouseX, mouseY, tick);
    }

    /** The item at {@code size}; on hover its full tooltip (new name, real lore), then {@code more} lines if any. */
    private void bigItem(GuiGraphicsExtractor g, ItemStack s, int x, int y, int size, int mouseX, int mouseY,
                         Supplier<List<Component>> more) {
        Shapes.round(g, x, y, size, size, Theme.SMALL_RADIUS + 1, Theme.SURFACE);
        float scale = (size - 4) / 16f;
        g.pose().pushMatrix();
        g.pose().translate(x + 2, y + 2);
        g.pose().scale(scale, scale);
        g.item(s, 0, 0);
        g.pose().popMatrix();
        if (mouseX < x || mouseX >= x + size || mouseY < y || mouseY >= y + size) return;
        // The item's tooltip is long: beside the icon's column, never over the controls next to it.
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, s));
        if (more != null) {
            lines.add(Component.empty());
            lines.addAll(more.get());
        }
        int[] c = columnOf(x);
        List<FormattedCharSequence> seq = new ArrayList<>(lines.size());
        for (Component l : lines) seq.add(l.getVisualOrderText());
        g.setTooltipForNextFrame(font, seq, s.getTooltipImage(), Tips.beside(c[0], c[1], c[2], c[3], x, y), mouseX, mouseY,
            false, null);
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
            g.text(font, "Original", x + 30, y, MUTED);
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
        List<Component> lines = new ArrayList<>(List.of(label("Skin").append(Component.literal(skin.text()).withColor(skin.color() & 0xFFFFFF)),
            dyeLine.append(Component.literal(dv.text()).withColor(dv.color() & 0xFFFFFF)),
            label("Name").append(name == null ? Component.literal("Original").withColor(MUTED & 0xFFFFFF)
                : Names.parse(name + all(l.name(), t.name()))),
            label("Glint").append(Component.literal(glint.text()).withColor(glint.color() & 0xFFFFFF))));
        // No room for chips here: other mods' changes are listed instead (Other Mods removes them).
        for (OtherLooks.Change c : chipsFor) lines.add(c.chip().copy().withColor(c.source().color() & 0xFFFFFF));
        return lines;
    }

    private static MutableComponent label(String label) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY);
    }

    /** One summary value: its text, colour, and whether this scope set it (then it has an [x]). */
    private record Value(String text, int color, boolean own) {}

    private static Value skinValue(Looks.Look l, Looks.Look t) {
        String id = l.skin() != null ? l.skin() : t.skin();
        SkinEntry e = Repo.get().skin(id);
        return new Value((e != null ? e.name : id != null ? "Unknown skin" : "Original") + all(l.skin(), t.skin()),
            e != null ? e.color : MUTED, l.skin() != null);
    }

    private static DyeEntry shownDye(Looks.Look l, Looks.Look t) {
        return Repo.get().dye(l.dye() != null ? l.dye() : t.dye());
    }

    private static Value dyeValue(Looks.Look l, Looks.Look t, DyeEntry dye) {
        String id = l.dye() != null ? l.dye() : t.dye();
        return new Value((dye != null ? dye.name : id != null ? "Unknown dye" : "Original") + all(l.dye(), t.dye()),
            dye != null ? dye.nameColor : MUTED, l.dye() != null);
    }

    private static Value glintValue(Looks.Look l, Looks.Look t) {
        String glint = l.glint() != null ? l.glint() : t.glint();
        String colour = l.glintColor() != null ? l.glintColor() : t.glintColor();
        Float speed = l.glintSpeed() != null ? l.glintSpeed() : t.glintSpeed();
        Float strength = l.glintStrength() != null ? l.glintStrength() : t.glintStrength();
        StringBuilder text = new StringBuilder(glint == null ? "Original" : "on".equals(glint) ? "On" : "Off");
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
        boolean moved = summaryNameRaw != null && summaryNameTick != Names.liveTick() && Names.animated(raw);
        if (!raw.equals(summaryNameRaw) || px != summaryNameWidth || moved) {
            summaryNameRaw = raw;
            summaryNameWidth = px;
            summaryNameTick = Names.liveTick();
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
            // Cut: the whole value, over the summary's own lines rather than the buttons under it.
            Tips.show(g, font, List.of(Component.literal(value)), Tips.beside(x, y - 1, right, y + 9, x + 30, y - 1,
                Tips.Side.ABOVE, Tips.Side.LEFT), mouseX, mouseY);
        }
    }

    private void clearMark(GuiGraphicsExtractor g, int y, boolean clearable, int right, int mouseX, int mouseY) {
        if (!clearable) return;
        int cx = right - 8;
        boolean over = mouseX >= cx && mouseX < cx + 8 && mouseY >= y - 1 && mouseY < y + 9;
        if (over) Shapes.round(g, cx, y - 1, 9, 9, 2, Theme.DANGER_HOVER);
        Ui.cross(g, cx + 2, y + 1, 5, over ? 0xFFFFFFFF : 0xFF9A5A5A);
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
            Shapes.round(g, cx + 1, cy + 1, cardW - 2, cardH - 2, Theme.SMALL_RADIUS + 1, Theme.ACCENT_BG);
            Shapes.frame(g, cx + 1, cy + 1, cardW - 2, cardH - 2, Theme.SMALL_RADIUS + 1, Theme.ACCENT);
        } else if (hover) {
            Shapes.round(g, cx + 1, cy + 1, cardW - 2, cardH - 2, Theme.SMALL_RADIUS + 1, Theme.SURFACE);
        }
    }

    /** A small "play" triangle in a card's top-right corner: animated (gray: frames not known yet). */
    private static void animatedBadge(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 4; i++) g.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
    }

    /**
     * The grid shows many animated skins, so it asks for textures on a budget (see {@link #FIRSTS_PER_TICK}): the
     * first frames of the cards in view, then, while the grid is still, the animation frames of the cards in view
     * whose first frame is ready (see {@link #prefetch}), then the next rows' first frames. Never every frame of
     * every skin at once.
     */
    private void drawSkins(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        List<SkinEntry> list = skinResults;
        int visible = gridH / cardH;
        clampScroll(list.size());
        SkinEntry hovered = null;
        int hx = 0, hy = 0;
        String current = look().skin();
        int start = scroll * cols;
        int end = Math.min(list.size(), start + (visible + 1) * cols);
        boolean still = tick - scrolledAt > STILL_TICKS;
        gridItems = gridChecks = 0;
        boolean firsts = true;
        for (int i = start; i < end; i++) { // every card's first frame before any animation
            String first = list.get(i).textures[0];
            ask(first);
            firsts &= asked.contains(first);
        }
        firstsDone = firsts;
        if (still && firsts) prefetch(list, start, end, tick);
        gridStart = start;
        gridEnd = end;
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
            if (e.animated() && frame >= 0 && !movedAt.containsKey(e.id)) {
                if (!shownAt.containsKey(e.id)) shownAt.put(e.id, tick);
                if (frame > 0) movedAt.put(e.id, tick);
            }
            drawCardName(g, cx, cy, e.id, e.name, e.color);
            if (e.animated() || e.missingFrames) animatedBadge(g, cx + cardW - 8, cy + 3, e.animated() ? Theme.ACCENT : MUTED);
            boolean fav = Favorites.skin(e.id);
            if (fav) star(g, cx, cy);
            int ly = fav ? cy + 12 : cy + 3; // under the star
            if (e.learned) g.fill(cx + 3, ly, cx + 6, ly + 3, 0xFF66DD88);
            if (hover) {
                hovered = e;
                hx = cx;
                hy = cy;
            }
        }
        g.disableScissor();
        // Warm the next rows with what the budget leaves, so scrolling down rarely shows empty cards.
        for (int i = end; i < Math.min(list.size(), end + cols * 2); i++) ask(list.get(i).textures[0]);
        if (list.isEmpty() && !Repo.get().skins.isEmpty()) {
            g.centeredText(font, "Nothing matches \"" + query + "\"", midX + midW / 2, gridY + 20, MUTED);
        }
        if (gridHover.settled(hovered == null ? null : hovered.id)) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name).withColor(hovered.color & 0xFFFFFF));
            if (hovered.animated()) {
                tip.add(Component.literal("Animated · " + hovered.textures.length + " frames").withStyle(ChatFormatting.LIGHT_PURPLE));
                if (hovered.timing == SkinEntry.Timing.GUESSED) tip.add(Component.literal("Timing estimated").withStyle(ChatFormatting.GRAY));
                else if (hovered.timing == SkinEntry.Timing.LEARNED) tip.add(Component.literal("Timing learned in game").withStyle(ChatFormatting.GRAY));
            } else if (hovered.missingFrames) {
                tip.add(Component.literal("Animated on Hypixel · preview it once to learn its frames").withStyle(ChatFormatting.GRAY));
            }
            if (hovered.learned) tip.add(Component.literal("Learned in game").withStyle(ChatFormatting.GREEN));
            tip.add(Component.literal(hovered.id.equals(current) ? "Click to remove" : "Click to apply")
                .withStyle(ChatFormatting.YELLOW));
            tip.add(favoriteHint(Favorites.skin(hovered.id)));
            Tips.show(g, font, tip, gridTip(hx, hy), mouseX, mouseY);
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
        // The frame the preview, the model and My Items show at this tick (one clock for all), once it is loaded;
        // while scrolling only frames asked for already, so a scroll never starts downloads.
        int want = e.frameAt(tick);
        if (want == 0 || (animate || asked.contains(e.textures[want])) && ready(e.textures[want], false)) {
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
            mostPerTick = Math.max(mostPerTick, ++askedThisTick);
        }
        return Textures.ready(texture);
    }

    /**
     * Asks for the animation frames of the cards in view whose first frame is ready, in the order each card will
     * show them and one step for every card before the next step, so they all start moving at about the same
     * time rather than one card after another. A card whose every frame was asked for is skipped from then on.
     */
    private void prefetch(List<SkinEntry> list, int start, int end, long tick) {
        for (int step = 1; frameBudget > 0; step++) {
            boolean more = false;
            for (int i = start; i < end && frameBudget > 0; i++) {
                SkinEntry e = list.get(i);
                int n = e.textures.length;
                if (step >= n || prefetched.contains(e.id) || !lastFrame.containsKey(e.id)) continue;
                more = true;
                String t = e.textures[(e.frameAt(tick) + step) % n];
                if (!asked.contains(t)) ready(t, false);
                if (step == n - 1 && asked.containsAll(Arrays.asList(e.textures))) prefetched.add(e.id);
            }
            if (!more) return;
        }
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
        int hx = 0, hy = 0;
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
            if (hover) {
                hovered = d;
                hx = cx;
                hy = cy;
            }
        }
        g.disableScissor();
        if (gridHover.settled(hovered == null ? null : hovered.id)) {
            int rgb = hovered.rgbAt(tick, 0);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name).withColor(hovered.nameColor & 0xFFFFFF));
            tip.add(Component.literal(String.format(Locale.ROOT, "#%06X", rgb)).withColor(rgb));
            if (hovered.animated()) {
                tip.add(Component.literal("Animated · " + hovered.colors.length + " colors")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            tip.add(Component.literal(hovered.id.equals(current) ? "Click to remove" : "Click to apply")
                .withStyle(ChatFormatting.YELLOW));
            tip.add(favoriteHint(Favorites.dye(hovered.id)));
            Tips.show(g, font, tip, gridTip(hx, hy), mouseX, mouseY);
        }
    }

    /** A card's tooltip: beside the grid (over the preview, or the item list), level with the card. */
    private ClientTooltipPositioner gridTip(int cx, int cy) {
        return Tips.beside(midX - 2, gridY - 2, midX + midW + 4, gridY + gridH + 2, cx, cy);
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
                .append(Component.literal(" to unfavorite").withStyle(ChatFormatting.GRAY));
        }
        return Component.literal("Right-click").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" to favorite ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("★").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" (listed first)").withStyle(ChatFormatting.GRAY));
    }

    /** Says what a right-click on a card did, and moves the card to its place in the list at once. */
    private boolean starred(boolean on, String name) {
        refilter();
        flash(on ? "★ Favorited " + name + ": listed first" : "Unfavorited " + name, on ? GOLD : MUTED);
        return true;
    }

    private void drawPaste(GuiGraphicsExtractor g) {
        g.textWithWordWrap(font, Component.literal("Paste a head's Value (e.g. from minecraft-heads.com), a skin URL "
            + "or a texture hash. Works on any item."), midX, gridY + 4, Math.min(midW, 360), MUTED);
    }

    private void drawStyle(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (ident() == null) {
            g.text(font, "Pick an item on the left", midX, gridY + 6, MUTED);
            return;
        }
        int top = PAD + 22, bottom = styleBottom(), dy = styleScroll;
        boolean inView = mouseY >= top && mouseY < bottom;
        g.enableScissor(midX, top, midX + midW, bottom);
        g.text(font, "Name", midX, nameTop + 3 - dy, Theme.ACCENT, false);
        g.text(font, "Enchant Glint", midX, glintTop - dy, Theme.ACCENT, false);
        // Live: the glint shows here. 3x in the roomy layout, 2x in the compact one.
        bigItem(g, target(), glintIconX, glintIconY - dy, glintIcon, inView ? mouseX : -1, mouseY, null);
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
            Shapes.round(g, midX + midW + 3, top, 2, bottom - top, 1, Theme.fade(Theme.SURFACE, 0.8f));
            Shapes.round(g, midX + midW + 3, ty, 2, thumb, 1, Theme.DIM);
        }
    }

    /** Saved: every look in sections, each row opening to its changes (see {@link SavedTab}). */
    private void drawSaved(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        boolean any = savedTab.render(g, midX, gridY, midW, gridH, mouseX, mouseY, tick);
        if (any) return;
        boolean none = Looks.uuidLooks().isEmpty() && Looks.typeLooks().isEmpty();
        g.centeredText(font, none ? "Nothing saved yet" : "Nothing matches \"" + query + "\"", midX + midW / 2, gridY + 20, MUTED);
        if (none) {
            g.textWithWordWrap(font, Component.translatable("skycosmetics.saved.emptyHelp"), midX + 12, gridY + 34,
                midW - 24, 0xFF6A6A78);
        }
    }

    /** "Looks saved in other mods: Skyblocker, SkyOcean", once per layout. */
    private static String otherNote() {
        List<String> names = new ArrayList<>();
        for (OtherLooks.Source src : OtherLooks.sources()) if (!names.contains(src.name())) names.add(src.name());
        return Component.translatable("skycosmetics.other.about", String.join(", ", names)).getString();
    }

    /** Other Mods: what other mods change on your items, with their mod's chip (see {@link OtherModsTab}). */
    private void drawOther(GuiGraphicsExtractor g, int mouseX, int mouseY, long tick) {
        g.text(font, clip(otherNote, midW), midX, otherNoteY + 1, MUTED);
        boolean overNote = mouseX >= midX && mouseX < midX + midW && mouseY >= otherNoteY && mouseY < otherNoteY + 10;
        if (noteHover.settled(overNote ? otherNote : null)) {
            Tips.show(g, font, List.of(Component.literal(otherNote), Component.translatable("skycosmetics.other.aboutHelp")
                .withStyle(ChatFormatting.GRAY)), Tips.beside(midX - 2, PAD, midX + midW + 4, height - PAD, midX, otherNoteY), mouseX, mouseY);
        }
        boolean any = otherTab.render(g, midX, gridY, midW, gridH, mouseX, mouseY, tick);
        if (any) return;
        boolean none = OtherLooks.all().isEmpty();
        g.centeredText(font, none ? Component.translatable("skycosmetics.other.none").getString()
            : "Nothing matches \"" + query + "\"", midX + midW / 2, gridY + 20, MUTED);
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
        if (event.button() == 1) { // a right-click on one of the player's gradients removes it
            for (Map.Entry<Button, int[]> e : customGradients.entrySet()) {
                if (!e.getKey().visible || !e.getKey().isMouseOver(mx, my)) continue;
                GradientPresets.remove(e.getValue());
                flash("Gradient preset removed", MUTED);
                rebuildWidgets();
                return true;
            }
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (tooSmall) return false;
        if (nameBox != null && getFocused() == nameBox) setFocused(null); // a click elsewhere shows the styled name
        if (mx < midX) return clickLeft(mx, my, event.button());
        if (clickSummary(mx, my)) return true;
        if (mx >= midX + midW || my < gridY || my >= gridY + gridH) return false;

        if (tab == Tab.SAVED) return savedTab.click(mx, my, event.button());
        if (tab == Tab.OTHER) return otherTab.click(mx, my, event.button());
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
        // Ctrl+Z: the footer's Undo, while it shows (text boxes here have no undo of their own).
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_Z && (event.hasControlDownWithQuirk() || minecraft.hasControlDown())
            && !event.hasShiftDown() && undoShown()) {
            runUndo();
            return true;
        }
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
        if (tab == Tab.SAVED && savedTab.scrolled(mx, my, dy, minecraft.hasShiftDown())) return true;
        if (tab == Tab.OTHER && otherTab.scrolled(mx, my, dy, minecraft.hasShiftDown())) return true;
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
        return popup instanceof ColorPopup c ? c : null;
    }

    /** The gradient builder, while it is open. */
    public GradientPopup gradientPopup() {
        return popup instanceof GradientPopup g ? g : null;
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

    /**
     * The animated cards in view: for each, the ticks from its first frame to its first move (-1: not moved yet,
     * -2: no frame yet).
     */
    public int[] startDelays() {
        List<Integer> out = new ArrayList<>();
        for (int i = gridStart; i < Math.min(gridEnd, skinResults.size()); i++) {
            SkinEntry e = skinResults.get(i);
            if (!e.animated()) continue;
            Long shown = shownAt.get(e.id), moved = movedAt.get(e.id);
            out.add(shown == null ? -2 : moved == null ? -1 : (int) (moved - shown));
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The most textures the grid asked for in one game tick since the studio opened. */
    public int mostAskedPerTick() {
        return mostPerTick;
    }

    /**
     * What the grid in view may ask for while it sits still: every frame of its cards and the first frames of
     * the next two rows. It must never ask for more (every frame of every skin).
     */
    public int gridWanted() {
        int n = 0;
        for (int i = gridStart; i < Math.min(gridEnd, skinResults.size()); i++) n += skinResults.get(i).textures.length;
        return n + Math.max(0, Math.min(skinResults.size(), gridEnd + cols * 2) - gridEnd);
    }

    /** What the Saved tab lists: section titles, rows ("> " closed, "v " open) and their changes. */
    public List<String> savedRows() {
        return savedTab == null ? List.of() : savedTab.describe();
    }

    /** Opens or closes a Saved row: "I" + UUID or "T" + type. */
    public void setSavedOpen(String key, boolean open) {
        savedTab.setOpen(key, open);
    }

    /** Where a Saved row's × was drawn, then each of its open changes' × (x, y pairs); null if not listed. */
    public int[] savedButtons(String key) {
        return savedTab.buttons(key);
    }

    /** What the Other Mods tab lists: section titles, items and each change. */
    public List<String> otherRows() {
        return otherTab == null ? List.of() : otherTab.describe();
    }

    /** Where the × and "Move Here" of a change on Other Mods were drawn (x, y, x, y); null if not listed. */
    public int[] otherButtons(String item, String mod, OtherLooks.Kind kind) {
        return otherTab.buttons(item, mod, kind);
    }

    /** The other mods' chips over the summary: their labels, and where each one's center is (x, y). */
    public Map<String, int[]> chips() {
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (OtherChip c : chips) out.put(c.getMessage().getString(), new int[]{c.getX() + c.getWidth() / 2, c.getY() + c.getHeight() / 2});
        return out;
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
