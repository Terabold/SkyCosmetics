package io.github.terabold.skycosmetics.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.terabold.skycosmetics.gui.SettingsScreen;

/**
 * Mod Menu's Configure button opens the same settings as {@code /skycosmetics}. Only Mod Menu loads this class
 * (through the "modmenu" entrypoint), so SkyCosmetics runs the same without it; nothing else may name Mod Menu.
 * Mod Menu asks for the factory during its own init, maybe before SkyCosmetics': it must build nothing here.
 */
public final class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::create;
    }
}
