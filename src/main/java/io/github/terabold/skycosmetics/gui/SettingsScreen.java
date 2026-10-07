package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.CardButton;
import io.github.terabold.skycosmetics.gui.hub.CloseButton;
import io.github.terabold.skycosmetics.gui.hub.Controls;
import io.github.terabold.skycosmetics.gui.hub.KeyBindButton;
import io.github.terabold.skycosmetics.gui.hub.Overlay;
import io.github.terabold.skycosmetics.gui.hub.OverlayHost;
import io.github.terabold.skycosmetics.gui.hub.SearchBox;
import io.github.terabold.skycosmetics.gui.hub.SectionTab;
import io.github.terabold.skycosmetics.gui.hub.ToggleSwitch;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The settings: one window in the studio's colors. A header (logo, name, version, search, close), a sidebar of
 * sections (the studio's own first, then one per feature) and the open section's rows in a pane that scrolls. Each
 * row is its title and gray help on the left and its control on the right, or under the text when the window is too
 * narrow for both. Every change applies and saves at once.
 *
 * The search looks through every section at once: each typed word must appear in a setting's title, help, group,
 * section, choices or search words. Results keep working controls, grouped under "Section > Group" lines that jump
 * to the setting in its section; so does Enter, and a click on a section in the sidebar scrolls to its results.
 *
 * Rows are vanilla widgets (Tab, narration and tests work as everywhere), placed by the scroll each frame and drawn
 * inside the pane's scissor; clicks outside the pane never reach them. Text is wrapped when rows are built, never per
 * frame, and every animation runs on real time without allocating. A feature's row that throws while built, drawn or
 * used is reported and shown as a gray line; the rest of the window keeps working.
 *
 * One screen behind every door: {@code /skycosmetics}, Mod Menu's Configure button ({@link #create}) and the
 * studio's Settings button. Esc and the x return to whatever opened it.
 */
public class SettingsScreen extends Screen implements Host, OverlayHost {
    private static final int MAX_W = 680, MAX_H = 420, HEADER_H = 26, HEAD_H = 34, RAIL_W = 30, MIN_W = 280, MIN_H = 170;
    private static final int ROW_X = 8, ROW_Y = 6, GAP = 12, MIN_TEXT = 110, WHEEL = 30, BAR_W = 3;
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, "icon.png");
    private static final String[] NONE = new String[0];
    /** How long a row found by a search glows after the jump. */
    private static final long FLASH_MS = 1600;
    /** How long a message from {@link #flash} shows. */
    private static final long TOAST_MS = 3500;

    /** Where the settings were last, this session: Mod Menu and the command come back to it. */
    private static String lastSection;
    private static double lastScroll;

    private final Screen parent;
    private String sectionId;
    /** A row to scroll to and highlight once the rows are built, then null. */
    private String pendingOption;

    // ------------------------------------------------------------- layout, set in layout()
    private boolean tooSmall, rail;
    private int wx, wy, ww, wh, sideW, bodyY, cx, cw, vx, vy, vw, vh, sideTop, sideH, tabStep, titleRight;

    // ------------------------------------------------------------- widgets
    private SearchBox search;
    private CloseButton close;
    private ActionButton done;
    private final List<SectionTab> tabs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<KeyBindButton> keys = new ArrayList<>();
    private Overlay overlay;

    // ------------------------------------------------------------- content
    private Section section;
    private final Map<String, List<Option>> options = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private String typed = "";
    private String[] words = NONE;
    private boolean muteSearch;
    private double scrollBeforeSearch;
    private int results;
    private FormattedCharSequence headTitle = FormattedCharSequence.EMPTY, logoTitle = FormattedCharSequence.EMPTY;
    private String headHelp = "", version = "", goTo = "", brokenRow = "";
    private Component headHelpFull = Component.empty();
    /** The pane header's help line was cut: hovering it shows the whole line. */
    private boolean headClipped;
    private ItemStack headIcon = ItemStack.EMPTY;
    /** A message from {@link #flash}, cut to the pane on its first frame. */
    private Component toast;
    private String toastLine;
    private long toastAt;
    private int contentH;
    /** The accent the rows' text was built with. */
    private int themeVersion;

    // ------------------------------------------------------------- motion
    private double scroll, shown, maxScroll, sideScroll, sideShown, sideMax;
    private boolean draggingBar;
    private double barGrab;
    private long lastFrame = -1;
    private float barY = -1;
    private boolean opened;
    /**
     * The key just pressed was ours (a key bound, Ctrl+F): its character, which arrives right after it in the same
     * event poll, must not type into the search. Cleared every frame, so a key without a character (F5, an arrow)
     * never eats the next letter typed.
     */
    private boolean swallowChar;
    private Row flashRow;
    private long flashAt;
    private final Anim openAnim = new Anim(150, 0), switchAnim = new Anim(170, 1), barHover = new Anim(100, 0);

    /** On the studio's own section: what the tests and the studio's Settings button open. */
    public SettingsScreen(Screen parent) {
        this(parent, Hub.STUDIO);
    }

    /** On the section with this id; an unknown id opens the first section. */
    public SettingsScreen(Screen parent, String sectionId) {
        this(parent, sectionId, null);
    }

    /** On a section, scrolled to the row with this option id, which glows for a moment (null: the top). */
    public SettingsScreen(Screen parent, String sectionId, String optionId) {
        super(Component.translatable("skycosmetics.name"));
        this.parent = parent;
        this.sectionId = sectionId;
        this.pendingOption = optionId;
    }

    /** For Mod Menu and {@code /skycosmetics}: where the settings were last this session, else the studio's section. */
    public static Screen create(Screen parent) {
        SettingsScreen s = new SettingsScreen(parent, lastSection != null ? lastSection : Hub.STUDIO);
        if (lastSection != null) s.scroll = s.shown = lastScroll;
        return s;
    }

    // ============================================================ building

    @Override
    protected void init() {
        closeOverlay();
        tabs.clear();
        rows.clear();
        keys.clear();
        options.clear();
        entries.clear();
        flashRow = null;
        draggingBar = false;
        layout();
        if (tooSmall) {
            done = new ActionButton(CommonComponents.GUI_DONE, 80, true, b -> onClose());
            done.setPosition(width / 2 - 40, height / 2 + 6);
            addWidget(done);
            return;
        }
        section = Hub.find(sectionId);
        if (section == null) section = Hub.sections().getFirst();
        sectionId = section.id();
        lastSection = sectionId;
        version = FabricLoader.getInstance().getModContainer(SkyCosmetics.MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        logoTitle = Component.translatable("skycosmetics.name").withStyle(ChatFormatting.BOLD).getVisualOrderText();
        goTo = Component.translatable("skycosmetics.menu.search.goTo").getString();
        brokenRow = Component.translatable("skycosmetics.menu.brokenRow").getString();

        int searchW = Math.clamp(ww * 34 / 100, 96, 210);
        close = new CloseButton(Component.translatable("skycosmetics.menu.close"),
            Component.translatable("skycosmetics.menu.close.tooltip"), b -> onClose());
        close.setPosition(wx + ww - 6 - CloseButton.SIZE, wy + 5);
        close.setTabOrderGroup(1);
        search = new SearchBox(font, searchW, Component.translatable("skycosmetics.menu.search.hint"));
        search.setPosition(close.getX() - 6 - searchW, wy + 5);
        search.setValue(typed);
        search.setResponder(this::searched);
        Tips.set(search, Component.translatable("skycosmetics.menu.search.tooltip"), 700);
        titleRight = search.getX() - 8;
        addWidget(search);
        addWidget(close);

        for (Section s : Hub.sections()) {
            SectionTab t = new SectionTab(s, sideW - (rail ? 6 : 10), rail, b -> clickTab(((SectionTab) b).section()));
            tabs.add(t);
            addWidget(t);
            collect(s);
        }
        tabStep = (rail ? SectionTab.RAIL_H : SectionTab.H) + 2;
        sideMax = Math.max(0, tabs.size() * tabStep + 6 - sideH);
        sideScroll = sideShown = Math.clamp(sideScroll, 0, sideMax);

        buildRows();
        if (pendingOption != null) {
            Row r = rowOf(section, pendingOption);
            pendingOption = null;
            if (r != null) reveal(r, true);
        }
    }

    private void layout() {
        tooSmall = width < MIN_W || height < MIN_H || Hub.sections().isEmpty();
        int margin = width >= 720 && height >= 460 ? 28 : width >= 480 && height >= 300 ? 12 : 4;
        ww = Math.min(width - 2 * margin, MAX_W);
        wh = Math.min(height - 2 * margin, MAX_H);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        rail = ww < 400;
        sideW = rail ? RAIL_W : Math.clamp(ww * 27 / 100, 112, 156);
        bodyY = wy + HEADER_H + 1;
        cx = wx + sideW + 1;
        cw = wx + ww - cx;
        vx = cx + 6;
        vw = cw - 6 - (BAR_W + 6);
        vy = bodyY + HEAD_H + 1;
        vh = wy + wh - 4 - vy;
        sideTop = bodyY + 5;
        sideH = wy + wh - 5 - sideTop;
    }

    /** A section's options, read once per opening, and what a search can match in each. */
    private void collect(Section s) {
        List<Option> list;
        try {
            list = List.copyOf(s.rows().apply(this));
        } catch (RuntimeException e) {
            Io.failed("Building the settings section " + s.id(), e);
            options.put(s.id(), null);
            return;
        }
        options.put(s.id(), list);
        String where = (s.name().getString() + " " + s.sub().getString()).toLowerCase(Locale.ROOT);
        Option group = null;
        for (Option o : list) {
            if (o.control() instanceof Control.Header) {
                group = o;
                continue;
            }
            StringBuilder hay = new StringBuilder(o.title().getString()).append(' ').append(o.help().getString())
                .append(' ').append(o.search()).append(' ').append(where);
            if (group != null) hay.append(' ').append(group.title().getString());
            switch (o.control()) {
                case Control.Action a -> hay.append(' ').append(a.label().getString());
                case Control.Choice<?> c -> choices(c, hay);
                default -> {}
            }
            entries.add(new Entry(s, group, o, hay.toString().toLowerCase(Locale.ROOT)));
        }
    }

    private static <T> void choices(Control.Choice<T> c, StringBuilder hay) {
        for (T v : c.values()) hay.append(' ').append(c.label().apply(v).getString());
    }

    /** Builds the rows the pane shows: the open section's, or every match of the search. */
    private void buildRows() {
        GuiEventListener focused = getFocused();
        for (Row r : rows) {
            if (r.widget == null) continue;
            if (focused == r.widget) setFocused(null);
            removeWidget(r.widget);
        }
        rows.clear();
        keys.clear();
        flashRow = null;
        results = 0;
        if (words.length == 0) {
            List<Option> list = options.get(section.id());
            if (list == null) rows.add(message(Component.translatable("skycosmetics.menu.broken")));
            else if (list.isEmpty()) rows.add(message(Component.translatable("skycosmetics.menu.empty")));
            else for (Option o : list) add(section, o);
            for (SectionTab t : tabs) {
                t.setCount(-1);
                t.setSelected(t.section() == section);
            }
        } else {
            int[] counts = new int[tabs.size()];
            Section lastIn = null;
            Option lastGroup = null;
            for (Entry e : entries) {
                if (!matches(e.hay)) continue;
                if (e.section != lastIn || e.group != lastGroup) {
                    rows.add(group(e.section, e.group));
                    lastIn = e.section;
                    lastGroup = e.group;
                }
                if (add(e.section, e.option)) {
                    results++;
                    counts[Math.max(0, tabIndex(e.section))]++;
                }
            }
            for (int i = 0; i < tabs.size(); i++) {
                tabs.get(i).setCount(counts[i]);
                tabs.get(i).setSelected(false);
            }
            if (results == 0) rows.add(message(Component.translatable("skycosmetics.menu.search.none.help")));
        }
        themeVersion = Theme.version();
        layoutRows();
        head();
        refresh();
    }

    /** The accent changed (General's color): search results carry it in their highlights, so they are built again. */
    private void retheme() {
        themeVersion = Theme.version();
        if (words.length == 0) return;
        for (Row r : rows) {
            if (!r.setting() || r.option == null) continue;
            r.titleText = highlight(r.option.title().getString(), words);
            if (r.key == null) r.helpText = highlight(r.option.help().getString(), words);
            wrap(r);
        }
        layoutRows();
    }

    /** Adds a row for this option; false for a header or when it could not be built (logged, and left out). */
    private boolean add(Section s, Option o) {
        Row r;
        try {
            r = o.control() instanceof Control.Header ? header(o)
                : o.control() instanceof Control.Action a && o.title().getString().isEmpty() ? card(s, o, a)
                : option(s, o);
        } catch (RuntimeException e) {
            Io.failed("Building the setting " + o.id(), e);
            return false;
        }
        rows.add(r);
        if (r.widget != null) addWidget(r.widget);
        return r.kind != Kind.HEADER;
    }

    private Row header(Option o) {
        Row r = new Row(Kind.HEADER, section, o);
        r.title = List.of(o.title().copy().withStyle(ChatFormatting.BOLD).getVisualOrderText());
        r.titleW = font.width(r.title.getFirst());
        r.textX = ROW_X;
        r.textY = rows.isEmpty() ? 3 : 12;
        r.h = r.textY + 13;
        return r;
    }

    /** A titleless Action is the section's main action: a big card with the section's icon. */
    private Row card(Section s, Option o, Control.Action a) {
        CardButton card = new CardButton(a.label(), o.help(), iconOf(s), vw, b -> a.run().run());
        Row r = new Row(Kind.CARD, s, o);
        r.widget = card;
        r.ctrlY = rows.isEmpty() ? 2 : 6;
        r.h = r.ctrlY + card.getHeight() + 6;
        r.refresh = () -> {
            boolean on = a.active().getAsBoolean() && o.enabled().getAsBoolean();
            card.active = on;
            Tips.set(card, on ? null : a.inactiveTip());
        };
        return r;
    }

    private Row option(Section s, Option o) {
        boolean custom = o.control() instanceof Control.Custom;
        int inner = vw - 2 * ROW_X;
        int w = custom ? inner : Controls.width(o, vw);
        boolean stacked = custom || !(o.control() instanceof Control.Toggle) && inner - w - GAP < MIN_TEXT;
        if (stacked && !custom) w = Controls.stackedWidth(o, inner);
        Controls.Built b = Controls.build(o, w, this, this::keyChanged);
        Row r = new Row(custom ? Kind.CUSTOM : Kind.OPTION, s, o);
        r.widget = b.widget();
        r.refresh = b.refresh();
        r.stacked = stacked;
        r.textX = ROW_X;
        r.textW = Math.max(40, stacked ? inner : inner - r.widget.getWidth() - GAP);
        r.titleText = words.length == 0 ? o.title() : highlight(o.title().getString(), words);
        r.helpText = o.help();
        if (r.widget instanceof KeyBindButton k) {
            keys.add(k);
            r.key = k;
            r.helpText = k.help(o.help(), Theme.MUTED);
        } else if (words.length > 0) {
            r.helpText = highlight(o.help().getString(), words);
        }
        wrap(r);
        return r;
    }

    /** In search results: the section (and group) the rows under it come from; a click jumps there. */
    private Row group(Section s, Option groupHeader) {
        Row r = new Row(Kind.GROUP, s, groupHeader);
        MutableComponent text = s.name().copy(); // drawn in the accent, so it follows an accent change
        if (groupHeader != null) {
            text.append(Component.literal("  »  ").withColor(Theme.DIM & 0xFFFFFF))
                .append(groupHeader.title().copy().withColor(Theme.MUTED & 0xFFFFFF));
        }
        List<FormattedCharSequence> lines = font.split(text, Math.max(40, vw - 2 * ROW_X - 30 - font.width(goTo)));
        r.title = lines.isEmpty() ? List.of() : List.of(lines.getFirst());
        r.icon = iconOf(s);
        r.textX = ROW_X + 15;
        r.textY = rows.isEmpty() ? 4 : 12;
        r.h = r.textY + 14;
        return r;
    }

    private Row message(Component text) {
        Row r = new Row(Kind.MESSAGE, section, null);
        r.title = font.split(text.copy().withColor(Theme.MUTED & 0xFFFFFF), Math.max(60, vw - 40));
        r.h = 30 + r.title.size() * 10;
        return r;
    }

    /** Wraps a row's title and help to its text width and sizes the row around them and its control. */
    private void wrap(Row r) {
        r.title = r.titleText.getString().isEmpty() ? List.of() : font.split(r.titleText, r.textW);
        r.help = r.helpText.getString().isEmpty() ? List.of() : font.split(r.helpText, r.textW);
        int textH = r.title.isEmpty() ? 0 : r.title.size() * 10 - 1;
        if (!r.help.isEmpty()) textH += (textH > 0 ? 3 : 0) + r.help.size() * 10 - 1;
        int ch = r.widget.getHeight();
        if (r.stacked) {
            r.textY = ROW_Y;
            r.ctrlX = ROW_X;
            r.ctrlY = ROW_Y + textH + (textH > 0 ? 5 : 0);
            r.h = r.ctrlY + ch + ROW_Y;
        } else {
            int inner = Math.max(textH, ch);
            r.h = inner + 2 * ROW_Y;
            r.textY = ROW_Y + (inner - textH) / 2;
            r.ctrlX = vw - ROW_X - r.widget.getWidth();
            r.ctrlY = ROW_Y + (inner - ch) / 2;
        }
    }

    /** Stacks the rows and marks the hairlines between neighboring settings. */
    private void layoutRows() {
        int y = 0;
        Row prev = null;
        for (Row r : rows) {
            r.y = y;
            y += r.h;
            r.line = prev != null && prev.setting() && r.setting();
            prev = r;
        }
        contentH = y + 8;
        maxScroll = Math.max(0, contentH - vh);
        scroll = Math.clamp(scroll, 0, maxScroll);
        shown = Math.clamp(shown, 0, maxScroll);
    }

    /** The pane's header: the section, or what the search found. */
    private void head() {
        int room = cw - 32 - 10;
        Component title;
        if (words.length == 0) {
            headIcon = iconOf(section);
            title = section.name().copy().withStyle(ChatFormatting.BOLD);
            headHelpFull = section.help();
        } else {
            headIcon = ItemStack.EMPTY;
            title = Component.translatable("skycosmetics.menu.search.results", typed.strip()).withStyle(ChatFormatting.BOLD);
            headHelpFull = results == 0 ? Component.translatable("skycosmetics.menu.search.none")
                : Component.translatable(results == 1 ? "skycosmetics.menu.search.one" : "skycosmetics.menu.search.many", results);
        }
        List<FormattedCharSequence> t = font.split(title, Math.max(20, room));
        headTitle = t.isEmpty() ? FormattedCharSequence.EMPTY : t.getFirst();
        String full = headHelpFull.getString();
        headHelp = Ui.clip(font, full, room);
        headClipped = !headHelp.equals(full);
    }

    private ItemStack iconOf(Section s) {
        for (SectionTab t : tabs) if (t.section() == s) return t.icon();
        return ItemStack.EMPTY;
    }

    private int tabIndex(Section s) {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).section() == s) return i;
        return -1;
    }

    private Row rowOf(Section s, String optionId) {
        for (Row r : rows) {
            if (r.section == s && r.kind != Kind.GROUP && r.option != null && r.option.id().equals(optionId)) return r;
        }
        return null;
    }

    // ============================================================ search

    private void searched(String text) {
        if (muteSearch) return;
        typed = text;
        String q = text.strip().toLowerCase(Locale.ROOT);
        String[] w = q.isEmpty() ? NONE : q.split("\\s+");
        if (Arrays.equals(w, words)) {
            head();
            return;
        }
        if (words.length == 0) scrollBeforeSearch = scroll;
        boolean back = w.length == 0;
        words = w;
        closeOverlay();
        scroll = shown = back ? scrollBeforeSearch : 0;
        buildRows();
        if (back) switchAnim.set(0.4f);
    }

    private boolean matches(String hay) {
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    /** The text with every typed word in the accent. Built when the search or the accent changes, never per frame. */
    private static Component highlight(String text, String[] words) {
        String low = text.toLowerCase(Locale.ROOT);
        if (low.length() != text.length()) return Component.literal(text);
        boolean[] hit = new boolean[text.length()];
        boolean any = false;
        for (String w : words) {
            for (int i = low.indexOf(w); i >= 0; i = low.indexOf(w, i + 1)) {
                Arrays.fill(hit, i, i + w.length(), true);
                any = true;
            }
        }
        if (!any) return Component.literal(text);
        MutableComponent out = Component.empty();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || hit[i] != hit[start]) {
                MutableComponent part = Component.literal(text.substring(start, i));
                out.append(hit[start] ? part.withColor(Theme.ACCENT & 0xFFFFFF) : part);
                start = i;
            }
        }
        return out;
    }

    /** Leaves the search for this section and scrolls to the row (or group) found there. */
    private void jumpTo(Section s, Option option) {
        muteSearch = true;
        search.setValue("");
        muteSearch = false;
        typed = "";
        words = NONE;
        if (getFocused() == search) setFocused(null);
        closeOverlay();
        section = s;
        sectionId = s.id();
        lastSection = sectionId;
        scroll = shown = 0;
        buildRows();
        switchAnim.set(0);
        Row r = option == null ? null : rowOf(s, option.id());
        if (r != null) reveal(r, true);
    }

    /** Enter in the search: the first result, in its section. */
    private void jumpToFirst() {
        for (Row r : rows) {
            if (r.setting() || r.kind == Kind.CARD) {
                jumpTo(r.section, r.option);
                return;
            }
        }
    }

    private void focusSearch() {
        setFocused(search);
        search.moveCursorToEnd(false);
        search.setHighlightPos(0);
    }

    // ============================================================ host

    @Override
    public void changed() {
        Settings.save();
        refresh();
    }

    /** Every row's value and grayed-out state, read again. */
    private void refresh() {
        for (Row r : rows) {
            if (r.refresh == null) continue;
            try {
                r.refresh.run();
                r.enabled = r.option == null || r.option.enabled().getAsBoolean();
            } catch (RuntimeException e) {
                Io.failed("Refreshing the setting " + r.id(), e);
            }
        }
    }

    /** A key row changed: its help line may have grown (a clash, what to press) or shrunk. */
    private void keyChanged() {
        boolean any = false;
        for (Row r : rows) {
            if (r.key == null) continue;
            r.helpText = r.key.help(r.option.help(), Theme.MUTED);
            wrap(r);
            any = true;
        }
        if (any) layoutRows();
    }

    @Override
    public void openPopup(ColorPopup popup) {
        openOverlay(new PopupOverlay(popup));
    }

    @Override
    public void openOverlay(Overlay o) {
        if (overlay != null && overlay != o) overlay.close();
        overlay = o;
        shown = scroll; // the widget it hangs from stays where it is
        // In the body, under the header, unless the body is too narrow: then anywhere in the window.
        if (rail || cw < 330) o.fit(wx, wy, ww, wh);
        else o.fit(cx, bodyY, cw, wy + wh - bodyY);
    }

    /** Gives the open overlay an event; one that throws is reported and dropped, a closed one is let go. */
    private void onOverlay(Consumer<Overlay> event) {
        try {
            event.accept(overlay);
        } catch (RuntimeException e) {
            overlayFailed(e);
            return;
        }
        if (overlay != null && overlay.isClosed()) overlay = null;
    }

    private void overlayFailed(RuntimeException e) {
        Io.failed("Using a settings pop-up", e);
        overlay = null;
    }

    private void closeOverlay() {
        if (overlay == null) return;
        Overlay o = overlay;
        overlay = null;
        try {
            o.close(); // applies a half-typed hex value; its close saves
        } catch (RuntimeException e) {
            Io.failed("Closing a settings pop-up", e);
        }
    }

    private void clickTab(Section s) {
        if (words.length > 0) {
            for (Row r : rows) {
                if (r.kind == Kind.GROUP && r.section == s) {
                    scroll = Math.clamp(r.y, 0, maxScroll);
                    return;
                }
            }
            jumpTo(s, null); // nothing found there: show the section
            return;
        }
        if (s == section) {
            scroll = 0;
            return;
        }
        closeOverlay();
        section = s;
        sectionId = s.id();
        lastSection = sectionId;
        scroll = shown = 0;
        buildRows();
        switchAnim.set(0);
    }

    /** Scrolls so the row shows whole; {@code glow} puts it a third down the pane and lights it up for a moment. */
    private void reveal(Row r, boolean glow) {
        if (glow) {
            scroll = Math.clamp(r.y - vh / 3.0, 0, maxScroll);
            flashRow = r;
            flashAt = Util.getMillis();
        } else if (r.y < scroll) {
            scroll = Math.max(0, r.y - 4);
        } else if (r.y + r.h > scroll + vh) {
            scroll = Math.min(maxScroll, r.y + r.h - vh + 4);
        }
    }

    /** Tab and the arrows scroll the pane (or the sidebar) to what they focus. */
    @Override
    public void setFocused(GuiEventListener focused) {
        super.setFocused(focused);
        if (focused == null || minecraft == null || !minecraft.getLastInputType().isKeyboard()) return;
        for (Row r : rows) {
            if (r.widget == focused) {
                reveal(r, false);
                return;
            }
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) != focused) continue;
            int top = i * tabStep;
            if (top < sideScroll) sideScroll = top;
            else if (top + tabStep > sideScroll + sideH) sideScroll = Math.min(sideMax, top + tabStep - sideH);
        }
    }

    /**
     * A short message at the bottom of the window for a few seconds, e.g. what an action just did ("Imported 3
     * looks"). A new message replaces the old one.
     */
    @Override
    public void flash(Component message) {
        toast = message;
        toastLine = null;
        toastAt = Util.getMillis();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        closeOverlay();
        if (!tooSmall && words.length == 0) lastScroll = scroll;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ============================================================ render

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) extractPanorama(g, delta);
        extractBlurredBackground(g);
        g.fill(0, 0, width, height, Theme.SHADE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        swallowChar = false;
        if (tooSmall) {
            g.centeredText(font, Component.translatable("skycosmetics.menu.tooSmall"), width / 2, height / 2 - 12, Theme.TEXT);
            if (done != null) done.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        if (overlay != null && overlay.isClosed()) overlay = null;
        if (themeVersion != Theme.version()) retheme();
        // Under an open overlay nothing is hovered: no highlights, no tooltips.
        int mx = overlay != null ? -10000 : mouseX, my = overlay != null ? -10000 : mouseY;
        step();
        float open = Anim.easeOut(openAnim.to(1));
        if (open >= 1) opened = true;
        int lift = opened ? 0 : Math.round((1 - open) * 8);
        g.pose().pushMatrix();
        g.pose().translate(0, lift);
        window(g);
        header(g, mx, my, delta);
        sidebar(g, mx, my, delta);
        content(g, mx, my, delta);
        toast(g);
        g.pose().popMatrix();
        if (overlay == null) Tips.widgets(g, font, children(), mx, my, this::tipPlace);
        if (overlay != null) {
            try {
                overlay.render(g, mouseX, mouseY);
            } catch (RuntimeException e) {
                overlayFailed(e);
            }
        }
    }

    /**
     * Where a widget's tooltip goes: under the header's search and close; right of the sidebar for a section;
     * left of a row's control, over its own row's text, else above or under it. Never over the controls near it.
     */
    private ClientTooltipPositioner tipPlace(AbstractWidget w) {
        if (w == search || w == close) {
            return Tips.beside(w.getX(), w.getY(), w.getRight(), w.getBottom(), w.getX(), w.getY(), Tips.Side.BELOW, Tips.Side.LEFT);
        }
        if (w instanceof SectionTab) return Tips.beside(wx, sideTop, cx, sideTop + sideH, w.getX(), w.getY(), Tips.Side.RIGHT);
        return Tips.beside(w.getX(), w.getY(), w.getRight(), w.getBottom(), w.getX(), w.getY(), Tips.Side.LEFT, Tips.Side.ABOVE,
            Tips.Side.BELOW);
    }

    /** Moves the scrolls toward their targets by the time since the last frame. */
    private void step() {
        long now = Util.getMillis();
        long dt = lastFrame < 0 ? 1000 : Math.min(250, now - lastFrame);
        lastFrame = now;
        double k = 1 - Math.exp(-dt / 55.0);
        shown = draggingBar || Math.abs(scroll - shown) < 0.5 ? scroll : shown + (scroll - shown) * k;
        sideShown = Math.abs(sideScroll - sideShown) < 0.5 ? sideScroll : sideShown + (sideScroll - sideShown) * k;
    }

    private void window(GuiGraphicsExtractor g) {
        int r = Theme.RADIUS, bh = wy + wh - bodyY;
        Shapes.shadow(g, wx, wy, ww, wh, r, 8);
        Shapes.round(g, wx, wy, ww, wh, r, Theme.CHROME);
        Shapes.round(g, cx, bodyY, cw, bh, r, Theme.BODY);
        g.fill(cx, bodyY, cx + cw, bodyY + r, Theme.BODY);
        g.fill(cx, bodyY, cx + r, bodyY + bh, Theme.BODY);
        g.fill(cx - 1, bodyY, cx, wy + wh - 1, Theme.LINE_SOFT);
        Shapes.gradient(g, wx + 1, bodyY - 1, wx + ww - 1, bodyY, Theme.GRADIENT_START, Theme.GRADIENT_END);
        Shapes.frame(g, wx, wy, ww, wh, r, Theme.LINE);
    }

    private void header(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, wx + 7, wy + 5, 0, 0, 16, 16, 128, 128, 128, 128);
        int tx = wx + 28, nameW = font.width(logoTitle);
        if (tx + nameW <= titleRight) {
            g.text(font, logoTitle, tx, wy + 9, Theme.ACCENT, false);
            int at = tx + nameW + 5;
            if (!version.isEmpty() && at + font.width(version) <= titleRight) g.text(font, version, at, wy + 9, Theme.DIM, false);
        }
        search.extractRenderState(g, mx, my, delta);
        close.extractRenderState(g, mx, my, delta);
    }

    private void sidebar(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int left = wx + (rail ? 3 : 5);
        boolean inSide = mx >= wx && mx < cx && my >= sideTop && my < sideTop + sideH;
        int smx = inSide ? mx : -10000, smy = inSide ? my : -10000;
        g.enableScissor(wx + 1, sideTop, cx - 1, sideTop + sideH);
        int y = sideTop + 1 - (int) Math.round(sideShown);
        int target = Integer.MIN_VALUE;
        for (SectionTab t : tabs) {
            t.setPosition(left, y);
            if (t.selected()) target = y;
            if (y + t.getHeight() > sideTop && y < sideTop + sideH) t.extractRenderState(g, smx, smy, delta);
            y += tabStep;
        }
        if (target != Integer.MIN_VALUE) {
            // The accent bar glides to the open section.
            barY = barY < 0 || Math.abs(barY - target) > sideH ? target : barY + (target - barY) * 0.35f;
            if (Math.abs(barY - target) < 0.5f) barY = target;
            int th = rail ? SectionTab.RAIL_H : SectionTab.H;
            Shapes.round(g, wx + 1, Math.round(barY) + 5, 2, th - 10, 1, Theme.ACCENT);
        }
        // Soft edges where more sections hide.
        if (sideShown > 1) g.fillGradient(wx + 1, sideTop, cx - 1, sideTop + 8, Theme.CHROME, Theme.CHROME & 0xFFFFFF);
        if (sideShown < sideMax - 1) {
            g.fillGradient(wx + 1, sideTop + sideH - 8, cx - 1, sideTop + sideH, Theme.CHROME & 0xFFFFFF, Theme.CHROME);
        }
        g.disableScissor();
        if (sideMax > 0) {
            int track = sideH - 4, thumb = Math.max(12, (int) (track * (long) sideH / (sideH + (long) sideMax)));
            int ty = sideTop + 2 + (int) Math.round((track - thumb) * sideShown / sideMax);
            Shapes.round(g, cx - 4, ty, 2, thumb, 1, Theme.DIM);
        }
    }

    private void content(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // The pane's header.
        int hx = cx + 10, hy = bodyY + 8;
        if (!headIcon.isEmpty()) g.item(headIcon, hx, bodyY + (HEAD_H - 16) / 2);
        else Ui.magnifier(g, hx + 3, bodyY + (HEAD_H - 9) / 2, Theme.ACCENT);
        g.text(font, headTitle, hx + 22, hy, Theme.TEXT, false);
        g.text(font, headHelp, hx + 22, hy + 12, Theme.MUTED, false);
        if (headClipped && mx >= hx + 22 && mx < cx + cw && my >= hy + 11 && my < hy + 21) {
            Tips.show(g, font, List.of(headHelpFull), Tips.beside(hx + 22, hy + 11, cx + cw, hy + 21, hx + 22, hy + 11,
                Tips.Side.BELOW, Tips.Side.ABOVE), mx, my);
        }
        g.fill(cx + 8, vy - 1, cx + cw - 8, vy, Theme.LINE_SOFT);

        float sw = Anim.easeOut(switchAnim.to(1));
        int slide = Math.round((1 - sw) * 10);
        boolean inView = inPane(mx, my);
        int pmx = inView ? mx : -10000, pmy = inView ? my : -10000;
        int base = vy - (int) Math.round(shown) + slide;
        g.enableScissor(cx + 1, vy, cx + cw - 1, vy + vh);
        for (Row r : rows) {
            int ry = base + r.y;
            if (r.widget != null) r.widget.setPosition(vx + r.ctrlX, ry + r.ctrlY);
            if (ry + r.h <= vy || ry >= vy + vh) continue;
            if (r.failed) {
                // Its title, if it has one, and what went wrong under it.
                int ty = r.title.isEmpty() ? ry + (r.h - 8) / 2 : ry + r.textY;
                if (!r.title.isEmpty()) {
                    g.text(font, r.title.getFirst(), vx + r.textX, ty, Theme.DIM, false);
                    ty += 13;
                }
                g.text(font, brokenRow, vx + ROW_X, ty, Theme.fade(Theme.WARN, 0.75f), false);
                continue;
            }
            try {
                row(g, r, ry, pmx, pmy, delta);
            } catch (RuntimeException e) {
                fail(r, "Drawing the setting " + r.id(), e);
            }
        }
        // Soft edges where more rows hide, and the fade-in after a section switch.
        if (shown > 1) g.fillGradient(cx + 1, vy, cx + cw - 1, vy + 8, Theme.BODY, Theme.BODY & 0xFFFFFF);
        if (shown < maxScroll - 1) g.fillGradient(cx + 1, vy + vh - 8, cx + cw - 1, vy + vh, Theme.BODY & 0xFFFFFF, Theme.BODY);
        if (sw < 1) g.fill(cx + 1, vy, cx + cw - 1, vy + vh, Theme.fade(Theme.BODY, 1 - sw));
        g.disableScissor();
        scrollbar(g, mx, my);
    }

    private void row(GuiGraphicsExtractor g, Row r, int ry, int mx, int my, float delta) {
        boolean over = mx >= vx && mx < vx + vw && my >= ry && my < ry + r.h;
        switch (r.kind) {
            case HEADER -> {
                int tx = vx + r.textX, ty = ry + r.textY;
                g.text(font, r.title.getFirst(), tx, ty, Theme.ACCENT, false);
                int lx = tx + r.titleW + 6, end = vx + vw - ROW_X;
                if (lx < end) g.fill(lx, ty + 4, end, ty + 5, Theme.LINE_SOFT);
            }
            case GROUP -> {
                float hv = r.hover.to(over ? 1 : 0);
                if (over) g.requestCursor(CursorTypes.POINTING_HAND);
                int top = ry + r.textY - 4;
                if (hv > 0) Shapes.round(g, vx, top, vw, 16, 4, Theme.fade(Theme.SURFACE, hv));
                g.pose().pushMatrix();
                g.pose().translate(vx + ROW_X - 2, top + 2);
                g.pose().scale(0.75f, 0.75f);
                g.item(r.icon, 0, 0);
                g.pose().popMatrix();
                if (!r.title.isEmpty()) g.text(font, r.title.getFirst(), vx + r.textX, ry + r.textY, Theme.ACCENT, false);
                if (hv > 0) {
                    int gx = vx + vw - ROW_X - 6 - font.width(goTo), color = Theme.fade(Theme.ACCENT, hv);
                    g.text(font, goTo, gx, ry + r.textY, color, false);
                    Ui.chevronRight(g, vx + vw - ROW_X - 3, ry + r.textY + 1, color);
                }
            }
            case MESSAGE -> {
                int ty = ry + 15;
                for (FormattedCharSequence line : r.title) {
                    g.text(font, line, vx + (vw - font.width(line)) / 2, ty, Theme.MUTED, false);
                    ty += 10;
                }
            }
            case CARD -> {
                flash(g, r, ry);
                r.widget.extractRenderState(g, mx, my, delta);
            }
            case OPTION, CUSTOM -> {
                if (r.line) g.fill(vx + ROW_X, ry, vx + vw - ROW_X, ry + 1, Theme.LINE_SOFT);
                float hv = r.hover.to(over ? 1 : 0);
                // The whole row of a switch flips it.
                if (over && r.widget instanceof ToggleSwitch t && t.isActive()) g.requestCursor(CursorTypes.POINTING_HAND);
                if (hv > 0) Shapes.round(g, vx, ry, vw, r.h, 4, Theme.fade(Theme.ROW_HOVER, hv));
                flash(g, r, ry);
                int tx = vx + r.textX, ty = ry + r.textY;
                int titleColor = r.enabled ? Theme.TEXT : Theme.DIM, helpColor = r.enabled ? Theme.MUTED : Theme.DIM;
                for (FormattedCharSequence line : r.title) {
                    g.text(font, line, tx, ty, titleColor, false);
                    ty += 10;
                }
                if (!r.title.isEmpty()) ty += 3;
                for (FormattedCharSequence line : r.help) {
                    g.text(font, line, tx, ty, helpColor, false);
                    ty += 10;
                }
                r.widget.extractRenderState(g, mx, my, delta);
            }
        }
    }

    /** A row whose feature code threw: reported once, then shown as a gray line; its widget takes no input. */
    private void fail(Row r, String what, RuntimeException e) {
        Io.failed(what, e);
        r.failed = true;
        if (r.widget == null) return;
        r.widget.visible = false;
        r.widget.active = false;
        if (getFocused() == r.widget) setFocused(null);
    }

    /**
     * Feature widgets get input through this: when {@code suspect} (the widget the input goes to) throws, its row is
     * reported and switched off, and the settings stay open.
     */
    private boolean safely(GuiEventListener suspect, BooleanSupplier input) {
        try {
            return input.getAsBoolean();
        } catch (RuntimeException e) {
            for (Row r : rows) {
                if (r.widget != null && r.widget == suspect) {
                    fail(r, "Using the setting " + r.id(), e);
                    return true;
                }
            }
            Io.failed("Using the settings", e);
            return true;
        }
    }

    /** The message from {@link #flash}: a pill at the bottom of the pane that rises in and fades out. */
    private void toast(GuiGraphicsExtractor g) {
        if (toast == null) return;
        long age = Util.getMillis() - toastAt;
        if (age >= TOAST_MS) {
            toast = null;
            toastLine = null;
            return;
        }
        if (toastLine == null) toastLine = Ui.clip(font, toast.getString(), cw - 40);
        float in = Math.min(1, age / 150f), a = in * Math.min(1, (TOAST_MS - age) / 400f);
        int w = font.width(toastLine) + 16, h = 16;
        int x = cx + (cw - w) / 2, y = wy + wh - h - 10 + Math.round((1 - in) * 4);
        Shapes.shadow(g, x, y, w, h, 8, 3);
        Shapes.round(g, x, y, w, h, 8, Theme.fade(Theme.SURFACE_HOVER, a));
        Shapes.frame(g, x, y, w, h, 8, Theme.fade(Theme.ACCENT, 0.8f * a));
        if (a > 0.05f) g.text(font, toastLine, x + 8, y + 4, Theme.fade(Theme.TEXT, a), false);
    }

    /** The glow of a row a search jumped to: accent tint and bar, fading out. */
    private void flash(GuiGraphicsExtractor g, Row r, int ry) {
        if (r != flashRow) return;
        float t = (Util.getMillis() - flashAt) / (float) FLASH_MS;
        if (t >= 1) {
            flashRow = null;
            return;
        }
        float a = 1 - Anim.easeOut(Math.max(0, t * 1.4f - 0.4f));
        Shapes.round(g, vx, ry, vw, r.h, 4, Theme.fade(Theme.ACCENT_BG, a * 0.85f));
        Shapes.round(g, vx, ry + 3, 2, r.h - 6, 1, Theme.fade(Theme.ACCENT, a));
    }

    private void scrollbar(GuiGraphicsExtractor g, int mx, int my) {
        if (maxScroll <= 0) return;
        int tx = barX(), ty = vy + 2, th = vh - 4;
        int thumbH = thumbH(th), thumbY = ty + (int) Math.round((th - thumbH) * shown / maxScroll);
        boolean over = draggingBar || mx >= tx - 3 && mx < tx + BAR_W + 3 && my >= ty && my < ty + th;
        float hv = barHover.to(over ? 1 : 0);
        Shapes.round(g, tx, ty, BAR_W, th, 1, Theme.fade(Theme.SURFACE, 0.5f + 0.5f * hv));
        Shapes.round(g, tx, thumbY, BAR_W, thumbH, 1, Theme.mix(Theme.DIM, Theme.ACCENT, hv));
    }

    private int barX() {
        return cx + cw - BAR_W - 4;
    }

    private int thumbH(int track) {
        return Math.max(14, (int) ((long) track * vh / Math.max(1, contentH)));
    }

    // ============================================================ input

    /** Content widgets get the mouse only inside the pane, tabs only inside the sidebar. */
    @Override
    public Optional<GuiEventListener> getChildAt(double x, double y) {
        if (tooSmall) return done != null && done.isMouseOver(x, y) ? Optional.of(done) : Optional.empty();
        if (search.isMouseOver(x, y)) return Optional.of(search);
        if (close.isMouseOver(x, y)) return Optional.of(close);
        if (x >= wx && x < cx && y >= sideTop && y < sideTop + sideH) {
            for (SectionTab t : tabs) if (t.isMouseOver(x, y)) return Optional.of(t);
        }
        if (inPane(x, y)) {
            for (Row r : rows) if (r.widget != null && r.widget.isMouseOver(x, y)) return Optional.of(r.widget);
        }
        return Optional.empty();
    }

    private boolean inPane(double x, double y) {
        return x >= cx && x < cx + cw && y >= vy && y < vy + vh;
    }

    private Row rowAt(double x, double y) {
        if (!inPane(x, y) || x < vx || x >= vx + vw) return null;
        double at = y - vy + shown;
        for (Row r : rows) if (at >= r.y && at < r.y + r.h) return r;
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (overlay != null) {
            onOverlay(o -> o.mouseClicked(event));
            return true;
        }
        for (KeyBindButton k : keys) if (k.handleClick(event)) return true;
        if (tooSmall) return super.mouseClicked(event, doubleClick);
        double x = event.x(), y = event.y();
        if (event.button() == 0 && maxScroll > 0 && x >= barX() - 3 && x < barX() + BAR_W + 3 && y >= vy && y < vy + vh) {
            int th = vh - 4, thumbH = thumbH(th), thumbY = vy + 2 + (int) Math.round((th - thumbH) * shown / maxScroll);
            barGrab = y >= thumbY && y < thumbY + thumbH ? y - thumbY : thumbH / 2.0;
            draggingBar = true;
            dragBar(y);
            return true;
        }
        if (safely(getChildAt(x, y).orElse(null), () -> super.mouseClicked(event, doubleClick))) return true;
        if (getFocused() == search) setFocused(null);
        Row r = event.button() == 0 ? rowAt(x, y) : null;
        if (r == null) return false;
        if (r.kind == Kind.GROUP) {
            AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
            jumpTo(r.section, r.option);
            return true;
        }
        // A click anywhere on a switch's row flips it.
        if (r.widget instanceof ToggleSwitch t && t.isActive()) {
            t.playDownSound(minecraft.getSoundManager());
            t.onPress(event);
            return true;
        }
        return false;
    }

    private void dragBar(double y) {
        int th = vh - 4, thumbH = thumbH(th);
        double p = (y - barGrab - (vy + 2)) / Math.max(1, th - thumbH);
        scroll = shown = Math.clamp(p, 0, 1) * maxScroll;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (overlay != null) {
            onOverlay(o -> o.mouseDragged(event));
            return true;
        }
        if (draggingBar) {
            dragBar(event.y());
            return true;
        }
        return safely(getFocused(), () -> super.mouseDragged(event, dx, dy));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (overlay != null) {
            onOverlay(o -> o.mouseReleased(event));
            super.mouseReleased(event); // ends the screen's own drag state; a release never presses a button
            return true;
        }
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return safely(getFocused(), () -> super.mouseReleased(event));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (overlay != null) {
            onOverlay(o -> o.mouseScrolled(x, y, dy));
            return true;
        }
        if (tooSmall) return false;
        if (x >= wx && x < cx && y >= sideTop && y < sideTop + sideH) {
            if (sideMax <= 0) return false;
            sideScroll = Math.clamp(sideScroll - dy * WHEEL, 0, sideMax);
            return true;
        }
        if (!inPane(x, y)) return false;
        Optional<GuiEventListener> child = getChildAt(x, y);
        if (child.isPresent() && safely(child.get(), () -> child.get().mouseScrolled(x, y, dx, dy))) return true;
        scroll = Math.clamp(scroll - dy * WHEEL, 0, maxScroll);
        return true;
    }

    /**
     * In order: a key row waiting for a key, an open overlay, Ctrl+F, the search box's own keys (Esc clears it,
     * Enter jumps to the first result), Esc clearing a search, then the focused widget and Tab, then Page Up / Page
     * Down / Home / End for the pane.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        swallowChar = false;
        for (KeyBindButton k : keys) {
            if (k.handleKey(event)) {
                swallowChar = true; // the character of the key just bound must not start a search
                return true;
            }
        }
        if (overlay != null) {
            onOverlay(o -> o.keyPressed(event));
            return true;
        }
        if (tooSmall) return super.keyPressed(event);
        int key = event.key();
        if (key == GLFW.GLFW_KEY_F && event.hasControlDown()) {
            focusSearch();
            swallowChar = true;
            return true;
        }
        if (getFocused() == search) {
            if (event.isEscape() && !search.getValue().isEmpty()) {
                search.setValue(""); // an empty search lets Esc through: it closes the settings
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                jumpToFirst();
                return true;
            }
        }
        if (event.isEscape() && words.length > 0) {
            search.setValue("");
            return true;
        }
        if (safely(getFocused(), () -> super.keyPressed(event))) return true;
        switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> scroll = Math.max(0, scroll - vh * 0.85);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll = Math.min(maxScroll, scroll + vh * 0.85);
            case GLFW.GLFW_KEY_HOME -> scroll = 0;
            case GLFW.GLFW_KEY_END -> scroll = maxScroll;
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Typing a letter or digit with no text box focused starts a search. */
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        if (overlay != null) {
            onOverlay(o -> o.charTyped(event));
            return true;
        }
        if (safely(getFocused(), () -> super.charTyped(event))) return true;
        if (tooSmall || getFocused() == search || !Character.isLetterOrDigit(event.codepoint())) return false;
        setFocused(search);
        search.moveCursorToEnd(false);
        return search.charTyped(event);
    }

    // ============================================================ rows

    private enum Kind { HEADER, OPTION, CUSTOM, CARD, GROUP, MESSAGE }

    /** What a search can find: an option, its section and group, and its words in lower case. */
    private record Entry(Section section, Option group, Option option, String hay) {}

    /** One row of the pane; positions are relative to the row, the row's to the top of the list. */
    private static final class Row {
        final Kind kind;
        final Section section;
        final Option option;
        AbstractWidget widget;
        Runnable refresh;
        KeyBindButton key;
        ItemStack icon = ItemStack.EMPTY;
        Component titleText = Component.empty(), helpText = Component.empty();
        List<FormattedCharSequence> title = List.of(), help = List.of();
        int y, h, textX, textY, textW, titleW, ctrlX, ctrlY;
        boolean stacked, line, enabled = true, failed;
        final Anim hover = new Anim(110, 0);

        Row(Kind kind, Section section, Option option) {
            this.kind = kind;
            this.section = section;
            this.option = option;
        }

        boolean setting() {
            return kind == Kind.OPTION || kind == Kind.CUSTOM;
        }

        String id() {
            return option == null ? "" : option.id();
        }
    }

    /** A {@link ColorPopup} a feature's own widget opened through {@link Host#openPopup}. */
    private static final class PopupOverlay implements Overlay {
        private final ColorPopup popup;

        PopupOverlay(ColorPopup popup) {
            this.popup = popup;
        }

        @Override
        public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            popup.render(g, mouseX, mouseY);
        }

        @Override
        public void mouseClicked(MouseButtonEvent e) {
            popup.mouseClicked(e.x(), e.y(), e.button());
        }

        @Override
        public void mouseDragged(MouseButtonEvent e) {
            popup.mouseDragged(e.x(), e.y(), e.button());
        }

        @Override
        public void mouseReleased(MouseButtonEvent e) {
            popup.mouseReleased(e.x(), e.y(), e.button());
        }

        @Override
        public void keyPressed(KeyEvent e) {
            popup.keyPressed(e);
        }

        @Override
        public void charTyped(CharacterEvent e) {
            popup.charTyped(e);
        }

        @Override
        public boolean isClosed() {
            return popup.isClosed();
        }

        @Override
        public void close() {
            popup.close();
        }

        @Override
        public void fit(int x, int y, int width, int height) {
            popup.place(x, y, width, height);
        }
    }

    // ============================================================ test hooks

    public Screen parent() {
        return parent;
    }

    public String sectionId() {
        return sectionId;
    }

    /** The pane's rows by option id, headers included, in order; a search's group lines read "> section id". */
    public List<String> rowIds() {
        List<String> out = new ArrayList<>();
        for (Row r : rows) out.add(r.kind == Kind.GROUP ? "> " + r.section.id() : r.kind == Kind.MESSAGE ? "!" : r.id());
        return out;
    }

    /** The widget of the row with this option id, or null. */
    public AbstractWidget widget(String optionId) {
        for (Row r : rows) if (r.widget != null && r.kind != Kind.GROUP && r.id().equals(optionId)) return r.widget;
        return null;
    }

    /** The row's title and help lines as shown. */
    public List<String> rowText(String optionId) {
        List<String> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.kind == Kind.GROUP || !r.id().equals(optionId)) continue;
            for (FormattedCharSequence l : r.title) out.add(plain(l));
            for (FormattedCharSequence l : r.help) out.add(plain(l));
        }
        return out;
    }

    private static String plain(FormattedCharSequence line) {
        StringBuilder sb = new StringBuilder();
        line.accept((i, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    /** Scrolls at once so the row with this option id shows whole; false when no row has it. */
    public boolean scrollTo(String optionId) {
        for (Row r : rows) {
            if (r.kind == Kind.GROUP || !r.id().equals(optionId)) continue;
            reveal(r, false);
            shown = scroll;
            return true;
        }
        return false;
    }

    /** The right end of the row's widest text line, on screen; 0 without text. */
    public int textRight(String optionId) {
        for (Row r : rows) {
            if (r.kind == Kind.GROUP || !r.id().equals(optionId)) continue;
            int w = 0;
            for (FormattedCharSequence l : r.title) w = Math.max(w, font.width(l));
            for (FormattedCharSequence l : r.help) w = Math.max(w, font.width(l));
            return w == 0 ? 0 : vx + r.textX + w;
        }
        return 0;
    }

    /** Where the sidebar is scrolling to, and how far it can. */
    public double sideScroll() {
        return sideScroll;
    }

    public double sideMax() {
        return sideMax;
    }

    /** Whether the row's control sits under its text (true) or beside it. */
    public boolean stacked(String optionId) {
        for (Row r : rows) if (r.kind != Kind.GROUP && r.id().equals(optionId)) return r.stacked;
        return false;
    }

    public SearchBox search() {
        return search;
    }

    public List<SectionTab> tabs() {
        return List.copyOf(tabs);
    }

    /** Where the pane is scrolling to, and how far it can. */
    public double scrollAmount() {
        return scroll;
    }

    public double maxScroll() {
        return maxScroll;
    }

    /** The scrolling pane: x, y, width, height. */
    public int[] pane() {
        return new int[]{cx, vy, cw, vh};
    }

    /** The window: x, y, width, height. */
    public int[] window() {
        return new int[]{wx, wy, ww, wh};
    }

    /** How many settings the search found, or -1 with no search. */
    public int results() {
        return words.length == 0 ? -1 : results;
    }

    public Overlay overlay() {
        return overlay;
    }

    /** The open color pop-up a feature's widget opened, or null. */
    /** The message {@link #flash} shows, or null once it has faded. */
    public String toastText() {
        return toast == null ? null : toast.getString();
    }

    /** How many rows have a control (a header or a search's group line has none). */
    public int buttonRows() {
        int n = 0;
        for (Row r : rows) if (r.widget != null) n++;
        return n;
    }

    public ColorPopup popup() {
        return overlay instanceof PopupOverlay p ? p.popup : null;
    }

    /** The row glowing after a jump, or null. */
    public String flashing() {
        return flashRow == null ? null : flashRow.id();
    }

    /** The sidebar shows icons only. */
    public boolean narrow() {
        return rail;
    }
}
