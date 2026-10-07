package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.compat.OtherLooks.Change;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.Consumer;

/**
 * "Dyed by Skyblocker" on the item the editor shows: another mod changes this item too, and its change shows
 * over SkyCosmetics' one. A click removes it in that mod, a right-click moves it into SkyCosmetics. A change
 * SkyCosmetics can only read says where to change it.
 */
final class OtherChip extends AbstractWidget {
    static final int HEIGHT = 12;

    private final Font font;
    private final Change change;
    private final Consumer<Change> remove, move;
    private String label;
    private int labelFor = -1;

    OtherChip(Font font, int x, int y, Change change, Consumer<Change> remove, Consumer<Change> move) {
        super(x, y, width(font, change), HEIGHT, change.chip());
        this.font = font;
        this.change = change;
        this.remove = remove;
        this.move = move;
        String mod = change.source().name();
        MutableComponent tip = change.chip().copy().withStyle(ChatFormatting.WHITE).append("\n")
            .append(valueLine(change))
            .append(Component.literal("\n"))
            .append(Component.translatable("skycosmetics.chip.over", mod).withStyle(ChatFormatting.GRAY));
        if (change.live()) {
            tip.append("\n").append(Component.translatable("skycosmetics.chip.click", mod).withStyle(ChatFormatting.YELLOW));
            if (change.movable()) tip.append("\n").append(Component.translatable("skycosmetics.chip.rightClick").withStyle(ChatFormatting.YELLOW));
        } else {
            tip.append("\n").append(Component.translatable("skycosmetics.other.changeIn", mod).withStyle(ChatFormatting.YELLOW));
        }
        Tips.set(this, tip);
    }

    /** The change's value for a tooltip: the name itself, or the value with a swatch of its color. */
    static Component valueLine(Change c) {
        if (c.name() != null) return c.name().copy();
        MutableComponent line = Component.empty();
        if (c.rgb() >= 0) line.append(Component.literal("■ ").withColor(c.rgb()));
        return line.append(Component.literal(c.value()).withStyle(ChatFormatting.GRAY));
    }

    static int width(Font font, Change c) {
        return font.width(c.chip()) + 8;
    }

    Change change() {
        return change;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int col = change.source().color();
        boolean over = isHoveredOrFocused() && change.live();
        Shapes.round(g, getX(), getY(), getWidth(), getHeight(), 3, (col & 0x00FFFFFF) | (over ? 0x90000000 : 0x50000000));
        Shapes.frame(g, getX(), getY(), getWidth(), getHeight(), 3, col);
        g.text(font, label(), getX() + 4, getY() + 2, change.live() ? 0xFFFFFFFF : 0xFFB0B0B8, false);
    }

    /** The label, cut with "…" when the chip is narrower than it; worked out once per width. */
    private String label() {
        int room = getWidth() - 8;
        if (room != labelFor) {
            labelFor = room;
            String full = getMessage().getString();
            label = font.width(full) <= room ? full : font.plainSubstrByWidth(full, Math.max(0, room - font.width("…"))) + "…";
        }
        return label;
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo button) {
        return change.live() && (button.button() == 0 || button.button() == 1 && change.movable());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1) move.accept(change);
        else remove.accept(change);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
