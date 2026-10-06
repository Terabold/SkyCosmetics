package io.github.terabold.skycosmetics.gui.hub;

/** What can show an {@link Overlay}: the settings screen. A widget whose host isn't one simply opens nothing. */
public interface OverlayHost {
    /** Shows it over everything, closing any other overlay first. */
    void openOverlay(Overlay overlay);
}
