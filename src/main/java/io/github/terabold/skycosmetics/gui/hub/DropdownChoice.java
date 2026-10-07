package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * A {@link Control.Choice} with many or long values: a field showing the chosen value that opens a list under
 * it (above it near the bottom of the window). The list scrolls past eight values, follows the arrow keys and
 * Enter, and closes on Esc or a click outside. The field's message is the row's title.
 */
public final class DropdownChoice<T> extends ThemedButton {
    public static final int H = 16;
    private final Control.Choice<T> choice;
    private final Host host;
    private String shown = "";
    private Menu open;

    public DropdownChoice(Component title, int width, Control.Choice<T> choice, Host host) {
        super(width, H, title, b -> ((DropdownChoice<?>) b).toggle());
        this.choice = choice;
        this.host = host;
        refresh();
    }

    public void refresh() {
        shown = Ui.clip(Minecraft.getInstance().font, choice.label().apply(choice.get().get()).getString(), getWidth() - 18);
    }

    /** The open list, or null. */
    public Overlay list() {
        return open != null && !open.isClosed() ? open : null;
    }

    /** The middle of the open list's entry for this value, for tests aiming a real click; null when not shown. */
    public int[] entryCenter(int index) {
        Menu m = open != null && !open.isClosed() ? open : null;
        if (m == null || index < m.top || index >= Math.min(m.labels.length, m.top + m.show)) return null;
        return new int[]{m.x + m.w / 2, m.y + 2 + (index - m.top) * Menu.ITEM + Menu.ITEM / 2};
    }

    private void toggle() {
        if (list() != null) {
            open.close();
            return;
        }
        if (!(host instanceof OverlayHost overlays)) return;
        open = new Menu();
        overlays.openOverlay(open);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float a = active ? 1 : 0.4f;
        boolean isOpen = list() != null;
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(isOpen ? Theme.ACCENT : Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
        g.text(font, shown, x + 6, y + (h - 8) / 2, Theme.fade(Theme.TEXT, a), false);
        Ui.chevron(g, x + w - 11, y + h / 2 - 1, isOpen, Theme.fade(isOpen ? Theme.ACCENT : Theme.MUTED, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(CommonComponents.optionNameValue(getMessage(), choice.label().apply(choice.get().get())));
    }

    /** The list under the field. */
    private final class Menu implements Overlay {
        private static final int ITEM = 14, SHOW = 8;
        private final FormattedCharSequence[] labels;
        private final Anim appear = new Anim(110, 0);
        private int x, y, w, h, top, cursor, show;
        private boolean closed, above;

        Menu() {
            Font font = Minecraft.getInstance().font;
            int n = choice.values().size();
            labels = new FormattedCharSequence[n];
            int widest = 0;
            for (int i = 0; i < n; i++) {
                Component c = choice.label().apply(choice.values().get(i));
                labels[i] = c.getVisualOrderText();
                widest = Math.max(widest, font.width(labels[i]));
            }
            w = Math.max(getWidth(), widest + 20);
            cursor = Math.max(0, choice.values().indexOf(choice.get().get()));
            size(Math.min(n, SHOW));
        }

        /** Shows {@code rows} values at a time, the chosen one among them. */
        private void size(int rows) {
            int n = labels.length;
            show = Math.max(1, rows);
            h = Math.min(n, show) * ITEM + 4;
            top = Math.clamp(cursor - show / 2, 0, Math.max(0, n - show));
        }

        /** Under the field, else above it, else on the roomier side with fewer values at a time (three at least). */
        @Override
        public void fit(int bx, int by, int bw, int bh) {
            int n = labels.length, need = Math.min(n, SHOW) * ITEM + 4;
            int roomBelow = by + bh - 2 - (getBottom() + 2), roomAbove = getY() - 2 - (by + 2);
            above = need > roomBelow && roomAbove > roomBelow;
            int room = above ? roomAbove : roomBelow;
            size(Math.clamp((room - 4) / ITEM, Math.min(n, 3), Math.min(n, SHOW)));
            x = Math.clamp(getX() + getWidth() - w, bx, Math.max(bx, bx + bw - w));
            y = above ? getY() - 2 - h : getBottom() + 2;
        }

        @Override
        public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            Font font = Minecraft.getInstance().font;
            g.nextStratum();
            float t = appear.to(1);
            int shownH = Math.max(ITEM, Math.round(h * (0.6f + 0.4f * t)));
            int sy = above ? y + h - shownH : y;
            Shapes.shadow(g, x, sy, w, shownH, Theme.SMALL_RADIUS, 4);
            Shapes.round(g, x, sy, w, shownH, Theme.SMALL_RADIUS, Theme.BODY);
            Shapes.frame(g, x, sy, w, shownH, Theme.SMALL_RADIUS, Theme.LINE);
            g.enableScissor(x, sy + 1, x + w, sy + shownH - 1);
            int sel = choice.values().indexOf(choice.get().get());
            int hovered = indexAt(mouseX, mouseY);
            int n = labels.length;
            for (int i = top; i < Math.min(n, top + show); i++) {
                int iy = y + 2 + (i - top) * ITEM;
                if (i == hovered || (hovered < 0 && i == cursor)) Shapes.round(g, x + 2, iy, w - 4, ITEM, 2, Theme.SURFACE_HOVER);
                if (i == sel) Shapes.round(g, x + 3, iy + 3, 2, ITEM - 6, 1, Theme.ACCENT);
                g.text(font, labels[i], x + 9, iy + 3, Theme.fade(i == sel ? 0xFFFFFFFF : Theme.TEXT, t), false);
            }
            if (n > show) {
                int track = h - 6, thumb = Math.max(8, track * show / n);
                int ty = y + 3 + (track - thumb) * top / (n - show);
                Shapes.round(g, x + w - 4, ty, 2, thumb, 1, Theme.MUTED);
            }
            g.disableScissor();
        }

        private int indexAt(double mx, double my) {
            if (mx < x || mx >= x + w || my < y + 2 || my >= y + h - 2) return -1;
            int i = top + (int) ((my - y - 2) / ITEM);
            return i < labels.length ? i : -1;
        }

        @Override
        public void mouseClicked(MouseButtonEvent event) {
            int i = indexAt(event.x(), event.y());
            if (i >= 0) pick(i);
            else if (!(event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + h)) close();
        }

        @Override
        public void mouseScrolled(double mouseX, double mouseY, double amount) {
            top = Math.clamp(top - (int) Math.signum(amount), 0, Math.max(0, labels.length - show));
        }

        @Override
        public void keyPressed(KeyEvent event) {
            int k = event.key();
            if (event.isEscape()) {
                close();
            } else if (event.isUp() || event.isDown()) {
                cursor = Math.clamp(cursor + (event.isUp() ? -1 : 1), 0, labels.length - 1);
                if (cursor < top) top = cursor;
                if (cursor >= top + show) top = cursor - show + 1;
            } else if (event.isSelection() || k == GLFW.GLFW_KEY_KP_ENTER) {
                pick(cursor);
            }
        }

        private void pick(int i) {
            if (!choice.values().get(i).equals(choice.get().get())) {
                choice.set().accept(choice.values().get(i));
                host.changed();
            }
            close();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
