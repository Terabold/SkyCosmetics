package io.github.terabold.skycosmetics.tooltip;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.List;
import java.util.function.Consumer;

/** Scales and moves the complete original tooltip, including other mods' text and image components. */
public final class TooltipRenderer {
    public record Layout(int x, int y, int width, int height, int fullHeight, float scale, int padding, boolean scrollable) {}

    private static Screen screen;
    private static Object owner;
    private static int hoverX, hoverY, contentWidth, contentHeight;
    private static double target, shown, minimum, limit;
    private static long seen, advanced;
    private static boolean drawingContent;
    private static Layout last;

    private TooltipRenderer() {}

    public static void init() {
        TooltipSettings.init();
        TooltipSection.register();
        TooltipImage.load();
        ScreenEvents.AFTER_INIT.register((mc, s, w, h) -> {
            ScreenMouseEvents.allowMouseScroll(s).register((view, mx, my, dx, dy) -> {
                try {
                    return !wheel(view, mx, my, dy, Minecraft.getInstance().hasShiftDown());
                } catch (RuntimeException e) {
                    Io.failed("Scrolling a tooltip", e);
                    reset();
                    return true;
                }
            });
        });
    }

    public static boolean drawingContent() {
        return drawingContent;
    }

    public static void reset() {
        screen = null;
        owner = null;
        seen = advanced = 0;
        target = shown = minimum = limit = 0;
        last = null;
    }

    public static Layout layout(int sw, int sh, int width, int height, int mx, int my, ClientTooltipPositioner positioner) {
        // Pack sprites include a 12px margin, which must fit even when our chosen padding is smaller.
        int pad = TooltipSettings.customBackground && TooltipSettings.customBorder
            ? TooltipSettings.padding : Math.max(12, TooltipSettings.padding);
        float scale = TooltipSettings.size / 100f;
        int availableWidth = Math.max(1, (sw - 10) * TooltipSettings.maxWidth / 100);
        // Fit whole components, rather than re-wrap or replace text supplied by other mods.
        scale = Math.min(scale, availableWidth / (float) Math.max(1, width + 2 * pad));
        int availableHeight = Math.max(1, (sh - 10) * TooltipSettings.maxHeight / 100);
        if (TooltipSettings.autoFit) scale = Math.min(scale, availableHeight / (float) (height + 2 * pad));
        int visible = height;
        int w = (int) Math.ceil((width + 2 * pad) * scale), h = (int) Math.ceil((visible + 2 * pad) * scale);
        // Position the content as vanilla does; padding reserves edge space, not an extra mouse offset.
        Vector2ic pos = positioner.positionTooltip(sw, sh, mx, my,
            (int)Math.ceil(width * scale), (int)Math.ceil(height * scale));
        int margin = (int)Math.ceil(pad * scale);
        int x = Math.clamp(pos.x() - margin, 5, Math.max(5, sw - 5 - w));
        int y = Math.clamp(pos.y() - margin, 5, Math.max(5, sh - 5 - h));
        return new Layout(x, y, width, visible, height, scale, pad, h > sh - 10);
    }

    public static void render(GuiGraphicsExtractor g, Font font, List<ClientTooltipComponent> parts, int mx, int my,
                              ClientTooltipPositioner positioner, Identifier style, Consumer<ClientTooltipPositioner> original) {
        int width = 0, height = parts.size() == 1 ? -2 : 0;
        for (ClientTooltipComponent part : parts) {
            width = Math.max(width, part.getWidth(font));
            height += part.getHeight(font);
        }
        if (parts.isEmpty() || width < 1 || height < 1) {
            original.accept(positioner);
            return;
        }
        Layout box = layout(g.guiWidth(), g.guiHeight(), width, height, mx, my, positioner);
        long now = Util.getMillis();
        Screen current = Minecraft.getInstance().screen;
        Object currentOwner = null;
        if (current instanceof AbstractContainerScreen<?>) {
            var slot = ((AbstractContainerScreenAccessor) current).skycosmetics$hoveredSlot();
            if (slot != null) currentOwner = slot.getItem();
        } else if (current instanceof TooltipItemSource source) {
            currentOwner = source.tooltipItem();
        }
        if (current != screen || currentOwner != owner || now - seen > 250 || width != contentWidth || height != contentHeight
            || Math.abs(mx - hoverX) > 4 || Math.abs(my - hoverY) > 4) {
            target = shown = 0;
            hoverX = mx;
            hoverY = my;
        }
        screen = current;
        owner = currentOwner;
        contentWidth = width;
        contentHeight = height;
        limit = Math.max(0, height + 2 * box.padding() - (g.guiHeight() - 10) / box.scale());
        minimum = 0;
        if (currentOwner instanceof net.minecraft.world.item.ItemStack stack && !stack.isEmpty()) {
            // Item tooltips can be moved even after auto-fit. Keep a line visible so a scroll cannot lose the tip.
            float total = (height + 2 * box.padding()) * box.scale();
            float retained = Math.min(24, total);
            minimum = (box.y() - (g.guiHeight() - 5 - retained)) / box.scale();
            limit = (box.y() - (5 - total + retained)) / box.scale();
        }
        target = Math.clamp(target, minimum, limit);
        shown = Math.clamp(shown, minimum, limit);
        if (TooltipSettings.scroll == TooltipSettings.Scroll.OFF) target = shown = 0;
        else if (TooltipSettings.scroll == TooltipSettings.Scroll.NORMAL) shown = target;
        else shown = Anim.glide((float) shown, (float) target, Math.min(100, Math.max(0, now - advanced)));
        if (Math.abs(shown - target) < 0.05) shown = target;
        advanced = seen = now;
        last = box;

        g.pose().pushMatrix();
        try {
            g.pose().translate(box.x(), box.y() - (float) shown * box.scale());
            g.pose().scale(box.scale(), box.scale());
            {
                // Let the original method draw its background and text together, after other mods' scale hooks.
                g.pose().translate(box.padding(), box.padding());
                boolean previous = drawingContent;
                drawingContent = true;
                try {
                    original.accept((sw, sh, x, y, w, h) -> new Vector2i(0, 0));
                } finally {
                    drawingContent = previous;
                }
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /** A fresh item tooltip or an overflowing foreign tooltip under this pointer owns the wheel. */
    public static boolean wheel(Screen view, double mx, double my, double dy, boolean shift) {
        if (view instanceof io.github.terabold.skycosmetics.gui.StudioScreen studio && studio.scrollsContent(mx, my)) return false;
        if (!TooltipSettings.enabled || TooltipSettings.scroll == TooltipSettings.Scroll.OFF || !Double.isFinite(dy) || dy == 0
            || view != screen || Util.getMillis() - seen > 250 || limit <= minimum || last == null
            || TooltipSettings.shiftToScroll && !shift || Math.abs(mx - hoverX) > 4 || Math.abs(my - hoverY) > 4) return false;
        target = Math.clamp(target - dy * TooltipSettings.scrollStep, minimum, limit);
        if (TooltipSettings.scroll == TooltipSettings.Scroll.NORMAL) shown = target;
        return true;
    }

    public static Layout lastLayout() { return last; }
    public static int borderColor() {
        if (TooltipSettings.rarityBorder && owner instanceof net.minecraft.world.item.ItemStack stack) {
            var rarity = io.github.terabold.skycosmetics.slots.ItemFacts.of(stack).rarity();
            if (rarity != null) return 0xFF000000 | io.github.terabold.skycosmetics.slots.RarityBackgrounds.color(rarity);
        }
        return TooltipSettings.border;
    }
    public static double scrollOffset() { return shown; }
    public static double scrollTarget() { return target; }
}
