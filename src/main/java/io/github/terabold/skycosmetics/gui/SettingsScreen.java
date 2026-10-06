package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.hub.Controls;
import io.github.terabold.skycosmetics.gui.hub.KeyBindButton;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The settings, in the studio's colors: a sidebar of sections (the studio's own first, then one per feature)
 * and the open section's rows in a list that scrolls. Each row is its title, its control and gray help under
 * it. Every change applies and saves at once.
 *
 * One screen behind every door: {@code /skycosmetics}, Mod Menu's Configure button ({@link #create}) and the
 * studio's Settings button. Done and Esc return to whatever opened it.
 */
public class SettingsScreen extends Screen implements Host {
    private static final int PAD = 8, TAB_H = 24, RAIL = 28, HEADER_H = 34, FOOTER_H = 30, ROW_W = 360;
    private static final int BG = 0xE0101014, BG_OPAQUE = 0xFF101014, PANEL = 0xF01B1B22, LINE = 0xFF34343F;
    private static final int TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A, ACCENT = 0xFFD58CFF, HELP = 0x9A9AA8;
    /** The section the settings opened on last, this session: Mod Menu and the command come back to it. */
    private static String lastSection;

    private final Screen parent;
    private String sectionId;
    /** Tick time of the footer message, and the message. */
    private long statusAt;
    private Component status = Component.empty();

    // Built in init().
    private Section section;
    private boolean rail, tooSmall;
    private int sideW, cx, cw;
    private ScrollableLayout list;
    private final List<Controls.Built> built = new ArrayList<>();
    private final List<String> rowIds = new ArrayList<>();
    private final List<SectionTab> tabs = new ArrayList<>();
    private KeyBindButton keyButton;
    private MultiLineTextWidget keyHelp;
    private Component keyUsualHelp;
    private ColorPopup popup;

    /** On the studio's own section: what the tests and the studio's Settings button open. */
    public SettingsScreen(Screen parent) {
        this(parent, Hub.STUDIO);
    }

    /** On the section with this id; an unknown id opens the first section. */
    public SettingsScreen(Screen parent, String sectionId) {
        super(Component.translatable("skycosmetics.name"));
        this.parent = parent;
        this.sectionId = sectionId;
    }

    /** For Mod Menu and {@code /skycosmetics}: the section viewed last this session, else the studio's. */
    public static Screen create(Screen parent) {
        return new SettingsScreen(parent, lastSection != null ? lastSection : Hub.STUDIO);
    }

    // ------------------------------------------------------------- layout ---

    @Override
    protected void init() {
        built.clear();
        rowIds.clear();
        tabs.clear();
        keyButton = null;
        keyHelp = null;
        tooSmall = width < 280 || height < 180 || Hub.sections().isEmpty();
        if (tooSmall) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done").withStyle(ChatFormatting.GREEN),
                b -> onClose()).bounds(width / 2 - 40, height / 2 + 14, 80, 20).build());
            return;
        }
        section = Hub.find(sectionId);
        if (section == null) section = Hub.sections().getFirst();
        sectionId = section.id();
        lastSection = sectionId;

        rail = width < 400;
        sideW = rail ? RAIL : Math.clamp(width * 24 / 100, 116, 160);
        cx = PAD * 2 + sideW;
        cw = width - cx - PAD;

        int ty = PAD + (rail ? 4 : 26);
        for (Section s : Hub.sections()) {
            SectionTab tab = addRenderableWidget(new SectionTab(s, PAD + 2, ty, sideW - 4));
            tabs.add(tab);
            ty += TAB_H;
        }

        int listTop = PAD + HEADER_H + 4, listBottom = height - PAD - FOOTER_H - 2;
        int rw = Math.min(ROW_W, cw - 30);
        list = new ScrollableLayout(minecraft, rows(rw), listBottom - listTop);
        list.setY(listTop);
        list.arrangeElements();
        list.setX(cx + Math.max(10, (cw - list.getWidth()) / 2)); // centered in the panel, scrollbar included
        list.arrangeElements();
        list.visitWidgets(this::addRenderableWidget);

        addRenderableWidget(Button.builder(Component.translatable("gui.done").withStyle(ChatFormatting.GREEN), b -> onClose())
            .bounds(cx + cw - 90, height - PAD - 25, 80, 20).build());
        refresh();
    }

    /** The open section's rows: title, control, help, with the group headers between them. */
    private LinearLayout rows(int w) {
        LinearLayout rows = LinearLayout.vertical().spacing(6);
        List<Option> options;
        try {
            options = section.rows().apply(this);
        } catch (RuntimeException e) {
            Io.failed("Building the settings section " + section.id(), e);
            rows.addChild(text(Component.translatable("skycosmetics.menu.broken").withColor(HELP), w));
            return rows;
        }
        for (Option o : options) {
            rowIds.add(o.id());
            if (o.control() instanceof Control.Header) {
                rows.addChild(text(o.title().copy().withColor(ACCENT & 0xFFFFFF), w), s -> s.paddingTop(rowIds.size() > 1 ? 6 : 2));
                continue;
            }
            Controls.Built b;
            try {
                b = Controls.build(o, w, this, this::keyChanged);
            } catch (RuntimeException e) {
                Io.failed("Building the setting " + o.id(), e);
                continue;
            }
            LinearLayout entry = LinearLayout.vertical().spacing(3);
            if (!o.title().getString().isEmpty()) entry.addChild(text(o.title().copy().withColor(TEXT & 0xFFFFFF), w));
            entry.addChild(b.widget());
            MultiLineTextWidget help = o.help().getString().isEmpty() ? null : text(o.help().copy().withColor(HELP), w);
            if (b.widget() instanceof KeyBindButton k) {
                keyButton = k;
                keyUsualHelp = o.help();
                keyHelp = help != null ? help : text(Component.empty(), w);
                help = keyHelp;
                keyHelp.setMessage(k.help(keyUsualHelp, HELP));
            }
            if (help != null) entry.addChild(help);
            rows.addChild(entry);
            built.add(b);
        }
        // Room under the last row, so its help is never flush with the footer.
        rows.addChild(text(Component.empty(), w), s -> s.paddingBottom(2));
        return rows;
    }

    private MultiLineTextWidget text(Component c, int w) {
        return new MultiLineTextWidget(c, font).setMaxWidth(w);
    }

    /** A key row changed: its help line may have grown (a clash) or shrunk; the key itself is in options.txt. */
    private void keyChanged() {
        if (keyButton == null || keyHelp == null) return;
        keyHelp.setMessage(keyButton.help(keyUsualHelp, HELP));
        if (list != null) list.arrangeElements();
    }

    // --------------------------------------------------------------- host ---

    @Override
    public void changed() {
        Settings.save();
        refresh();
    }

    private void refresh() {
        for (Controls.Built b : built) {
            try {
                b.refresh().run();
            } catch (RuntimeException e) {
                Io.failed("Refreshing a setting", e);
            }
        }
    }

    @Override
    public void openPopup(ColorPopup p) {
        popup = p;
        if (rail) p.place(PAD, PAD, width - 2 * PAD, height - 2 * PAD);
        else p.place(cx, PAD, cw, height - 2 * PAD);
    }

    /** A message in the footer for a few seconds. */
    public void flash(Component message) {
        status = message;
        statusAt = Util.getMillis();
    }

    private void open(Section s) {
        if (s.id().equals(sectionId)) return;
        sectionId = s.id();
        popup = null;
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        if (popup != null) {
            ColorPopup p = popup;
            popup = null;
            p.close(); // applies a half-typed hex value; its close action saves
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------- render ---

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // Opaque from the title screen (Mod Menu): its panorama would shimmer through.
        g.fill(0, 0, width, height, minecraft.level == null ? BG_OPAQUE : BG);
        if (tooSmall) return;
        panel(g, PAD, PAD, sideW, height - 2 * PAD);
        panel(g, cx, PAD, cw, height - 2 * PAD);
        g.fill(cx + 1, PAD + HEADER_H, cx + cw - 1, PAD + HEADER_H + 1, LINE);
        g.fill(cx + 1, height - PAD - FOOTER_H, cx + cw - 1, height - PAD - FOOTER_H + 1, LINE);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, LINE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (popup != null && popup.isClosed()) popup = null;
        // Under an open pop-up nothing is hovered: no highlights, no tooltips.
        int mx = popup != null ? -10000 : mouseX, my = popup != null ? -10000 : mouseY;
        super.extractRenderState(g, mx, my, delta);
        if (tooSmall) {
            g.centeredText(font, Component.translatable("skycosmetics.menu.tooSmall"), width / 2, height / 2 - 10, TEXT);
            return;
        }
        if (!rail) {
            g.text(font, Component.translatable("skycosmetics.name").withStyle(ChatFormatting.BOLD), PAD + 6, PAD + 9, ACCENT);
            String version = FabricLoader.getInstance().getModContainer("skycosmetics")
                .map(c -> "v" + c.getMetadata().getVersion().getFriendlyString()).orElse("");
            small(g, version, PAD + 6, height - PAD - 10, MUTED);
        }
        g.text(font, section.name().copy().withStyle(ChatFormatting.BOLD), cx + 10, PAD + 9, TEXT);
        String help = clip(section.help().getString(), cw - 20);
        g.text(font, help, cx + 10, PAD + 21, MUTED);
        if (help.length() != section.help().getString().length() && mx >= cx && mx < cx + cw && my >= PAD + 20 && my < PAD + 30) {
            g.setTooltipForNextFrame(font, section.help(), mx, my);
        }
        if (Util.getMillis() - statusAt < 4000) g.text(font, clip(status.getString(), cw - 110), cx + 10, height - PAD - 19, ACCENT);
        if (popup != null) popup.render(g, mouseX, mouseY);
    }

    private void small(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, text, 0, 0, color);
        g.pose().popMatrix();
    }

    private String clip(String s, int px) {
        if (px <= 0) return "";
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("..."))) + "...";
    }

    // -------------------------------------------------------------- input ---

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (popup != null) {
            popup.mouseClicked(event.x(), event.y(), event.button());
            if (popup.isClosed()) popup = null;
            return true;
        }
        if (keyButton != null && keyButton.handleClick(event)) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (popup != null) return popup.mouseDragged(event.x(), event.y(), event.button());
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (popup != null) {
            popup.mouseReleased(event.x(), event.y(), event.button());
            super.mouseReleased(event); // ends the screen's own drag state; a release never presses a button
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return popup != null || super.mouseScrolled(mx, my, dx, dy);
    }

    /** A listening key row gets every key first (Esc cancels it, never closes the screen), then the pop-up. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (keyButton != null && keyButton.handleKey(event)) return true;
        if (popup != null) {
            popup.keyPressed(event);
            if (popup.isClosed()) popup = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (popup != null) return popup.charTyped(event);
        return super.charTyped(event);
    }

    // --------------------------------------------------------------- tabs ---

    /** A section in the sidebar, drawn like the studio's item rows: icon, name, a gray line under it. */
    private final class SectionTab extends Button.Plain {
        private final Section s;
        private final ItemStack icon;

        SectionTab(Section s, int x, int y, int w) {
            super(x, y, w, TAB_H - 2, s.name(), b -> open(s), Supplier::get);
            this.s = s;
            ItemStack i;
            try {
                i = s.icon().get();
            } catch (RuntimeException e) {
                Io.failed("Building the icon of settings section " + s.id(), e);
                i = ItemStack.EMPTY;
            }
            this.icon = i;
            if (rail) setTooltip(Tooltip.create(s.name()));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            boolean open = s.id().equals(sectionId);
            if (open) g.fill(getX(), getY(), getRight(), getBottom(), 0x50FFC94A);
            else if (isHoveredOrFocused()) g.fill(getX(), getY(), getRight(), getBottom(), 0x30FFFFFF);
            g.item(icon, getX() + (rail ? 4 : 3), getY() + 3);
            if (rail) return;
            int color = s.order() == Section.FIRST ? ACCENT : TEXT;
            g.text(font, clip(s.name().getString(), getWidth() - 26), getX() + 23, getY() + 3, color);
            small(g, clip(s.sub().getString(), (int) ((getWidth() - 26) / 0.75f)), getX() + 23, getY() + 13, MUTED);
        }
    }

    // --------------------------------------------------------- test hooks ---

    public Screen parent() {
        return parent;
    }

    public String sectionId() {
        return sectionId;
    }

    /** The open section's row ids, headers included, in order. */
    public List<String> rowIds() {
        return List.copyOf(rowIds);
    }

    /** How many rows of the open section have a button: a toggle, choice, key or action. */
    public int buttonRows() {
        int n = 0;
        for (Controls.Built b : built) if (b.widget() instanceof Button) n++;
        return n;
    }

    /** The open color pop-up, or null. */
    public ColorPopup popup() {
        return popup;
    }
}
