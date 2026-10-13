package io.github.terabold.skycosmetics.update;

import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The once-a-session update toast, in the settings' colors: the logo, what happened, and where to go
 * ({@code /skycosmetics}). Build it on the render thread.
 */
public final class UpdateToast implements Toast {
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, "icon.png");
    private static final long SHOW_MS = 8000;
    private static final int H = 32, MIN_W = 160, MAX_W = 250;
    private final Component title, line;
    private final String titleText, lineText;
    private final int width;
    private Visibility visibility = Visibility.SHOW;

    private UpdateToast(Component title, Component line) {
        this.title = title;
        this.line = line;
        Font font = Minecraft.getInstance().font;
        int text = Math.max(font.width(title), font.width(line));
        width = Math.clamp(text + 38, MIN_W, MAX_W);
        titleText = Ui.clip(font, title.getString(), width - 38);
        lineText = Ui.clip(font, line.getString(), width - 38);
    }

    public static UpdateToast available(String version) {
        return new UpdateToast(Component.translatable("skycosmetics.update.toast.available", version),
            Component.translatable("skycosmetics.update.toast.open"));
    }

    public static UpdateToast ready(String version) {
        return new UpdateToast(Component.translatable("skycosmetics.update.toast.ready", version),
            Component.translatable("skycosmetics.update.toast.restart"));
    }

    public static UpdateToast failed(String version) {
        return new UpdateToast(Component.translatable("skycosmetics.update.toast.available", version),
            Component.translatable("skycosmetics.update.toast.failed"));
    }

    public static UpdateToast updated(String version) {
        return new UpdateToast(Component.translatable("skycosmetics.update.toast.updated", version),
            Component.translatable("skycosmetics.update.toast.whatsNew"));
    }

    @Override
    public Visibility getWantedVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long visibleMs) {
        visibility = visibleMs >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return H;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, Font font, long visibleMs) {
        Shapes.round(g, 0, 0, width, H, Theme.RADIUS, Theme.CHROME);
        Shapes.frame(g, 0, 0, width, H, Theme.RADIUS, Theme.LINE);
        Shapes.round(g, 1, 6, 2, H - 12, 1, Theme.ACCENT);
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, 9, 8, 0, 0, 16, 16, 128, 128, 128, 128);
        g.text(font, titleText, 31, 7, Theme.TEXT, false);
        g.text(font, lineText, 31, 18, Theme.MUTED, false);
    }

    /** The text, for tests. */
    public String text() {
        return title.getString() + " / " + line.getString();
    }
}
