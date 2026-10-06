package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A section's main action as a big card: the section's icon on an accent tile, the action in bold with its
 * help under it, and a round arrow that nudges forward on hover. The whole card is one button. A row whose
 * {@code Action} has no title becomes one, e.g. the studio's Open Studio.
 */
public final class CardButton extends ThemedButton {
    private static final int TILE = 36, ARROW = 18, MIN_H = 48;
    private final ItemStack icon;
    private final Component help;
    private FormattedCharSequence label;
    private List<FormattedCharSequence> lines;

    public CardButton(Component label, Component help, ItemStack icon, int width, OnPress onPress) {
        super(width, MIN_H, label, onPress);
        this.icon = icon;
        this.help = help;
        layout();
    }

    /** Text width: the card minus its tile and arrow. */
    private int textW() {
        return Math.max(40, getWidth() - TILE - ARROW - 36);
    }

    private void layout() {
        Font font = Minecraft.getInstance().font;
        label = Component.empty().append(getMessage()).withStyle(ChatFormatting.BOLD).getVisualOrderText();
        lines = help.getString().isEmpty() ? List.of() : font.split(help, textW());
        setHeight(Math.max(MIN_H, 14 + 9 + 4 + lines.size() * 10 + 10));
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        if (help != null) layout();
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float a = active ? 1 : 0.5f;
        Shapes.roundGradient(g, x, y, w, h, Theme.RADIUS, Theme.mix(Theme.SURFACE, Theme.ACCENT_BG, 0.55f + 0.45f * hover),
            Theme.mix(Theme.SURFACE, 0xFF3A2238, 0.35f + 0.4f * hover));
        Shapes.frame(g, x, y, w, h, Theme.RADIUS, Theme.fade(Theme.ACCENT, (0.35f + 0.65f * hover) * a));

        int tx = x + 8, ty = y + (h - TILE) / 2;
        Shapes.round(g, tx, ty, TILE, TILE, Theme.RADIUS, Theme.fade(0xFF120E18, 0.9f));
        Shapes.frame(g, tx, ty, TILE, TILE, Theme.RADIUS, Theme.fade(Theme.PURPLE, 0.6f * a));
        if (!icon.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(tx + 2, ty + 2);
            g.pose().scale(2f, 2f);
            g.item(icon, 0, 0);
            g.pose().popMatrix();
        }

        int textX = tx + TILE + 10;
        int textH = 9 + (lines.isEmpty() ? 0 : 4 + lines.size() * 10 - 1);
        int textY = y + (h - textH) / 2;
        g.text(font, label, textX, textY, Theme.fade(0xFFFFFFFF, a), true);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), textX, textY + 13 + i * 10, Theme.fade(Theme.MUTED, a), false);

        int ax = x + w - ARROW - 10 + Math.round(hover * 2), ay = y + (h - ARROW) / 2;
        Shapes.circle(g, ax, ay, ARROW, Theme.fade(Theme.mix(Theme.PURPLE, Theme.PINK, 0.35f + 0.4f * hover), a));
        Ui.arrowRight(g, ax + ARROW / 2 - 1, ay + (ARROW - 7) / 2, Theme.fade(0xFFFFFFFF, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.RADIUS);
    }
}
