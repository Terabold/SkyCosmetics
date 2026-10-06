package io.github.terabold.skycosmetics.hub;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** What a row edits. Each kind has exactly one widget, built by {@code gui.hub.Controls}. */
public sealed interface Control {
    /** A group title; no control. */
    record Header() implements Control {}

    /** On / Off. The setter applies the change; the settings screen saves after it. */
    record Toggle(BooleanSupplier get, Consumer<Boolean> set) implements Control {}

    /**
     * A number from {@code min} to {@code max} in steps of {@code step}. Every step applies live; the
     * settings save when the drag ends.
     */
    record Slider(DoubleSupplier get, DoubleConsumer set, double min, double max, double step,
                  DoubleFunction<Component> label) implements Control {}

    /** One of several values: side by side when up to four short ones fit, else a drop-down list. */
    record Choice<T>(List<T> values, Function<T, Component> label, Supplier<T> get, Consumer<T> set) implements Control {}

    /** A key mapping, bound in place: click, then press a key. */
    record Key(Supplier<KeyMapping> mapping) implements Control {}

    /** Does something now, e.g. Open Studio; grayed out with {@code inactiveTip} when {@code active} is false. */
    record Action(Component label, Runnable run, BooleanSupplier active, Component inactiveTip) implements Control {}

    /** A feature's own widget (a preview, preset swatches...), given the settings screen and the row width. */
    record Custom(BiFunction<Host, Integer, AbstractWidget> widget) implements Control {}

    /**
     * A color: a swatch that opens a color picker next to it. Values are ARGB. Without {@code alpha} the picker
     * has no opacity bar and {@code set} always gets an opaque color. Every change applies live (drag through the
     * square and a preview follows); the settings save when a drag ends and when the picker closes.
     */
    record Color(IntSupplier get, IntConsumer set, boolean alpha) implements Control {
        /** An opaque color, without the opacity bar. */
        public Color(IntSupplier get, IntConsumer set) {
            this(get, set, false);
        }
    }
}
