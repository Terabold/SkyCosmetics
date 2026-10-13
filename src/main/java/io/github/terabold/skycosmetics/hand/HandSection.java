package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Hand section of the settings: the options for every pose, the list of poses (edit, remove, add), presets,
 * and share codes. Poses themselves are edited in {@link HandScreen}, next to your hand.
 */
public final class HandSection {
    public static final String ID = "hand";
    private static int presetPage;

    private HandSection() {}

    static void register() {
        Hub.add(new Section(ID, Section.FEATURE, tr("skycosmetics.menu.section.hand"), tr("skycosmetics.menu.section.hand.sub"),
            tr("skycosmetics.menu.section.hand.tooltip"), () -> new ItemStack(Items.DIAMOND_SWORD), HandSection::rows));
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        var overlaps = io.github.terabold.skycosmetics.compat.Overlaps.note(ID); if (overlaps != null) rows.add(overlaps);
        rows.add(Option.header("handGroup", tr("skycosmetics.hand.group.hand")));
        rows.add(toggle("handEnabled", Hand::enabled, Hand::setEnabled).search(tr("skycosmetics.hand.search").getString()));
        rows.add(Option.of("handEdit", Component.empty(), tr("skycosmetics.hand.option.edit.tooltip"),
            new Control.Action(tr("skycosmetics.hand.option.edit"), () -> edit(settings, Hand.EVERYTHING), HandSection::inWorld,
                tr("skycosmetics.hand.option.edit.noWorld"))).enabledWhen(Hand::enabled));

        rows.add(Option.header("handSwing", tr("skycosmetics.hand.group.swing")));
        rows.add(toggle("handFullSwings", Hand::fullSwings, Hand::setFullSwings));
        rows.add(toggle("handIgnoreEffects", Hand::ignoreEffects, Hand::setIgnoreEffects));

        rows.add(Option.header("handEquip", tr("skycosmetics.hand.group.equip")));
        rows.add(Option.of("handEquipAnimation", tr("skycosmetics.hand.option.handEquipAnimation"),
            tr("skycosmetics.hand.option.handEquipAnimation.tooltip"), new Control.Choice<>(List.of(Hand.Equip.values()),
                e -> tr("skycosmetics.hand.equip." + e.key), Hand::equip, Hand::setEquip)));
        rows.add(Option.of("handSway", tr("skycosmetics.hand.option.handSway"), tr("skycosmetics.hand.option.handSway.tooltip"),
            new Control.Slider(Hand::sway, v -> Hand.setSway((float) v), 0, 2, 0.05, HandSection::swayLabel)));
        rows.add(toggle("handEmptyAndMaps", Hand::emptyAndMaps, Hand::setEmptyAndMaps));

        rows.add(Option.header("handPresets", tr("skycosmetics.hand.group.presets")));
        rows.add(Option.of("handSavedSlots", Component.empty(), Component.empty(), new Control.Custom((host, width) -> {
            var row = new ButtonRow(width, width >= 380 ? 6 : 3, 32);
            var setups = Hand.presets().stream().filter(p -> !p.name().equals(Hand.PREVIOUS)).toList();
            int pages = Math.max(1, (setups.size() + 1 + 5) / 6);
            presetPage = Math.clamp(presetPage, 0, pages - 1);
            for (int i = 0; i < 6; i++) {
                int index = presetPage * 6 + i;
                if (index < setups.size()) {
                    var preset = setups.get(index);
                    var tile = new io.github.terabold.skycosmetics.gui.hub.ActionButton(Component.literal(preset.name()), 70, 32,
                        io.github.terabold.skycosmetics.gui.hub.ActionButton.Kind.SECONDARY, b -> {
                            Hand.apply(preset.setup().deepCopy()); Hand.save(); rebuild(settings);
                        }) {
                        @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
                            if (event.button() == 1 && active && visible && isMouseOver(event.x(), event.y())) {
                                Minecraft.getInstance().setScreen(new HandPresetScreen(settings, preset.name())); return true;
                            }
                            return super.mouseClicked(event, doubleClick);
                        }
                    };
                    tile.setTooltip(Tooltip.create(Component.literal(preset.name() + "\nClick to apply. Right-click to edit.")));
                    row.add(tile, 0);
                } else {
                    var tile = new io.github.terabold.skycosmetics.gui.hub.ActionButton(Component.literal("+"), 70, 32,
                        io.github.terabold.skycosmetics.gui.hub.ActionButton.Kind.SECONDARY, b -> {
                            var saved = Hand.savePreset(null); Hand.save();
                            var all = Hand.presets().stream().filter(p -> !p.name().equals(Hand.PREVIOUS)).toList();
                            presetPage = all.indexOf(saved) / 6; rebuild(settings);
                        });
                    tile.setTooltip(Tooltip.create(Component.literal("Save the current viewmodel setup")));
                    row.add(tile, 0, () -> Hand.presets().size() < Hand.MAX_PRESETS);
                }
            }
            return row;
        })));
        if (Hand.presets().stream().filter(p -> !p.name().equals(Hand.PREVIOUS)).count() >= 6) {
            rows.add(Option.of("handSlotPages", Component.empty(), Component.empty(), new Control.Custom((host, width) -> {
                var row = new ButtonRow(width);
                row.add(Button.builder(Component.literal("Previous slots"), b -> { presetPage--; rebuild(settings); }).build(), 0, () -> presetPage > 0);
                row.add(Button.builder(Component.literal("Next slots"), b -> { presetPage++; rebuild(settings); }).build(), 0,
                    () -> (presetPage + 1) * 6 <= Hand.presets().stream().filter(p -> !p.name().equals(Hand.PREVIOUS)).count());
                return row;
            })));
        }
        rows.add(Option.of("handSetupTools", Component.empty(), Component.empty(), new Control.Custom((host, width) -> {
            var row = new ButtonRow(width);
            row.add(new io.github.terabold.skycosmetics.gui.hub.ActionButton(Component.literal("Edit slots"), 100, false,
                b -> Minecraft.getInstance().setScreen(new HandPresetScreen(settings, null))), 0,
                () -> !Hand.presets().isEmpty());
            row.add(new io.github.terabold.skycosmetics.gui.hub.ActionButton(Component.literal("Share"), 100, false,
                b -> Minecraft.getInstance().setScreen(new HandCodeScreen(settings))), 0);
            row.add(new io.github.terabold.skycosmetics.gui.hub.ActionButton(Component.literal("Reset"), 100, false,
                b -> { Hand.apply(new JsonObject()); Hand.save(); rebuild(settings); }), 0);
            return row;
        })));
        return rows.stream().map(Option::compact).toList();
    }

    private static Component swayLabel(double v) {
        if (v == 0) return tr("skycosmetics.menu.off");
        if (v == 1) return tr("skycosmetics.hand.sway.normal");
        return Component.literal(Math.round(v * 100) + "%");
    }

    // ------------------------------------------------------------ helpers ---

    private static boolean inWorld() {
        return Minecraft.getInstance().player != null;
    }

    /** Opens the live editor on a pose, coming back to these settings. */
    private static void edit(Screen settings, String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setScreen(new HandScreen(settings, key));
    }

    /** Builds the section again (a pose or preset added, a setup applied), after the click that asked for it. */
    private static void rebuild(Screen settings) {
        Minecraft mc = Minecraft.getInstance();
        mc.schedule(() -> {
            if (mc.screen == settings) settings.resize(settings.width, settings.height);
        });
    }

    private static Option toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
        return Option.of(id, tr("skycosmetics.hand.option." + id), tr("skycosmetics.hand.option." + id + ".tooltip"),
            new Control.Toggle(get, set));
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
