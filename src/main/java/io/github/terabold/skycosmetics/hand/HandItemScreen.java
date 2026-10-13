package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.SearchBox;
import io.github.terabold.skycosmetics.gui.hub.ItemTile;
import io.github.terabold.skycosmetics.tooltip.TooltipItemSource;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;

/** Searches captured profile items or vanilla base items; listing happens only while editing settings. */
final class HandItemScreen extends Screen implements TooltipItemSource {
    private record Choice(String key, String name, ItemStack stack) {}
    private ItemStack hovered = ItemStack.EMPTY;
    private final Screen parent;
    private List<Choice> profile = List.of(), vanilla = List.of();
    private boolean vanillaTab;
    private String query = "";
    private int page, x, y, w, h;
    HandItemScreen(Screen parent) { super(Component.literal("Choose a viewmodel item")); this.parent = parent; }
    private void button(String name, int bx, int by, int bw, Runnable action, boolean active) {
        var b = addRenderableWidget(new ActionButton(Component.literal(name), bw, 22, ActionButton.Kind.SECONDARY, v -> action.run()));
        b.setPosition(bx, by); b.active = active;
    }
    @Override protected void init() {
        if (vanilla.isEmpty()) {
            var all = new ArrayList<Choice>();
            for (var item : BuiltInRegistries.ITEM) if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item);
                all.add(new Choice(Hand.ITEM + BuiltInRegistries.ITEM.getKey(item), stack.getHoverName().getString(), stack));
            }
            all.sort(Comparator.comparing(Choice::name, String.CASE_INSENSITIVE_ORDER)); vanilla = List.copyOf(all);
            var mine = new LinkedHashMap<String, Choice>();
            var mc = minecraft;
            if (mc.player != null) for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) candidate(mine, mc.player.getInventory().getItem(i));
            for (var owned : OwnedItems.list()) candidate(mine, owned.stack());
            profile = List.copyOf(mine.values());
        }
        w = Math.min(520, width - 24); h = Math.min(380, height - 16); x = (width - w) / 2; y = (height - h) / 2;
        button("My items", x + 12, y + 32, (w - 28) / 2, () -> { vanillaTab = false; page = 0; rebuildWidgets(); }, vanillaTab);
        button("Vanilla items", x + 16 + (w - 28) / 2, y + 32, (w - 28) / 2, () -> { vanillaTab = true; page = 0; rebuildWidgets(); }, !vanillaTab);
        var search = addRenderableWidget(new SearchBox(font, w - 24, 20, Component.literal("Find item"), Component.literal("Search items…")));
        search.setPosition(x + 12, y + 60); search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; rebuildWidgets(); });
        String filter = query.toLowerCase(Locale.ROOT);
        var choices = (vanillaTab ? vanilla : profile).stream().filter(c -> (c.name() + " " + c.key()).toLowerCase(Locale.ROOT).contains(filter)).toList();
        int cols = Math.max(1, (w - 24) / 28), rows = Math.max(1, (h - 138) / 28);
        int capacity = cols * rows, pages = Math.max(1, (choices.size() + capacity - 1) / capacity);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = page * capacity; i < Math.min(choices.size(), (page + 1) * capacity); i++) {
            var choice = choices.get(i);
            int local = i - page * capacity;
            var tile = addRenderableWidget(new ItemTile(choice.name(), choice.stack(), 26, 26, Hand.rule(choice.key()) != null, () -> {
                if (Hand.rule(choice.key()) != null || Hand.addRule(choice.key(), choice.name()) != null) {
                    Hand.save(); minecraft.setScreen(new HandScreen(parent, choice.key()));
                }
            }));
            tile.setPosition(x + 12 + local % cols * 28, y + 88 + local / cols * 28);
        }
        button("Previous", x + 12, y + h - 34, 80, () -> { page--; rebuildWidgets(); }, page > 0);
        button("Next", x + 96, y + h - 34, 80, () -> { page++; rebuildWidgets(); }, page + 1 < pages);
        button("Back", x + w - 92, y + h - 34, 80, this::onClose, true); setInitialFocus(search);
    }
    private static void candidate(Map<String, Choice> choices, ItemStack stack) {
        var id = Cosmetics.identify(stack);
        if (id != null && id.type() != null && !id.type().startsWith("PET")) {
            String key = Hand.ITEM + id.type();
            if (Hand.validKey(key)) choices.putIfAbsent(key, new Choice(key, Cosmetics.originalName(stack).getString(), stack.copy()));
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Shapes.round(g, x, y, w, h, Theme.RADIUS, Theme.BODY);
        g.centeredText(font, Component.literal(Ui.clip(font, title.getString(), w - 24)), width / 2, y + 12, Theme.TEXT);
        super.extractRenderState(g, mx, my, dt);
        hovered = ItemStack.EMPTY;
        for (var child : children()) if (child instanceof ItemTile tile && tile.isMouseOver(mx, my)) {
            hovered = tile.stack; g.setTooltipForNextFrame(font, tile.tooltip, tile.tooltipImage, mx, my); break;
        }
    }
    @Override public ItemStack tooltipItem() { return hovered; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
