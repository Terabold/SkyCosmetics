package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a name gradient: 2 to {@link Names#MAX_STOPS} colors in order (click one to change it with the picker,
 * + adds one at the end, the arrows move it, x or right-click removes it), shown live on the item's name as it
 * is now, at whatever length. Apply puts it on the selected letters or the whole name; Save Preset keeps it
 * beside the built-in gradients.
 */
public final class GradientPopup implements StudioPopup {
    /** What the pop-up works on: the name box, and what Apply and Save do. */
    public interface Host {
        String name();

        int selectionStart();

        int selectionEnd();

        void apply(int[] stops, boolean whole);

        /** False when the same gradient is saved already. */
        boolean save(int[] stops);
    }

    private static final int PAD = 6, TITLE_H = 12, CLOSE = 12, TOGGLE_H = 14, PREVIEW_H = 20, BAR_H = 5;
    private static final int CHIP = 14, GAP = 3, BTN_H = 16, W = 264, PICKER_W = 218, PICKER_MIN = 56, PICKER_MAX = 100;
    private static final int[] START = {0xFF5FA8, 0x5FA8FF};

    private final Host host;
    private final List<Integer> stops = new ArrayList<>();
    private int selected;
    private final ColorPicker picker;
    /** The box had letters selected when it opened: Apply may go to them or the whole name. */
    private final boolean hadSelection;
    private boolean whole, closed;
    private Runnable onClose = () -> {};
    private final Anim appear = new Anim(110, 0);
    private String note = "";
    private int noteColor;
    private long noteAt;

    private int x, y, w, h, toggleY, previewY, chipsY, buttonsY;
    /** The preview, built again only when the name, selection, target, colors or width change. */
    private FormattedCharSequence preview;
    private String previewName;
    private int previewA, previewB, previewW, version, previewVersion = -1;
    private boolean previewWhole;

    /** @param start the colors to begin with (a gradient under the selection), or null for a default pair */
    public GradientPopup(Host host, int[] start) {
        this.host = host;
        for (int c : start != null && start.length >= 2 ? start : START) stops.add(c & 0xFFFFFF);
        hadSelection = host.selectionStart() != host.selectionEnd();
        whole = !hadSelection;
        picker = new ColorPicker(0, 0, PICKER_W, PICKER_MAX);
        picker.setRgb(stops.getFirst());
        picker.setOnChange(rgb -> {
            stops.set(selected, rgb);
            version++;
        });
        place(0, 0, W, 240);
    }

    /** Called when it closes (x, Esc, a click outside). */
    public GradientPopup onClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {} : onClose;
        return this;
    }

    @Override
    public void place(int areaX, int areaY, int areaW, int areaH) {
        w = Math.min(areaW, W);
        int fixed = PAD + TITLE_H + 4 + (hadSelection ? TOGGLE_H + 5 : 0) + PREVIEW_H + 3 + BAR_H + 6 + CHIP + 6 + 6 + BTN_H + PAD;
        int ph = Math.clamp(areaH - fixed, PICKER_MIN, PICKER_MAX);
        h = fixed + ph;
        x = areaX + (areaW - w) / 2;
        y = areaY + Math.max(0, (areaH - h) / 2);
        toggleY = y + PAD + TITLE_H + 4;
        previewY = toggleY + (hadSelection ? TOGGLE_H + 5 : 0);
        chipsY = previewY + PREVIEW_H + 3 + BAR_H + 6;
        int pickerY = chipsY + CHIP + 6;
        int pw = Math.min(w - 2 * PAD, PICKER_W);
        picker.setSize(pw, ph);
        picker.setPosition(x + (w - pw) / 2, pickerY);
        buttonsY = pickerY + ph + 6;
    }

    /** The colors, first to last (0xRRGGBB). */
    public int[] stops() {
        return stops.stream().mapToInt(Integer::intValue).toArray();
    }

    public int selected() {
        return selected;
    }

    /** Whether Apply goes to the whole name rather than the selected letters. */
    public boolean wholeName() {
        return whole;
    }

    public ColorPicker picker() {
        return picker;
    }

    /** Centers of the parts a test clicks: the chip of color {@code i} (or the + after the last), and the buttons. */
    public int[] chipAt(int i) {
        return new int[]{x + PAD + i * (CHIP + GAP) + CHIP / 2, chipsY + CHIP / 2};
    }

    public int[] applyAt() {
        int[] b = buttons();
        return new int[]{b[2] + b[3] / 2, buttonsY + BTN_H / 2};
    }

    public int[] saveAt() {
        int[] b = buttons();
        return new int[]{b[0] + b[1] / 2, buttonsY + BTN_H / 2};
    }

    /** The Selection (false) or Whole Name (true) half of the switch; only there when letters were selected. */
    public int[] targetAt(boolean wholeName) {
        int half = (w - 2 * PAD) / 2;
        return new int[]{x + PAD + (wholeName ? half + half / 2 : half / 2), toggleY + TOGGLE_H / 2};
    }

    /** The preview as drawn last, for tests. */
    public FormattedCharSequence preview() {
        return preview;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ---------------------------------------------------------- render ---

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        g.nextStratum();
        float t = Anim.easeOut(appear.to(1));
        Shapes.shadow(g, x, y, w, h, Theme.RADIUS, 6);
        Shapes.round(g, x, y, w, h, Theme.RADIUS, Theme.BODY);
        Shapes.frame(g, x, y, w, h, Theme.RADIUS, Theme.mix(Theme.LINE, Theme.ACCENT, 0.5f * t));
        g.text(font, "Gradient", x + PAD, y + PAD + 2, Theme.TEXT);
        if (!note.isEmpty() && Util.getMillis() - noteAt < 2500) {
            g.text(font, note, x + w - PAD - CLOSE - 6 - font.width(note), y + PAD + 2, noteColor);
        }
        int cx = x + w - PAD - CLOSE, cy = y + PAD;
        boolean overClose = in(mouseX, mouseY, cx, cy, CLOSE, CLOSE);
        if (overClose) Shapes.round(g, cx, cy, CLOSE, CLOSE, Theme.SMALL_RADIUS, 0xFF8A2E4A);
        Ui.cross(g, cx + 3, cy + 3, CLOSE - 6, overClose ? 0xFFFFFFFF : Theme.MUTED);
        if (overClose) g.requestCursor(CursorTypes.POINTING_HAND);

        int bw = w - 2 * PAD;
        if (hadSelection) {
            int half = bw / 2;
            AnimatedDyeEditor.segment(g, font, "Selection", x + PAD, toggleY, half - 1, !whole, mouseX, mouseY);
            AnimatedDyeEditor.segment(g, font, "Whole Name", x + PAD + half + 1, toggleY, bw - half - 1, whole, mouseX, mouseY);
        }

        // The name as it would look, on a tooltip's dark background, then the gradient itself.
        int[] st = stops();
        Shapes.round(g, x + PAD, previewY, bw, PREVIEW_H, Theme.SMALL_RADIUS, 0xF0100010);
        Shapes.frame(g, x + PAD, previewY, bw, PREVIEW_H, Theme.SMALL_RADIUS, Theme.LINE);
        FormattedCharSequence p = preview(font, bw - 8);
        g.text(font, p, x + PAD + (bw - font.width(p)) / 2, previewY + (PREVIEW_H - 8) / 2, 0xFFFFFFFF, true);
        Shapes.roundStops(g, x + PAD, previewY + PREVIEW_H + 3, bw, BAR_H, 2, st);

        for (int i = 0; i < st.length; i++) {
            int chx = x + PAD + i * (CHIP + GAP);
            boolean over = in(mouseX, mouseY, chx, chipsY, CHIP, CHIP);
            Shapes.round(g, chx + 1, chipsY + 1, CHIP - 2, CHIP - 2, 2, 0xFF000000 | st[i]);
            if (i == selected) {
                Shapes.frame(g, chx, chipsY, CHIP, CHIP, 3, 0xFFFFFFFF);
                Shapes.frame(g, chx + 1, chipsY + 1, CHIP - 2, CHIP - 2, 2, 0xC0000000);
            } else {
                Shapes.frame(g, chx, chipsY, CHIP, CHIP, 3, over ? 0xFFFFFFFF : 0x50FFFFFF);
            }
            if (over) g.requestCursor(CursorTypes.POINTING_HAND);
        }
        int right = x + w - PAD;
        if (st.length < Names.MAX_STOPS) icon(g, font, "+", x + PAD + st.length * (CHIP + GAP), chipsY, true, mouseX, mouseY);
        icon(g, font, "x", right - CHIP, chipsY, st.length > 2, mouseX, mouseY);
        icon(g, font, ">", right - 2 * CHIP - GAP - 4, chipsY, selected < st.length - 1, mouseX, mouseY);
        icon(g, font, "<", right - 3 * CHIP - 2 * GAP - 4, chipsY, selected > 0, mouseX, mouseY);

        picker.render(g, mouseX, mouseY);

        int[] b = buttons();
        button(g, font, "Save Preset", b[0], buttonsY, b[1], false, mouseX, mouseY);
        button(g, font, whole ? "Apply to Name" : "Apply to Selection", b[2], buttonsY, b[3], true, mouseX, mouseY);
    }

    /** x and width of Save Preset, then of Apply. */
    private int[] buttons() {
        Font font = Minecraft.getInstance().font;
        int bw = w - 2 * PAD;
        int save = Math.min(bw / 2 - 2, font.width("Save Preset") + 14);
        return new int[]{x + PAD, save, x + PAD + save + 4, bw - save - 4};
    }

    private FormattedCharSequence preview(Font font, int width) {
        String name = host.name();
        int a = host.selectionStart(), b = host.selectionEnd();
        if (preview == null || version != previewVersion || !name.equals(previewName) || a != previewA || b != previewB
            || whole != previewWhole || width != previewW) {
            previewVersion = version;
            previewName = name;
            previewA = a;
            previewB = b;
            previewWhole = whole;
            previewW = width;
            String text = name.isBlank() ? "Your Item Name" : name;
            boolean all = whole || a == b || name.isBlank();
            Names.Edit e = Names.gradient(text, all ? 0 : a, all ? text.length() : b, stops());
            preview = NameBox.fit(font, Names.parse(e.text()), width);
        }
        return preview;
    }

    private static void icon(GuiGraphicsExtractor g, Font font, String glyph, int ix, int iy, boolean on, int mouseX, int mouseY) {
        boolean over = on && in(mouseX, mouseY, ix, iy, CHIP, CHIP);
        float a = on ? 1 : 0.35f;
        Shapes.round(g, ix, iy, CHIP, CHIP, Theme.SMALL_RADIUS, Theme.fade(over ? Theme.SURFACE_HOVER : Theme.SURFACE, a));
        Shapes.frame(g, ix, iy, CHIP, CHIP, Theme.SMALL_RADIUS, Theme.fade(over ? Theme.ACCENT : Theme.LINE, a));
        if (glyph.equals("x")) Ui.cross(g, ix + 4, iy + 4, CHIP - 8, Theme.fade(over ? 0xFFFFFFFF : Theme.TEXT, a));
        else if (glyph.equals(">")) Ui.chevronRight(g, ix + 5, iy + 4, Theme.fade(Theme.TEXT, a));
        else if (glyph.equals("<")) chevronLeft(g, ix + 5, iy + 4, Theme.fade(Theme.TEXT, a));
        else Ui.centered(g, font, glyph, ix + CHIP / 2 + 1, iy + 3, Theme.fade(Theme.ACCENT, a));
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private static void chevronLeft(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 3; i++) g.fill(x + 2 - i, y + i, x + 3 - i, y + 6 - i, color);
    }

    private static void button(GuiGraphicsExtractor g, Font font, String label, int bx, int by, int bw, boolean primary,
                               int mouseX, int mouseY) {
        boolean over = in(mouseX, mouseY, bx, by, bw, BTN_H);
        if (primary) {
            Shapes.roundGradient(g, bx, by, bw, BTN_H, Theme.SMALL_RADIUS,
                over ? Theme.GRADIENT_START_HOVER : Theme.GRADIENT_START, over ? Theme.GRADIENT_END_HOVER : Theme.GRADIENT_END);
        } else {
            Shapes.round(g, bx, by, bw, BTN_H, Theme.SMALL_RADIUS, over ? Theme.SURFACE_HOVER : Theme.SURFACE);
            Shapes.frame(g, bx, by, bw, BTN_H, Theme.SMALL_RADIUS, over ? Theme.ACCENT : Theme.LINE);
        }
        Ui.centered(g, font, Ui.clip(font, label, bw - 6), bx + bw / 2, by + (BTN_H - 8) / 2, primary ? Theme.ON_ACCENT : Theme.TEXT);
        if (over) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ----------------------------------------------------------- input ---

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!in(mx, my, x, y, w, h) || in(mx, my, x + w - PAD - CLOSE, y + PAD, CLOSE, CLOSE)) {
            close();
            return true;
        }
        if (picker.mouseClicked(mx, my, button)) return true;
        picker.stopEditing();
        boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
        if (hadSelection && left && in(mx, my, x + PAD, toggleY, w - 2 * PAD, TOGGLE_H)) {
            whole = mx >= x + PAD + (w - 2 * PAD) / 2;
            return true;
        }
        int n = stops.size(), right = x + w - PAD;
        for (int i = 0; i < n; i++) {
            if (!in(mx, my, x + PAD + i * (CHIP + GAP), chipsY, CHIP, CHIP)) continue;
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) remove(i);
            else select(i);
            return true;
        }
        if (!left) return true;
        if (n < Names.MAX_STOPS && in(mx, my, x + PAD + n * (CHIP + GAP), chipsY, CHIP, CHIP)) add();
        else if (in(mx, my, right - CHIP, chipsY, CHIP, CHIP)) remove(selected);
        else if (in(mx, my, right - 2 * CHIP - GAP - 4, chipsY, CHIP, CHIP)) move(1);
        else if (in(mx, my, right - 3 * CHIP - 2 * GAP - 4, chipsY, CHIP, CHIP)) move(-1);
        int[] b = buttons();
        if (in(mx, my, b[0], buttonsY, b[1], BTN_H)) save();
        else if (in(mx, my, b[2], buttonsY, b[3], BTN_H)) apply();
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button) {
        picker.mouseDragged(mx, my, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        picker.mouseReleased(mx, my, button);
        return true;
    }

    /** Keys go to the hex box while it is typed in. Else Esc closes, Enter applies, the arrows pick a color. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (picker.isEditing()) {
            picker.keyPressed(event);
            return true;
        }
        int k = event.key();
        if (k == InputConstants.KEY_ESCAPE) close();
        else if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) apply();
        else if (event.isLeft() && selected > 0) select(selected - 1);
        else if (event.isRight() && selected < stops.size() - 1) select(selected + 1);
        else if (k == InputConstants.KEY_DELETE) remove(selected);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        picker.charTyped(event);
        return true;
    }

    @Override
    public void close() {
        if (closed) return;
        picker.stopEditing();
        closed = true;
        onClose.run();
    }

    // --------------------------------------------------------- editing ---

    private void select(int i) {
        picker.stopEditing();
        selected = i;
        picker.setRgb(stops.get(i));
    }

    /** A new last color, lighter than the one before it; the arrows move it where it should go. */
    private void add() {
        if (stops.size() >= Names.MAX_STOPS) return;
        stops.add(Names.mix(new int[]{stops.getLast(), 0xFFFFFF}, 0.5f));
        version++;
        select(stops.size() - 1);
    }

    private void remove(int i) {
        if (stops.size() <= 2) return;
        stops.remove(i);
        version++;
        select(Math.min(selected > i ? selected - 1 : selected, stops.size() - 1));
    }

    private void move(int by) {
        int to = selected + by;
        if (to < 0 || to >= stops.size()) return;
        stops.add(to, stops.remove(selected));
        version++;
        selected = to;
    }

    private void apply() {
        picker.stopEditing();
        host.apply(stops(), whole || !hadSelection);
        flash("Applied", Theme.ACCENT);
    }

    private void save() {
        picker.stopEditing();
        boolean saved = host.save(stops());
        flash(saved ? "Saved" : "Saved already", saved ? 0xFF66DD88 : Theme.MUTED);
    }

    private void flash(String text, int color) {
        note = text;
        noteColor = color;
        noteAt = Util.getMillis();
    }
}
