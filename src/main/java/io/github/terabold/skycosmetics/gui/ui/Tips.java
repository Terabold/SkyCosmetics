package io.github.terabold.skycosmetics.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.joml.Vector2i;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Tooltips that never hide what you are about to click. A row, a card or a button shows its tooltip only after
 * the mouse rests on it a moment ({@link #DELAY_MS}), and beside the list or column it belongs to (right of it,
 * else left, above or below), level with what is hovered: never over the next rows or the buttons around it.
 * An (i) that exists for its tooltip shows it at once, also beside its column.
 *
 * Widgets keep a vanilla tooltip for narration, but vanilla never draws it; the screen draws it through
 * {@link #widgets} instead.
 */
public final class Tips {
    /** How long the mouse rests on a row, card or button before its tooltip shows. */
    public static final long DELAY_MS = 400;
    /** Gap between a tooltip's text and the area it keeps clear (its frame takes 4 of it), and the screen edge. */
    private static final int GAP = 7, EDGE = 5;
    private static final Duration NEVER = Duration.ofDays(365);

    /** Where a tooltip may go around the area it keeps clear, in order of preference. */
    public enum Side { RIGHT, LEFT, ABOVE, BELOW }

    private static final Side[] BESIDE = {Side.RIGHT, Side.LEFT, Side.ABOVE, Side.BELOW};

    private record Tip(Tooltip tooltip, long delay) {}

    private static final Map<AbstractWidget, Tip> WIDGETS = new WeakHashMap<>();
    private static final Hover WIDGET_HOVER = new Hover();

    private Tips() {}

    /** What one list has under the mouse, and since when. */
    public static final class Hover {
        private Object key;
        private long since;

        /** Whether {@code key} (null: nothing) has been hovered {@link #DELAY_MS} without a change. */
        public boolean settled(Object key) {
            return settled(key, DELAY_MS);
        }

        public boolean settled(Object key, long delay) {
            long now = Util.getMillis();
            if (!Objects.equals(key, this.key)) {
                this.key = key;
                since = now;
            }
            return key != null && now - since >= delay;
        }
    }

    /**
     * Places a tooltip beside the area x0..x1, y0..y1: on the first side of {@code order} it fits on whole, level
     * with the hovered thing at (ax, ay); where none fits, on the side with the most room, kept on screen.
     */
    public static ClientTooltipPositioner beside(int x0, int y0, int x1, int y1, int ax, int ay, Side... order) {
        Side[] sides = order.length == 0 ? BESIDE : order;
        return (sw, sh, mx, my, tw, th) -> {
            Side best = null;
            int most = Integer.MIN_VALUE;
            for (Side s : sides) {
                int room = switch (s) {
                    case RIGHT -> sw - EDGE - (x1 + GAP) - tw;
                    case LEFT -> x0 - GAP - EDGE - tw;
                    case ABOVE -> y0 - GAP - EDGE - th;
                    case BELOW -> sh - EDGE - (y1 + GAP) - th;
                };
                if (room >= 0) {
                    best = s;
                    break;
                }
                if (room > most) {
                    most = room;
                    best = s;
                }
            }
            int x, y;
            switch (best) {
                case RIGHT -> {
                    x = x1 + GAP;
                    y = ay;
                }
                case LEFT -> {
                    x = x0 - GAP - tw;
                    y = ay;
                }
                case ABOVE -> {
                    x = ax;
                    y = y0 - GAP - th;
                }
                default -> {
                    x = ax;
                    y = y1 + GAP;
                }
            }
            return new Vector2i(Math.clamp(x, EDGE, Math.max(EDGE, sw - EDGE - tw)), Math.clamp(y, EDGE, Math.max(EDGE, sh - EDGE - th)));
        };
    }

    /** Shows {@code lines} this frame where {@code at} puts them. */
    public static void show(GuiGraphicsExtractor g, Font font, List<Component> lines, ClientTooltipPositioner at, int mx, int my) {
        if (lines == null || lines.isEmpty()) return;
        List<FormattedCharSequence> seq = new ArrayList<>(lines.size());
        for (Component c : lines) seq.add(c.getVisualOrderText());
        g.setTooltipForNextFrame(font, seq, Optional.empty(), at, mx, my, false, null);
    }

    /** {@link #show}, once {@code hover} has rested on {@code key}; beside the area, level with the hovered row. */
    public static void list(GuiGraphicsExtractor g, Font font, Hover hover, Object key, List<Component> lines,
                            int x0, int y0, int x1, int y1, int ax, int ay, int mx, int my) {
        if (hover.settled(lines == null || lines.isEmpty() ? null : key)) show(g, font, lines, beside(x0, y0, x1, y1, ax, ay), mx, my);
    }

    /** A widget's tooltip, drawn by {@link #widgets} after {@link #DELAY_MS}; null takes it off. */
    public static void set(AbstractWidget w, Component tip) {
        set(w, tip, DELAY_MS);
    }

    /** The same with its own delay: 0 for a button that is there for its tooltip. */
    public static void set(AbstractWidget w, Component tip, long delayMs) {
        Tooltip t = tip == null || tip.getString().isEmpty() ? null : Tooltip.create(tip);
        w.setTooltip(t); // narration reads it; vanilla never shows it
        w.setTooltipDelay(NEVER);
        if (t == null) WIDGETS.remove(w);
        else WIDGETS.put(w, new Tip(t, delayMs));
    }

    /**
     * Draws the tooltip of the widget under the mouse (or with the keyboard's focus) among {@code children}, once
     * it rested its delay, where {@code place(widget)} puts it: usually {@link #beside} the widget's column.
     */
    public static void widgets(GuiGraphicsExtractor g, Font font, List<? extends GuiEventListener> children, int mx, int my,
                               Function<AbstractWidget, ClientTooltipPositioner> place) {
        AbstractWidget hovered = null;
        Tip tip = null;
        boolean keys = Minecraft.getInstance().getLastInputType().isKeyboard();
        for (GuiEventListener c : children) {
            if (!(c instanceof AbstractWidget w) || !w.visible) continue;
            Tip t = WIDGETS.get(w);
            if (t == null) continue;
            // Hovered when last drawn and under the mouse now: a control scrolled out of view keeps neither.
            boolean over = w.isHovered() && mx >= w.getX() && mx < w.getRight() && my >= w.getY() && my < w.getBottom();
            if (over || keys && w.isFocused() && hovered == null) {
                hovered = w;
                tip = t;
                if (over) break;
            }
        }
        if (!WIDGET_HOVER.settled(hovered, tip == null ? DELAY_MS : tip.delay)) return;
        g.setTooltipForNextFrame(font, tip.tooltip.toCharSequence(Minecraft.getInstance()), tip.tooltip.component(),
            place.apply(hovered), mx, my, false, tip.tooltip.style());
    }
}
