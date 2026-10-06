package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Saved tab: every look, in sections (Helmets, Armor, Weapons & Tools, Pets, Power Orbs, Other Items, then
 * the looks for every item of a type). A row shows the item, its name and what changed, with a big × that removes
 * the whole look. Clicking a row opens the list of its changes under it, each with its own ×, and a link that
 * opens the look in the editor. Every removal can be undone from the footer.
 */
final class SavedTab extends RowList {
    /** What the tab needs from the studio. */
    interface Host {
        /** The frame of {@code e} to draw now on the studio's texture budget, or -1 while it loads. */
        int readyFrame(SkinEntry e, long tick);

        /** Says what was removed in the footer, with an Undo. */
        void removed(String message, Runnable undo);

        /** Whether the look's item is in My Items, so the editor can open on it. */
        boolean canEdit(boolean type, String key);

        void edit(boolean type, String key);

        /** The tab's search, lower case. */
        String query();

        /** Changes when {@link #canEdit} may answer differently. */
        int editVersion();
    }

    /** One change a look makes, as listed under it. */
    enum Part {
        SKIN("skin"), DYE("dye"), NAME("name"), GLINT("glint"), GLINT_COLOR("glintColor"), GLINT_SPEED("glintSpeed"),
        GLINT_STRENGTH("glintStrength");

        private final String key;

        Part(String key) {
            this.key = key;
        }

        String label() {
            return Component.translatable("skycosmetics.saved.part." + key).getString();
        }

        Looks.Look without(Looks.Look l) {
            return switch (this) {
                case SKIN -> l.withSkin(null);
                case DYE -> l.withDye(null);
                case NAME -> l.withName(null);
                case GLINT -> l.withGlint(null);
                case GLINT_COLOR -> l.withGlintColor(null);
                case GLINT_SPEED -> l.withGlintSpeed(null);
                case GLINT_STRENGTH -> l.withGlintStrength(null);
            };
        }

        boolean in(Looks.Look l) {
            return switch (this) {
                case SKIN -> l.skin() != null;
                case DYE -> l.dye() != null;
                case NAME -> l.name() != null;
                case GLINT -> l.glint() != null;
                case GLINT_COLOR -> l.glintColor() != null;
                case GLINT_SPEED -> l.glintSpeed() != null;
                case GLINT_STRENGTH -> l.glintStrength() != null;
            };
        }
    }

    private static final int ROW_H = 24, PART_H = 12, LINK_H = 13, LABEL_W = 72;

    private final Host host;
    /** Open rows: "T" + type or "I" + UUID. */
    private final Set<String> open = new HashSet<>();
    private int openVersion;
    private int shown;

    SavedTab(Font font, Host host) {
        super(font);
        this.host = host;
    }

    @Override
    protected Object stamp() {
        return List.of(Looks.version(), OwnedItems.version(), System.identityHashCode(Repo.get()), host.query(),
            openVersion, host.editVersion());
    }

    /** How many looks are listed (after the search). */
    int shown() {
        lines();
        return shown;
    }

    /** One saved look, with what is known about its item. */
    private record Entry(boolean type, String key, Looks.Look look, String title, String itemType, ItemStack icon,
                         LookGroup group) {
        String id() {
            return (type ? "T" : "I") + key;
        }
    }

    @Override
    protected List<Line> build() {
        Catalog c = Repo.get();
        String q = host.query();
        Map<LookGroup, List<Entry>> groups = new EnumMap<>(LookGroup.class);
        for (var e : Looks.uuidLooks().entrySet()) {
            OwnedItems.Owned owned = OwnedItems.get(e.getKey());
            String itemType = owned != null ? owned.type : Looks.itemType(e.getKey());
            ItemStack icon = owned != null ? owned.stack() : ItemStack.EMPTY;
            String title = !icon.isEmpty() ? Cosmetics.originalName(icon).getString()
                : e.getValue().label() != null ? e.getValue().label()
                : itemType != null ? c.typeName(itemType) : Component.translatable("skycosmetics.saved.unknownItem").getString();
            LookGroup g = LookGroup.of(owned != null ? owned.category : OwnedItems.guess(itemType));
            add(groups, new Entry(false, e.getKey(), e.getValue(), title, itemType, icon, g), q, c);
        }
        for (var e : Looks.typeLooks().entrySet()) {
            OwnedItems.Owned any = OwnedItems.anyOf(e.getKey());
            String title = Component.translatable("skycosmetics.saved.every", c.typeName(e.getKey())).getString();
            add(groups, new Entry(true, e.getKey(), e.getValue(), title, e.getKey(),
                any != null ? any.stack() : ItemStack.EMPTY, LookGroup.TYPES), q, c);
        }
        List<Line> out = new ArrayList<>();
        int n = 0;
        for (var g : groups.entrySet()) {
            List<Entry> list = g.getValue();
            list.sort(Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER));
            out.add(new Header(g.getKey().title(), list.size()));
            for (Entry e : list) {
                n++;
                boolean isOpen = open.contains(e.id());
                out.add(new Row(e, c, isOpen));
                if (!isOpen) continue;
                for (Part p : Part.values()) if (p.in(e.look())) out.add(new PartRow(e, p, c));
                if (host.canEdit(e.type(), e.key())) out.add(new EditLink(e));
            }
        }
        shown = n;
        return out;
    }

    private static void add(Map<LookGroup, List<Entry>> groups, Entry e, String q, Catalog c) {
        if (!q.isEmpty() && !matches(e, q, c)) return;
        groups.computeIfAbsent(e.group(), k -> new ArrayList<>()).add(e);
    }

    /** The search finds the item's name or id and what its look uses ("knight", "aurora", "necron"). */
    private static boolean matches(Entry e, String q, Catalog c) {
        if (e.title().toLowerCase(Locale.ROOT).contains(q) || e.key().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (e.itemType() != null && e.itemType().toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) return true;
        return summary(e.look(), c).toLowerCase(Locale.ROOT).contains(q)
            || e.look().name() != null && Names.parse(e.look().name()).getString().toLowerCase(Locale.ROOT).contains(q);
    }

    /** "Knight Skin · Aurora Dye · Renamed · Glint off". */
    static String summary(Looks.Look l, Catalog c) {
        List<String> parts = new ArrayList<>();
        if (l.skin() != null) parts.add(skinName(l.skin(), c));
        if (l.dye() != null) parts.add(dyeName(l.dye(), c));
        if (l.name() != null) parts.add(tr("skycosmetics.saved.renamed"));
        if (l.glint() != null) parts.add(tr("on".equals(l.glint()) ? "skycosmetics.saved.glintOn" : "skycosmetics.saved.glintOff"));
        if (l.glintColor() != null || l.glintSpeed() != null || l.glintStrength() != null) {
            parts.add(tr("skycosmetics.saved.glintStyle"));
        }
        return String.join(" · ", parts);
    }

    static String skinName(String id, Catalog c) {
        SkinEntry e = c.skin(id);
        return e != null ? e.name : tr("skycosmetics.saved.unknownSkin");
    }

    static String dyeName(String id, Catalog c) {
        DyeEntry d = c.dye(id);
        return d != null ? d.name : tr("skycosmetics.saved.unknownDye");
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    // ------------------------------------------------------------ actions ---

    private void toggle(Entry e) {
        if (!open.remove(e.id())) open.add(e.id());
        openVersion++;
    }

    /** Puts {@code next} (null: nothing) as the look, keeping a UUID look's item type. */
    private static void put(Entry e, Looks.Look next) {
        if (e.type()) Looks.put(true, e.key(), next);
        else Looks.putItem(e.key(), e.itemType(), next);
    }

    private void removeAll(Entry e) {
        Looks.Look before = e.look();
        put(e, null);
        open.remove(e.id());
        host.removed(Component.translatable("skycosmetics.saved.removedLook", e.title()).getString(), () -> put(e, before));
    }

    private void removePart(Entry e, Part p) {
        Looks.Look before = e.look();
        Looks.Look next = p.without(before);
        put(e, next.empty() ? null : next);
        host.removed(Component.translatable("skycosmetics.saved.removedPart", p.label().toLowerCase(Locale.ROOT), e.title()).getString(),
            () -> put(e, before));
    }

    // --------------------------------------------------------------- rows ---

    /** One look: icon, name, what changed, a chevron and the big remove button. */
    private final class Row implements Line {
        private final Entry e;
        private final SkinEntry skin;
        private final DyeEntry dye;
        private final String summary;
        private final boolean isOpen;

        Row(Entry e, Catalog c, boolean isOpen) {
            this.e = e;
            this.skin = c.skin(e.look().skin());
            this.dye = c.dye(e.look().dye());
            this.summary = summary(e.look(), c);
            this.isOpen = isOpen;
        }

        @Override
        public int height() {
            return ROW_H;
        }

        @Override
        public boolean hoverable() {
            return true;
        }

        private int xAt(int x, int w) {
            return x + w - X_BIG - 4;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            if (isOpen) g.fill(x, y, x + w, y + ROW_H, 0x20D58CFF);
            if (!e.icon().isEmpty()) {
                g.item(e.icon(), x + 4, y + 4);
            } else {
                int frame = skin != null ? host.readyFrame(skin, tick) : -1;
                if (frame >= 0) g.item(skin.icon(frame), x + 4, y + 4);
                else if (dye != null) g.fill(x + 6, y + 6, x + 18, y + 18, 0xFF000000 | dye.rgbAt(tick, 0));
                else g.centeredText(font, "?", x + 12, y + 8, MUTED);
            }
            int textW = w - 24 - X_BIG - 18;
            g.text(font, clip(e.title(), textW), x + 24, y + 3, TEXT);
            g.text(font, clip(summary, textW), x + 24, y + 13, MUTED);
            int bx = xAt(x, w);
            drawChevron(g, bx - 10, y + 9, isOpen, in(mouseX, mouseY, x, y, w, ROW_H) ? TEXT : MUTED);
            drawX(g, font, bx, y + 4, X_BIG, in(mouseX, mouseY, bx, y + 4, X_BIG, X_BIG));
        }

        @Override
        public boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            int bx = xAt(x, w);
            if (in(mouseX, mouseY, bx - 1, y + 3, X_BIG + 2, X_BIG + 2)) removeAll(e);
            else toggle(e);
            return true;
        }

        @Override
        public List<Component> tooltip(int x, int y, int w, int mouseX, int mouseY) {
            int bx = xAt(x, w);
            if (in(mouseX, mouseY, bx - 1, y + 3, X_BIG + 2, X_BIG + 2)) {
                return List.of(Component.translatable(e.type() ? "skycosmetics.saved.removeEvery" : "skycosmetics.saved.removeLook")
                    .withStyle(ChatFormatting.RED));
            }
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(e.title()));
            if (!summary.isEmpty() && font.width(summary) > w - 24 - X_BIG - 18) tip.add(Component.literal(summary).withStyle(ChatFormatting.GRAY));
            tip.add(Component.translatable(isOpen ? "skycosmetics.saved.hideParts" : "skycosmetics.saved.showParts")
                .withStyle(ChatFormatting.YELLOW));
            return tip;
        }

        @Override
        public String toString() {
            return (isOpen ? "v " : "> ") + e.title() + (summary.isEmpty() ? "" : " | " + summary);
        }
    }

    /** One change of an open look: its name, its value, its own remove button. */
    private final class PartRow implements Line {
        private final Entry e;
        private final Part p;
        private final String value;
        private final int color;
        private final DyeEntry dye;
        private final int swatch;
        private FormattedCharSequence name;
        private int nameWidth = -1;

        PartRow(Entry e, Part p, Catalog c) {
            this.e = e;
            this.p = p;
            Looks.Look l = e.look();
            DyeEntry d = null;
            int sw = -1, col = TEXT;
            String v = switch (p) {
                case SKIN -> {
                    SkinEntry s = c.skin(l.skin());
                    if (s != null) col = s.color;
                    yield skinName(l.skin(), c);
                }
                case DYE -> {
                    d = c.dye(l.dye());
                    if (d != null) col = d.nameColor;
                    yield dyeName(l.dye(), c);
                }
                case NAME -> l.name();
                case GLINT -> tr("on".equals(l.glint()) ? "skycosmetics.menu.on" : "skycosmetics.menu.off");
                case GLINT_COLOR -> {
                    sw = DyeEntry.parseHex(l.glintColor());
                    yield l.glintColor();
                }
                case GLINT_SPEED -> SpeedSlider.format(l.glintSpeed());
                case GLINT_STRENGTH -> SpeedSlider.format(l.glintStrength());
            };
            this.value = v;
            this.color = col | 0xFF000000;
            this.dye = d;
            this.swatch = sw;
        }

        @Override
        public int height() {
            return PART_H;
        }

        @Override
        public boolean hoverable() {
            return true;
        }

        private int xAt(int x, int w) {
            return x + w - X_BIG - 4 + (X_BIG - X_SMALL) / 2;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            g.fill(x + 11, y, x + 12, y + PART_H, LINE);
            g.text(font, p.label(), x + 24, y + 2, MUTED);
            int vx = x + 24 + LABEL_W, vw = xAt(x, w) - 4 - vx;
            if (dye != null || swatch >= 0) {
                g.fill(vx, y + 2, vx + 7, y + 9, 0xFF000000);
                g.fill(vx + 1, y + 3, vx + 6, y + 8, 0xFF000000 | (dye != null ? dye.rgbAt(tick, 0) : swatch));
                vx += 10;
                vw -= 10;
            }
            if (p == Part.NAME) {
                if (vw != nameWidth) {
                    nameWidth = vw;
                    name = NameBox.fit(font, Names.parse(value), Math.max(0, vw));
                }
                g.text(font, name, vx, y + 2, TEXT);
            } else {
                g.text(font, clip(value, vw), vx, y + 2, color);
            }
            int bx = xAt(x, w);
            drawX(g, font, bx, y + 1, X_SMALL - 1, in(mouseX, mouseY, bx - 1, y, X_SMALL + 1, PART_H));
        }

        @Override
        public boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            if (!in(mouseX, mouseY, xAt(x, w) - 1, y, X_SMALL + 1, PART_H)) return true;
            removePart(e, p);
            return true;
        }

        @Override
        public List<Component> tooltip(int x, int y, int w, int mouseX, int mouseY) {
            if (in(mouseX, mouseY, xAt(x, w) - 1, y, X_SMALL + 1, PART_H)) {
                return List.of(Component.translatable("skycosmetics.saved.removePart", p.label().toLowerCase(Locale.ROOT))
                    .withStyle(ChatFormatting.RED));
            }
            return p == Part.NAME ? List.of(Names.parse(value)) : null;
        }

        @Override
        public String toString() {
            return "  " + p.label() + ": " + (p == Part.NAME ? Names.parse(value).getString() : value);
        }
    }

    /** Under an open look: opens it in the editor, on its item (or an item of its type). */
    private final class EditLink implements Line {
        private final Entry e;

        EditLink(Entry e) {
            this.e = e;
        }

        @Override
        public int height() {
            return LINK_H;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY, long tick) {
            g.fill(x + 11, y, x + 12, y + LINK_H - 3, LINE);
            String text = tr("skycosmetics.saved.edit");
            boolean over = in(mouseX, mouseY, x + 24, y, font.width(text), LINK_H);
            g.text(font, text, x + 24, y + 2, over ? 0xFFF0C8FF : ACCENT);
            if (over) g.fill(x + 24, y + 11, x + 24 + font.width(text), y + 12, 0xFFF0C8FF);
        }

        @Override
        public boolean click(int x, int y, int w, int mouseX, int mouseY, int button) {
            if (in(mouseX, mouseY, x + 24, y, font.width(tr("skycosmetics.saved.edit")), LINK_H)) host.edit(e.type(), e.key());
            return true;
        }

        @Override
        public String toString() {
            return "  [Edit]";
        }
    }

    // -------------------------------------------------------- test hooks ---

    /** Opens or closes the row of the look on {@code key} ("T" + type or "I" + UUID). */
    void setOpen(String key, boolean isOpen) {
        if (isOpen ? open.add(key) : open.remove(key)) openVersion++;
    }

    /**
     * Where the remove button of {@code key}'s row is, then each of its open parts' (x, y pairs), as drawn last
     * frame; null when it is not listed.
     */
    int[] buttons(String key) {
        int x = drawnX(), w = drawnRowWidth(), ly = drawnY() - scroll();
        List<Integer> out = new ArrayList<>();
        boolean in = false;
        for (Line l : lines()) {
            if (l instanceof Row r) {
                in = r.e.id().equals(key);
                if (in) {
                    out.add(r.xAt(x, w) + X_BIG / 2);
                    out.add(ly + 4 + X_BIG / 2);
                }
            } else if (in && l instanceof PartRow p) {
                out.add(p.xAt(x, w) + X_SMALL / 2);
                out.add(ly + PART_H / 2);
            } else if (!(l instanceof PartRow) && !(l instanceof EditLink)) {
                in = false;
            }
            ly += l.height();
        }
        return out.isEmpty() ? null : out.stream().mapToInt(Integer::intValue).toArray();
    }
}
