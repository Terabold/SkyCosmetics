package io.github.terabold.skycosmetics.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * A small window over the studio's middle column (a color, a gradient): it gets every mouse and key event first
 * while open and takes them all, so nothing under it reacts. A click outside, its x or Esc closes it.
 */
interface StudioPopup {
    /** Centers it in an area, never bigger than the area. */
    void place(int areaX, int areaY, int areaW, int areaH);

    void render(GuiGraphicsExtractor g, int mouseX, int mouseY);

    boolean mouseClicked(double mx, double my, int button);

    boolean mouseDragged(double mx, double my, int button);

    boolean mouseReleased(double mx, double my, int button);

    boolean keyPressed(KeyEvent event);

    boolean charTyped(CharacterEvent event);

    boolean isClosed();

    /** Closes as the user would: a half-typed value applies first, then its close action runs. */
    void close();
}
