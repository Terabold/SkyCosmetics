package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The x at the end of the settings' header; it closes them like Esc. The message names where it goes. */
public final class CloseButton extends ThemedButton {
    public static final int SIZE = 16;

    public CloseButton(Component label, Component tooltip, OnPress onPress) {
        super(SIZE, SIZE, label, onPress);
        Tips.set(this, tooltip);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        int x = getX(), y = getY();
        if (hover > 0) Shapes.round(g, x, y, SIZE, SIZE, Theme.SMALL_RADIUS + 1, Theme.fade(0xFF8A2E4A, hover));
        Ui.cross(g, x + 5, y + 5, 6, Theme.mix(Theme.MUTED, 0xFFFFFFFF, hover));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, SIZE, SIZE, Theme.SMALL_RADIUS + 1);
    }
}
