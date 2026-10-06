package io.github.terabold.skycosmetics.slots;

import io.github.terabold.skycosmetics.Io;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A strip of inventory slots in the settings that shows a feature as a menu would: one slot per rarity with its
 * background, or a row of heads at the chosen size next to a sword for scale. It draws through the same code as the
 * real slots, so what it shows is what menus show. Grayed out (the feature off), it shows the plain slots.
 */
final class SlotPreview extends AbstractWidget {
    enum Kind { RARITIES, HEADS }

    static final int SLOT = 18, PAD = 5;
    private static final int PANEL = 0xFFC6C6C6, EDGE = 0xFF555555, DARK = 0xFF373737, LIGHT = 0xFFFFFFFF, INNER = 0xFF8B8B8B;

    /** One item per rarity, lowest first; any recognizable icon will do. */
    private static final Item[] RARITY_ITEMS = {Items.IRON_SWORD, Items.EMERALD, Items.DIAMOND_PICKAXE, Items.BOW,
        Items.GOLDEN_CHESTPLATE, Items.NETHER_STAR, Items.HEART_OF_THE_SEA, Items.CAKE, Items.FIREWORK_ROCKET,
        Items.ENCHANTED_BOOK, Items.COMMAND_BLOCK};
    /** Heads of every kind, with a sword in the middle to compare the size with. */
    private static final Item[] HEAD_ITEMS = {Items.PLAYER_HEAD, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.DIAMOND_SWORD,
        Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.PIGLIN_HEAD, Items.PLAYER_HEAD};

    private final Kind kind;
    private final Item[] source;
    private final int cols, rows;
    /** Made on the first draw; empty when items can't be made yet (the title screen, before any world). */
    private ItemStack[] items;

    SlotPreview(int width, Kind kind) {
        super(0, 0, width, 0, Component.translatable("skycosmetics.slots.preview"));
        this.kind = kind;
        source = kind == Kind.RARITIES ? RARITY_ITEMS : HEAD_ITEMS;
        cols = Math.clamp((width - 2 * PAD) / SLOT, 1, source.length);
        rows = (source.length + cols - 1) / cols;
        // Room around the heads: at the biggest size they reach past their slot, as in a real menu.
        int pad = kind == Kind.HEADS ? PAD + 3 : PAD;
        height = rows * SLOT + 2 * pad;
    }

    private ItemStack[] items() {
        if (items != null) return items;
        try {
            ItemStack[] made = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) made[i] = new ItemStack(source[i]);
            items = made;
        } catch (RuntimeException e) {
            items = new ItemStack[0]; // item components are bound only once a world has loaded
        }
        return items;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x0 = getX() + (width - cols * SLOT) / 2, y0 = getY() + (height - rows * SLOT) / 2;
        g.fill(x0 - PAD, getY(), x0 + cols * SLOT + PAD, getBottom(), PANEL);
        g.outline(x0 - PAD, getY(), cols * SLOT + 2 * PAD, height, EDGE);
        Identifier tex = null;
        if (kind == Kind.RARITIES && active) {
            try {
                tex = RarityBackgrounds.texture();
            } catch (RuntimeException e) {
                Io.failed("Painting the rarity background preview", e);
            }
        }
        int hovered = -1;
        for (int i = 0; i < source.length; i++) {
            int x = x0 + i % cols * SLOT, y = y0 + i / cols * SLOT;
            slot(g, x, y);
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) hovered = i;
            if (tex == null) continue;
            Rarity r = Rarity.byIndex(i);
            int tint = r == null ? 0 : RarityBackgrounds.previewTint(r);
            if (tint != 0) RarityBackgrounds.drawTint(g, tex, x + 1, y + 1, tint, RarityBackgrounds.recombMark && r == Rarity.LEGENDARY);
        }
        ItemStack[] shown = items();
        if (kind == Kind.HEADS) SlotArea.enter(SlotArea.PREVIEW);
        try {
            for (int i = 0; i < shown.length; i++) g.item(shown[i], x0 + i % cols * SLOT + 1, y0 + i / cols * SLOT + 1);
        } finally {
            if (kind == Kind.HEADS) SlotArea.leave();
        }
        if (hovered >= 0 && kind == Kind.RARITIES) {
            Rarity r = Rarity.byIndex(hovered);
            if (r != null) {
                g.setTooltipForNextFrame(Minecraft.getInstance().font, r.label().copy().withColor(RarityBackgrounds.color(r)),
                    mouseX, mouseY);
            }
        }
    }

    /** A vanilla inventory slot: dark top-left edge, light bottom-right edge, gray inside. */
    static void slot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, INNER);
        g.fill(x, y, x + SLOT - 1, y + 1, DARK);
        g.fill(x, y, x + 1, y + SLOT - 1, DARK);
        g.fill(x + 1, y + SLOT - 1, x + SLOT, y + SLOT, LIGHT);
        g.fill(x + SLOT - 1, y + 1, x + SLOT, y + SLOT, LIGHT);
    }

    /** Nothing to click: the preview only shows. */
    @Override
    protected boolean isValidClickButton(MouseButtonInfo info) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
    }
}
