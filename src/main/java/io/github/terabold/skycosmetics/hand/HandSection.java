package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Hand section of the settings: the options for every pose, the list of poses (edit, remove, add), presets,
 * imports from other mods and share codes. Poses themselves are edited in {@link HandScreen}, next to your hand.
 */
public final class HandSection {
    public static final String ID = "hand";
    /** How long a button shows what it just did ("Copied") before its label comes back. */
    private static final long FLASH_MS = 2500;

    private HandSection() {}

    static void register() {
        Hub.add(new Section(ID, Section.FEATURE, tr("skycosmetics.menu.section.hand"), tr("skycosmetics.menu.section.hand.sub"),
            tr("skycosmetics.menu.section.hand.tooltip"), () -> new ItemStack(Items.DIAMOND_SWORD), HandSection::rows));
    }

    private static List<Option> rows(Screen settings) {
        List<Option> rows = new ArrayList<>();
        rows.add(Option.header("handGroup", tr("skycosmetics.hand.group.hand")));
        rows.add(toggle("handEnabled", Hand::enabled, Hand::setEnabled).search(tr("skycosmetics.hand.search").getString()));
        rows.add(Option.of("handEdit", Component.empty(), tr("skycosmetics.hand.option.edit.tooltip"),
            new Control.Action(tr("skycosmetics.hand.option.edit"), () -> edit(settings, Hand.EVERYTHING), HandSection::inWorld,
                tr("skycosmetics.hand.option.edit.noWorld"))).enabledWhen(Hand::enabled));

        rows.add(Option.header("handPoses", tr("skycosmetics.hand.group.poses")));
        for (Hand.Rule r : Hand.rules()) rows.add(ruleRow(settings, r));
        rows.add(addRow(settings));

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
        for (Hand.Preset p : Hand.presets()) rows.add(presetRow(settings, p));
        rows.add(Option.of("handVanilla", tr("skycosmetics.hand.option.handVanilla"), tr("skycosmetics.hand.option.handVanilla.tooltip"),
            new Control.Action(tr("skycosmetics.hand.option.handVanilla.use"), () -> {
                Hand.apply(new JsonObject());
                Hand.save();
                rebuild(settings);
            }, () -> !Hand.isVanillaSetup(), tr("skycosmetics.hand.option.handVanilla.already"))));
        rows.add(saveRow(settings));

        rows.add(Option.header("handImport", tr("skycosmetics.hand.group.import")));
        for (HandImport.Source s : HandImport.Source.values()) rows.add(importRow(settings, s));
        rows.add(shareRow(settings));
        return rows;
    }

    // -------------------------------------------------------------- poses ---

    /** A pose: its name, Edit and Remove (Undo after), and what it changes under it. */
    private static Option ruleRow(Screen settings, Hand.Rule r) {
        boolean everything = r.key().equals(Hand.EVERYTHING);
        Hand.Rule[] removed = {null};
        Control control = new Control.Custom((host, w) -> {
            ButtonRow row = new ButtonRow(w);
            Button edit = Button.builder(tr("skycosmetics.hand.rule.edit"), b -> edit(settings, r.key())).build();
            edit.setTooltip(Tooltip.create(tr(inWorld() ? "skycosmetics.hand.rule.edit.tooltip" : "skycosmetics.hand.option.edit.noWorld")));
            row.add(edit, 0, () -> removed[0] == null && inWorld());
            if (!everything) {
                Button remove = Button.builder(tr("skycosmetics.hand.rule.remove"), b -> {
                    if (removed[0] == null) {
                        removed[0] = Hand.rule(r.key());
                        Hand.removeRule(r.key());
                        edit.setMessage(tr("skycosmetics.hand.rule.removed").withStyle(ChatFormatting.GRAY));
                        b.setMessage(tr("skycosmetics.hand.undo"));
                    } else {
                        Hand.restoreRule(removed[0]);
                        removed[0] = null;
                        edit.setMessage(tr("skycosmetics.hand.rule.edit"));
                        b.setMessage(tr("skycosmetics.hand.rule.remove"));
                    }
                    Hand.save();
                }).build();
                row.add(remove, Math.min(70, w / 3));
            }
            return row;
        });
        MutableComponent help = everything ? tr("skycosmetics.hand.rule.everything.help", summary(r.pose(), false))
            : r.key().startsWith(Hand.ITEM) ? tr("skycosmetics.hand.rule.itemOnly", summary(r.pose(), false))
            : summary(r.pose(), true);
        return Option.of("handRule:" + r.key(), Hand.label(r.key()), help, control);
    }

    /** Add a Pose: what to add (kinds without a pose, then your SkyBlock items without one) and Add. */
    private static Option addRow(Screen settings) {
        Map<String, Component> can = addable();
        List<String> keys = new ArrayList<>(can.keySet());
        int[] at = {0};
        Control control = new Control.Custom((host, w) -> {
            ButtonRow row = new ButtonRow(w);
            Button pick = Button.builder(pickLabel(can, keys, at[0]), b -> {
                at[0] = (at[0] + 1) % Math.max(1, keys.size());
                b.setMessage(pickLabel(can, keys, at[0]));
            }).build();
            pick.setTooltip(Tooltip.create(tr("skycosmetics.hand.add.pick.tooltip")));
            row.add(pick, 0, () -> keys.size() > 1);
            row.add(Button.builder(tr("skycosmetics.hand.add"), b -> {
                String key = keys.get(at[0]);
                Hand.Rule added = Hand.addRule(key, key.startsWith(Hand.ITEM) ? can.get(key).getString() : null);
                Hand.save();
                if (added != null && inWorld()) edit(settings, key);
                else rebuild(settings);
            }).build(), Math.min(60, w / 3), () -> !keys.isEmpty());
            return row;
        });
        return Option.of("handAdd", tr("skycosmetics.hand.add.title"), tr("skycosmetics.hand.add.tooltip"), control);
    }

    private static Component pickLabel(Map<String, Component> can, List<String> keys, int at) {
        if (keys.isEmpty()) return tr("skycosmetics.hand.add.nothing");
        return can.get(keys.get(at)).copy().append(keys.size() > 1 ? "  >" : "");
    }

    /** Kinds without a pose yet, then the SkyBlock items you hold or carry without one. */
    static Map<String, Component> addable() {
        Map<String, Component> out = new LinkedHashMap<>();
        for (HandCategory c : HandCategory.values()) if (Hand.rule(c.key) == null) out.put(c.key, c.label());
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return out;
        try {
            List<ItemStack> mine = new ArrayList<>();
            mine.add(p.getMainHandItem());
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) mine.add(p.getInventory().getItem(i));
            for (ItemStack s : mine) {
                Cosmetics.Ident id = s.isEmpty() ? null : Cosmetics.identify(s);
                if (id == null || id.type() == null || id.type().startsWith("PET")) continue;
                String key = Hand.ITEM + id.type();
                if (out.containsKey(key) || Hand.rule(key) != null || !Hand.validKey(key)) continue;
                String name = Hand.cleanName(Cosmetics.originalName(s).getString(), key);
                out.put(key, tr("skycosmetics.hand.add.item", Hand.cleanName(stripReforge(name, key), key)));
            }
        } catch (RuntimeException e) {
            Io.failed("Listing items for a hand pose", e);
        }
        return out;
    }

    /**
     * The name for an item rule: Hypixel's name minus what one copy of the item has and another doesn't (stars,
     * a reforge before the base name). When the name doesn't end with the id's words, the id's words are used.
     */
    static String stripReforge(String name, String key) {
        String fromId = Hand.cleanName(null, key);
        String plain = name.replaceAll("[^\\p{L}\\p{N}' -]", "").replaceAll("\\s+", " ").strip();
        String[] words = plain.split(" "), idWords = fromId.split(" ");
        if (words.length < idWords.length) return fromId;
        StringBuilder tail = new StringBuilder();
        for (int i = words.length - idWords.length; i < words.length; i++) {
            if (!tail.isEmpty()) tail.append(' ');
            tail.append(words[i]);
        }
        return tail.toString().equalsIgnoreCase(fromId) ? tail.toString() : fromId;
    }

    /** "Moved, turned, size 0.61x, in-place swing", or "As Minecraft"; lowercase first for use inside a sentence. */
    static MutableComponent summary(HandPose p, boolean capital) {
        List<Component> parts = new ArrayList<>();
        if (p == null || p.isVanilla()) parts.add(tr("skycosmetics.hand.summary.vanilla"));
        else describe(p, parts);
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(i == 0 && capital ? capitalize(parts.get(i)) : parts.get(i));
        }
        return out;
    }

    private static void describe(HandPose p, List<Component> parts) {
        if (p.offset) parts.add(tr("skycosmetics.hand.summary.moved"));
        if (p.turned) parts.add(tr("skycosmetics.hand.summary.turned"));
        if (p.resized) {
            boolean uniform = p.scaleX == p.scaleY && p.scaleY == p.scaleZ;
            parts.add(uniform ? tr("skycosmetics.hand.summary.size", PoseSlider.trim(p.scaleX) + "x")
                : tr("skycosmetics.hand.summary.stretched"));
        }
        if (p.swing != HandPose.Swing.MOVING) parts.add(tr("skycosmetics.hand.summary.swing." + p.swing.key));
        else if (p.swingScaled) parts.add(tr("skycosmetics.hand.summary.swingDistance"));
        if (p.swing != HandPose.Swing.NONE && p.speed() != 1) {
            parts.add(tr("skycosmetics.hand.summary.speed", PoseSlider.trim(p.speed()) + "x"));
        }
        if (p.swing != HandPose.Swing.NONE && p.swingTurned) parts.add(tr("skycosmetics.hand.summary.swingTurn"));
        if (parts.isEmpty()) parts.add(tr("skycosmetics.hand.summary.pivot")); // only the pivot differs
    }

    private static Component capitalize(Component c) {
        String s = c.getString();
        return s.isEmpty() ? c : Component.literal(Character.toUpperCase(s.charAt(0)) + s.substring(1));
    }

    private static Component swayLabel(double v) {
        if (v == 0) return tr("skycosmetics.menu.off");
        if (v == 1) return tr("skycosmetics.hand.sway.normal");
        return Component.literal(Math.round(v * 100) + "%");
    }

    // ------------------------------------------------------------ presets ---

    /** A saved preset: Use and Delete (Undo after). */
    private static Option presetRow(Screen settings, Hand.Preset p) {
        Hand.Preset[] removed = {null};
        Control control = new Control.Custom((host, w) -> {
            ButtonRow row = new ButtonRow(w);
            Button use = Button.builder(tr("skycosmetics.hand.preset.use"), b -> {
                Hand.apply(p.setup().deepCopy());
                Hand.save();
                rebuild(settings);
            }).build();
            row.add(use, 0, () -> removed[0] == null);
            row.add(Button.builder(tr("skycosmetics.hand.rule.remove"), b -> {
                if (removed[0] == null) {
                    removed[0] = Hand.removePreset(p.name());
                    use.setMessage(tr("skycosmetics.hand.preset.removed").withStyle(ChatFormatting.GRAY));
                    b.setMessage(tr("skycosmetics.hand.undo"));
                } else {
                    Hand.restorePreset(removed[0]);
                    removed[0] = null;
                    use.setMessage(tr("skycosmetics.hand.preset.use"));
                    b.setMessage(tr("skycosmetics.hand.rule.remove"));
                }
                Hand.save();
            }).build(), Math.min(70, w / 3));
            return row;
        });
        Component help = p.name().equals(Hand.PREVIOUS) ? tr("skycosmetics.hand.preset.previous.tooltip")
            : summary(HandPose.fromJson(p.setup().get("pose")), true);
        return Option.of("handPreset:" + p.name(), Component.literal(p.name()), help, control);
    }

    /** Save as Preset: a name box and Save. */
    private static Option saveRow(Screen settings) {
        Control control = new Control.Custom((host, w) -> {
            ButtonRow row = new ButtonRow(w);
            Minecraft mc = Minecraft.getInstance();
            EditBox name = new EditBox(mc.font, 0, 0, 100, 20, tr("skycosmetics.hand.preset.name"));
            name.setMaxLength(Hand.MAX_NAME);
            name.setHint(tr("skycosmetics.hand.preset.name").withStyle(ChatFormatting.DARK_GRAY));
            row.add(name, 0);
            row.add(Button.builder(tr("skycosmetics.hand.preset.save"), b -> {
                Hand.savePreset(name.getValue());
                Hand.save();
                rebuild(settings);
            }).build(), Math.min(60, w / 3), () -> !Hand.isVanillaSetup());
            return row;
        });
        return Option.of("handSavePreset", tr("skycosmetics.hand.preset.saveTitle"), tr("skycosmetics.hand.preset.save.tooltip"), control);
    }

    // ----------------------------------------------------- import & share ---

    private static Option importRow(Screen settings, HandImport.Source s) {
        HandImport.Found found = HandImport.find(s);
        Component help = found == null ? tr("skycosmetics.hand.import.missing", s.name)
            : found.on() ? tr("skycosmetics.hand.import.on", s.name).withStyle(ChatFormatting.GOLD)
            : tr("skycosmetics.hand.import.found", s.name);
        return Option.of("handImport:" + s.modId, Component.literal(s.name), help,
            new Control.Action(tr("skycosmetics.hand.import"), () -> {
                Hand.apply(found.setup().deepCopy());
                Hand.save();
                rebuild(settings);
            }, () -> found != null, tr("skycosmetics.hand.import.missing", s.name)));
    }

    /** Copy Code and Paste Code; each says what happened on its own label for a moment. */
    private static Option shareRow(Screen settings) {
        Control control = new Control.Custom((host, w) -> {
            ButtonRow row = new ButtonRow(w);
            Minecraft mc = Minecraft.getInstance();
            row.add(flashing(tr("skycosmetics.hand.share.copy"), b -> {
                mc.keyboardHandler.setClipboard(HandImport.code(Hand.setup()));
                return tr("skycosmetics.hand.share.copied").withStyle(ChatFormatting.GREEN);
            }), 0);
            row.add(flashing(tr("skycosmetics.hand.share.paste"), b -> {
                JsonObject setup = HandImport.decode(mc.keyboardHandler.getClipboard());
                if (setup == null) return tr("skycosmetics.hand.share.notCode").withStyle(ChatFormatting.RED);
                Hand.apply(setup);
                Hand.save();
                rebuild(settings);
                return tr("skycosmetics.hand.share.pasted").withStyle(ChatFormatting.GREEN);
            }), 0);
            return row;
        });
        return Option.of("handShare", tr("skycosmetics.hand.share"), tr("skycosmetics.hand.share.tooltip"), control);
    }

    /** A button that shows what its press did (returned by {@code press}) for a moment, then its label again. */
    private static Button flashing(Component label, java.util.function.Function<Button, Component> press) {
        long[] until = {0};
        return new Button.Plain(0, 0, 100, 20, label, b -> {
            Component said;
            try {
                said = press.apply(b);
            } catch (RuntimeException e) {
                Io.failed("A hand share code", e);
                said = tr("skycosmetics.hand.share.notCode").withStyle(ChatFormatting.RED);
            }
            b.setMessage(said);
            until[0] = Util.getMillis() + FLASH_MS;
        }, java.util.function.Supplier::get) {
            @Override
            protected void extractContents(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
                if (until[0] != 0 && Util.getMillis() > until[0]) {
                    until[0] = 0;
                    setMessage(label);
                }
                super.extractContents(g, mouseX, mouseY, delta);
            }
        };
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
