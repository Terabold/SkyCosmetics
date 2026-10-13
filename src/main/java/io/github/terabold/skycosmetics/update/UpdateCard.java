package io.github.terabold.skycosmetics.update;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The top of General: what the updater has to say. Nothing while there's nothing (no room taken); one quiet line while
 * checking, when up to date, when the check failed or the newest is skipped; a card when there is a new version (its
 * notes and Download &amp; Install on Restart, Release Page, Skip This Version), while it downloads, once it waits for a
 * restart, when it failed, and after an update ("Updated to ...").
 *
 * One widget, for the settings' {@code Control.Custom} row: its buttons are drawn and clicked through it, and with the
 * keyboard Left and Right pick one, Enter presses it. It lays itself out again whenever {@link Updater#changes()} moves
 * (asked from {@link #getHeight}, which the settings read every frame), so the row grows and shrinks with it.
 */
public final class UpdateCard extends AbstractWidget {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, "icon.png");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final int PAD = 8, LINE = 18, MAX_NOTES = 8, GAP = 4;

    /** A button and where it sits in the card. */
    private static final class Button {
        final ActionButton widget;
        final Runnable run;
        int rx, ry;

        Button(ActionButton widget, Runnable run) {
            this.widget = widget;
            this.run = run;
        }

        ActionButton widget() {
            return widget;
        }

        Runnable run() {
            return run;
        }
    }

    /** One drawn line of the notes: a list item's bullet on its first line, its text hanging after it. */
    private record Row(String prefix, int indent, FormattedCharSequence text) {
        static final Row GAP = new Row("", 0, FormattedCharSequence.EMPTY);
    }

    private final List<Button> buttons = new ArrayList<>();
    private List<FormattedCharSequence> message = List.of();
    private List<Row> notes = List.of();
    private FormattedCharSequence title = FormattedCharSequence.EMPTY;
    private String sub = "", quiet = "", quietRight = "", progressText = "";
    private boolean card, warn, progress, more;
    private int quietIcon;
    private long quietMinute = -1;
    private int built = Integer.MIN_VALUE, builtW = -1, builtTheme = -1, selected;
    private int notesY, messageY, progressY, buttonsY;

    /** The settings that asked for Check Now: they hear the result once it is in. */
    private static Host flashTo;
    private final Host host;

    public UpdateCard(Host host, int width) {
        super(0, 0, width, 0, Component.translatable("skycosmetics.update.title"));
        this.host = host;
    }

    /** After Check Now: the next result shows as a message at the bottom of these settings. */
    static void flashNext(Host settings) {
        flashTo = settings;
    }

    // ================================================================ layout

    /** Lays out again when the updater, the width or the accent changed. Cheap otherwise: three int compares. */
    private void sync() {
        int c = Updater.changes();
        if (c == built && builtW == width && builtTheme == Theme.version()) return;
        built = c;
        builtW = width;
        builtTheme = Theme.version();
        flashResult();
        try {
            layout();
        } catch (RuntimeException e) {
            Io.failed("Laying out the update card", e);
            card = false;
            quiet = "";
            buttons.clear();
            height = 0;
        }
    }

    @Override
    public int getHeight() {
        sync();
        return height;
    }

    private void layout() {
        Font font = Minecraft.getInstance().font;
        buttons.clear();
        notes = List.of();
        message = List.of();
        card = warn = progress = more = false;
        quiet = quietRight = sub = "";
        quietMinute = -1;
        Updater.Status s = Updater.status();
        Updater.Updated up = Updater.updated();
        if (!Updater.checkEnabled()) {
            height = 0;
            return;
        }
        int textW = Math.max(40, width - 2 * PAD - 22);
        Releases.Offer offer = s.offer();
        switch (s.kind()) {
            case AVAILABLE -> {
                head(font, tr("available", offer.version()), you(offer));
                if (s.blocked() != null) note(font, tr("blocked", s.blocked()), false, textW);
                notes(font, offer.release(), offer.newer());
                if (s.blocked() == null) add(tr("download"), true, Updater::download);
                add(tr("page"), false, () -> open(offer.release().url()));
                add(tr("skip"), false, Updater::skip);
            }
            case DOWNLOADING -> {
                head(font, tr("downloading", offer.version()), you(offer));
                progress = true;
                long total = Math.max(1, s.total());
                int pct = (int) Math.min(100, s.done() * 100 / total);
                progressText = tr("progress", pct, mb(s.done()), mb(total)).getString();
                add(tr("cancel"), false, Updater::cancel);
            }
            case READY -> {
                head(font, tr("ready", offer.version()), tr("ready.sub").getString());
                notes(font, offer.release(), offer.newer());
                add(tr("page"), false, () -> open(offer.release().url()));
                add(tr("undo"), false, Updater::undo);
            }
            case FAILED -> {
                head(font, tr("failed", offer.version()), you(offer));
                note(font, Component.literal(s.message() == null ? "" : s.message()), true, textW);
                note(font, tr("failed.page"), false, textW);
                add(tr("page"), true, () -> open(offer.release().url()));
                if (s.blocked() == null) add(tr("retry"), false, Updater::download);
                add(tr("skip"), false, Updater::skip);
            }
            default -> {
                if (up != null) {
                    head(font, tr("updated", up.to()), tr("updated.sub", up.from()).getString());
                    notes(font, up.release(), 1);
                    if (up.release() != null) add(tr("page"), false, () -> open(up.release().url()));
                    add(tr("dismiss"), false, Updater::dismissUpdated);
                } else {
                    quiet(s);
                }
            }
        }
        if (!card) {
            height = quiet.isEmpty() ? 0 : LINE;
            return;
        }
        // Text under the header, then the buttons wrapped to the width.
        int y = PAD + 22;
        messageY = y;
        y += message.size() * 10 + (message.isEmpty() ? 0 : 2);
        progressY = y;
        if (progress) y += 20;
        notesY = y;
        if (!notes.isEmpty()) y += 6 + notes.size() * 10 + (more ? 10 : 0) + 2;
        y += 2;
        int x = PAD;
        for (Button b : buttons) {
            int w = Math.min(width - 2 * PAD, ActionButton.fit(b.widget().getMessage()));
            b.widget().setWidth(w);
            if (x > PAD && x + w > width - PAD) {
                x = PAD;
                y += ActionButton.H + GAP;
            }
            b.rx = x;
            b.ry = y;
            x += w + GAP;
        }
        buttonsY = y;
        height = y + ActionButton.H + PAD;
        selected = Math.clamp(selected, 0, Math.max(0, buttons.size() - 1));
    }

    private void flashResult() {
        if (flashTo == null || flashTo != host) return;
        Updater.Status s = Updater.status();
        Component msg = switch (s.kind()) {
            case CURRENT -> tr("flash.current");
            case AVAILABLE, READY, SKIPPED, FAILED -> tr("flash.available", s.offer().version());
            case ERROR -> tr("error", s.message() == null ? "" : s.message());
            default -> null;
        };
        if (msg == null) return;
        flashTo = null;
        host.flash(msg);
    }

    private void head(Font font, Component t, String subtitle) {
        card = true;
        title = Component.empty().append(t).withStyle(ChatFormatting.BOLD).getVisualOrderText();
        sub = Ui.clip(font, subtitle, Math.max(20, width - 2 * PAD - 22));
    }

    /** "You have 1.4.0; Beta; Oct 5". */
    private static String you(Releases.Offer offer) {
        StringBuilder b = new StringBuilder(tr("you", Updater.current()).getString());
        if (offer.release().prerelease()) b.append("; ").append(tr("beta").getString());
        String date = date(offer.release().published());
        if (!date.isEmpty()) b.append("; ").append(date);
        return b.toString();
    }

    private void note(Font font, Component text, boolean isWarn, int w) {
        List<FormattedCharSequence> lines = new ArrayList<>(message);
        lines.addAll(font.split(text, Math.max(40, width - 2 * PAD)));
        message = lines;
        warn |= isWarn;
    }

    private void notes(Font font, Releases.Release r, int newer) {
        if (r == null) return;
        List<Row> out = new ArrayList<>();
        int w = Math.max(40, width - 2 * PAD - 4);
        for (Notes.Line line : Notes.lines(r.body())) {
            if (line.gap()) {
                out.add(Row.GAP);
                continue;
            }
            int indent = Math.min(w / 2, font.width(line.prefix()));
            boolean first = true;
            for (FormattedCharSequence part : font.split(line.body(), w - indent)) {
                out.add(new Row(first ? line.prefix() : "", indent, part));
                first = false;
            }
        }
        if (newer > 1) {
            if (!out.isEmpty()) out.add(Row.GAP);
            for (FormattedCharSequence part : font.split(tr("more", newer).withStyle(ChatFormatting.ITALIC), w)) {
                out.add(new Row("", 0, part));
            }
        }
        while (!out.isEmpty() && out.getLast() == Row.GAP) out.removeLast();
        more = out.size() > MAX_NOTES;
        notes = more ? out.subList(0, MAX_NOTES) : out;
    }

    private void quiet(Updater.Status s) {
        switch (s.kind()) {
            case CHECKING -> {
                quiet = tr("checking").getString();
                quietIcon = 0;
            }
            case CURRENT -> {
                quiet = tr("current", Updater.current()).getString();
                quietIcon = 1;
            }
            case SKIPPED -> {
                quiet = tr("skipped", s.offer().version()).getString();
                quietIcon = 2;
            }
            case ERROR -> {
                quiet = tr("error", s.message() == null ? "" : s.message()).getString();
                quietIcon = 3;
            }
            default -> quiet = "";
        }
        quiet = Ui.clip(Minecraft.getInstance().font, quiet, Math.max(20, width - 24 - 90));
    }

    private void add(Component label, boolean primary, Runnable run) {
        buttons.add(new Button(new ActionButton(label, 40, primary, b -> {}), run));
    }

    // ================================================================ drawing

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        sync();
        if (height == 0) return;
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = width;
        if (!card) {
            quietLine(g, font, x, y, w);
            return;
        }
        Shapes.roundGradient(g, x, y, w, height, Theme.RADIUS, Theme.mix(Theme.SURFACE, Theme.ACCENT_BG, 0.55f),
            Theme.mix(Theme.SURFACE, Theme.ACCENT_BG_END, 0.35f));
        Shapes.frame(g, x, y, w, height, Theme.RADIUS, Theme.fade(warn ? Theme.WARN : Theme.ACCENT, 0.55f));
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, x + PAD, y + PAD + 2, 0, 0, 16, 16, 128, 128, 128, 128);
        g.text(font, title, x + PAD + 22, y + PAD, Theme.TEXT, false);
        g.text(font, sub, x + PAD + 22, y + PAD + 11, Theme.MUTED, false);
        int ty = y + messageY;
        for (FormattedCharSequence line : message) {
            g.text(font, line, x + PAD, ty, warn && ty == y + messageY ? Theme.WARN : Theme.MUTED, false);
            ty += 10;
        }
        if (progress) {
            int by = y + progressY + 2, bw = w - 2 * PAD;
            Updater.Status s = Updater.status();
            float p = s.total() <= 0 ? 0 : Math.min(1f, s.done() / (float) s.total());
            Shapes.round(g, x + PAD, by, bw, 5, 2, Theme.fade(0xFF000000, 0.35f));
            if (p > 0) Shapes.roundGradient(g, x + PAD, by, Math.max(5, Math.round(bw * p)), 5, 2, Theme.GRADIENT_START, Theme.GRADIENT_END);
            g.text(font, progressText, x + PAD, by + 9, Theme.MUTED, false);
        }
        if (!notes.isEmpty()) {
            int ny = y + notesY;
            g.fill(x + PAD, ny + 2, x + w - PAD, ny + 3, Theme.fade(Theme.LINE, 0.8f));
            ny += 6;
            for (Row row : notes) {
                if (!row.prefix().isEmpty()) g.text(font, row.prefix(), x + PAD + 2, ny, Theme.MUTED, false);
                g.text(font, row.text(), x + PAD + 2 + row.indent(), ny, Theme.TEXT, false);
                ny += 10;
            }
            if (more) g.text(font, "...", x + PAD + 2, ny, Theme.DIM, false);
        }
        boolean keys = Ui.keyboardFocus(this);
        for (int i = 0; i < buttons.size(); i++) {
            Button bt = buttons.get(i);
            ActionButton b = bt.widget();
            b.setPosition(x + bt.rx, y + bt.ry);
            b.active = active;
            b.extractRenderState(g, mouseX, mouseY, delta);
            if (keys && i == selected) Ui.focusRing(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), Theme.SMALL_RADIUS + 1);
            if (b.isHovered() && active) g.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void quietLine(GuiGraphicsExtractor g, Font font, int x, int y, int w) {
        Shapes.round(g, x, y, w, LINE, Theme.SMALL_RADIUS + 1, Theme.fade(Theme.SURFACE, 0.55f));
        int cy = y + (LINE - 7) / 2, tx = x + 8;
        switch (quietIcon) {
            case 0 -> { // three dots taking turns
                int phase = (int) (Util.getMillis() / 300 % 3);
                for (int i = 0; i < 3; i++) Shapes.round(g, tx + i * 4, cy + 3, 2, 2, 1, i == phase ? Theme.ACCENT : Theme.DIM);
                tx += 16;
            }
            case 1 -> { // a check mark
                for (int i = 0; i < 3; i++) g.fill(tx + i, cy + 3 + i, tx + i + 1, cy + 4 + i, Theme.ACCENT);
                for (int i = 0; i < 5; i++) g.fill(tx + 3 + i, cy + 4 - i, tx + 4 + i, cy + 5 - i, Theme.ACCENT);
                tx += 14;
            }
            case 3 -> {
                g.text(font, "!", tx + 1, cy, Theme.WARN, false);
                tx += 9;
            }
            default -> {
                Ui.chevronRight(g, tx + 1, cy + 1, Theme.DIM);
                tx += 9;
            }
        }
        g.text(font, quiet, tx, cy, quietIcon == 3 ? Theme.mix(Theme.WARN, Theme.MUTED, 0.4f) : Theme.MUTED, false);
        long checked = Updater.status().checkedAt();
        if (quietIcon != 0 && checked > 0) {
            long minute = (System.currentTimeMillis() - checked) / 60_000;
            if (minute != quietMinute) {
                quietMinute = minute;
                quietRight = ago(minute).getString();
            }
            int rw = font.width(quietRight);
            if (tx + font.width(quiet) + 12 + rw <= x + w - 8) g.text(font, quietRight, x + w - 8 - rw, cy, Theme.DIM, false);
        }
    }

    private static Component ago(long minutes) {
        if (minutes < 1) return tr("checked.now");
        if (minutes < 60) return tr("checked.minutes", minutes);
        if (minutes < 48 * 60) return tr("checked.hours", minutes / 60);
        return tr("checked.days", minutes / (24 * 60));
    }

    // ================================================================ input

    private int at(double mx, double my) {
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            int bx = getX() + b.rx, by = getY() + b.ry;
            if (mx >= bx && mx < bx + b.widget().getWidth() && my >= by && my < by + ActionButton.H) return i;
        }
        return -1;
    }

    /** Only the buttons take clicks; the rest of the card is text. */
    @Override
    public boolean isMouseOver(double mx, double my) {
        return active && visible && height > 0 && super.isMouseOver(mx, my) && at(mx, my) >= 0;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        press(at(event.x(), event.y()));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || buttons.isEmpty()) return false;
        if (event.isLeft() || event.isRight()) {
            selected = Math.clamp(selected + (event.isLeft() ? -1 : 1), 0, buttons.size() - 1);
            return true;
        }
        if (event.isSelection()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            press(selected);
            return true;
        }
        return false;
    }

    /** Tab skips the card while it has no buttons. */
    @Override
    public net.minecraft.client.gui.ComponentPath nextFocusPath(net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
        sync();
        return buttons.isEmpty() ? null : super.nextFocusPath(event);
    }

    private void press(int i) {
        if (i < 0 || i >= buttons.size()) return;
        selected = i;
        try {
            buttons.get(i).run().run();
        } catch (RuntimeException e) {
            Io.failed("Pressing an update button", e);
        }
    }

    /** Opens a release page in the browser: only this repository's pages on GitHub. */
    static void open(String url) {
        String u = url != null && url.startsWith(Fetch.PAGE) ? url : Fetch.PAGE;
        try {
            Util.getPlatform().openUri(URI.create(u));
        } catch (RuntimeException e) {
            Io.failed("Opening the release page", e);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, Component.literal(text()));
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable("skycosmetics.update." + key, args);
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String date(String iso) {
        try {
            return iso == null || iso.isEmpty() ? "" : DATE.format(Instant.parse(iso).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ================================================================ tests

    /** The labels of the buttons shown now. */
    public List<String> buttonLabels() {
        sync();
        List<String> out = new ArrayList<>();
        for (Button b : buttons) out.add(b.widget().getMessage().getString());
        return out;
    }

    /** The middle of the button with this label, on screen; null without one. */
    public int[] centerOf(String label) {
        sync();
        for (Button b : buttons) {
            ActionButton w = b.widget();
            if (w.getMessage().getString().equals(label)) {
                return new int[]{getX() + b.rx + w.getWidth() / 2, getY() + b.ry + ActionButton.H / 2};
            }
        }
        return null;
    }

    /** The card's text as drawn: title, subtitle or the quiet line. */
    public String text() {
        sync();
        if (!card) return quiet;
        StringBuilder b = new StringBuilder();
        title.accept((i, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        return b + " / " + sub;
    }

    public boolean isCard() {
        sync();
        return card;
    }
}
