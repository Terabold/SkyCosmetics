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
 * accent frame, and the settings screen hands it every key and click first: a key binds, Esc, Backspace or Delete
 * unbinds (Esc as in Controls), a side mouse button binds and any other click cancels. A key another mapping also uses
 * shows in red, with the clash in the row's help.
 */
public final class KeyBindButton extends ThemedButton {
    public static final int H = 16, W = 96;
    private final Supplier<KeyMapping> mapping;
    private final Runnable onChange;
    private final boolean allowMiddleMouse;
    /** Shown instead of "Not bound" when no key means something ("Same as the lock key"); null for the usual. */
    private final Component unbound;
    private boolean listening;
    private boolean clash;
    private String shown = "";

    /** @param onChange runs after every change of state or key: the screen refreshes the help line and saves */
    public KeyBindButton(int width, Supplier<KeyMapping> mapping, Runnable onChange) {
        this(width, mapping, onChange, false);
    }

    public KeyBindButton(int width, Supplier<KeyMapping> mapping, Runnable onChange, boolean allowMiddleMouse) {
        this(width, mapping, onChange, allowMiddleMouse, null);
    }

    public KeyBindButton(int width, Supplier<KeyMapping> mapping, Runnable onChange, boolean allowMiddleMouse, Component unbound) {
        super(width, H, Component.empty(), b -> ((KeyBindButton) b).listen());
        this.mapping = mapping;
        this.onChange = onChange;
        this.allowMiddleMouse = allowMiddleMouse;
        this.unbound = unbound;
        update();
    }

    /** Mappings made to work on one key together (a lock key that also binds): no clash shown between them. */
    private static final java.util.Set<String> SHARED = new java.util.HashSet<>();

    public static void mayShare(KeyMapping a, KeyMapping b) {
        SHARED.add(a.getName() + "|" + b.getName());
        SHARED.add(b.getName() + "|" + a.getName());
    }

    /** Mappings that only act while a menu is open (slot locking's keys). */
    private static final java.util.Set<String> MENUS_ONLY = new java.util.HashSet<>();
    /** Minecraft's own keys that do something while a menu is open; its others (Advancements...) never do. */
    private static final java.util.Set<String> VANILLA_IN_MENUS = java.util.Set.of("key.inventory", "key.drop", "key.swapOffhand",
        "key.pickItem", "key.screenshot", "key.fullscreen", "key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.hotbar.4",
        "key.hotbar.5", "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9");

    /** Marks a mapping that only acts in menus: Minecraft keys that never act there aren't shown as a clash. */
    public static void onlyInMenus(KeyMapping m) {
        MENUS_ONLY.add(m.getName());
    }

    private static boolean neverTogether(KeyMapping open, KeyMapping other) {
        return MENUS_ONLY.contains(open.getName()) && "minecraft".equals(other.getCategory().id().getNamespace())
            && !VANILLA_IN_MENUS.contains(other.getName());
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
            : m == null ? Component.empty() : m.isUnbound() && unbound != null ? unbound : keyLabel(m);
        setMessage(label);
        shown = Ui.clip(Minecraft.getInstance().font, label.getString(), getWidth() - 8);
    }

    /** Letters identify the physical binding, independent of GLFW's cached keyboard layout. */
    public static Component keyLabel(KeyMapping mapping) {
        String saved = mapping.saveString();
        String prefix = "key.keyboard.";
        if (saved.startsWith(prefix) && saved.length() == prefix.length() + 1) {
            char letter = saved.charAt(prefix.length());
            if (letter >= 'a' && letter <= 'z') return Component.literal(String.valueOf(Character.toUpperCase(letter)));
        }
        return mapping.getTranslatedKeyMessage();
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
            if (!help.getString().isEmpty()) help.append(Component.literal("\n"));
            help.append(Component.translatable("skycosmetics.option.openKey.conflict",
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
            if (m != open && m.same(open) && !SHARED.contains(open.getName() + "|" + m.getName()) && !neverTogether(open, m)) {
                out.add(Component.translatable(m.getName()).getString());
            }
        }
        return out;
    }

    /** While listening every key is ours, so nothing else reacts to it. False when not listening. */
    public boolean handleKey(KeyEvent e) {
        if (!listening) return false;
        if (e.isEscape() || e.key() == GLFW.GLFW_KEY_BACKSPACE || e.key() == GLFW.GLFW_KEY_DELETE) {
            bind(InputConstants.UNKNOWN);
        } else {
            bind(InputConstants.getKey(e));
        }
        return true;
    }

    /** While listening, a side mouse button binds and any other click cancels. False when not listening. */
    public boolean handleClick(MouseButtonEvent e) {
        if (!listening) return false;
        if (e.button() >= GLFW.GLFW_MOUSE_BUTTON_4 || allowMiddleMouse && e.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
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
