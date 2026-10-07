package io.github.terabold.skycosmetics.gui;

import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.hub.ColorPresets;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * General, first in the settings' sidebar: the accent color of the settings and the studio (switches, sliders,
 * selections, headings, highlights). Stored as {@code "accent": "#RRGGBB"} in settings.json; a value that isn't a
 * color keeps the current one.
 */
public final class GeneralSection {
    public static final String ID = "general";
    private static final String[] PRESET_KEYS = {"cyan", "blue", "purple", "pink", "green", "gold", "red", "white"};
    /** The presets' colors, in {@link #PRESET_KEYS} order; Purple is the accent SkyCosmetics had before. */
    private static final int[] PRESET_RGB = {0x22D3EE, 0x60A5FA, 0xD58CFF, 0xF472B6, 0x4ADE80, 0xFBBF24, 0xF87171, 0xFFFFFF};

    private GeneralSection() {}

    public static void register() {
        Settings.register("accent", v -> {
            Integer rgb = parse(Settings.str(v, null));
            if (rgb != null) Theme.setAccent(rgb);
        }, () -> new JsonPrimitive(hex(Theme.accent())));
        Hub.add(new Section(ID, Section.GENERAL, Component.translatable("skycosmetics.menu.section.general"),
            Component.translatable("skycosmetics.menu.section.general.sub"),
            Component.translatable("skycosmetics.menu.section.general.tooltip"),
            () -> new ItemStack(dye(nearest(Theme.accent()))), GeneralSection::rows));
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.header("groupAppearance", Component.translatable("skycosmetics.menu.group.appearance")));
        rows.add(Option.of("accent", Component.translatable("skycosmetics.option.accent"),
            Component.translatable("skycosmetics.option.accent.tooltip"), new Control.Color(Theme::accent, Theme::setAccent))
            .search(Component.translatable("skycosmetics.option.accent.search").getString()));
        Component presetsTitle = Component.translatable("skycosmetics.option.accentPresets");
        rows.add(Option.of("accentPresets", presetsTitle, Component.translatable("skycosmetics.option.accentPresets.tooltip"),
            new Control.Custom((host, width) -> new ColorPresets(presetsTitle, width, presets(), Theme::accent,
                Theme::setAccent, host)))
            .search(String.join(" ", presetNames())));
        rows.add(Option.of("resetAccent", Component.translatable("skycosmetics.option.resetAccent"),
            Component.translatable("skycosmetics.option.resetAccent.tooltip"),
            new Control.Action(Component.translatable("skycosmetics.option.resetAccent.button"), () -> {
                Theme.setAccent(Theme.DEFAULT_ACCENT);
                if (settings instanceof Host host) host.changed(); // saves, and the swatch and pills show it
                else Settings.save();
            }, () -> Theme.accent() != Theme.DEFAULT_ACCENT, Component.empty())));
        return rows;
    }

    private static List<ColorPresets.Preset> presets() {
        List<ColorPresets.Preset> out = new ArrayList<>();
        for (int i = 0; i < PRESET_KEYS.length; i++) {
            out.add(new ColorPresets.Preset(Component.translatable("skycosmetics.accent." + PRESET_KEYS[i]), PRESET_RGB[i]));
        }
        return out;
    }

    private static List<String> presetNames() {
        List<String> out = new ArrayList<>();
        for (String k : PRESET_KEYS) out.add(Component.translatable("skycosmetics.accent." + k).getString());
        return out;
    }

    /** The section's icon: the dye of a preset, built when the settings open. */
    private static Item dye(int preset) {
        return switch (preset) {
            case 0 -> Items.CYAN_DYE;
            case 1 -> Items.LIGHT_BLUE_DYE;
            case 2 -> Items.PURPLE_DYE;
            case 3 -> Items.PINK_DYE;
            case 4 -> Items.LIME_DYE;
            case 5 -> Items.YELLOW_DYE;
            case 6 -> Items.RED_DYE;
            default -> Items.WHITE_DYE;
        };
    }

    /** The preset closest to a color, by distance in RGB. */
    private static int nearest(int rgb) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < PRESET_RGB.length; i++) {
            int dr = (rgb >> 16 & 0xFF) - (PRESET_RGB[i] >> 16 & 0xFF), dg = (rgb >> 8 & 0xFF) - (PRESET_RGB[i] >> 8 & 0xFF);
            int db = (rgb & 0xFF) - (PRESET_RGB[i] & 0xFF);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** "#RRGGBB" or "RRGGBB" to its color; null for anything else. */
    static Integer parse(String s) {
        if (s == null) return null;
        String hex = s.strip();
        if (hex.startsWith("#")) hex = hex.substring(1);
        return hex.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(hex, 16) : null;
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }
}
