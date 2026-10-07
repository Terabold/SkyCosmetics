package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button the settings and the studio draw entirely themselves, never with vanilla's sprite. Still a vanilla
 * {@link Button}, so Tab, Enter, Space, narration, tooltips and tests work as everywhere else. Its message is what
 * it does ("Open Studio", a row's title), so a test can press it by name.
 */
public abstract class ThemedButton extends Button {
    /** 0 at rest to 1 under the mouse, fading both ways. */
    protected final Anim hover = new Anim(90, 0);
    private boolean keepsFocus;

    protected ThemedButton(int width, int height, Component message, OnPress onPress) {
        super(0, 0, width, height, message, onPress, DEFAULT_NARRATION);
    }

    /**
     * A click leaves the keyboard where it was: the studio's name box keeps its focus and selection while its color
     * and style buttons format the selected letters.
     */
    public ThemedButton keepFocus() {
        keepsFocus = true;
        return this;
    }

    @Override
    public boolean shouldTakeFocusAfterInteraction() {
        return !keepsFocus;
    }

    @Override
    protected final void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        draw(g, mouseX, mouseY, hover.to(isHovered() && active ? 1 : 0));
    }

    /** @param hover 0..1, eased in and out by real time */
    protected abstract void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover);
}
