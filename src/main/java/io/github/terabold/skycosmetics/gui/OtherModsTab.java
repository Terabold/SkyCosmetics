package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.compat.OtherLooks.Change;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Other Mods tab: every look Skyblocker or SkyOcean saved for your items, grouped per item (named and drawn
 * from My Items when it knows the UUID) in the same sections as Saved. Each change has its mod's chip, its value,
 * a × that removes it in that mod (through the mod's own config and save; Undo puts it back) and, when
 * SkyCosmetics can do the same, "Move here", which saves it as a SkyCosmetics look and removes it there. A mod
 * SkyCosmetics can only read shows its changes without buttons: they are changed in that mod's settings.
 */
final class OtherModsTab extends RowList {
    /** What the tab needs from the studio. */
    interface Host {
        /** Says what was removed or moved in the footer, with an Undo. */
        void removed(String message, Runnable undo);

        /** Says something went wrong in the footer. */
        void failed(String message);

        /** The tab's search, lower case. */
        String query();
    }

    private static final int ITEM_H = 22, CHANGE_H = 13;
    /** Under this much room for a value, the Move button says "Move" only. */
    private static final int VALUE_ROOM = 80;

    private final Host host;
    private int shownItems, shownChanges;
    /** The kind column ("Dye", "Model"): as wide as the longest kind, and a gap. */
    private int kindW = -1;

    OtherModsTab(Font font, Host host) {
        super(font);
        this.host = host;
    }

    @Override
    protected Object stamp() {
        OtherLooks.refresh(false);
        return List.of(OtherLooks.version(), OwnedItems.version(), Looks.version(), System.identityHashCode(Repo.get()),
            host.query());
    }

    /** Items and changes listed (after the search). */
    int[] shown() {
        lines();
        return new int[]{shownItems, shownChanges};
    }

    /** One item another mod changes, and what is known about it here. */
    private record Item(String key, String title, String sub, String type, ItemStack icon, LookGroup group,
                       List<Change> changes) {}

    @Override
    protected List<Line> build() {
        if (kindW < 0) for (OtherLooks.Kind k : OtherLooks.Kind.values()) kindW = Math.max(kindW, font.width(k.label()) + 6);
        Catalog c = Repo.get();
        String q = host.query();
        Map<String, List<Change>> perItem = new LinkedHashMap<>();
        for (Change ch : OtherLooks.all()) perItem.computeIfAbsent(ch.item(), k -> new ArrayList<>()).add(ch);
        Map<LookGroup, List<Item>> groups = new EnumMap<>(LookGroup.class);
        for (var e : perItem.entrySet()) {
            Item item = item(e.getKey(), e.getValue(), c);
            if (!q.isEmpty() && !matches(item, q)) continue;
            groups.computeIfAbsent(item.group(), k -> new ArrayList<>()).add(item);
        }
        List<Line> out = new ArrayList<>();
        int items = 0, changes = 0;
        for (var g : groups.entrySet()) {
            List<Item> list = g.getValue();
            list.sort(Comparator.comparing(Item::title, String.CASE_INSENSITIVE_ORDER));
            out.add(new Header(g.getKey().title(), list.size()));
            for (Item item : list) {
                items++;
                out.add(new ItemRow(item));
                for (Change ch : item.changes()) {
                    changes++;
                    out.add(new ChangeRow(item, ch));
                }
            }
        }
        shownItems = items;
        shownChanges = changes;
        return out;
    }

    private static Item item(String key, List<Change> changes, Catalog c) {
        Change first = changes.getFirst();
        String uuid = first.uuid(), type = first.type();
        if (uuid != null) {
            OwnedItems.Owned o = OwnedItems.get(uuid);
            if (o != null) {
                ItemStack s = o.stack();
                return new Item(key, Cosmetics.originalName(s).getString(), o.source, o.type, s, LookGroup.of(o.category), changes);
            }
            String known = Looks.itemType(uuid);
            Looks.Look own = Looks.byUuid(uuid);
            String title = own != null && own.label() != null ? own.label()
                : known != null ? c.typeName(known) : tr("skycosmetics.saved.unknownItem");
            String sub = "UUID " + (uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid);
            return new Item(key, title, sub, known, ItemStack.EMPTY,
                known != null ? LookGroup.of(OwnedItems.guess(known)) : LookGroup.UNSEEN, changes);
        }
        OwnedItems.Owned any = OwnedItems.anyOf(type);
        ItemStack icon = any != null ? any.stack() : ItemStack.EMPTY;
        if (first.everyItem()) {
            return new Item(key, Component.translatable("skycosmetics.saved.every", c.typeName(type)).getString(),
                tr("skycosmetics.other.everyItem"), type, icon, LookGroup.TYPES, changes);
        }
        return new Item(key, c.typeName(type), tr("skycosmetics.other.oneItem"), type, icon,
            LookGroup.of(OwnedItems.guess(type)), changes);
    }

    /** The search finds the item, the mod, the kind of change and its value ("skyblocker", "dye", "#FF"). */
    private static boolean matches(Item item, String q) {
        if (item.title().toLowerCase(Locale.ROOT).contains(q) || item.key().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (item.type() != null && item.type().toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) return true;
        for (Change ch : item.changes()) {
            if (ch.source().name().toLowerCase(Locale.ROOT).contains(q) || ch.value().toLowerCase(Locale.ROOT).contains(q)
                || ch.kind().label().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    // ------------------------------------------------------------ actions ---

    private void remove(Item item, Change ch) {
        if (OtherLooks.remove(ch)) {
            host.removed(Component.translatable("skycosmetics.other.removed", ch.source().name(),
                ch.kind().label().getString().toLowerCase(Locale.ROOT), item.title()).getString(), () -> OtherLooks.restore(ch));
        } else {
            host.failed(Component.translatable("skycosmetics.other.kept", ch.source().name()).getString());
        }
    }

    private void removeAll(Item item) {
        List<Change> gone = new ArrayList<>();
        for (Change ch : item.changes()) if (ch.live() && OtherLooks.remove(ch)) gone.add(ch);
        if (gone.isEmpty()) {
            host.failed(Component.translatable("skycosmetics.other.kept", item.changes().getFirst().source().name()).getString());
            return;
        }
        host.removed(Component.translatable("skycosmetics.other.removedAll", Names.count(gone.size(), "change"), item.title()).getString(),
            () -> gone.forEach(OtherLooks::restore));
    }

    private void move(Item item, Change ch) {
        Looks.Look before = OtherLooks.take(ch, item.type(), item.title());
        if (before == null) {
            host.failed(Component.translatable("skycosmetics.other.kept", ch.source().name()).getString());
            return;
        }
        boolean type = ch.uuid() == null;
        String key = type ? ch.type() : ch.uuid();
        String itemType = item.type();
        host.removed(Component.translatable("skycosmetics.other.moved", ch.kind().label().getString().toLowerCase(Locale.ROOT),
            item.title()).getString(), () -> {
                Looks.Look back = before.empty() ? null : before;
                if (type) Looks.put(true, key, back);
                else Looks.putItem(key, itemType, back);
                OtherLooks.restore(ch);
            });
    }

    // --------------------------------------------------------------- rows ---

    /** The item: icon, name, where it is (or its UUID), and a × for all its changes when it has more than one. */
    private final class ItemRow implements Line {
        private final Item item;
        private final int removable;
        private ItemStack fallback;

        ItemRow(Item item) {
            this.item = item;
            int n = 0;
            for (Change ch : item.changes()) if (ch.live()) n++;
            this.removable = n;
        }

        @Override
        public int height() {
            return ITEM_H;
        }

        private boolean hasX() {
            return removable > 1;
        }

        private int xAt(int x, int w) {
            return x + w - X_BIG - 4;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            ItemStack icon = item.icon();
            if (icon.isEmpty()) {
                if (fallback == null) fallback = iconFor(item.changes().getFirst()); // built on the render thread
                icon = fallback;
            }
            g.item(icon, x + 4, y + 3);
            int textW = w - 24 - (hasX() ? X_BIG + 8 : 4);
            g.text(font, clip(item.title(), textW), x + 24, y + 2, TEXT);
            if (item.sub() != null && !item.sub().isEmpty()) g.text(font, clip(item.sub(), textW), x + 24, y + 12, MUTED);
            if (hasX()) {
                int bx = xAt(x, w);
                drawX(g, font, bx, y + 3, X_BIG, in(mouseX, mouseY, bx, y + 3, X_BIG, X_BIG));
            }
        }

        @Override
        public boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            if (hasX() && in(mouseX, mouseY, xAt(x, w) - 1, y + 2, X_BIG + 2, X_BIG + 2)) {
                removeAll(item);
                return true;
            }
            return false;
        }

        @Override
        public List<Component> tooltip(int x, int y, int w, int mouseX, int mouseY) {
            if (hasX() && in(mouseX, mouseY, xAt(x, w) - 1, y + 2, X_BIG + 2, X_BIG + 2)) {
                return List.of(Component.translatable("skycosmetics.other.removeAll").withStyle(ChatFormatting.RED));
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(item.title()));
            if (item.group() == LookGroup.UNSEEN) {
                tip.add(Component.literal(item.key()).withStyle(ChatFormatting.DARK_GRAY));
                tip.add(Component.translatable("skycosmetics.other.unseenHelp").withStyle(ChatFormatting.GRAY));
            }
            return tip;
        }

        @Override
        public String toString() {
            return item.title() + " [" + item.group() + "]";
        }
    }

    /** One change: the mod's chip, what it changes, its value, "Move Here" and ×, or where to change it. */
    private final class ChangeRow implements Line {
        private final Item item;
        private final Change ch;
        private String move = tr("skycosmetics.other.move");
        /** "Change it in Skyblocker's settings", or "Read-only" where that doesn't fit; per width. */
        private String note;
        private int noteFor = -1;
        private FormattedCharSequence name;
        private int nameWidth = -1;

        ChangeRow(Item item, Change ch) {
            this.item = item;
            this.ch = ch;
        }

        @Override
        public int height() {
            return CHANGE_H;
        }

        @Override
        public boolean hoverable() {
            return true;
        }

        private int xAt(int x, int w) {
            return x + w - X_BIG - 4 + (X_BIG - X_SMALL) / 2;
        }

        private int moveW() {
            return font.width(move) + 6;
        }

        private int moveX(int x, int w) {
            return xAt(x, w) - 4 - moveW();
        }

        private boolean overX(int x, int y, int w, int mx, int my) {
            return ch.live() && in(mx, my, xAt(x, w) - 1, y, X_SMALL + 1, CHANGE_H);
        }

        private boolean overMove(int x, int y, int w, int mx, int my) {
            return ch.movable() && in(mx, my, moveX(x, w), y + 1, moveW(), CHANGE_H - 2);
        }

        /** Lays the row out for width {@code w}: the Move label and the read-only note. */
        private void fit(int w) {
            if (noteFor == w) return;
            noteFor = w;
            int valueX = 18 + font.width(ch.source().name()) + 6 + 5 + kindW;
            move = tr("skycosmetics.other.move");
            if (w - X_BIG - 8 - (font.width(move) + 6) - 4 - valueX < VALUE_ROOM) move = tr("skycosmetics.other.moveShort");
            String full = Component.translatable("skycosmetics.other.changeIn", ch.source().name()).getString();
            note = w - 4 - font.width(full) - valueX >= VALUE_ROOM ? full : tr("skycosmetics.other.readOnly");
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            fit(w);
            g.fill(x + 11, y, x + 12, y + CHANGE_H, LINE);
            int cx = x + 18;
            String mod = ch.source().name();
            int pw = font.width(mod) + 6;
            int col = ch.source().color();
            g.fill(cx, y + 1, cx + pw, y + CHANGE_H - 1, (col & 0x00FFFFFF) | 0x50000000);
            g.outline(cx, y + 1, pw, CHANGE_H - 2, col);
            g.text(font, mod, cx + 3, y + 3, 0xFFFFFFFF);
            cx += pw + 5;
            g.text(font, ch.kind().label(), cx, y + 3, MUTED);
            cx += kindW;
            int right = ch.live() ? (ch.movable() ? moveX(x, w) : xAt(x, w)) - 4 : x + w - 4 - font.width(note);
            if (ch.rgb() >= 0) {
                g.fill(cx, y + 3, cx + 7, y + 10, 0xFF000000);
                g.fill(cx + 1, y + 4, cx + 6, y + 9, 0xFF000000 | ch.rgb());
                cx += 10;
            }
            int vw = right - cx;
            if (ch.name() != null) {
                if (vw != nameWidth) {
                    nameWidth = vw;
                    name = NameBox.fit(font, ch.name(), Math.max(0, vw));
                }
                g.text(font, name, cx, y + 3, TEXT);
            } else {
                g.text(font, clip(ch.value(), vw), cx, y + 3, TEXT);
            }
            if (!ch.live()) {
                g.text(font, note, x + w - 4 - font.width(note), y + 3, MUTED);
                return;
            }
            if (ch.movable()) {
                int mx = moveX(x, w);
                boolean over = overMove(x, y, w, mouseX, mouseY);
                g.fill(mx, y + 1, mx + moveW(), y + CHANGE_H - 1, over ? 0xFF4A3A66 : 0xFF2A2436);
                g.outline(mx, y + 1, moveW(), CHANGE_H - 2, over ? ACCENT : 0xFF5A4A7A);
                g.text(font, move, mx + 3, y + 3, over ? 0xFFFFFFFF : 0xFFE0C8FF);
            }
            int bx = xAt(x, w);
            drawX(g, font, bx, y + 1, X_SMALL, overX(x, y, w, mouseX, mouseY));
        }

        @Override
        public boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            fit(w);
            if (overX(x, y, w, mouseX, mouseY)) remove(item, ch);
            else if (overMove(x, y, w, mouseX, mouseY)) move(item, ch);
            return true;
        }

        @Override
        public List<Component> tooltip(int x, int y, int w, int mouseX, int mouseY) {
            String mod = ch.source().name();
            if (overX(x, y, w, mouseX, mouseY)) {
                return List.of(Component.translatable("skycosmetics.other.removeIn", mod).withStyle(ChatFormatting.RED),
                    Component.translatable("skycosmetics.other.removeHelp", mod).withStyle(ChatFormatting.GRAY));
            }
            if (overMove(x, y, w, mouseX, mouseY)) {
                return List.of(Component.translatable("skycosmetics.other.moveTitle").withStyle(ChatFormatting.LIGHT_PURPLE),
                    Component.translatable("skycosmetics.other.moveHelp", mod).withStyle(ChatFormatting.GRAY));
            }
            List<Component> tip = new ArrayList<>();
            MutableComponent head = ch.chip().copy().withStyle(ChatFormatting.WHITE);
            tip.add(head);
            tip.add(OtherChip.valueLine(ch));
            if (!ch.live()) tip.add(Component.translatable("skycosmetics.other.changeIn", mod).withStyle(ChatFormatting.YELLOW));
            return tip;
        }

        @Override
        public String toString() {
            return "  " + ch.source().name() + " " + ch.kind().key + ": " + ch.value() + (ch.live() ? "" : " (read-only)")
                + (ch.movable() ? " [move]" : "");
        }
    }

    /** An item for a change on an item never seen: a dyed chestplate for a dye, a name tag for a name... */
    static ItemStack iconFor(Change ch) {
        return switch (ch.kind()) {
            case DYE -> {
                ItemStack s = new ItemStack(Items.LEATHER_CHESTPLATE);
                if (ch.rgb() >= 0) s.set(DataComponents.DYED_COLOR, new DyedItemColor(ch.rgb()));
                yield s;
            }
            case NAME -> new ItemStack(Items.NAME_TAG);
            case SKIN -> new ItemStack(Items.PLAYER_HEAD);
            case GLINT -> new ItemStack(Items.ENCHANTED_BOOK);
            case TRIM -> new ItemStack(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE);
            case MODEL -> new ItemStack(Items.ITEM_FRAME);
        };
    }

    // -------------------------------------------------------- test hooks ---

    /** Where the × of {@code mod}'s {@code kind} change on {@code item} was drawn, and its "Move here" (or -1s). */
    int[] buttons(String item, String mod, OtherLooks.Kind kind) {
        int x = drawnX(), w = drawnRowWidth(), ly = drawnY() - scroll();
        for (Line l : lines()) {
            if (l instanceof ChangeRow r && r.item.key().equals(item) && r.ch.source().name().equals(mod) && r.ch.kind() == kind) {
                r.fit(w);
                int mx = r.ch.movable() ? r.moveX(x, w) + r.moveW() / 2 : -1;
                return new int[]{r.xAt(x, w) + X_SMALL / 2, ly + CHANGE_H / 2, mx, ly + CHANGE_H / 2};
            }
            ly += l.height();
        }
        return null;
    }
}
