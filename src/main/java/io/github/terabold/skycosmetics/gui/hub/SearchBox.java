package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A search field, in the settings and the studio: vanilla's text box (typing, selection, copy and paste, narration)
 * drawn as a rounded field with a magnifier, and an x that clears it once something is typed. The text sits inset in
 * the field, so clicks are moved by the same inset before the text box places its cursor.
 */
public final class SearchBox extends EditBox {
    public static final int H = 16;
    private static final int TEXT_X = 17, CLEAR = 12;
    private final Anim hover = new Anim(90, 0);
    private final Anim focus = new Anim(120, 0);

    /** The settings' search: named "Search settings". */
    public SearchBox(Font font, int width, Component hint) {
        this(font, width, H, Component.translatable("skycosmetics.menu.search"), hint);
    }

    /** @param name what the field is called (narration, tests); the hint is the gray text while it is empty */
    public SearchBox(Font font, int width, int height, Component name, Component hint) {
        super(font, 0, 0, width, height, name);
        setBordered(false);
        setMaxLength(64);
        setTextColor(Theme.TEXT);
        setTextShadow(false);
        setHint(hint.copy().withColor(Theme.DIM & 0xFFFFFF));
    }

    /** The text area: the field minus the magnifier and the clear button. */
    @Override
    public int getInnerWidth() {
        return Math.max(1, getWidth() - TEXT_X - CLEAR - 2);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float hv = hover.to(isHovered() ? 1 : 0), f = focus.to(isFocused() ? 1 : 0);
        int r = Math.min(Theme.SMALL_RADIUS + 1, h / 2);
        Shapes.round(g, x, y, w, h, r, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hv * (1 - f)));
        Shapes.frame(g, x, y, w, h, r, Theme.mix(Theme.mix(Theme.LINE, Theme.MUTED, hv * 0.5f), Theme.ACCENT, f));
        Ui.magnifier(g, x + 5, y + (h - 9) / 2, Theme.mix(Theme.MUTED, Theme.ACCENT, f));
        g.pose().pushMatrix();
        g.pose().translate(TEXT_X, (h - 8) / 2f);
        super.extractWidgetRenderState(g, mouseX - TEXT_X, mouseY - (h - 8) / 2, delta);
        g.pose().popMatrix();
        if (!getValue().isEmpty()) {
            boolean over = overClear(mouseX, mouseY);
            int cx = clearX(), cy = y + (h - CLEAR) / 2;
            if (over) Shapes.round(g, cx, cy, CLEAR, CLEAR, 3, Theme.SURFACE_HOVER);
            Ui.cross(g, cx + 3, cy + 3, CLEAR - 6, over ? Theme.TEXT : Theme.MUTED);
        }
    }

    private int clearX() {
        return getX() + getWidth() - CLEAR - 2;
    }

    private boolean overClear(double mx, double my) {
        int cy = getY() + (getHeight() - CLEAR) / 2;
        return !getValue().isEmpty() && mx >= clearX() && mx < clearX() + CLEAR && my >= cy && my < cy + CLEAR;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (overClear(event.x(), event.y())) {
            setValue("");
            return;
        }
        super.onClick(shifted(event), doubleClick);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        super.onDrag(shifted(event), dx, dy);
    }

    private MouseButtonEvent shifted(MouseButtonEvent e) {
        return new MouseButtonEvent(e.x() - TEXT_X, e.y(), e.buttonInfo());
    }
}
