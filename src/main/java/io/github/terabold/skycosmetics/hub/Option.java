package io.github.terabold.skycosmetics.hub;

import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * One row of a settings section: a title, its control and optional inline help. Rows are built when the
 * settings open, so values are read then and after each change, never per frame.
 *
 * @param id      stable name for tests and links; never shown, never a settings.json key
 * @param search  extra words a search may match, e.g. "skull scale" for Head Icon Size
 * @param enabled false grays the row's control out (asked again after every change)
 */
public record Option(String id, Component title, Component help, Control control, String search, BooleanSupplier enabled,
                     boolean inlineHelp, BooleanSupplier visible, boolean half) {
    public Option(String id, Component title, Component help, Control control, String search, BooleanSupplier enabled) {
        this(id, title, help, control, search, enabled, true, () -> true, false);
    }

    public static Option of(String id, Component title, Component help, Control control) {
        return new Option(id, title, help, control, "", () -> true);
    }

    /** A group title such as "My Items". */
    public static Option header(String id, Component title) {
        return of(id, title, Component.empty(), new Control.Header());
    }

    public Option search(String words) {
        return new Option(id, title, help, control, words, enabled, inlineHelp, visible, half);
    }

    public Option enabledWhen(BooleanSupplier when) {
        return new Option(id, title, help, control, search, when, inlineHelp, visible, half);
    }

    /** Obvious controls keep their full explanation in hover help, narration and search results. */
    public Option compact() {
        return new Option(id, title, help, control, search, enabled, false, visible, half);
    }

    public Option visibleWhen(BooleanSupplier when) {
        return new Option(id, title, help, control, search, enabled, inlineHelp, when, half);
    }

    /** Two compact controls share a row when the pane has room; narrow windows stack normally. */
    public Option beside() {
        return new Option(id, title, help, control, search, enabled, false, visible, true);
    }

    public Option fullWidth() {
        return new Option(id, title, help, control, search, enabled, inlineHelp, visible, false);
    }
}
