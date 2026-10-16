package io.github.terabold.skycosmetics.gui.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** The mod's logo inside its own screens: the island and crown alone, cut out of the icon's dark background. */
public final class Logo {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("skycosmetics", "logo.png");
    /** The texture's side: the 64-pixel art at twice its size. */
    private static final int TEX = 110;
    /** The size screens draw it at, a little over a line of text and its padding. */
    public static final int SIZE = 18;

    private Logo() {}

    public static void draw(GuiGraphicsExtractor g, int x, int y) {
        g.blit(RenderPipelines.GUI_TEXTURED, ID, x, y, 0, 0, SIZE, SIZE, TEX, TEX, TEX, TEX);
    }
}
