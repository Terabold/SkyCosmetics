package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A key bound in place: click it, then press a key. While it listens it reads "> Press a key <" in a pulsing
 * accent frame, and the settings screen hands it every key and click first: a key binds, Esc cancels, Backspace
 * or Delete unbinds, a side mouse button binds and any other click cancels. A key another mapping also uses
 * shows in red, with the clash in the row's help.
 */
public final class KeyBindButton extends ThemedButton {
    public static final int H = 16, W = 96;
    private final Supplier<KeyMapping> mapping;
    private final Runnable onChange;
    private boolean listening;
    private boolean clash;
    private String shown = "";

    /** @param onChange runs after every change of state or key: the screen refreshes the help line and saves */
    public KeyBindButton(int width, Supplier<KeyMapping> mapping, Runnable onChange) {
        super(width, H, Component.empty(), b -> ((KeyBindButton) b).listen());
        this.mapping = mapping;
        this.onChange = onChange;
        update();
    }

    public boolean listening() {
        return listening;
    }

    private void listen() {
        listening = true;
        refresh();
    }

    private void update() {
        KeyMapping m = mapping.get();
        clash = !listening && !conflicts().isEmpty();
        Component label = listening ? Component.translatable("skycosmetics.option.openKey.listening")
            : m == null ? Component.empty() : m.getTranslatedKeyMessage();
        setMessage(label);
        shown = Ui.clip(Minecraft.getInstance().font, label.getString(), getWidth() - 8);
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float a = active ? 1 : 0.4f;
        Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), a));
        int text;
        if (listening) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(Util.getMillis() / 160.0);
            Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.ACCENT_BG, 0.8f));
            Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(Theme.ACCENT, pulse));
            text = Theme.ACCENT;
        } else {
            Shapes.frame(g, x, y, w, h, Theme.SMALL_RADIUS, Theme.fade(clash ? Theme.WARN : Theme.mix(Theme.LINE, Theme.ACCENT, hover), a));
            text = clash ? Theme.WARN : Theme.TEXT;
        }
        Ui.centered(g, font, shown, x + w / 2, y + (h - 7) / 2, Theme.fade(text, a));
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS);
    }

    /** The row's help: the usual line, what to press while listening, and a red line when the key clashes. */
    public Component help(Component usual, int helpColor) {
        MutableComponent help = (listening ? Component.translatable("skycosmetics.option.openKey.listening.tooltip")
            : usual.copy()).withColor(helpColor & 0xFFFFFF);
        List<String> clashes = conflicts();
        if (!clashes.isEmpty() && !listening) {
            help.append(Component.literal("\n")).append(Component.translatable("skycosmetics.option.openKey.conflict",
                String.join(", ", clashes)).withColor(Theme.WARN & 0xFFFFFF));
        }
        return help;
    }

    /** The other key mappings on this key, by their names in Controls. */
    private List<String> conflicts() {
        KeyMapping open = mapping.get();
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (open == null || open.isUnbound()) return out;
        for (KeyMapping m : mc.options.keyMappings) {
            if (m != open && m.same(open)) out.add(Component.translatable(m.getName()).getString());
        }
        return out;
    }

    /** While listening every key is ours, so nothing else reacts to it. False when not listening. */
    public boolean handleKey(KeyEvent e) {
        if (!listening) return false;
        if (e.isEscape()) {
            listening = false;
            refresh();
        } else if (e.key() == GLFW.GLFW_KEY_BACKSPACE || e.key() == GLFW.GLFW_KEY_DELETE) {
            bind(InputConstants.UNKNOWN);
        } else {
            bind(InputConstants.getKey(e));
        }
        return true;
    }

    /** While listening, a side mouse button binds and any other click cancels. False when not listening. */
    public boolean handleClick(MouseButtonEvent e) {
        if (!listening) return false;
        if (e.button() >= GLFW.GLFW_MOUSE_BUTTON_4) {
            bind(InputConstants.Type.MOUSE.getOrCreate(e.button()));
        } else {
            listening = false;
            refresh();
        }
        return true;
    }

    private void bind(InputConstants.Key key) {
        KeyMapping m = mapping.get();
        if (m != null) {
            m.setKey(key);
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        }
        listening = false;
        refresh();
    }

    public void refresh() {
        update();
        onChange.run();
    }
}
