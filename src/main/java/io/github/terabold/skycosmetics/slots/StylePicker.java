package io.github.terabold.skycosmetics.slots;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.hub.Host;
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
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Picks a shape or a fill from small previews of each, painted in the current style: what you click is what menus
 * will show. The previews are one texture, repainted only when the style changes. Arrow keys move the choice.
 */
public final class StylePicker<T> extends AbstractWidget {
    /** The previews of one picker: each cell is the current style with one shape, or one fill, swapped in. */
    enum Sheet {
        SHAPES(Mask.Shape.values().length), FILLS(Mask.Fill.values().length);

        final Mask.Sheet sheet;

        Sheet(int n) {
            sheet = new Mask.Sheet("slots/picker_" + name().toLowerCase(java.util.Locale.ROOT), n, 1);
        }

        /** The style cell {@code i} shows; never fainter than 60%, so every option stays easy to tell apart. */
        Mask.Style style(int i) {
            int op = Math.max(RarityBackgrounds.opacity, 60);
            return this == SHAPES
                ? new Mask.Style(Mask.Shape.values()[i], RarityBackgrounds.fill, op, RarityBackgrounds.outline)
                : new Mask.Style(RarityBackgrounds.shape, Mask.Fill.values()[i], op, RarityBackgrounds.outline);
        }

        Identifier texture() {
            return sheet.ensure(RarityBackgrounds.styleKey(), (img, i, x0, y0, n, s) -> Mask.paint(img, x0, y0, n, s, style(i), 3));
        }
    }

    private static final int BOX = Mask.CELL + 4, GAP = 2;
    private static final int TEXT = 0xFFE8E8EE, ACCENT = 0xFFD58CFF, FRAME = 0xFF34343F, BG = 0xFF26262F;
    /** The color previews are tinted with: Legendary's, as it reads on any shape. */
    private static final Rarity SAMPLE = Rarity.LEGENDARY;

    private final Host host;
    private final T[] values;
    private final Function<T, Component> label;
    private final Supplier<T> get;
    private final Consumer<T> set;
    private final Sheet sheet;
    private final int cols;

    StylePicker(int width, Host host, T[] values, Function<T, Component> label, Supplier<T> get, Consumer<T> set, Sheet sheet) {
        super(0, 0, width, 0, Component.empty());
        this.host = host;
        this.values = values;
        this.label = label;
        this.get = get;
        this.set = set;
        this.sheet = sheet;
        cols = Math.clamp((width + GAP) / (BOX + GAP), 1, values.length);
        int rows = (values.length + cols - 1) / cols;
        height = rows * (BOX + GAP) - GAP;
        setMessage(label.apply(get.get()));
    }

    private int index(T v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    /** The cell under a point, or -1. */
    private int at(double mx, double my) {
        int cx = (int) Math.floor((mx - getX()) / (BOX + GAP)), cy = (int) Math.floor((my - getY()) / (BOX + GAP));
        if (cx < 0 || cy < 0 || cx >= cols) return -1;
        if (mx - getX() - cx * (BOX + GAP) >= BOX || my - getY() - cy * (BOX + GAP) >= BOX) return -1;
        int i = cy * cols + cx;
        return i < values.length ? i : -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        int selected = index(get.get());
        int hovered = active && isHovered() ? at(mouseX, mouseY) : -1;
        Identifier tex = null;
        try {
            tex = sheet.texture();
        } catch (RuntimeException e) {
            Io.failed("Painting the style previews", e);
        }
        int tint = active ? 0xFF000000 | RarityBackgrounds.color(SAMPLE) : 0xFF707078;
        int tw = sheet.sheet.texWidth(), th = sheet.sheet.texHeight();
        for (int i = 0; i < values.length; i++) {
            int x = getX() + i % cols * (BOX + GAP), y = getY() + i / cols * (BOX + GAP);
            g.fill(x, y, x + BOX, y + BOX, BG);
            SlotPreview.slot(g, x + 2, y + 2);
            if (tex != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, tex, x + 2, y + 2, i * Mask.CELL, 0, Mask.CELL, Mask.CELL, tw, th, tint);
            }
            int frame = i == selected ? (active ? ACCENT : 0xFF8C8C9A) : i == hovered ? 0xFFFFFFFF : FRAME;
            g.outline(x, y, BOX, BOX, frame);
            if (i == selected) g.outline(x + 1, y + 1, BOX - 2, BOX - 2, frame);
        }
        // The name of the choice, right of the cells when it fits; the tooltip has it otherwise.
        int right = getX() + cols * (BOX + GAP) + 4;
        Component name = label.apply(values[selected]);
        if (values.length <= cols && right + font.width(name) <= getRight()) {
            g.text(font, name, right, getY() + (BOX - 8) / 2, active ? TEXT : 0xFF8C8C9A);
        }
        if (hovered >= 0) {
            g.requestCursor(CursorTypes.POINTING_HAND);
            g.setTooltipForNextFrame(font, label.apply(values[hovered]), mouseX, mouseY);
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int i = at(event.x(), event.y());
        if (i >= 0) pick(values[i]);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active) return false;
        int step = event.key() == GLFW.GLFW_KEY_LEFT ? -1 : event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
        if (step == 0) return false;
        pick(values[Math.floorMod(index(get.get()) + step, values.length)]);
        return true;
    }

    private void pick(T v) {
        if (v == get.get()) return;
        set.accept(v);
        setMessage(label.apply(v));
        host.changed();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, label.apply(get.get()));
    }

    /** The value picked now. */
    public T selected() {
        return get.get();
    }

    /** For tests: the screen point at the middle of a value's cell. */
    public int[] centerOf(T v) {
        int i = index(v);
        return new int[]{getX() + i % cols * (BOX + GAP) + BOX / 2, getY() + i / cols * (BOX + GAP) + BOX / 2};
    }
}
