package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Option;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Turns each {@link Control} kind into its themed widget, plus how to show the value again after a change. The
 * settings screen asks for a width first ({@link #width}, or {@link #stackedWidth} when the row is too narrow for
 * its text and control side by side), then builds the widget at that width.
 */
public final class Controls {
    /** A row's widget and its refresh: value, grayed-out state, tooltip. */
    public record Built(AbstractWidget widget, Runnable refresh) {}

    /** Narrowest and widest a slider gets beside its text, and the widest anything gets on a line of its own. */
    private static final int SLIDER_MIN = 90, SLIDER_MAX = 170, STACKED_MAX = 220;

    private Controls() {}

    /** The control's width beside the row's text, in a row {@code rowW} wide; 0 for a header. */
    public static int width(Option o, int rowW) {
        return switch (o.control()) {
            case Control.Header h -> 0;
            case Control.Toggle t -> ToggleSwitch.W;
            case Control.Slider s -> Math.clamp(rowW * 36 / 100, SLIDER_MIN, SLIDER_MAX);
            case Control.Choice<?> c -> choiceWidth(c, Math.clamp(rowW * 46 / 100, 100, 230));
            case Control.Key k -> KeyBindButton.W;
            case Control.Action a -> Math.clamp(ActionButton.fit(a.label()), 40, Math.max(60, rowW / 2));
            case Control.Color c -> ColorSwatch.fit(c.alpha());
            case Control.Custom c -> rowW;
        };
    }

    /** The control's width on a line of its own under the text, {@code avail} wide at most. */
    public static int stackedWidth(Option o, int avail) {
        return switch (o.control()) {
            case Control.Slider s -> Math.min(avail, STACKED_MAX);
            case Control.Choice<?> c -> choiceWidth(c, Math.min(avail, STACKED_MAX + 40));
            default -> Math.min(width(o, avail), avail);
        };
    }

    /** Side by side when the values fit, else a drop-down as wide as the longest value. */
    private static <T> int choiceWidth(Control.Choice<T> c, int max) {
        int seg = SegmentedChoice.naturalWidth(c);
        if (seg > 0 && seg <= max) return seg;
        Font font = Minecraft.getInstance().font;
        int widest = 0;
        for (T v : c.values()) widest = Math.max(widest, font.width(c.label().apply(v)));
        return Math.clamp(widest + 26, Math.min(70, max), max);
    }

    /**
     * @param keyChanged runs when a key row's state changes (its help line grows or shrinks)
     * @return null for a header, which has no control
     */
    public static Built build(Option o, int width, Host host, Runnable keyChanged) {
        return switch (o.control()) {
            case Control.Header h -> null;
            case Control.Toggle t -> {
                ToggleSwitch b = new ToggleSwitch(o.title(), t.get(), btn -> {
                    t.set().accept(!t.get().getAsBoolean());
                    host.changed();
                });
                yield new Built(b, () -> b.active = o.enabled().getAsBoolean());
            }
            case Control.Slider s -> {
                OptionSlider slider = new OptionSlider(o.title(), width, s, host);
                yield new Built(slider, () -> {
                    slider.refresh();
                    slider.active = o.enabled().getAsBoolean();
                });
            }
            case Control.Choice<?> c -> choice(c, o, width, host);
            case Control.Key k -> {
                KeyBindButton b = new KeyBindButton(width, k.mapping(), keyChanged);
                yield new Built(b, () -> b.active = o.enabled().getAsBoolean());
            }
            case Control.Action a -> {
                ActionButton b = new ActionButton(a.label(), width, false, btn -> a.run().run());
                yield new Built(b, () -> {
                    boolean on = a.active().getAsBoolean() && o.enabled().getAsBoolean();
                    b.active = on;
                    b.setTooltip(on || a.inactiveTip().getString().isEmpty() ? null : Tooltip.create(a.inactiveTip()));
                });
            }
            case Control.Color c -> {
                ColorSwatch b = new ColorSwatch(o.title(), width, c, host);
                yield new Built(b, () -> {
                    b.refresh();
                    b.active = o.enabled().getAsBoolean();
                });
            }
            case Control.Custom c -> {
                AbstractWidget w = c.widget().apply(host, width);
                yield new Built(w, () -> w.active = o.enabled().getAsBoolean());
            }
        };
    }

    private static <T> Built choice(Control.Choice<T> c, Option o, int width, Host host) {
        if (SegmentedChoice.fits(c, width)) {
            SegmentedChoice<T> s = new SegmentedChoice<>(o.title(), width, c, host);
            return new Built(s, () -> s.active = o.enabled().getAsBoolean());
        }
        DropdownChoice<T> d = new DropdownChoice<>(o.title(), width, c, host);
        return new Built(d, () -> {
            d.refresh();
            d.active = o.enabled().getAsBoolean();
        });
    }

    /** On in green, Off in red, for text that names a switch's state. */
    public static Component onOff(boolean on) {
        return Component.translatable(on ? "skycosmetics.menu.on" : "skycosmetics.menu.off")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
