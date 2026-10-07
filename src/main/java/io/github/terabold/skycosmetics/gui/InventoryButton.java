package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.mixin.RecipeBookScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A small brush in the top-left corner of the player-model box of your
 * inventory (Skyblocker's button sits top-right). It is a real widget of the
 * inventory screen, so it draws under tooltips like every other button, and it
 * follows the panel when the recipe book slides it.
 */
public final class InventoryButton extends AbstractWidget {
    private static final int SIZE = 11;
    /** Top-left corner inside the black player-model box (26,8)-(75,78). */
    private static final int OFF_X = 27, OFF_Y = 9;
    /** Built on first draw: item components are not bound yet when the mod initialises. */
    private static ItemStack icon;

    private final InventoryScreen inv;
    private String hint = "";
    /** The accent the tooltip was built with: its title is in the accent. */
    private int tipTheme = -1;

    private InventoryButton(InventoryScreen inv) {
        super(0, 0, SIZE, SIZE, Component.literal("SkyCosmetics"));
        this.inv = inv;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof InventoryScreen inv)) return;
            InventoryButton button = new InventoryButton(inv);
            button.sync();
            Screens.getWidgets(screen).add(button);
            // Before the screen draws its widgets: follow the recipe book and the settings.
            ScreenEvents.beforeExtract(screen).register((s, g, mx, my, delta) -> button.sync());
        });
    }

    /** Hidden when switched off, or in narrow windows where the open recipe book covers the panel. */
    private boolean hidden() {
        if (!Settings.brush) return true;
        RecipeBookScreenAccessor r = (RecipeBookScreenAccessor) inv;
        return r.skycosmetics$widthTooNarrow() && r.skycosmetics$recipeBook().isVisible();
    }

    private void sync() {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) inv;
        setX(a.skycosmetics$leftPos() + OFF_X);
        setY(a.skycosmetics$topPos() + OFF_Y);
        visible = !hidden();
        String now = SkyCosmetics.openKeyHint();
        if (!now.equals(hint) || tipTheme != Theme.version()) {
            hint = now;
            tipTheme = Theme.version();
            setTooltip(Tooltip.create(Component.literal("SkyCosmetics").withColor(Theme.ACCENT & 0xFFFFFF)
                .append(Component.literal("\nSkins, dyes and names for your gear").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\nTip: " + now).withStyle(ChatFormatting.DARK_GRAY))));
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY();
        boolean hover = isHovered();
        Shapes.round(g, x, y, SIZE, SIZE, 2, hover ? Theme.fade(Theme.ACCENT, 0.75f) : 0x80000000);
        Shapes.frame(g, x, y, SIZE, SIZE, 2, hover ? Theme.mix(Theme.ACCENT, 0xFFFFFFFF, 0.45f) : 0xFF6A6A78);
        if (icon == null) icon = new ItemStack(Items.BRUSH);
        g.pose().pushMatrix();
        g.pose().translate(x + 1, y + 1);
        g.pose().scale(0.5625f, 0.5625f);
        g.fakeItem(icon, 0, 0);
        g.pose().popMatrix();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Minecraft mc = Minecraft.getInstance();
        playButtonClickSound(mc.getSoundManager());
        mc.setScreen(new StudioScreen(inv, ItemStack.EMPTY));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
