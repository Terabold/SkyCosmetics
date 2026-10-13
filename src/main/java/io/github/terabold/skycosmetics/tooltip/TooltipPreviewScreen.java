package io.github.terabold.skycosmetics.tooltip;

import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import io.github.terabold.skycosmetics.Io;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** Hover real inventory snapshots without opening a container or changing an item. */
public final class TooltipPreviewScreen extends Screen implements TooltipItemSource {
    private final Screen parent;
    private final List<ItemStack> inventory = new ArrayList<>();
    private final List<List<Component>> tooltips = new ArrayList<>();
    private final List<Optional<TooltipComponent>> images = new ArrayList<>();
    private boolean captured;
    private ItemStack hovered = ItemStack.EMPTY;

    public TooltipPreviewScreen(Screen parent) {
        super(TooltipSection.text("preview.button"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!captured) {
            captured = true;
            if (minecraft.player != null) {
                for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND))
                    inventory.add(minecraft.player.getItemBySlot(slot).copy());
                for (int i = 0; i < 36; i++) inventory.add(minecraft.player.getInventory().getItem(i).copy());
                for (var stack : inventory) {
                    List<Component> lines;
                    Optional<TooltipComponent> image;
                    try {
                        lines = stack.isEmpty() ? List.of() : List.copyOf(Screen.getTooltipFromItem(minecraft, stack));
                        image = stack.getTooltipImage();
                        if (!stack.isEmpty()) io.github.terabold.skycosmetics.slots.ItemFacts.of(stack);
                    } catch (RuntimeException e) { lines = List.of(stack.getHoverName()); image = Optional.empty(); Io.failed("Preparing tooltip preview", e); }
                    tooltips.add(lines); images.add(image);
                }
            }
        }
        var back = addRenderableWidget(new ActionButton(Component.literal("Back"), 70, false, b -> onClose()));
        back.setPosition(width / 2 - 74, height - 30);
        var done = addRenderableWidget(new ActionButton(TooltipSection.text("image.done"), 70, true, b -> onClose()));
        done.setPosition(width / 2 + 4, height - 30);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        g.fill(0, 0, width, height, Theme.SHADE);
        g.centeredText(font, title, width / 2, 16, Theme.ACCENT);
        g.centeredText(font, TooltipSection.text(TooltipSettings.enabled ? "preview.hint" : "preview.disabled"), width / 2, 34, Theme.TEXT);
        hovered = ItemStack.EMPTY;
        if (inventory.isEmpty()) {
            g.centeredText(font, Component.literal("Join a world to preview your inventory."), width / 2, height / 2, Theme.MUTED);
        } else {
            int left = width / 2 - 108, top = Math.max(60, height / 2 - 60);
            Shapes.round(g, left - 12, top - 22, 240, 184, Theme.RADIUS, Theme.BODY);
            Shapes.frame(g, left - 12, top - 22, 240, 184, Theme.RADIUS, Theme.LINE);
            g.text(font, Component.literal("Armor & offhand"), left, top - 12, Theme.MUTED, false);
            g.text(font, Component.literal("Hover an item to preview its tooltip"), left, top + 28, Theme.MUTED, false);
            for (int i = 0; i < inventory.size(); i++) {
                // Main inventory above hotbar, matching Minecraft's familiar arrangement.
                int index = i < 5 ? i : i < 32 ? i + 9 : i - 27;
                int sx = left + (i < 5 ? i : (i - 5) % 9) * 24;
                int sy = top + (i < 5 ? 0 : 42 + (i - 5) / 9 * 24 + (i >= 32 ? 4 : 0));
                Shapes.round(g, sx, sy, 22, 22, 2, Theme.SURFACE);
                var stack = inventory.get(index);
                if (!stack.isEmpty()) { g.item(stack, sx + 3, sy + 3); g.itemDecorations(font, stack, sx + 3, sy + 3); }
                if (mx >= sx && mx < sx + 22 && my >= sy && my < sy + 22 && !stack.isEmpty()) {
                    hovered = stack;
                    g.setTooltipForNextFrame(font, tooltips.get(index), images.get(index), mx, my,
                        stack.get(net.minecraft.core.component.DataComponents.TOOLTIP_STYLE));
                }
            }
        }
        super.extractRenderState(g, mx, my, dt);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
    @Override public ItemStack tooltipItem() { return hovered; }
}
