package io.github.terabold.skycosmetics.compat;

import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Option;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Installed mods that have a feature of their own doing what one of ours does. Two of them on at once stack (a
 * viewmodel moved twice) or fight over who draws last, so the section says which ones are installed and what their
 * feature is called. Nothing of theirs is read, changed or overridden: which one stays on is the player's choice.
 */
public final class Overlaps {
    private record Other(String modId, String mod, String feature) {}

    private static final Map<String, List<Other>> BY_SECTION = Map.of(
        "hand", List.of(new Other("nofrills", "NoFrills", "Viewmodel"), new Other("devonian", "Devonian", "Item Animations"),
            new Other("noammaddons", "NoammAddons", "Animations")),
        "nametags", List.of(new Other("devonian", "Devonian", "nametag options"), new Other("noammaddons", "NoammAddons", "Name Tag Tweaks")),
        "rarityBackgrounds", List.of(new Other("skyblocker", "Skyblocker", "Item Rarity Background"),
            new Other("firmament", "Firmament", "Item Rarity Cosmetics"), new Other("skyhanni", "SkyHanni", "Rarity Background"),
            new Other("noammaddons", "NoammAddons", "Item Rarity"), new Other("skyblock_enhancements", "Skyblock Enhancements", "Item Rarity")),
        "tooltips", List.of(new Other("skyblock_enhancements", "Skyblock Enhancements", "Tooltip Scroll")),
        "slotLocks", List.of(new Other("skyblockaddons", "SkyblockAddons", "Lock Slots"), new Other("firmament", "Firmament", "Slot Locking"),
            new Other("nofrills", "NoFrills", "Item Protection"), new Other("skyblocker", "Skyblocker", "Item Protection")));

    private Overlaps() {}

    /** A note for the top of a section, or null when no mod with the same kind of feature is installed. */
    public static Option note(String section) {
        List<String> found = new ArrayList<>();
        for (Other o : BY_SECTION.getOrDefault(section, List.of())) {
            if (FabricLoader.getInstance().isModLoaded(o.modId())) found.add(o.mod() + " (" + o.feature() + ")");
        }
        if (found.isEmpty()) return null;
        Component text = Component.literal("Also installed: " + String.join(", ", found)
            + ". If both are on, things can show twice; turn one of them off.").withColor(Theme.MUTED & 0xFFFFFF);
        return Option.of(section + ".overlaps", Component.empty(), Component.empty(), new Control.Custom((host, width) ->
            new MultiLineTextWidget(text, Minecraft.getInstance().font).setMaxWidth(width)));
    }
}
