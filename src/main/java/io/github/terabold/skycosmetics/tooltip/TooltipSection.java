package io.github.terabold.skycosmetics.tooltip;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Host;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.hub.Option;
import io.github.terabold.skycosmetics.hub.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Tooltips live in the same searchable themed menu as the other cosmetic sections. */
public final class TooltipSection {
    public static final String ID = "tooltips";
    private TooltipSection() {}

    public static net.minecraft.network.chat.MutableComponent text(String key) { return Component.translatable("skycosmetics.tooltips." + key); }

    public static void register() {
        Hub.add(new Section(ID, Section.FEATURE, text("title"), text("sub"), text("help"),
            () -> new ItemStack(Items.PAPER), TooltipSection::rows));
    }

    private static List<Option> rows(Screen parent) {
        List<Option> r = new ArrayList<>();
        r.add(row("enabled", new Control.Toggle(() -> TooltipSettings.enabled, v -> {
            TooltipSettings.enabled = v;
            if (v && TooltipSettings.scroll == TooltipSettings.Scroll.OFF) TooltipSettings.scroll = TooltipSettings.Scroll.SMOOTH;
            TooltipRenderer.reset();
        })));
        r.add(row("preview", new Control.Action(text("preview.button"), () -> Minecraft.getInstance().setScreen(
            new TooltipPreviewScreen(parent)), () -> true, Component.empty())));
        r.add(Option.header("tooltipLayout", text("layout")));
        r.add(Option.of("autoFit", Component.literal("Fit entire tooltip"), Component.literal("Shrink oversized tooltips to fit the screen. The wheel can still move item tooltips."),
            new Control.Toggle(() -> TooltipSettings.autoFit, v -> { TooltipSettings.autoFit = v; TooltipRenderer.reset(); })));
        r.add(slider("size", () -> TooltipSettings.size, v -> TooltipSettings.size = (int) v, 50, 200, 5, "%"));
        r.add(Option.header("tooltipScrolling", text("scrolling")));
        r.add(row("scroll", choice(new TooltipSettings.Scroll[]{TooltipSettings.Scroll.NORMAL, TooltipSettings.Scroll.SMOOTH}, () -> TooltipSettings.scroll, v -> TooltipSettings.scroll = v)));
        r.add(slider("scrollStep", () -> TooltipSettings.scrollStep, v -> TooltipSettings.scrollStep = (int) v, 5, 100, 5, " px")
            .enabledWhen(() -> TooltipSettings.enabled && TooltipSettings.scroll != TooltipSettings.Scroll.OFF));
        r.add(Option.header("tooltipBackground", text("backgroundGroup")));
        r.add(row("customBackground", new Control.Toggle(() -> TooltipSettings.customBackground, v -> TooltipSettings.customBackground = v)));
        r.add(row("background", choice(new TooltipSettings.Background[]{TooltipSettings.Background.SOLID, TooltipSettings.Background.GRADIENT}, TooltipSettings::effectiveBackground, v -> TooltipSettings.background = v)));
        r.add(row("color", new Control.Color(() -> TooltipSettings.color, v -> TooltipSettings.color = v, true)));
        r.add(row("colorEnd", new Control.Color(() -> TooltipSettings.colorEnd, v -> TooltipSettings.colorEnd = v, true))
            .enabledWhen(() -> TooltipSettings.enabled && TooltipSettings.customBackground
                && TooltipSettings.background == TooltipSettings.Background.GRADIENT));
        r.add(Option.header("tooltipBorder", text("borderGroup")));
        r.add(Option.of("customBorder", Component.literal("Override border"), Component.literal("Off keeps the resource pack or server tooltip frame."),
            new Control.Toggle(() -> TooltipSettings.customBorder, v -> TooltipSettings.customBorder = v)));
        r.add(Option.of("rarityBorder", Component.literal("Border color"), Component.literal("Item rarity uses the same detection and palette as Rarity Backgrounds."),
            new Control.Choice<>(List.of(true, false), v -> Component.literal(v ? "Item rarity" : "Custom color"),
                () -> TooltipSettings.rarityBorder, v -> TooltipSettings.rarityBorder = v)));
        r.add(row("border", new Control.Color(() -> TooltipSettings.border, v -> TooltipSettings.border = v, true)));
        r.add(slider("radius", () -> TooltipSettings.radius, v -> TooltipSettings.radius = (int) v, 0, 12, 1, " px"));
        r.add(Option.header("tooltipReset", text("resetGroup")));
        r.add(row("reset", new Control.Action(text("reset.button"), () -> {
            TooltipSettings.reset();
            if (parent instanceof Host host) host.changed(); else Settings.save();
        }, () -> true, Component.empty())));
        return r.stream().map(o -> {
            Option compact = o.compact();
            if (TooltipSettings.effectiveBackground() == TooltipSettings.Background.GRADIENT && List.of("color", "colorEnd").contains(o.id())) compact = compact.beside();
            return compact.visibleWhen(() -> switch (o.id()) {
                case "scrollStep" -> TooltipSettings.scroll != TooltipSettings.Scroll.OFF;
                case "background", "color" -> TooltipSettings.customBackground;
                case "rarityBorder" -> TooltipSettings.customBorder;
                case "radius" -> TooltipSettings.customBackground && TooltipSettings.customBorder;
                case "border" -> TooltipSettings.customBorder && !TooltipSettings.rarityBorder;
                case "colorEnd" -> TooltipSettings.customBackground && TooltipSettings.effectiveBackground() == TooltipSettings.Background.GRADIENT;
                default -> true;
            });
        }).toList();
    }

    private static Option row(String id, Control control) {
        return Option.of(id, text(id), text(id + ".help"), control).enabledWhen(() -> {
            if (id.equals("enabled") || id.equals("preview") || id.equals("reset")) return true;
            if (!TooltipSettings.enabled) return false;
            return switch (id) {
                case "background", "color" -> TooltipSettings.customBackground;
                case "border" -> TooltipSettings.customBorder;
                case "radius" -> TooltipSettings.customBackground && TooltipSettings.customBorder;
                default -> true;
            };
        });
    }

    private static Option slider(String id, DoubleSupplier get, DoubleConsumer set, int min, int max, int step, String unit) {
        return row(id, new Control.Slider(get, set, min, max, step, n -> Component.literal(Math.round(n) + unit)));
    }

    private static <T extends Enum<T>> Control.Choice<T> choice(T[] values, java.util.function.Supplier<T> get, java.util.function.Consumer<T> set) {
        return new Control.Choice<>(List.of(values), v -> text("value." + v.name().toLowerCase(Locale.ROOT)), get, set);
    }
}
