package io.github.terabold.skycosmetics.hand;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A few controls side by side in one settings row: Edit and Remove, a name box and Save, Copy and Paste. The
 * settings list holds one widget per row, so this one lays its parts out and passes clicks, drags and typing to
 * the part under the mouse (or the box being typed in).
 */
public final class ButtonRow extends AbstractWidget {
    private static final int GAP = 4;

    private final List<AbstractWidget> parts = new ArrayList<>();
    /** Fixed width per part; 0 shares what the fixed ones leave. */
    private final List<Integer> widths = new ArrayList<>();
    private final List<BooleanSupplier> on = new ArrayList<>();
    private AbstractWidget pressed, typing;

    ButtonRow(int width) {
        super(0, 0, width, 20, Component.empty());
    }

    /** Adds a part; {@code fixed} 0 makes it share the free width. Grayed out while {@code enabled} is false. */
    ButtonRow add(AbstractWidget part, int fixed, BooleanSupplier enabled) {
        parts.add(part);
        widths.add(Math.max(0, fixed));
        on.add(enabled);
        return this;
    }

    ButtonRow add(AbstractWidget part, int fixed) {
        return add(part, fixed, () -> true);
    }

    /** The parts, left to right (for tests). */
    public List<AbstractWidget> parts() {
        return parts;
    }

    private void layout() {
        int fixed = 0, flex = 0;
        for (int w : widths) {
            if (w > 0) fixed += w;
            else flex++;
        }
        int free = getWidth() - fixed - GAP * Math.max(0, parts.size() - 1);
        int share = flex == 0 ? 0 : free / flex;
        int x = getX(), left = free;
        for (int i = 0; i < parts.size(); i++) {
            AbstractWidget p = parts.get(i);
            int w = widths.get(i);
            if (w == 0) {
                w = --flex == 0 ? left : share; // the last shared part takes the rounding
                left -= w;
            }
            p.setX(x);
            p.setY(getY());
            p.setWidth(Math.max(8, w));
            p.active = active && on.get(i).getAsBoolean();
            x += w + GAP;
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        layout();
        for (AbstractWidget p : parts) p.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!visible) return false;
        layout();
        for (AbstractWidget p : parts) {
            if (!p.isMouseOver(event.x(), event.y())) continue;
            boolean used = p.mouseClicked(event, doubleClick);
            pressed = used ? p : null;
            type(used && p instanceof EditBox ? p : null);
            return used;
        }
        type(null);
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        AbstractWidget p = pressed;
        pressed = null;
        return p != null && p.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return pressed != null && pressed.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return typing != null && typing.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return typing != null && typing.charTyped(event);
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) type(null);
    }

    private void type(AbstractWidget box) {
        typing = box;
        for (AbstractWidget p : parts) if (p instanceof EditBox) p.setFocused(p == box);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        for (AbstractWidget p : parts) {
            if (p.isHoveredOrFocused()) {
                p.updateNarration(out);
                return;
            }
        }
    }
}
