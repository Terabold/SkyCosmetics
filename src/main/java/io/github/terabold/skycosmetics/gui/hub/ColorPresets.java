package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Named colors as pills (a dot and the name), wrapped to the row's width: one click picks one. The one in use is
 * framed in the accent; under the mouse a pill's frame takes its own color, a preview of the pick. Left and Right
 * step through them when focused. For the settings' {@code Control.Custom} rows.
 */
public final class ColorPresets extends AbstractWidget {
    /** A preset: its name and color (opaque RGB). */
    public record Preset(Component name, int rgb) {}

    private static final int H = 16, GAP = 4, DOT = 8;
    private final List<Preset> presets;
    private final IntSupplier current;
    private final IntConsumer pick;
    private final Host host;
    private final int[] px, py, pw;
    private final String[] names;
    private final Anim[] hover;

    /**
     * @param title   the row's title, for narration
     * @param current the color in use, compared by RGB
     * @param pick    applies a preset; the host then saves and refreshes the other rows
     */
    public ColorPresets(Component title, int width, List<Preset> presets, IntSupplier current, IntConsumer pick, Host host) {
        super(0, 0, width, H, title);
        this.presets = List.copyOf(presets);
        this.current = current;
        this.pick = pick;
        this.host = host;
        Font font = Minecraft.getInstance().font;
        int n = presets.size();
        px = new int[n];
        py = new int[n];
        pw = new int[n];
        names = new String[n];
        hover = new Anim[n];
        int x = 0, y = 0;
        for (int i = 0; i < n; i++) {
            names[i] = presets.get(i).name().getString();
            pw[i] = Math.min(width, 6 + DOT + 5 + font.width(names[i]) + 8);
            if (x > 0 && x + pw[i] > width) {
                x = 0;
                y += H + GAP;
            }
            px[i] = x;
            py[i] = y;
            x += pw[i] + GAP;
            hover[i] = new Anim(90, 0);
        }
        setHeight(y + H);
    }

    private int selected() {
        int c = current.getAsInt() & 0xFFFFFF;
        for (int i = 0; i < presets.size(); i++) if ((presets.get(i).rgb() & 0xFFFFFF) == c) return i;
        return -1;
    }

    private int at(double mx, double my) {
        for (int i = 0; i < presets.size(); i++) {
            int x = getX() + px[i], y = getY() + py[i];
            if (mx >= x && mx < x + pw[i] && my >= y && my < y + H) return i;
        }
        return -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        float a = active ? 1 : 0.4f;
        int sel = selected(), over = active && isHovered() ? at(mouseX, mouseY) : -1;
        if (over >= 0) g.requestCursor(CursorTypes.POINTING_HAND);
        for (int i = 0; i < presets.size(); i++) {
            int x = getX() + px[i], y = getY() + py[i], w = pw[i], rgb = 0xFF000000 | presets.get(i).rgb();
            float hv = hover[i].to(i == over ? 1 : 0);
            boolean on = i == sel;
            Shapes.round(g, x, y, w, H, H / 2, Theme.fade(on ? Theme.ACCENT_BG : Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hv), a));
            Shapes.frame(g, x, y, w, H, H / 2, Theme.fade(on ? Theme.ACCENT : Theme.mix(Theme.LINE, rgb, hv), a));
            Shapes.circle(g, x + 5, y + (H - DOT) / 2, DOT, Theme.fade(rgb, a));
            int text = on ? 0xFFFFFFFF : Theme.mix(Theme.MUTED, Theme.TEXT, hv);
            g.text(font, names[i], x + 5 + DOT + 5, y + (H - 7) / 2, Theme.fade(text, a), false);
        }
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), Theme.SMALL_RADIUS);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        choose(at(event.x(), event.y()));
    }

    /** A click between two pills is no click at all. */
    @Override
    public boolean isMouseOver(double mx, double my) {
        return super.isMouseOver(mx, my) && at(mx, my) >= 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) return false;
        int sel = selected();
        int next = sel < 0 ? 0 : Math.clamp(sel + (event.isLeft() ? -1 : 1), 0, presets.size() - 1);
        choose(next);
        return true;
    }

    private void choose(int i) {
        if (i < 0 || i == selected()) return;
        pick.accept(presets.get(i).rgb());
        host.changed();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        int sel = selected();
        out.add(NarratedElementType.TITLE, sel < 0 ? getMessage()
            : CommonComponents.optionNameValue(getMessage(), presets.get(sel).name()));
    }

    /** The middle of the pill with this name, for tests aiming a real click; null without one. */
    public int[] centerOf(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return new int[]{getX() + px[i] + pw[i] / 2, getY() + py[i] + H / 2};
        }
        return null;
    }
}
