package io.github.terabold.skycosmetics.hub;

import io.github.terabold.skycosmetics.gui.ColorPopup;

/** What a row's widget may ask of the settings screen that shows it. */
public interface Host {
    /** Saves the settings and refreshes every row's value and grayed-out state. */
    void changed();

    /** Shows a color pop-up over the screen; it gets every click and key until it closes. */
    void openPopup(ColorPopup popup);
}
