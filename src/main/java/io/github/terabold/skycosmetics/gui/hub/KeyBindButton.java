package io.github.terabold.skycosmetics.gui.hub;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A key bound in place, like SkyHanni's and Firmament's: click it, then press a key. While it listens the
 * settings screen hands it every key and click first: a key binds, Esc cancels, Backspace or Delete unbinds,
 * a side mouse button binds and any other click cancels.
 */
public final class KeyBindButton extends Button.Plain {
    private static final int WARN = 0xFF6B6B;
    private final Supplier<KeyMapping> mapping;
    private final Runnable onChange;
    private boolean listening;

    /** @param onChange runs after every change of state or key: the screen refreshes the help line and saves */
    public KeyBindButton(int width, Supplier<KeyMapping> mapping, Runnable onChange) {
        super(0, 0, width, 20, Component.empty(), b -> ((KeyBindButton) b).listen(), Supplier::get);
        this.mapping = mapping;
        this.onChange = onChange;
        setMessage(label());
    }

    public boolean listening() {
        return listening;
    }

    private void listen() {
        listening = true;
        refresh();
    }

    private Component label() {
        if (listening) return Component.translatable("skycosmetics.option.openKey.listening").withStyle(ChatFormatting.YELLOW);
        KeyMapping m = mapping.get();
        if (m == null) return Component.empty();
        MutableComponent name = m.getTranslatedKeyMessage().copy();
        return conflicts().isEmpty() ? name : name.withColor(WARN);
    }

    /** The row's help: the usual line, what to press while listening, and a red line when the key clashes. */
    public Component help(Component usual, int helpColor) {
        MutableComponent help = (listening ? Component.translatable("skycosmetics.option.openKey.listening.tooltip")
            : usual.copy()).withColor(helpColor);
        List<String> clash = conflicts();
        if (!clash.isEmpty() && !listening) {
            help.append(Component.literal("\n")).append(Component.translatable("skycosmetics.option.openKey.conflict",
                String.join(", ", clash)).withColor(WARN));
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
        setMessage(label());
        onChange.run();
    }
}
