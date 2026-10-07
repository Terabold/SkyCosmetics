package io.github.terabold.skycosmetics.gui.hub;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.hub.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.time.Duration;

/**
 * A section in the settings' sidebar: its icon, name and a gray line under it. The open one sits on the accent's
 * dark background; while a search runs each tab shows how many settings it matched, and one with none dims. In a
 * narrow window only the icon shows, with the name as a tooltip; so does a tab too narrow for its text. The message
 * is the section's name.
 */
public final class SectionTab extends ThemedButton {
    public static final int H = 26, RAIL_H = 22;
    private final Section section;
    private final ItemStack icon;
    private final boolean rail;
    private final Anim open = new Anim(120, 0);
    private String name = "", sub = "", count = "";
    private boolean selected, dim, tip;
    private int found = -1;

    public SectionTab(Section section, int width, boolean rail, OnPress onPress) {
        super(width, rail ? RAIL_H : H, section.name(), onPress);
        this.section = section;
        this.rail = rail;
        ItemStack i;
        try {
            i = section.icon().get();
        } catch (RuntimeException e) {
            Io.failed("Building the icon of settings section " + section.id(), e);
            i = ItemStack.EMPTY;
        }
        this.icon = i == null ? ItemStack.EMPTY : i;
        if (!rail) setTooltipDelay(Duration.ofMillis(400)); // the text is there, cut: no rush
        clip();
    }

    /** The name and the gray line under it, for a tab that shows only its icon or cuts its text. */
    private Tooltip fullName() {
        return Tooltip.create(section.sub().getString().isEmpty() ? section.name()
            : Component.empty().append(section.name()).append("\n").append(section.sub().copy().withColor(Theme.MUTED & 0xFFFFFF)));
    }

    public Section section() {
        return section;
    }

    public ItemStack icon() {
        return icon;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean selected() {
        return selected;
    }

    /** While a search runs: how many of its settings match ({@code -1} when no search runs). */
    public void setCount(int n) {
        found = n;
        dim = n == 0;
        count = n < 0 ? "" : Integer.toString(n);
        clip();
    }

    /** How many settings the running search found here, or -1 with no search. */
    public int count() {
        return found;
    }

    /** Cuts the name and the gray line to the tab; a tab that cut either shows both whole as a tooltip. */
    private void clip() {
        boolean cut = rail;
        if (!rail) {
            Font font = Minecraft.getInstance().font;
            int room = getWidth() - 26 - (count.isEmpty() ? 4 : font.width(count) + 10);
            String fullName = section.name().getString(), fullSub = section.sub().getString();
            name = Ui.clip(font, fullName, room);
            sub = Ui.clip(font, fullSub, getWidth() - 30);
            cut = !name.equals(fullName) || !sub.equals(fullSub);
        }
        if (cut != tip) {
            tip = cut;
            setTooltip(cut ? fullName() : null);
        }
    }

    @Override
    protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float hover) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        float sel = open.to(selected ? 1 : 0);
        if (hover > 0 && sel < 1) Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS + 1, Theme.fade(Theme.SURFACE, hover * (1 - sel)));
        if (sel > 0) Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS + 1, Theme.fade(Theme.ACCENT_BG, sel));
        float a = dim && !selected ? 0.45f : 1;
        if (rail) {
            g.item(icon, x + (w - 16) / 2, y + (h - 16) / 2);
        } else {
            g.item(icon, x + 4, y + (h - 16) / 2);
            int nameColor = selected ? 0xFFFFFFFF : section.order() == Section.FIRST ? Theme.ACCENT : Theme.TEXT;
            g.text(font, name, x + 24, y + 4, Theme.fade(nameColor, a), false);
            g.text(font, sub, x + 24, y + 14, Theme.fade(selected ? Theme.mix(Theme.MUTED, Theme.TEXT, 0.4f) : Theme.MUTED, a), false);
            if (!count.isEmpty()) {
                int cw = font.width(count) + 6, cx = x + w - cw - 4, cy = y + 4;
                Shapes.round(g, cx, cy, cw, 10, 5, dim ? Theme.SURFACE : Theme.GRADIENT_START);
                g.text(font, count, cx + 3, cy + 1, dim ? Theme.DIM : Theme.ON_ACCENT, false);
            }
        }
        if (dim && rail && !selected) Shapes.round(g, x, y, w, h, Theme.SMALL_RADIUS + 1, 0x99111116);
        if (Ui.keyboardFocus(this)) Ui.focusRing(g, x, y, w, h, Theme.SMALL_RADIUS + 1);
    }
}
