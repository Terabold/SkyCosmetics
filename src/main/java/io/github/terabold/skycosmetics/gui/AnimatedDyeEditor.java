package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Designs a custom animated dye: up to 8 keyframe chips (click to edit with a
 * {@link ColorPicker}, + to add, right-click or the corner x to remove), a
 * Step/Blend toggle, a speed slider, a little armour set showing the ripple and
 * a strip of the whole cycle with a moving playhead.
 *
 * The result is an {@code anim:} dye id ({@link #dyeId()}), which a look stores
 * like any other dye; {@code Catalog.dye} expands it. Input forwarding works
 * like {@link ColorPicker}: every click through {@link #mouseClicked} first,
 * keys through {@link #keyPressed} / {@link #charTyped} while {@link #isEditing()}.
 * Comfortable from about 220 x 150.
 */
public class AnimatedDyeEditor {
    public static final int MAX_KEYS = 8;
    private static final int MIN_KEYS = 2;
    private static final int CHIP = 14;
    private static final int CHIP_GAP = 3;
    private static final int STRIP_H = 10;
    private static final int TOGGLE_H = 14;
    /** Ticks per colour step offered by the slider, fastest first; Hypixel's dyes use 2. */
    private static final int[] SPEEDS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100};
    private static final int[] DEFAULT_KEYS = {0xD58CFF, 0x4AC8FF};

    private static final int LINE = Theme.LINE, TEXT = Theme.TEXT, MUTED = Theme.MUTED;

    private int x, y, width, height;
    private final List<Integer> keys = new ArrayList<>();
    private int selected;
    private int ticks = DyeEntry.TICKS_PER_COLOR;
    private boolean blend = true;
    private final ColorPicker picker;
    private boolean draggingSpeed;
    private final Tips.Hover chipHover = new Tips.Hover();

    /** Rebuilt on every edit, never per frame. */
    private String id;
    private DyeEntry preview;
    private Consumer<String> onChange = s -> {};
    private Consumer<String> onCommit = s -> {};

    // Layout, from layout().
    private int pickerY, sideX, sideW, toggleY, speedY, armourY, stripY;

    public AnimatedDyeEditor(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        picker = new ColorPicker(x, y, 100, 100);
        picker.setOnChange(rgb -> {
            keys.set(selected, rgb);
            edited(false);
        });
        picker.setOnCommit(rgb -> onCommit.accept(id));
        layout();
        load(null);
    }

    // ------------------------------------------------------------- API ---

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        layout();
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        layout();
    }

    /**
     * Starts from {@code dyeId} without calling the listeners: an {@code anim:} id
     * is edited as it is; a Hypixel animated dye is sampled into 6 keyframes at a
     * similar pace; a plain colour becomes that colour and a hue-shifted partner;
     * null or unknown starts from a default pair.
     */
    public void load(String dyeId) {
        keys.clear();
        ticks = DyeEntry.TICKS_PER_COLOR;
        blend = true;
        DyeEntry.AnimSpec spec = DyeEntry.parseCustomAnimated(dyeId);
        if (spec != null) {
            for (int i = 0; i < Math.min(MAX_KEYS, spec.keyframes().length); i++) keys.add(spec.keyframes()[i]);
            ticks = spec.ticksPerStep();
            blend = spec.blend();
        } else {
            DyeEntry d = dyeId == null ? null : Repo.get().dye(dyeId);
            if (d != null && d.animated()) {
                int n = Math.min(6, d.colors.length);
                for (int i = 0; i < n; i++) keys.add(d.colors[i * d.colors.length / n]);
                ticks = nearestSpeed(Math.round(d.colors.length * d.ticksPerColor / (float) (n * DyeEntry.BLEND_STEPS)));
            } else if (d != null) {
                keys.add(d.colors[0]);
            }
        }
        if (keys.isEmpty()) for (int k : DEFAULT_KEYS) keys.add(k);
        if (keys.size() < MIN_KEYS) keys.add(shiftHue(keys.getFirst()));
        selected = 0;
        picker.setRgb(keys.getFirst());
        rebuild();
    }

    /** The dye being designed, as an {@code anim:} id; never null. */
    public String dyeId() {
        return id;
    }

    /** The expanded dye, as {@code Catalog.dye(dyeId())} would return it. */
    public DyeEntry dye() {
        return preview;
    }

    /** Called on every edit (often while dragging); use for live previews. */
    public void setOnChange(Consumer<String> onChange) {
        this.onChange = onChange == null ? s -> {} : onChange;
    }

    /** Called when an edit is finished (mouse released, chip added or removed, mode toggled); save here. */
    public void setOnCommit(Consumer<String> onCommit) {
        this.onCommit = onCommit == null ? s -> {} : onCommit;
    }

    public boolean isEditing() {
        return picker.isEditing();
    }

    public void stopEditing() {
        picker.stopEditing();
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    // ---------------------------------------------------------- render ---

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        long tick = Util.getMillis() / 50;

        // Keyframe chips.
        int hoveredChip = chipAt(mouseX, mouseY);
        for (int i = 0; i < keys.size(); i++) {
            int cx = chipX(i);
            Shapes.round(g, cx, y, CHIP, CHIP, Theme.SMALL_RADIUS, 0xFF000000 | keys.get(i));
            if (i == selected) {
                Shapes.frame(g, cx - 2, y - 2, CHIP + 4, CHIP + 4, Theme.SMALL_RADIUS + 2, 0xFFFFFFFF);
                Shapes.frame(g, cx - 1, y - 1, CHIP + 2, CHIP + 2, Theme.SMALL_RADIUS + 1, 0xFF000000);
            } else {
                Shapes.frame(g, cx, y, CHIP, CHIP, Theme.SMALL_RADIUS, i == hoveredChip ? 0xFFFFFFFF : 0x50FFFFFF);
            }
            if (i == hoveredChip && keys.size() > MIN_KEYS) {
                boolean overX = inRemove(i, mouseX, mouseY);
                Shapes.round(g, cx + CHIP - 6, y - 1, 7, 7, 3, overX ? Theme.DANGER_HOVER : 0xE0111116);
                Ui.cross(g, cx + CHIP - 4, y + 1, 3, overX ? 0xFFFFFFFF : Theme.WARN);
            }
        }
        if (keys.size() < MAX_KEYS) {
            int px = chipX(keys.size());
            boolean over = inChip(keys.size(), mouseX, mouseY);
            Shapes.round(g, px, y, CHIP, CHIP, Theme.SMALL_RADIUS, over ? Theme.SURFACE_HOVER : Theme.SURFACE);
            Shapes.frame(g, px, y, CHIP, CHIP, Theme.SMALL_RADIUS, over ? Theme.ACCENT : LINE);
            g.centeredText(font, "+", px + CHIP / 2 + 1, y + 3, over ? Theme.ACCENT : TEXT);
        }
        String count = keys.size() + "/" + MAX_KEYS + " colors";
        int countX = x + width - font.width(count);
        if (countX > chipX(Math.min(keys.size() + 1, MAX_KEYS)) + 4) g.text(font, count, countX, y + 3, MUTED);

        picker.render(g, mouseX, mouseY);

        // Side column: mode, speed, a tiny armour set.
        g.text(font, "Mode", sideX, toggleY - 10, MUTED);
        int half = sideW / 2;
        segment(g, font, "Step", sideX, toggleY, half - 1, !blend, mouseX, mouseY);
        segment(g, font, "Blend", sideX + half + 1, toggleY, sideW - half - 1, blend, mouseX, mouseY);

        g.text(font, "Speed", sideX, speedY - 10, MUTED);
        float seconds = ticks * (blend ? DyeEntry.BLEND_STEPS : 1) / 20f;
        String pace = String.format(Locale.ROOT, seconds < 1 ? "%.2fs" : "%.1fs", seconds);
        g.text(font, pace, sideX + sideW - font.width(pace), speedY - 10, TEXT);
        int trackY = speedY + 3;
        Shapes.round(g, sideX, trackY - 1, sideW, 4, 2, Theme.SURFACE_HOVER);
        int knob = sideX + Math.round(speedPos() * (sideW - 4));
        Shapes.roundGradient(g, sideX, trackY - 1, knob + 2 - sideX, 4, 2, Theme.GRADIENT_START, Theme.GRADIENT_END);
        boolean overTrack = inSpeed(mouseX, mouseY);
        Shapes.round(g, knob, speedY, 4, 8, 2, overTrack || draggingSpeed ? 0xFFFFFFFF : 0xFFCCCCD6);
        g.text(font, blend ? "per color fade" : "per color", sideX, speedY + 11, MUTED);
        if (overTrack || draggingSpeed) {
            g.requestCursor(CursorTypes.RESIZE_EW);
            // Above the track, so the knob and the text under it stay in view while dragging.
            Tips.show(g, font, List.of(Component.literal(ticks + (ticks == 1 ? " tick" : " ticks") + " per step (Hypixel dyes: 2)")),
                Tips.beside(sideX, speedY - 2, sideX + sideW, speedY + 20, sideX, speedY, Tips.Side.ABOVE, Tips.Side.BELOW), mouseX, mouseY);
        }

        if (armourY + 40 <= stripY - 4) {
            g.text(font, "Preview", sideX, armourY, MUTED);
            int mid = sideX + sideW / 2, ay = armourY + 11;
            // Helmet, chestplate, leggings, boots: pieces 3..0 up from the boots, as Cosmetics numbers them.
            int[][] shape = {{10, 6}, {18, 9}, {14, 8}, {16, 4}};
            for (int p = 0; p < 4; p++) {
                int w = shape[p][0], h = shape[p][1];
                g.fill(mid - w / 2, ay, mid + w / 2, ay + h, 0xFF000000 | preview.rgbAt(tick, 3 - p));
                ay += h + 1;
            }
        }

        // The whole cycle, with a playhead where the boots are now.
        int[] cols = preview.colors;
        int len = cols.length;
        if (len <= width) {
            for (int i = 0; i < len; i++) {
                g.fill(x + i * width / len, stripY, x + (i + 1) * width / len, stripY + STRIP_H, 0xFF000000 | cols[i]);
            }
        } else {
            for (int i = 0; i < width; i++) g.fill(x + i, stripY, x + i + 1, stripY + STRIP_H, 0xFF000000 | cols[i * len / width]);
        }
        g.outline(x - 1, stripY - 1, width + 2, STRIP_H + 2, LINE);
        if (preview.animated()) {
            int hx = x + (preview.indexAt(tick) * width + width / 2) / len;
            g.fill(hx - 1, stripY - 2, hx + 2, stripY + STRIP_H + 2, 0xFF000000);
            g.fill(hx, stripY - 2, hx + 1, stripY + STRIP_H + 2, 0xFFFFFFFF);
        }

        if (hoveredChip >= 0) g.requestCursor(CursorTypes.POINTING_HAND);
        else if (keys.size() < MAX_KEYS && inChip(keys.size(), mouseX, mouseY)) g.requestCursor(CursorTypes.POINTING_HAND);
        // A chip's color and what clicks do, after a rest and beside the editor, never over the other chips.
        if (chipHover.settled(hoveredChip < 0 ? null : hoveredChip)) {
            Tips.show(g, font, List.of(Component.literal("Color " + (hoveredChip + 1) + " · " + ColorPicker.hex(keys.get(hoveredChip))),
                Component.literal(keys.size() > MIN_KEYS ? "Click to edit · Right-click to remove" : "Click to edit")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW)), Tips.beside(x, y, x + width, y + height, x, y), mouseX, mouseY);
        }
    }

    /** One half of a two-way switch, 14 px tall; the chosen half has the accent gradient. Shared with {@link ColorPopup}. */
    static void segment(GuiGraphicsExtractor g, Font font, String label, int sx, int sy, int w, boolean on,
                        int mouseX, int mouseY) {
        boolean over = mouseX >= sx && mouseX < sx + w && mouseY >= sy && mouseY < sy + TOGGLE_H;
        if (on) {
            Shapes.roundGradient(g, sx, sy, w, TOGGLE_H, Theme.SMALL_RADIUS, Theme.GRADIENT_START, Theme.GRADIENT_END);
        } else {
            Shapes.round(g, sx, sy, w, TOGGLE_H, Theme.SMALL_RADIUS, over ? Theme.SURFACE_HOVER : Theme.SURFACE);
            Shapes.frame(g, sx, sy, w, TOGGLE_H, Theme.SMALL_RADIUS, over ? Theme.ACCENT : LINE);
        }
        Ui.centered(g, font, label, sx + w / 2, sy + 3, on ? Theme.ON_ACCENT : over ? Theme.TEXT : MUTED);
        if (over && !on) g.requestCursor(CursorTypes.POINTING_HAND);
    }

    // ----------------------------------------------------------- input ---

    public boolean mouseClicked(double mx, double my, int button) {
        if (picker.mouseClicked(mx, my, button)) return true;
        int chip = chipAt(mx, my);
        if (chip >= 0) {
            boolean remove = button == InputConstants.MOUSE_BUTTON_RIGHT || inRemove(chip, mx, my);
            if (remove && keys.size() > MIN_KEYS) {
                keys.remove(chip);
                if (selected >= keys.size() || selected > chip) selected = Math.max(0, selected - 1);
                picker.setRgb(keys.get(selected));
                edited(true);
            } else if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                selected = chip;
                picker.setRgb(keys.get(chip));
            }
            return true;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return isMouseOver(mx, my);
        if (keys.size() < MAX_KEYS && inChip(keys.size(), mx, my)) {
            // Insert the midpoint of the selected colour and the next, so adding changes nothing until edited.
            int next = keys.get((selected + 1) % keys.size());
            keys.add(selected + 1, DyeEntry.mixOklab(keys.get(selected), next, 0.5f));
            selected++;
            picker.setRgb(keys.get(selected));
            edited(true);
            return true;
        }
        int half = sideW / 2;
        if (my >= toggleY && my < toggleY + TOGGLE_H && mx >= sideX && mx < sideX + sideW) {
            boolean wantBlend = mx >= sideX + half;
            if (wantBlend != blend) {
                blend = wantBlend;
                edited(true);
            }
            return true;
        }
        if (inSpeed(mx, my)) {
            draggingSpeed = true;
            speedTo(mx);
            return true;
        }
        return isMouseOver(mx, my);
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (draggingSpeed) {
            speedTo(mx);
            return true;
        }
        return picker.mouseDragged(mx, my, button);
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingSpeed) {
            draggingSpeed = false;
            onCommit.accept(id);
            return true;
        }
        return picker.mouseReleased(mx, my, button);
    }

    public boolean keyPressed(KeyEvent event) {
        return picker.keyPressed(event);
    }

    public boolean charTyped(CharacterEvent event) {
        return picker.charTyped(event);
    }

    // --------------------------------------------------------- helpers ---

    private void layout() {
        Font font = Minecraft.getInstance().font;
        pickerY = y + CHIP + 8;
        stripY = y + height - STRIP_H;
        sideW = Math.clamp(width * 2 / 5, 70, 110);
        sideX = x + width - sideW;
        picker.setPosition(x, pickerY);
        picker.setSize(Math.max(40, width - sideW - 10), Math.max(40, stripY - 8 - pickerY));
        toggleY = pickerY + font.lineHeight + 1;
        speedY = toggleY + TOGGLE_H + font.lineHeight + 8;
        armourY = speedY + 8 + font.lineHeight + 10;
    }

    private void edited(boolean commit) {
        rebuild();
        onChange.accept(id);
        if (commit) onCommit.accept(id);
    }

    private void rebuild() {
        int[] k = new int[keys.size()];
        for (int i = 0; i < k.length; i++) k[i] = keys.get(i);
        id = DyeEntry.customAnimatedId(ticks, blend, k);
        preview = DyeEntry.customAnimated(id);
    }

    private int chipX(int i) {
        return x + i * (CHIP + CHIP_GAP);
    }

    private boolean inChip(int i, double mx, double my) {
        int cx = chipX(i);
        return mx >= cx && mx < cx + CHIP && my >= y && my < y + CHIP;
    }

    private int chipAt(double mx, double my) {
        for (int i = 0; i < keys.size(); i++) if (inChip(i, mx, my)) return i;
        return -1;
    }

    private boolean inRemove(int i, double mx, double my) {
        int cx = chipX(i);
        return keys.size() > MIN_KEYS && mx >= cx + CHIP - 5 && mx < cx + CHIP && my >= y && my < y + 5;
    }

    private boolean inSpeed(double mx, double my) {
        return mx >= sideX - 2 && mx < sideX + sideW + 2 && my >= speedY - 2 && my < speedY + 10;
    }

    /** 0 = slowest (left) .. 1 = fastest (right). */
    private float speedPos() {
        int i = speedIndex(ticks);
        return 1 - i / (float) (SPEEDS.length - 1);
    }

    private void speedTo(double mx) {
        float pos = (float) Math.clamp((mx - sideX) / Math.max(1, sideW - 4), 0.0, 1.0);
        int i = Math.round((1 - pos) * (SPEEDS.length - 1));
        if (SPEEDS[i] != ticks) {
            ticks = SPEEDS[i];
            edited(false);
        }
    }

    private static int speedIndex(int t) {
        int best = 0;
        for (int i = 1; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - t) < Math.abs(SPEEDS[best] - t)) best = i;
        return best;
    }

    private static int nearestSpeed(int t) {
        return SPEEDS[speedIndex(Math.max(1, t))];
    }

    private static int shiftHue(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        if (d == 0) return max > 0.5f ? 0x303040 : 0xE0E0F0; // grey: pair it with its opposite
        float h;
        if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        return ColorPicker.hsvToRgb(h / 6 + 1 / 6f, d / max, max);
    }
}
