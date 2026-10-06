package io.github.terabold.skycosmetics.slots;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.gui.ColorPicker;
import io.github.terabold.skycosmetics.gui.ColorPopup;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * One color swatch per rarity, with its name. A click opens the color pop-up for that rarity and every change shows
 * at once; right-click puts a changed color back to Hypixel's.
 */
public final class RaritySwatches extends AbstractWidget {
    private static final int ROW = 14, BOX = 10, MIN_COL = 84;
    private static final int TEXT = 0xFFE8E8EE, MUTED = 0xFF8C8C9A, EDGE = 0xFF000000;
    private static final Rarity[] ALL = Rarity.values();

    private final Host host;
    private final int cols;

    RaritySwatches(int width, Host host) {
        super(0, 0, width, 0, Component.translatable("skycosmetics.rarityBackgrounds.swatches"));
        this.host = host;
        cols = Math.clamp(width / MIN_COL, 1, 4);
        height = (ALL.length + cols - 1) / cols * ROW;
    }

    private int colWidth() {
        return width / cols;
    }

    /** The rarity under a point, or null. */
    private Rarity at(double mx, double my) {
        if (mx < getX() || my < getY() || mx >= getRight() || my >= getBottom()) return null;
        int c = Math.min(cols - 1, (int) ((mx - getX()) / colWidth())), r = (int) ((my - getY()) / ROW);
        int i = r * cols + c;
        return i < ALL.length ? ALL[i] : null;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        Rarity hovered = active && isHovered() ? at(mouseX, mouseY) : null;
        int cw = colWidth();
        for (int i = 0; i < ALL.length; i++) {
            Rarity r = ALL[i];
            int x = getX() + i % cols * cw, y = getY() + i / cols * ROW;
            if (r == hovered) g.fill(x, y, x + cw - 2, y + ROW - 1, 0x30FFFFFF);
            int rgb = RarityBackgrounds.color(r);
            g.fill(x + 1, y + 1, x + 3 + BOX, y + 3 + BOX, EDGE);
            g.fill(x + 2, y + 2, x + 2 + BOX, y + 2 + BOX, active ? 0xFF000000 | rgb : 0xFF000000 | dim(rgb));
            boolean changed = rgb != RarityBackgrounds.presetColor(r);
            String name = clip(font, r.label().getString() + (changed ? "*" : ""), cw - BOX - 8);
            g.text(font, name, x + BOX + 6, y + 3, active ? TEXT : MUTED);
        }
        if (hovered != null) {
            g.requestCursor(CursorTypes.POINTING_HAND);
            boolean changed = RarityBackgrounds.color(hovered) != RarityBackgrounds.presetColor(hovered);
            List<Component> lines = changed
                ? List.of(hovered.label().copy().withColor(RarityBackgrounds.color(hovered)),
                    Component.translatable("skycosmetics.rarityBackgrounds.swatches.edit").withStyle(ChatFormatting.GRAY),
                    Component.translatable("skycosmetics.rarityBackgrounds.swatches.reset").withStyle(ChatFormatting.GRAY))
                : List.of(hovered.label().copy().withColor(RarityBackgrounds.color(hovered)),
                    Component.translatable("skycosmetics.rarityBackgrounds.swatches.edit").withStyle(ChatFormatting.GRAY));
            g.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    private static int dim(int rgb) {
        int r = (rgb >> 16 & 0xFF) / 3 + 40, gr = (rgb >> 8 & 0xFF) / 3 + 40, b = (rgb & 0xFF) / 3 + 40;
        return r << 16 | gr << 8 | b;
    }

    private static String clip(Font font, String s, int px) {
        if (font.width(s) <= px) return s;
        return font.plainSubstrByWidth(s, Math.max(0, px - font.width("."))) + ".";
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo info) {
        return info.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || info.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Rarity r = at(event.x(), event.y());
        if (r == null) return;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (RarityBackgrounds.color(r) != RarityBackgrounds.presetColor(r)) {
                RarityBackgrounds.setColor(r, RarityBackgrounds.presetColor(r));
                host.changed();
            }
            return;
        }
        edit(r);
    }

    /** Opens the color pop-up for a rarity: every change applies live, closing it saves. */
    public void edit(Rarity r) {
        Component title = Component.translatable("skycosmetics.rarityBackgrounds.swatches.title", r.label());
        host.openPopup(new ColorPopup(title, ColorPicker.hex(RarityBackgrounds.color(r)), false, id -> {
            int rgb = DyeEntry.parseHex(id);
            if (rgb >= 0 && rgb != RarityBackgrounds.color(r)) RarityBackgrounds.setColor(r, rgb);
        }).onClose(host::changed));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
    }

    /** For tests: the screen point at the middle of a rarity's swatch. */
    public int[] centerOf(Rarity r) {
        int i = r.ordinal(), cw = colWidth();
        return new int[]{getX() + i % cols * cw + 2 + BOX / 2, getY() + i / cols * ROW + 2 + BOX / 2};
    }
}
