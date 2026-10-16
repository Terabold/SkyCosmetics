package io.github.terabold.skycosmetics.nametag;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

/**
 * Where the name's two gradient colors meet: the track is the gradient itself, the knob sits where the two are
 * mixed half and half. Right moves it toward the end, so more of the name is the first color. Drag, click, or the
 * arrow keys; right-click puts it back in the middle.
 */
final class GradientBalance extends AbstractWidget {
    private static final int H = 16, KNOB = 10, MAX_W = 240;
    private final Host host;
    private final Anim hover = new Anim(90, 0);
    private boolean dragging;

    GradientBalance(int width, Host host) {
        super(0, 0, Math.min(width, MAX_W), H, Component.literal("Gradient balance"));
        this.host = host;
    }

    private int trackX() {
        return getX() + 30 + KNOB / 2;
    }

    private int trackW() {
        return Math.max(1, getX() + getWidth() - KNOB / 2 - trackX());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Font font = Minecraft.getInstance().font;
        float a = active ? 1 : 0.4f;
        String label = Math.round(p.gradientMid * 100) + "%";
        g.text(font, label, getX() + 26 - font.width(label), getY() + (H - 8) / 2, Theme.fade(Theme.TEXT, a), false);
        int tx = trackX(), tw = trackW(), cy = getY() + H / 2, ty = cy - 3;
        int[] colors = {p.color, p.colorEnd};
        // The gradient as the name will have it, a column at a time, inside a rounded frame.
        for (int i = 0; i < tw; i++) {
            int rgb = Names.mix(colors, Nametags.bend(i / (float) Math.max(1, tw - 1)));
            g.fill(tx + i, ty + 1, tx + i + 1, ty + 5, Theme.fade(0xFF000000 | rgb, a));
        }
        Shapes.frame(g, tx - 1, ty, tw + 2, 6, 2, Theme.fade(Theme.LINE, a));
        int kx = tx + Math.round((float) p.gradientMid * (tw - 1)); // exactly where the two are mixed half and half
        float hv = hover.to(active && (dragging || isHovered()) ? 1 : 0);
        int d = KNOB + Math.round(hv * 2);
        if (hv > 0) Shapes.circle(g, kx - d / 2 - 2, cy - d / 2 - 2, d + 4, Theme.fade(Theme.ACCENT, 0.3f * hv * a));
        Shapes.circle(g, kx - d / 2, cy - d / 2, d, Theme.fade(0xFFFFFFFF, a));
        // The knob's middle shows the color found there: half of each.
        int mid = Names.mix(colors, .5f);
        Shapes.circle(g, kx - d / 2 + 2, cy - d / 2 + 2, d - 4, Theme.fade(0xFF000000 | mid, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), 3);
        if (isHovered() && active) g.requestCursor(CursorTypes.RESIZE_EW);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && active && visible && isMouseOver(event.x(), event.y())) {
            set(.5);
            host.changed();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        dragging = true;
        setFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        setFromMouse(event.x());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (!dragging) return;
        dragging = false;
        host.changed();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!active || !(event.isLeft() || event.isRight())) return false;
        set(p.gradientMid + (event.isLeft() ? -.05 : .05));
        host.changed();
        return true;
    }

    private void setFromMouse(double mx) {
        set((mx - trackX()) / Math.max(1, trackW() - 1));
    }

    private void set(double v) {
        double next = Math.round(Math.clamp(v, .1, .9) * 100) / 100.0;
        if (next == p.gradientMid) return;
        p.gradientMid = next;
        changed();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, CommonComponents.optionNameValue(getMessage(), Component.literal(Math.round(p.gradientMid * 100) + "%")));
    }
}
