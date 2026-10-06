package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.CardButton;
import io.github.terabold.skycosmetics.gui.hub.ColorPopover;
import io.github.terabold.skycosmetics.gui.hub.ColorSwatch;
import io.github.terabold.skycosmetics.gui.hub.DropdownChoice;
import io.github.terabold.skycosmetics.gui.hub.KeyBindButton;
import io.github.terabold.skycosmetics.gui.hub.OptionSlider;
import io.github.terabold.skycosmetics.gui.hub.SectionTab;
import io.github.terabold.skycosmetics.gui.hub.SegmentedChoice;
import io.github.terabold.skycosmetics.gui.hub.ToggleSwitch;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings window's own widgets, on test sections added for the run: one row of every control kind, used with the
 * real mouse and keyboard (switch, slider, segmented choice, drop-down, key box, actions, an opaque and a see-through
 * color), the grayed-out state, the search (typing starts it, several words, counts per section, Enter jumps and the
 * row glows, Ctrl+F, Esc), a sidebar of many sections, a long list, the icon rail of a narrow window, and the studio's
 * Settings button (opens on the studio's section with Back to Studio; Esc returns to the studio). Screenshots at GUI
 * scale 1 to 4 at 1920x1080 and in a small window.
 */
public class MenuClientTest implements FabricClientGameTest {
    private static final String CONTROLS = "test-controls", LONG = "test-long", BROKEN = "test-broken",
        BROKEN_SECTION = "test-broken-section";
    private static final List<String> EDGES = List.of("Soft", "Sharp", "Off");
    private static final List<String> PLACES = List.of("Hub", "Dungeon Hub", "Crimson Isle", "The End", "Dwarven Mines",
        "Crystal Hollows", "The Park", "Spider's Den", "Gold Mine", "Deep Caverns");
    private static final List<String> ADDED = new ArrayList<>();
    private static boolean sparkles = true, trail = true, filler, boom;
    private static double size = 1;
    private static String edge = "Soft", place = "Hub";
    private static int tint = 0xFF3080FF, glass = 0x80FF4060, pressed;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            int[] before = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
            ctx.runOnClient(mc -> addSections());
            try {
                scales(ctx);
                controls(ctx);
                search(ctx);
                broken(ctx);
                manySections(ctx);
                fromStudio(ctx, sp);
            } finally {
                ctx.setScreen(() -> null);
                ctx.runOnClient(mc -> {
                    for (String id : ADDED) Hub.remove(id);
                    ADDED.clear();
                });
                ctx.getInput().resizeWindow(before[0], before[1]);
                setScale(ctx, 0);
            }
        }
        System.out.println("[SkyCosmeticsTest] settings menu checks passed");
    }

    // ------------------------------------------------------------ sections ---

    private static void addSections() {
        add(new Section(CONTROLS, Section.FEATURE, Component.literal("Test Controls"), Component.literal("One of each kind"),
            Component.literal("Every kind of setting row, for the tests."), () -> new ItemStack(Items.REDSTONE_TORCH),
            s -> controlRows()));
        add(new Section(LONG, Section.FEATURE, Component.literal("Test Long List"), Component.literal("Sixty rows"),
            Component.literal("A section with many rows, to scroll."), () -> new ItemStack(Items.BOOK), s -> longRows()));
        add(new Section(BROKEN, Section.FEATURE, Component.literal("Test Broken Rows"), Component.literal("Throw on purpose"),
            Component.literal("Rows whose feature code throws."), () -> new ItemStack(Items.TNT), s -> brokenRows()));
        add(new Section(BROKEN_SECTION, Section.FEATURE, Component.literal("Test Broken Section"),
            Component.literal("Can't build"), Component.literal("A section whose rows throw."), () -> new ItemStack(Items.BARRIER),
            s -> {
                throw new IllegalStateException("test: a section that can't build its rows");
            }));
        for (int i = 1; i <= 12; i++) {
            String n = String.format(Locale.ROOT, "%02d", i);
            add(new Section("test-more-" + n, Section.FEATURE, Component.literal("Test Section " + n),
                Component.literal("Fills the sidebar"), Component.literal("One of many sections."),
                () -> new ItemStack(Items.PAPER), s -> List.of(toggle("filler", "Filler " + n, "A switch."))));
        }
    }

    private static void add(Section s) {
        Hub.add(s);
        ADDED.add(s.id());
    }

    private static List<Option> controlRows() {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.header("groupSwitches", Component.literal("Switches")));
        rows.add(Option.of("sparkles", Component.literal("Sparkles"), Component.literal("Adds sparkles around your items. "
            + "This help is long on purpose, so it wraps onto more lines in a narrow window."),
            new Control.Toggle(() -> sparkles, v -> sparkles = v)));
        rows.add(Option.of("trail", Component.literal("Sparkle Trail"), Component.literal("Needs Sparkles: grayed out while they are off."),
            new Control.Toggle(() -> trail, v -> trail = v)).enabledWhen(() -> sparkles));
        rows.add(Option.header("groupValues", Component.literal("Values")));
        rows.add(Option.of("size", Component.literal("Size"), Component.literal("From half to double."),
            new Control.Slider(() -> size, v -> size = v, 0.5, 2, 0.05,
                v -> Component.literal(String.format(Locale.ROOT, "%.2fx", v)))));
        rows.add(Option.of("edge", Component.literal("Edge"), Component.literal("Three short values: side by side."),
            new Control.Choice<>(EDGES, Component::literal, () -> edge, v -> edge = v)));
        rows.add(Option.of("place", Component.literal("Place"), Component.literal("Ten values: a drop-down list."),
            new Control.Choice<>(PLACES, Component::literal, () -> place, v -> place = v)).search("island location"));
        rows.add(Option.of("tint", Component.literal("Tint"), Component.literal("An opaque color."),
            new Control.Color(() -> tint, v -> tint = v)));
        rows.add(Option.of("glass", Component.literal("Glass"), Component.literal("A color with opacity."),
            new Control.Color(() -> glass, v -> glass = v, true)));
        rows.add(Option.header("groupMore", Component.literal("Keys and Actions")));
        rows.add(Option.of("testKey", Component.literal("Test Key"), Component.literal("The open key again."),
            new Control.Key(SkyCosmetics::openKey)));
        rows.add(Option.of("press", Component.literal("Press Me"), Component.literal("Counts presses."),
            new Control.Action(Component.literal("Press"), () -> pressed++, () -> true, Component.empty())));
        rows.add(Option.of("never", Component.literal("Never Ready"), Component.literal("Grayed out, with a tooltip saying why."),
            new Control.Action(Component.literal("Do It"), () -> pressed += 100, () -> false, Component.literal("Not ready yet"))));
        return rows;
    }

    private static List<Option> brokenRows() {
        return List.of(
            Option.of("throwsOnDraw", Component.literal("Throws When Drawn"), Component.literal("Its value can't be read."),
                new Control.Toggle(() -> {
                    if (boom) throw new IllegalStateException("test: a switch whose value can't be read");
                    return true;
                }, v -> {})),
            Option.of("throwsOnClick", Component.literal("Throws When Clicked"), Component.literal("A feature's own widget."),
                new Control.Custom((host, width) -> new Boom(width))),
            toggle("stillWorks", "Still Works", "The rows around a broken one keep working."));
    }

    /** A feature's own widget that throws when clicked. */
    private static final class Boom extends AbstractWidget {
        Boom(int width) {
            super(0, 0, Math.min(width, 60), 16, Component.literal("Boom"));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF803040);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            throw new IllegalStateException("test: a widget that throws when clicked");
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    private static List<Option> longRows() {
        List<Option> rows = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            if (i % 15 == 1) rows.add(Option.header("group" + i, Component.literal("Group " + (i / 15 + 1))));
            rows.add(toggle("row" + i, "Row " + i, i % 3 == 0
                ? "A longer help line that runs on and on, so this row is taller than its neighbors at every scale."
                : "A short help line."));
        }
        return rows;
    }

    private static Option toggle(String id, String title, String help) {
        return Option.of(id, Component.literal(title), Component.literal(help), new Control.Toggle(() -> filler, v -> filler = v));
    }

    // ------------------------------------------------------------ checks ---

    /** The controls section at GUI scale 1 to 4, in a small window, and as an icon rail. */
    private static void scales(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1920, 1080);
        open(ctx, CONTROLS);
        for (int scale = 1; scale <= 4; scale++) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-30-menu-controls-1920x1080-guiscale-" + scale);
            ctx.runOnClient(MenuClientTest::checkLayout);
        }
        ctx.getInput().resizeWindow(854, 480);
        for (int scale = 1; scale <= 2; scale++) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-30-menu-controls-854x480-guiscale-" + scale);
            ctx.runOnClient(MenuClientTest::checkLayout);
        }
        // 320 x 240 scaled, the narrowest Minecraft allows: the sidebar becomes a rail of icons.
        ctx.getInput().resizeWindow(1280, 960);
        setScale(ctx, 4);
        check(ctx.computeOnClient(mc -> screen(mc).narrow()), "a narrow window shows the sidebar as icons");
        ctx.takeScreenshot("skycosmetics-30-menu-controls-1280x960-guiscale-4-rail");
        ctx.runOnClient(MenuClientTest::checkLayout);
        ctx.getInput().resizeWindow(1920, 1080);
        setScale(ctx, 3);
    }

    /** Every control with the real mouse and keyboard at 1920x1080, GUI scale 3. */
    private static void controls(ClientGameTestContext ctx) {
        open(ctx, CONTROLS);

        // Switch: a click on it flips it and grays out the row that needs it; a click on the row's text flips it back.
        check(widget(ctx, "sparkles") instanceof ToggleSwitch, "a toggle is a switch");
        click(ctx, "sparkles");
        check(!sparkles && !widget(ctx, "trail").active, "the switch turned Sparkles off and grayed out Sparkle Trail");
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-31-menu-switch-off");
        show(ctx, "sparkles");
        int[] text = ctx.computeOnClient(mc -> {
            AbstractWidget w = screen(mc).widget("sparkles");
            return new int[]{screen(mc).pane()[0] + 20, w.getY() + w.getHeight() / 2};
        });
        clickAt(ctx, text[0], text[1]);
        check(sparkles && widget(ctx, "trail").active, "a click on the row's text flips its switch back");

        // Slider: click three quarters along, drag to the end, then Left steps down once.
        show(ctx, "size");
        int[] track = ctx.computeOnClient(mc -> {
            OptionSlider s = (OptionSlider) screen(mc).widget("size");
            return new int[]{s.trackX(), s.trackW(), s.getY() + s.getHeight() / 2};
        });
        clickAt(ctx, track[0] + track[1] * 3 / 4, track[2]);
        check(size > 1.5 && size < 1.75, "a click on the track sets the value there: " + size);
        double scale = guiScale(ctx);
        ctx.getInput().setCursorPos((track[0] + track[1] * 3 / 4.0) * scale, track[2] * scale);
        ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        ctx.getInput().setCursorPos((track[0] + track[1] + 40) * scale, track[2] * scale);
        ctx.waitTicks(1);
        ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        check(size == 2, "dragging past the end gives the maximum: " + size);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_LEFT);
        ctx.waitTicks(1);
        check(Math.abs(size - 1.95) < 1e-9, "Left steps down once: " + size);

        // Segmented choice: a click on the middle value picks it.
        show(ctx, "edge");
        int[] mid = ctx.computeOnClient(mc -> {
            AbstractWidget w = screen(mc).widget("edge");
            check(w instanceof SegmentedChoice<?>, "three short values sit side by side");
            return ((SegmentedChoice<?>) w).centerOf(1);
        });
        clickAt(ctx, mid[0], mid[1]);
        check(edge.equals("Sharp"), "the middle segment picked Sharp: " + edge);

        // Drop-down: opens a list over the rows, a click on a value picks it and closes the list; Esc closes it too.
        check(widget(ctx, "place") instanceof DropdownChoice<?>, "ten values make a drop-down");
        click(ctx, "place");
        check(ctx.computeOnClient(mc -> ((DropdownChoice<?>) screen(mc).widget("place")).list() != null), "the list opened");
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-32-menu-dropdown");
        int[] entry = ctx.computeOnClient(mc -> ((DropdownChoice<?>) screen(mc).widget("place")).entryCenter(3));
        check(entry != null, "The End shows in the list");
        clickAt(ctx, entry[0], entry[1]);
        check(place.equals("The End") && ctx.computeOnClient(mc -> screen(mc).overlay() == null),
            "a click on The End picks it and closes the list: " + place);
        click(ctx, "place");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        ctx.waitTicks(1);
        check(place.equals("Dwarven Mines"), "Down and Enter pick the next value: " + place);
        click(ctx, "place");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && s.overlay() == null),
            "Esc closes the list, not the settings");

        // Key box: a click listens ("> Press a key <"), Esc cancels and keeps the settings open.
        String listening = ctx.computeOnClient(mc -> I18n.get("skycosmetics.option.openKey.listening"));
        click(ctx, "testKey");
        check(ctx.computeOnClient(mc -> ((KeyBindButton) screen(mc).widget("testKey")).listening()
            && screen(mc).widget("testKey").getMessage().getString().equals(listening)), "the key box listens");
        ctx.takeScreenshot("skycosmetics-33-menu-key-listening");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && !((KeyBindButton) s.widget("testKey")).listening()),
            "Esc stops listening and keeps the settings open");

        // Actions: one runs, the grayed-out one doesn't.
        check(widget(ctx, "press") instanceof ActionButton, "an action is a themed button");
        int was = pressed;
        click(ctx, "press");
        click(ctx, "never");
        check(pressed == was + 1, "the action ran once and the grayed-out one never: " + (pressed - was));

        // Opaque color: the swatch opens the picker; a click in the square's top left picks white; Esc closes.
        click(ctx, "tint");
        ColorPopover pop = popover(ctx, "tint");
        check(pop != null, "the swatch opened its picker");
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-34-menu-color");
        int[] square = ctx.computeOnClient(mc -> new int[]{pop.picker().x() + 1, pop.picker().y() + 1});
        clickAt(ctx, square[0], square[1]);
        check((tint & 0xFFFFFF) > 0xF0F0F0 && tint >>> 24 == 0xFF, "the square's top left is white and opaque: "
            + Integer.toHexString(tint));
        check(ctx.computeOnClient(mc -> ((ColorSwatch) screen(mc).widget("tint")).value() == tint), "the swatch shows it");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && s.overlay() == null),
            "Esc closes the picker, not the settings");

        // See-through color: an opacity bar; its right end makes it opaque. A click outside closes the picker.
        int rgb = glass & 0xFFFFFF;
        click(ctx, "glass");
        ColorPopover alpha = popover(ctx, "glass");
        check(alpha != null && alpha.alphaAt(255) != null, "a color with opacity has an opacity bar");
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-35-menu-color-opacity");
        int[] end = ctx.computeOnClient(mc -> alpha.alphaAt(255));
        clickAt(ctx, end[0] + 2, end[1]);
        check(glass >>> 24 == 0xFF && (glass & 0xFFFFFF) == rgb, "the bar's end made it opaque, same color: "
            + Integer.toHexString(glass));
        int[] outside = ctx.computeOnClient(mc -> new int[]{screen(mc).window()[0] + 10, screen(mc).window()[1] + screen(mc).window()[3] - 10});
        clickAt(ctx, outside[0], outside[1]);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && s.overlay() == null), "a click outside closes the picker");
        ctx.setScreen(() -> null);
        System.out.println("[SkyCosmeticsTest] menu control checks passed");
    }

    /** Typing starts a search over every section; Enter jumps to the first result; Ctrl+F; Esc clears, then closes. */
    private static void search(ClientGameTestContext ctx) {
        open(ctx, Hub.STUDIO);
        ctx.getInput().typeChars("open");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> screen(mc).getFocused() == screen(mc).search() && screen(mc).search().getValue().equals("open")),
            "typing a letter starts a search");
        List<String> ids = ctx.computeOnClient(mc -> screen(mc).rowIds());
        System.out.println("[SkyCosmeticsTest] search 'open': " + ids);
        check(ids.contains("> " + Hub.STUDIO) && ids.contains("> " + CONTROLS) && ids.contains("openStudio")
            && ids.contains("openKey") && ids.contains("testKey"), "'open' finds Open Studio, Open Key and the test key");
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-36-menu-search");

        ctx.getInput().typeChars(" key");
        ctx.waitTicks(2);
        List<String> both = ctx.computeOnClient(mc -> screen(mc).rowIds());
        check(!both.contains("openStudio") && both.contains("openKey") && both.contains("testKey"),
            "'open key' needs both words: " + both);
        check(ctx.computeOnClient(mc -> {
            int studio = -2, controls = -2;
            for (SectionTab t : screen(mc).tabs()) {
                if (t.section().id().equals(Hub.STUDIO)) studio = t.count();
                if (t.section().id().equals(CONTROLS)) controls = t.count();
            }
            return studio == 1 && controls == 1 && screen(mc).results() == 2;
        }), "each section counts its results");

        ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> screen(mc).sectionId().equals(Hub.STUDIO) && "openKey".equals(screen(mc).flashing())
            && screen(mc).search().getValue().isEmpty() && screen(mc).results() == -1), "Enter jumps to Open Key, which glows");
        ctx.takeScreenshot("skycosmetics-37-menu-search-jump");

        // The test input sends keys without modifier bits, so Ctrl+F goes straight to the screen.
        ctx.runOnClient(mc -> screen(mc).keyPressed(new KeyEvent(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_CONTROL)));
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> screen(mc).getFocused() == screen(mc).search()), "Ctrl+F focuses the search");
        ctx.getInput().typeChars("zzqx");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> screen(mc).search().getValue().equals("zzqx")),
            "no letter is lost after Ctrl+F: " + ctx.computeOnClient(mc -> screen(mc).search().getValue()));
        check(ctx.computeOnClient(mc -> screen(mc).results() == 0 && screen(mc).rowIds().equals(List.of("!"))),
            "nothing found shows one message");
        ctx.takeScreenshot("skycosmetics-38-menu-search-none");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && s.search().getValue().isEmpty()
            && s.results() == -1), "Esc clears the search first");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> mc.screen == null), "then Esc closes the settings");
        System.out.println("[SkyCosmeticsTest] menu search checks passed");
    }

    /** Feature code that throws, while drawing, on a click or while building a section, never closes the settings. */
    private static void broken(ClientGameTestContext ctx) {
        open(ctx, BROKEN);
        ctx.runOnClient(mc -> boom = true);
        try {
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> !screen(mc).widget("throwsOnDraw").visible), "a row that throws while drawn is switched off");
            click(ctx, "throwsOnClick");
            check(ctx.computeOnClient(mc -> !screen(mc).widget("throwsOnClick").visible), "a widget that throws when clicked is switched off");
            boolean was = filler;
            click(ctx, "stillWorks");
            check(filler != was, "the rows around them still work");
            ctx.takeScreenshot("skycosmetics-42-menu-broken-rows");
        } finally {
            ctx.runOnClient(mc -> boom = false);
        }
        open(ctx, BROKEN_SECTION);
        check(ctx.computeOnClient(mc -> screen(mc).rowIds().equals(List.of("!"))), "a section that can't build shows one message");
        ctx.setScreen(() -> null);
        System.out.println("[SkyCosmeticsTest] menu broken feature checks passed");
    }

    /** Seventeen sections in a small window: the sidebar scrolls and its last tab opens; a long list scrolls to its end. */
    private static void manySections(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(854, 480);
        setScale(ctx, 2);
        open(ctx, CONTROLS);
        check(ctx.computeOnClient(mc -> screen(mc).tabs().size() >= 17 && screen(mc).sideMax() > 0),
            "seventeen sections overflow the sidebar");
        ctx.takeScreenshot("skycosmetics-39-menu-many-sections");
        int[] side = ctx.computeOnClient(mc -> {
            int[] w = screen(mc).window(), pane = screen(mc).pane();
            return new int[]{(w[0] + pane[0]) / 2, pane[1] + pane[3] / 2};
        });
        double scale = guiScale(ctx);
        ctx.getInput().setCursorPos(side[0] * scale, side[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().scroll(-50);
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> screen(mc).sideScroll() == screen(mc).sideMax()), "the wheel scrolls the sidebar to the end");
        ctx.takeScreenshot("skycosmetics-39b-menu-many-sections-scrolled");
        String lastId = ctx.computeOnClient(mc -> screen(mc).tabs().getLast().section().id());
        int[] last = ctx.computeOnClient(mc -> {
            SectionTab t = screen(mc).tabs().getLast();
            return new int[]{t.getX() + t.getWidth() / 2, t.getY() + t.getHeight() / 2};
        });
        clickAt(ctx, last[0], last[1]);
        check(ctx.computeOnClient(mc -> screen(mc).sectionId().equals(lastId)), "the last tab opens its section");

        ctx.getInput().resizeWindow(1920, 1080);
        setScale(ctx, 2);
        open(ctx, LONG);
        ctx.runOnClient(MenuClientTest::checkLayout);
        ctx.takeScreenshot("skycosmetics-40-menu-long-list");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_END);
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> screen(mc).maxScroll() > 0 && screen(mc).scrollAmount() == screen(mc).maxScroll()),
            "End scrolls to the last row");
        ctx.takeScreenshot("skycosmetics-40b-menu-long-list-end");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_HOME);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> screen(mc).scrollAmount() == 0), "Home scrolls back to the top");
        ctx.setScreen(() -> null);
        setScale(ctx, 3);
        System.out.println("[SkyCosmeticsTest] menu sidebar and long list checks passed");
    }

    /**
     * The studio's Settings button opens the settings on the studio's section, whose card reads Back to Studio; Esc
     * returns to that studio. Open Studio in the settings opens a studio whose Settings button comes back to them.
     */
    private static void fromStudio(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword[minecraft:custom_data="
            + "{id:\"HYPERION\",uuid:\"t-menu-sword\"}]");
        ctx.waitTicks(3);
        ctx.setScreen(() -> new StudioScreen(null, Minecraft.getInstance().player.getMainHandItem().copy()));
        ctx.waitForScreen(StudioScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(3);
        ctx.clickScreenButton("Settings");
        ctx.waitForScreen(SettingsScreen.class);
        ctx.waitTicks(3);
        String back = ctx.computeOnClient(mc -> I18n.get("skycosmetics.option.openStudio.back"));
        check(ctx.computeOnClient(mc -> screen(mc).sectionId().equals(Hub.STUDIO) && screen(mc).parent() instanceof StudioScreen
            && screen(mc).widget("openStudio") instanceof CardButton c && c.getMessage().getString().equals(back)),
            "the studio's Settings button opens the studio's section, with Back to Studio");
        ctx.takeScreenshot("skycosmetics-41-menu-from-studio");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitForScreen(StudioScreen.class);

        open(ctx, Hub.STUDIO);
        SettingsScreen settings = ctx.computeOnClient(MenuClientTest::screen);
        click(ctx, "openStudio");
        ctx.waitForScreen(StudioScreen.class);
        ctx.waitTicks(3);
        ctx.clickScreenButton("Settings");
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> mc.screen == settings), "the studio's Settings button returns to the same settings");
        ctx.setScreen(() -> null);
        System.out.println("[SkyCosmeticsTest] menu and studio checks passed");
    }

    /** On screen, every control inside the pane and right of its text (or under it), only themed widgets. */
    private static void checkLayout(Minecraft mc) {
        SettingsScreen s = screen(mc);
        int[] w = s.window(), pane = s.pane();
        check(w[0] >= 0 && w[1] >= 0 && w[0] + w[2] <= s.width && w[1] + w[3] <= s.height,
            "the window is on screen (" + s.width + "x" + s.height + ")");
        check(pane[0] > w[0] && pane[0] + pane[2] <= w[0] + w[2] && pane[1] + pane[3] <= w[1] + w[3], "the pane is in the window");
        for (String id : s.rowIds()) {
            AbstractWidget c = s.widget(id);
            if (c == null) continue;
            check(c.getX() >= pane[0] && c.getX() + c.getWidth() <= pane[0] + pane[2] - 4, "control inside the pane: " + id);
            if (!s.stacked(id)) check(s.textRight(id) < c.getX(), "control right of its text: " + id);
            for (String line : s.rowText(id)) check(mc.font.width(line) <= pane[2], "text wrapped to the pane: " + line);
        }
        for (GuiEventListener l : s.children()) {
            check(l.getClass().getName().startsWith("io.github.terabold.skycosmetics."), "only themed widgets: " + l.getClass().getName());
        }
    }

    // ------------------------------------------------------------ helpers ---

    private static SettingsScreen screen(Minecraft mc) {
        check(mc.screen instanceof SettingsScreen, "the settings are open: " + mc.screen);
        return (SettingsScreen) mc.screen;
    }

    private static void open(ClientGameTestContext ctx, String section) {
        ctx.setScreen(() -> new SettingsScreen(null, section));
        ctx.waitForScreen(SettingsScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> screen(mc).sectionId().equals(section)), "the settings opened on " + section);
    }

    private static AbstractWidget widget(ClientGameTestContext ctx, String id) {
        AbstractWidget w = ctx.computeOnClient(mc -> screen(mc).widget(id));
        check(w != null, "a widget for " + id);
        return w;
    }

    private static ColorPopover popover(ClientGameTestContext ctx, String id) {
        return ctx.computeOnClient(mc -> ((ColorSwatch) screen(mc).widget(id)).popover());
    }

    /** Scrolls the row into view, then clicks the middle of its control with the real mouse. */
    private static void click(ClientGameTestContext ctx, String id) {
        show(ctx, id);
        int[] at = ctx.computeOnClient(mc -> center(screen(mc).widget(id)));
        clickAt(ctx, at[0], at[1]);
    }

    /** Scrolls the pane so the row shows (widgets move on the next frame). */
    private static void show(ClientGameTestContext ctx, String id) {
        check(ctx.computeOnClient(mc -> screen(mc).scrollTo(id)), "a row for " + id);
        ctx.waitTicks(1);
    }

    private static int[] center(AbstractWidget w) {
        check(w != null, "the widget exists");
        return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
    }

    /** A real left click at GUI coordinates. */
    private static void clickAt(ClientGameTestContext ctx, double x, double y) {
        double scale = guiScale(ctx);
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
    }

    private static double guiScale(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
    }

    private static void setScale(ClientGameTestContext ctx, int scale) {
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
