package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Choices you pick by their look: a row of tiles, each a slot with the choice drawn in it, wrapped to the row's
 * width. The one in use is framed in the accent; the name shows when the mouse rests on a tile. Left and Right step
 * through them when focused. For the settings' {@code Control.Custom} rows.
 */
public final class PreviewPicker<T> extends AbstractWidget {
    /** Draws {@code value} into the 16x16 slot at {@code x, y}. */
    public interface Painter<T> {
        void paint(GuiGraphicsExtractor g, T value, int x, int y);
    }

    private static final int TILE = 24, GAP = 4;
    private final List<T> values;
    private final Function<T, Component> name;
    private final Supplier<T> current;
    private final Consumer<T> pick;
    private final Painter<T> painter;
    private final Host host;
    private final int perRow;
    private final Anim[] hover;
    private int tipFor = -1;

    public PreviewPicker(Component title, int width, List<T> values, Function<T, Component> name, Supplier<T> current,
                         Consumer<T> pick, Painter<T> painter, Host host) {
        super(0, 0, width, TILE, title);
        this.values = List.copyOf(values);
        this.name = name;
        this.current = current;
        this.pick = pick;
        this.painter = painter;
        this.host = host;
        perRow = Math.max(1, (width + GAP) / (TILE + GAP));
        hover = new Anim[values.size()];
        for (int i = 0; i < hover.length; i++) hover[i] = new Anim(90, 0);
        setHeight(((values.size() + perRow - 1) / perRow) * (TILE + GAP) - GAP);
    }

    private int tileX(int i) { return getX() + i % perRow * (TILE + GAP); }

    private int tileY(int i) { return getY() + i / perRow * (TILE + GAP); }

    private int at(double mx, double my) {
        for (int i = 0; i < values.size(); i++) {
            if (mx >= tileX(i) && mx < tileX(i) + TILE && my >= tileY(i) && my < tileY(i) + TILE) return i;
        }
        return -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        float a = active ? 1 : 0.4f;
        T now = current.get();
        int over = active && isHovered() ? at(mouseX, mouseY) : -1;
        if (over >= 0) g.requestCursor(CursorTypes.POINTING_HAND);
        if (over != tipFor) {
            tipFor = over;
            Tips.set(this, over < 0 ? null : name.apply(values.get(over)), 150);
        }
        for (int i = 0; i < values.size(); i++) {
            int x = tileX(i), y = tileY(i);
            float hv = hover[i].to(i == over ? 1 : 0);
            boolean on = values.get(i).equals(now);
            Shapes.round(g, x, y, TILE, TILE, Theme.SMALL_RADIUS, Theme.fade(on ? Theme.ACCENT_BG : Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hv), a));
            Shapes.frame(g, x, y, TILE, TILE, Theme.SMALL_RADIUS, Theme.fade(on ? Theme.ACCENT : Theme.mix(Theme.LINE, Theme.ACCENT, hv), a));
            // A slot as the inventory draws it, so the mark is seen where it will be.
            int sx = x + (TILE - 18) / 2, sy = y + (TILE - 18) / 2;
            g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
            g.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
            g.fill(sx, sy, sx + 1, sy + 17, 0xFF373737);
            g.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
            g.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
            painter.paint(g, values.get(i), sx + 1, sy + 1);
        }
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), Theme.SMALL_RADIUS);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        choose(at(event.x(), event.y()));
    }

    /** A click between two tiles is no click at all. */
    @Override
    public boolean isMouseOver(double mx, double my) {
        return super.isMouseOver(mx, my) && at(mx, my) >= 0;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) return false;
        int sel = values.indexOf(current.get());
        choose(sel < 0 ? 0 : Math.clamp(sel + (event.isLeft() ? -1 : 1), 0, values.size() - 1));
        return true;
    }

    private void choose(int i) {
        if (i < 0 || values.get(i).equals(current.get())) return;
        pick.accept(values.get(i));
        host.changed();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        int sel = values.indexOf(current.get());
        out.add(NarratedElementType.TITLE, sel < 0 ? getMessage() : CommonComponents.optionNameValue(getMessage(), name.apply(values.get(sel))));
    }
}
