package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A small window over the studio for picking a colour: a title with a close x,
 * an optional "Single color | Animated" switch, and a {@link ColorPicker} or
 * {@link AnimatedDyeEditor} under it. Used for custom dyes, and meant for the
 * name and glint colours too.
 *
 * Every edit goes to the change callback at once as a dye-style id ("#RRGGBB",
 * or an {@code anim:} id in animated mode), so the host can show it live and
 * save once the mouse rests. While it is open the host sends it every mouse and
 * key event first; it takes them all, so nothing under it reacts. x, Esc or a
 * click outside closes it. When the tab or the item changes the host simply
 * drops it: no callback fires, so nothing lands on the wrong item.
 */
public class ColorPopup {
    private static final int PAD = 6;
    private static final int TITLE_H = 12;
    private static final int TOGGLE_H = 14;
    private static final int CLOSE = 12;
    private static final int SINGLE_W = 230, SINGLE_H = 120;
    private static final int ANIM_W = 320, ANIM_H = 150;

    private final Anim appear = new Anim(110, 0);

    private final Component title;
    private final Consumer<String> onChange;
    private Consumer<String> onCommit = s -> {};
    private Runnable onClose = () -> {};
    private final ColorPicker picker;
    /** Null when the pop-up offers a single colour only. */
    private final AnimatedDyeEditor editor;
    private boolean animated, closed;
    /** Which side the user changed, so switching sides carries a fresh colour over but never overwrites work. */
    private boolean pickerEdited, editorEdited;

    private int x, y, w, h, toggleY, bodyY;

    /**
     * @param initial  the colour to start from: "#RRGGBB", an {@code anim:} id or any catalogue dye id; may be null
     * @param animated offer the Animated side (custom dyes); a single colour only otherwise
     * @param onChange gets "#RRGGBB" or an {@code anim:} id on every change, many times per second while dragging
     */
    public ColorPopup(Component title, String initial, boolean animated, Consumer<String> onChange) {
        this.title = title;
        this.onChange = onChange == null ? s -> {} : onChange;
        DyeEntry start = Repo.get().dye(initial);
        picker = new ColorPicker(0, 0, SINGLE_W, SINGLE_H);
        if (start != null) picker.setRgb(start.colors[0]);
        picker.setOnChange(rgb -> {
            pickerEdited = true;
            this.onChange.accept(ColorPicker.hex(rgb));
        });
        picker.setOnCommit(rgb -> onCommit.accept(ColorPicker.hex(rgb)));
        if (animated) {
            editor = new AnimatedDyeEditor(0, 0, ANIM_W, ANIM_H);
            editor.load(initial);
            editor.setOnChange(id -> {
                editorEdited = true;
                this.onChange.accept(id);
            });
            editor.setOnCommit(id -> onCommit.accept(id));
            this.animated = DyeEntry.isCustomAnimated(initial) || start != null && start.animated();
        } else {
            editor = null;
        }
        place(0, 0, ANIM_W, ANIM_H + 60);
    }

    /** Called once a colour is settled (mouse released, Enter in the hex box, mode switched). */
    public ColorPopup onCommit(Consumer<String> onCommit) {
        this.onCommit = onCommit == null ? s -> {} : onCommit;
        return this;
    }

    /** Called when the user closes it (x, Esc, click outside), after any typed hex value was applied. */
    public ColorPopup onClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {} : onClose;
        return this;
    }

    /** Centres the pop-up in an area: as big as its editor likes, never bigger than the area. */
    public void place(int areaX, int areaY, int areaW, int areaH) {
        w = Math.min(areaW, editor != null ? ANIM_W : SINGLE_W);
        int top = PAD + TITLE_H + 4 + (editor != null ? TOGGLE_H + 6 : 0);
        int body = Math.max(60, Math.min(editor != null ? ANIM_H : SINGLE_H, areaH - top - PAD));
        h = top + body + PAD;
        x = areaX + (areaW - w) / 2;
        y = areaY + Math.max(0, (areaH - h) / 2);
        toggleY = y + PAD + TITLE_H + 4;
        bodyY = y + top;
        int bw = w - 2 * PAD;
        int pw = Math.min(bw, SINGLE_W - 2 * PAD);
        picker.setSize(pw, body);
        picker.setPosition(x + PAD + (bw - pw) / 2, bodyY);
        if (editor != null) {
            editor.setSize(bw, body);
            editor.setPosition(x + PAD, bodyY);
        }
    }

    public int x() { return x; }

    public int y() { return y; }

    public int width() { return w; }

    public int height() { return h; }

    public boolean isAnimated() {
        return animated;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The colour shown now: "#RRGGBB", or an {@code anim:} id on the Animated side. */
    public String value() {
        return animated ? editor.dyeId() : ColorPicker.hex(picker.rgb());
    }

    /** The single-colour editor, for tests aiming a real click. */
    public ColorPicker picker() {
        return picker;
    }

    /** Centre of the Single colour (false) or Animated (true) switch half, for tests aiming a real click. */
    public int[] toggleAt(boolean animatedSide) {
        int half = (w - 2 * PAD) / 2;
        return new int[]{x + PAD + (animatedSide ? half + half / 2 : half / 2), toggleY + TOGGLE_H / 2};
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------- render ---

    /** Draws in a layer of its own, so the text and items of the screen under it never show through. */
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        g.nextStratum();
        float t = Anim.easeOut(appear.to(1));
        Shapes.shadow(g, x, y, w, h, Theme.RADIUS, 6);
        Shapes.round(g, x, y, w, h, Theme.RADIUS, Theme.BODY);
        Shapes.frame(g, x, y, w, h, Theme.RADIUS, Theme.mix(Theme.LINE, Theme.ACCENT, 0.5f * t));
        g.text(font, title, x + PAD, y + PAD + 2, Theme.TEXT);

        int cx = x + w - PAD - CLOSE, cy = y + PAD;
        boolean overClose = inClose(mouseX, mouseY);
        if (overClose) Shapes.round(g, cx, cy, CLOSE, CLOSE, Theme.SMALL_RADIUS, 0xFF8A2E4A);
        Ui.cross(g, cx + 3, cy + 3, CLOSE - 6, overClose ? 0xFFFFFFFF : Theme.MUTED);
        if (overClose) {
            g.requestCursor(CursorTypes.POINTING_HAND);
            g.setTooltipForNextFrame(font, Component.literal("Close (Esc)"), mouseX, mouseY);
        }

        if (editor != null) {
            int half = (w - 2 * PAD) / 2;
            AnimatedDyeEditor.segment(g, font, "Single Color", x + PAD, toggleY, half - 1, !animated, mouseX, mouseY);
            AnimatedDyeEditor.segment(g, font, "Animated", x + PAD + half + 1, toggleY, w - 2 * PAD - half - 1, animated,
                mouseX, mouseY);
        }
        if (animated) editor.render(g, mouseX, mouseY);
        else picker.render(g, mouseX, mouseY);
    }

    // ----------------------------------------------------------- input ---

    /** Takes every click: outside closes, inside goes to the switch or the editor. */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my) || inClose(mx, my)) {
            close();
            return true;
        }
        if (editor != null && my >= toggleY && my < toggleY + TOGGLE_H && mx >= x + PAD && mx < x + w - PAD) {
            boolean want = mx >= x + PAD + (w - 2 * PAD) / 2;
            if (want != animated && button == InputConstants.MOUSE_BUTTON_LEFT) switchTo(want);
            return true;
        }
        if (animated) editor.mouseClicked(mx, my, button);
        else picker.mouseClicked(mx, my, button);
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (animated) editor.mouseDragged(mx, my, button);
        else picker.mouseDragged(mx, my, button);
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (animated) editor.mouseReleased(mx, my, button);
        else picker.mouseReleased(mx, my, button);
        return true;
    }

    /** Keys go to the hex box while it is being typed in; otherwise Esc closes and the rest are swallowed. */
    public boolean keyPressed(KeyEvent event) {
        boolean editing = animated ? editor.isEditing() : picker.isEditing();
        if (editing) {
            if (animated) editor.keyPressed(event);
            else picker.keyPressed(event);
        } else if (event.key() == InputConstants.KEY_ESCAPE) {
            close();
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (animated) editor.charTyped(event);
        else picker.charTyped(event);
        return true;
    }

    /** Closes as the user asked: a half-typed hex value is applied first, then the host is told. */
    public void close() {
        if (closed) return;
        stopEditing();
        closed = true;
        onClose.run();
    }

    // --------------------------------------------------------- helpers ---

    private void switchTo(boolean on) {
        stopEditing();
        if (on && pickerEdited && !editorEdited) editor.load(ColorPicker.hex(picker.rgb()));
        if (!on && editorEdited && !pickerEdited) picker.setRgb(editor.dye().colors[0]);
        animated = on;
        onChange.accept(value());
        onCommit.accept(value());
    }

    private void stopEditing() {
        picker.stopEditing();
        if (editor != null) editor.stopEditing();
    }

    private boolean inClose(double mx, double my) {
        int cx = x + w - PAD - CLOSE, cy = y + PAD;
        return mx >= cx && mx < cx + CLOSE && my >= cy && my < cy + CLOSE;
    }
}
