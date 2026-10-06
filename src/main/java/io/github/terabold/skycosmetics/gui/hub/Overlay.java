package io.github.terabold.skycosmetics.gui.hub;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Something drawn over the settings (a drop-down, a color picker) that gets every mouse and key event first
 * while it is open. A click outside closes it and is used up, so nothing under it reacts.
 */
public interface Overlay {
    void render(GuiGraphicsExtractor g, int mouseX, int mouseY);

    void mouseClicked(MouseButtonEvent event);

    default void mouseDragged(MouseButtonEvent event) {}

    default void mouseReleased(MouseButtonEvent event) {}

    default void mouseScrolled(double mouseX, double mouseY, double amount) {}

    void keyPressed(KeyEvent event);

    default void charTyped(CharacterEvent event) {}

    boolean isClosed();

    /** Closes as the user would (Esc): typed values apply first. */
    void close();

    /** Moves it back inside this area after a resize. */
    default void fit(int x, int y, int width, int height) {}

    /** What can open an overlay: the settings screen. */
    interface Host {
        void openOverlay(Overlay overlay);
    }
}
