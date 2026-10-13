package io.github.terabold.skycosmetics.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.update.Releases;
import io.github.terabold.skycosmetics.update.Updater;
import net.minecraft.network.chat.Component;

/**
 * Mod Menu's Configure button opens the same settings as {@code /skycosmetics}, and its update badge shows a newer
 * SkyCosmetics from the same check (GitHub Releases; never Modrinth). Only Mod Menu loads this class (through the
 * "modmenu" entrypoint), so SkyCosmetics runs the same without it; nothing else may name Mod Menu. Mod Menu asks for
 * the factory during its own init, maybe before SkyCosmetics': it must build nothing here.
 */
public final class ModMenuCompat implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::create;
    }

    /** On Mod Menu's own thread: waits for SkyCosmetics' check (at most 20 seconds); nothing when checks are off. */
    @Override
    public UpdateChecker getUpdateChecker() {
        return () -> {
            Releases.Offer offer = Updater.awaitOffer(20_000);
            return offer == null ? null : new Info(offer);
        };
    }

    private record Info(Releases.Offer offer) implements UpdateInfo {
        @Override
        public boolean isUpdateAvailable() {
            return true;
        }

        @Override
        public Component getUpdateMessage() {
            return Component.translatable("skycosmetics.update.toast.available", offer.version());
        }

        @Override
        public String getDownloadLink() {
            return offer.release().url();
        }

        @Override
        public UpdateChannel getUpdateChannel() {
            return offer.release().prerelease() ? UpdateChannel.BETA : UpdateChannel.RELEASE;
        }
    }
}
