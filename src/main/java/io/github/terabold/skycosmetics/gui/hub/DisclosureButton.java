package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Control;
import io.github.terabold.skycosmetics.hub.Option;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.github.terabold.skycosmetics.gui.MenuState;

/** A quiet footer link, with normal keyboard focus and button narration. */
public final class DisclosureButton extends ThemedButton {
    private DisclosureButton(int width, BooleanSupplier expanded, Runnable action) {
        super(width, 18, label(expanded.getAsBoolean()), b -> {
            action.run(); b.setMessage(label(expanded.getAsBoolean()));
        });
    }
    private static Component label(boolean expanded) { return Component.literal("Advanced controls " + (expanded ? "▴" : "▾")); }

    public static Option advanced(BooleanSupplier get, Consumer<Boolean> set) {
        return Option.of("advanced", Component.empty(), Component.empty(), new Control.Custom((host, width) ->
            new DisclosureButton(width, get, () -> {
                set.accept(!get.getAsBoolean());
                host.changed();
            }))).search("advanced controls more options").compact();
    }

    /** Extra settings follow their disclosure; their original groups remain searchable when collapsed. */
    public static List<Option> grouped(String section, List<Option> rows, Set<String> basicIds) {
        List<Option> basic = new ArrayList<>(), extra = new ArrayList<>();
        Option header = null;
        boolean basicHeader = false, extraHeader = false;
        for (Option option : rows) {
            if (option.control() instanceof Control.Header) {
                header = option; basicHeader = extraHeader = false; continue;
            }
            if (basicIds.contains(option.id())) {
                if (header != null && !basicHeader) { basic.add(header); basicHeader = true; }
                basic.add(option);
            } else {
                if (header != null && !extraHeader) {
                    extra.add(Option.header(header.id() + "Advanced", header.title()).visibleWhen(() -> MenuState.advanced(section))); extraHeader = true;
                }
                extra.add(option.visibleWhen(() -> MenuState.advanced(section) && option.visible().getAsBoolean()));
            }
        }
        basic.add(advanced(() -> MenuState.advanced(section), value -> MenuState.advanced(section, value)));
        basic.addAll(extra);
        return List.copyOf(basic);
    }

    @Override protected void draw(GuiGraphicsExtractor g, int mx, int my, float hover) {
        g.text(Minecraft.getInstance().font, getMessage(), getX() + 2, getY() + 5,
            hover > 0 || isFocused() ? Theme.ACCENT : Theme.MUTED, false);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, getX(), getY(), getWidth(), getHeight(), Theme.SMALL_RADIUS);
    }
}
