package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Option;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Turns each {@link Control} kind into its widget, plus how to show the value again after a change. */
public final class Controls {
    /** A row's widget and its refresh: label, grayed-out state, tooltip. */
    public record Built(AbstractWidget widget, Runnable refresh) {}

    private Controls() {}

    /**
     * @param keyChanged runs when a key row's state changes (its help line grows or shrinks)
     * @return null for a header, which has no control
     */
    public static Built build(Option o, int width, Host host, Runnable keyChanged) {
        return switch (o.control()) {
            case Control.Header h -> null;
            case Control.Toggle t -> {
                Button b = Button.builder(onOff(t.get().getAsBoolean()), btn -> {
                    t.set().accept(!t.get().getAsBoolean());
                    host.changed();
                }).width(width).build();
                yield new Built(b, () -> {
                    b.setMessage(onOff(t.get().getAsBoolean()));
                    b.active = o.enabled().getAsBoolean();
                });
            }
            case Control.Slider s -> {
                OptionSlider slider = new OptionSlider(width, s, host);
                yield new Built(slider, () -> slider.active = o.enabled().getAsBoolean());
            }
            case Control.Choice<?> c -> choice(c, o, width, host);
            case Control.Key k -> {
                KeyBindButton b = new KeyBindButton(width, k.mapping(), keyChanged);
                yield new Built(b, () -> b.active = o.enabled().getAsBoolean());
            }
            case Control.Action a -> {
                Button b = Button.builder(a.label(), btn -> a.run().run()).width(width).build();
                yield new Built(b, () -> {
                    boolean on = a.active().getAsBoolean() && o.enabled().getAsBoolean();
                    b.active = on;
                    b.setTooltip(on ? null : Tooltip.create(a.inactiveTip()));
                });
            }
            case Control.Custom c -> {
                AbstractWidget w = c.widget().apply(host, width);
                yield new Built(w, () -> w.active = o.enabled().getAsBoolean());
            }
        };
    }

    private static <T> Built choice(Control.Choice<T> c, Option o, int width, Host host) {
        Button b = Button.builder(c.label().apply(c.get().get()), btn -> {
            int i = c.values().indexOf(c.get().get());
            c.set().accept(c.values().get((i + 1) % c.values().size()));
            host.changed();
        }).width(width).build();
        return new Built(b, () -> {
            b.setMessage(c.label().apply(c.get().get()));
            b.active = o.enabled().getAsBoolean();
        });
    }

    /** On in green, Off in red, as the settings always showed them. */
    public static Component onOff(boolean on) {
        return Component.translatable(on ? "skycosmetics.menu.on" : "skycosmetics.menu.off")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
