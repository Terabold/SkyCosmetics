package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Optional;

/** Compact item selection tile; stacks and hover text are captured during init. */
public final class ItemTile extends ThemedButton {
    public final ItemStack stack;
    private final boolean selected;
    private final String label;
    public final List<Component> tooltip;
    public final Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> tooltipImage;
    public boolean itemTooltip = true;
    public boolean addMarker = true;
    public ItemTile(String label, ItemStack stack, int width, int height, boolean selected, Runnable action) {
        super(width, height, Component.literal(label), b -> action.run());
        // Callers supply isolated copies and preserve any cosmetic render marker themselves.
        this.stack = stack; this.selected = selected;
        List<Component> lines;
        Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> image;
        try {
            lines = stack.isEmpty() ? List.of() : List.copyOf(Screen.getTooltipFromItem(net.minecraft.client.Minecraft.getInstance(), this.stack));
            image = this.stack.getTooltipImage();
            if (!stack.isEmpty()) io.github.terabold.skycosmetics.slots.ItemFacts.of(this.stack);
        } catch (RuntimeException e) { lines = List.of(Component.literal(label)); image = Optional.empty(); Io.failed("Preparing item tooltip", e); }
        this.tooltip = lines;
        this.tooltipImage = image;
        this.label = Ui.clip(net.minecraft.client.Minecraft.getInstance().font, label, Math.max(0, width - 44));
    }
    @Override protected void draw(GuiGraphicsExtractor g, int mx, int my, float hover) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, selected ? Theme.ACCENT_BG : Theme.SURFACE);
        Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, selected ? Theme.ACCENT : Theme.LINE);
        if (!stack.isEmpty()) {
            if (w <= 48 && h >= 32) {
                g.pose().pushMatrix();
                try {
                    g.pose().translate(x + w / 2f, y + h / 2f); g.pose().scale(1.5f, 1.5f);
                    g.item(stack, -8, -8);
                } finally { g.pose().popMatrix(); }
            } else g.item(stack, x + (w <= 48 ? (w - 16) / 2 : 4), y + (h - 16) / 2);
        }
        if (w > 48) {
            g.text(net.minecraft.client.Minecraft.getInstance().font, label, x + 24, y + (h - 8) / 2, Theme.TEXT, false);
            g.text(net.minecraft.client.Minecraft.getInstance().font, selected ? "✓" : "○", x + w - 12, y + (h - 8) / 2, selected ? Theme.ACCENT : Theme.MUTED, false);
        } else if (selected) g.fill(x + w - 5, y + 2, x + w - 2, y + 5, Theme.ACCENT);
        else if (!itemTooltip && addMarker) g.text(net.minecraft.client.Minecraft.getInstance().font, "+", x + w - 7, y + h - 9, Theme.MUTED, false);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }
}
