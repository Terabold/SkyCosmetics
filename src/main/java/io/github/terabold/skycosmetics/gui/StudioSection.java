package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The studio's own section of the settings, first in the sidebar: Open Studio, then every setting the old
 * settings screen had, under short Title Case names. Their fields don't change (settings.json and
 * looks.json read as before).
 */
public final class StudioSection {
    private StudioSection() {}

    public static void register() {
        Hub.add(new Section(Hub.STUDIO, Section.FIRST, Component.translatable("skycosmetics.menu.section.studio"),
            Component.translatable("skycosmetics.menu.section.studio.sub"),
            Component.translatable("skycosmetics.menu.section.studio.tooltip"),
            () -> new ItemStack(Items.BRUSH), StudioSection::rows));
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.header("groupStudio", Component.translatable("skycosmetics.menu.group.studio")));
        rows.add(Option.of("openStudio", Component.empty(), Component.translatable("skycosmetics.option.openStudio.tooltip"),
            openStudio(settings)));
        rows.add(Option.of("openKey", Component.translatable("skycosmetics.option.openKey"),
            Component.translatable("skycosmetics.option.openKey.tooltip"), new Control.Key(SkyCosmetics::openKey))
            .search(Component.translatable("skycosmetics.option.openKey.search").getString()));
        rows.add(toggle("inventoryButton", () -> Settings.brush, v -> Settings.brush = v));
        rows.add(toggle("learnSkins", () -> Settings.learnSkins, v -> Settings.learnSkins = v));

        rows.add(Option.header("groupMyItems", Component.translatable("skycosmetics.menu.group.myItems")));
        rows.add(toggle("storedItems", () -> Settings.storedItems, v -> {
            Settings.storedItems = v;
            OwnedItems.refresh();
        }).search(Component.translatable("skycosmetics.option.storedItems.search").getString()));
        rows.add(toggle("allProfiles", () -> Settings.allProfiles, v -> {
            Settings.allProfiles = v;
            OwnedItems.refresh();
        }).enabledWhen(() -> Settings.storedItems));

        rows.add(Option.header("groupWhereLooksShow", Component.translatable("skycosmetics.menu.group.whereLooksShow")));
        rows.add(toggle("worldReskin", () -> Settings.worldReskin, v -> Settings.worldReskin = v));
        rows.add(toggle("typeLooksOnOthers", () -> Looks.typeLooksOnOthers, v -> {
            Looks.typeLooksOnOthers = v;
            Looks.bump();
            Looks.save();
        }));
        rows.add(toggle("namesInOtherMods", () -> Settings.namesInOtherMods, v -> Settings.namesInOtherMods = v));
        return rows;
    }

    /** A toggle named by its lang keys: {@code skycosmetics.option.<id>} and its {@code .tooltip}. */
    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        return Option.of(id, Component.translatable("skycosmetics.option." + id),
            Component.translatable("skycosmetics.option." + id + ".tooltip"), new Control.Toggle(get, set));
    }

    /**
     * Opens the studio on your held item (or helmet). From a studio's Settings button it reads Back to Studio
     * and returns to that same studio, so studios never stack. The studio needs a player for its preview.
     */
    private static Control openStudio(Screen settings) {
        Screen parent = settings instanceof SettingsScreen s ? s.parent() : null;
        if (parent instanceof StudioScreen) {
            return new Control.Action(Component.translatable("skycosmetics.option.openStudio.back"), settings::onClose,
                () -> true, Component.empty());
        }
        return new Control.Action(Component.translatable("skycosmetics.option.openStudio"), () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.setScreen(new StudioScreen(settings, SkyCosmetics.heldOrHelmet(mc)));
        }, () -> Minecraft.getInstance().player != null, Component.translatable("skycosmetics.option.openStudio.noWorld"));
    }
}
