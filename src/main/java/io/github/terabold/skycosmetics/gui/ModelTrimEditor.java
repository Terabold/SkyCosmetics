package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.gui.hub.*;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.gui.ui.Ui;
import io.github.terabold.skycosmetics.render.ItemModels;
import io.github.terabold.skycosmetics.render.Trims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Inline controls share Studio's selected item, save scope and live preview. */
final class ModelTrimEditor {
    private record Model(String id, String name, ItemStack icon) {}
    private record Choice(String id, Component name) {}
    private List<Model> models;
    private String query = "";
    private boolean trims;
    private int page, pages, x, width, modelTop, materialTop, bottom;
    private String activeLabel = "";
    private final Runnable rebuild;
    private final Consumer<UnaryOperator<Looks.Look>> edit;
    ModelTrimEditor(Runnable rebuild, Consumer<UnaryOperator<Looks.Look>> edit) {
        this.rebuild = rebuild; this.edit = edit;
    }

    int build(Consumer<AbstractWidget> add, ItemStack item, Looks.Look own, Looks.Look inherited, int x, int y, int w, int end) {
        this.x = x; width = w; bottom = end;
        boolean armor = Trims.allowed(item);
        if (!armor) { trims = false; return buildModels(add, item, own, inherited, y); }
        int half = (w - 4) / 2;
        boolean wrap = armor && half < Minecraft.getInstance().font.width("Armor Trims") + 12;
        add.accept(at(new TabButton(Component.literal("Item Model").withColor(Theme.ACCENT & 0xFFFFFF), armor && !wrap ? half : w, 26, !trims || !armor,
            b -> { trims = false; rebuild.run(); }), x, y));
        if (armor) add.accept(at(new TabButton(Component.literal("Armor Trims").withColor(Theme.ACCENT & 0xFFFFFF), wrap ? w : w - half - 4, 26, trims,
            b -> { trims = true; rebuild.run(); }), wrap ? x : x + half + 4, wrap ? y + 30 : y));
        int start = y + (wrap ? 60 : 30);
        if (armor && trims) return buildTrims(add, item, own, inherited, start);
        return buildModels(add, item, own, inherited, start);
    }

    private int buildModels(Consumer<AbstractWidget> add, ItemStack item, Looks.Look own, Looks.Look inherited, int y) {
        if (models == null) models = BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
            .filter(id -> ItemModels.valid(id) != null).map(id -> {
                var icon = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
                return new Model(id, icon.getHoverName().getString(), icon);
            }).sorted(Comparator.comparing(Model::name, String.CASE_INSENSITIVE_ORDER)).toList();
        var search = new SearchBox(Minecraft.getInstance().font, width, 18, Component.literal("Search models"), Component.literal("Search vanilla items"));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; rebuild.run(); });
        add.accept(at(search, x, y)); y += 22;
        int bw = (width - 4) / 2;
        boolean shared = inherited.model() != null && !inherited.model().equals("original");
        boolean wrap = shared && bw < Minecraft.getInstance().font.width("Use Shared Model") + 12;
        button(add, "Reset Model", x, y, shared && !wrap ? bw : width, () -> model(shared ? "original" : null));
        if (shared) button(add, "Use Shared Model", wrap ? x : x + bw + 4, wrap ? y + 24 : y,
            wrap ? width : width - bw - 4, () -> model(null)); y += wrap ? 48 : 24;
        var ident = Cosmetics.identify(item);
        String axe = ident == null ? null : ItemModels.oldAxe(ident.type());
        if (axe != null) { button(add, "Original Axe", x, y, width, () -> model(axe)); y += 24; }
        String current = own.model() != null ? own.model() : inherited.model();
        activeLabel = current == null || current.equals("original") ? "Original appearance"
            : models.stream().filter(c -> c.id().equals(current)).map(Model::name).findFirst().orElse("Original appearance");
        modelTop = y + 16;
        int cols = Math.max(1, width / 28), rows = Math.max(1, (bottom - modelTop - 28) / 28), capacity = cols * rows;
        String q = query.strip().toLowerCase(Locale.ROOT);
        var choices = models.stream().filter(c -> (c.id() + " " + c.name()).toLowerCase(Locale.ROOT).contains(q)).toList();
        pages = Math.max(1, (choices.size() + cols - 1) / cols - rows + 1); page = Math.clamp(page, 0, pages - 1);
        for (int i = page * cols; i < Math.min(choices.size(), page * cols + capacity); i++) {
            var c = choices.get(i); int n = i - page * cols;
            var tile = new ItemTile(c.name(), c.icon(), 26, 26, c.id().equals(current), () -> model(c.id()));
            tile.itemTooltip = tile.addMarker = false; Tips.set(tile, Component.literal(c.name()));
            add.accept(at(tile, x + n % cols * 28, modelTop + n / cols * 28));
        }
        int by = modelTop + rows * 28 + 2;
        button(add, "Previous models", x, by, bw, () -> { page = Math.max(0, page - rows); rebuild.run(); }).active = page > 0;
        button(add, "Next models", x + bw + 4, by, width - bw - 4, () -> { page = Math.min(pages - 1, page + rows); rebuild.run(); }).active = page + 1 < pages;
        return by + 20;
    }
    private void model(String value) { edit.accept(l -> l.withModel(value)); rebuild.run(); }

    private int buildTrims(Consumer<AbstractWidget> add, ItemStack item, Looks.Look own, Looks.Look inherited, int y) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return y;
        var registry = mc.level.registryAccess();
        List<Choice> patterns = registry.lookupOrThrow(Registries.TRIM_PATTERN).listElements()
            .map(r -> new Choice(r.key().identifier().toString(), r.value().description()))
            .sorted(Comparator.comparing(c -> c.name().getString())).toList();
        List<Choice> materials = registry.lookupOrThrow(Registries.TRIM_MATERIAL).listElements()
            .map(r -> new Choice(r.key().identifier().toString(), r.value().description()))
            .sorted(Comparator.comparing(c -> c.name().getString())).toList();
        String token = own.trim() != null ? own.trim() : inherited.trim();
        var existing = item.get(DataComponents.TRIM);
        String pattern = "minecraft:sentry", material = "minecraft:gold";
        if (existing != null) { pattern = existing.pattern().unwrapKey().map(k -> k.identifier().toString()).orElse(pattern);
            material = existing.material().unwrapKey().map(k -> k.identifier().toString()).orElse(material); }
        if (token != null && !token.equals("off")) { String[] parts = token.split("\\|"); pattern = parts[0]; material = parts[1]; }
        final String p = pattern, m = material;
        button(add, "Remove Trim", x, y, width, () -> trim("off")); y += 24;
        activeLabel = token == null && existing == null || "off".equals(token) ? "No trim" :
            patterns.stream().filter(c -> c.id().equals(p)).map(c -> c.name().getString().replace(" Armor Trim", "")).findFirst().orElse("Sentry")
            + " in " + materials.stream().filter(c -> c.id().equals(m)).map(c -> c.name().getString().replace(" Material", "")).findFirst().orElse("Gold");
        modelTop = y + 16;
        int cols = Math.max(1, width / 38);
        // Restyle once, then preserve its render marker so the chosen icon trim cannot be restyled again.
        var base = Cosmetics.forRender(item, null);
        for (int i = 0; i < patterns.size(); i++) {
            var c = patterns.get(i);
            ItemStack icon = Cosmetics.copied(base, base.copy());
            Trims.apply(icon, Trims.token(c.id(), material));
            add.accept(at(new TrimIcon(c.name(), icon, 36, token == null ? existing != null && c.id().equals(p) : !token.equals("off") && c.id().equals(p),
                () -> trim(Trims.token(c.id(), m))), x + i % cols * 38, modelTop + i / cols * 38));
        }
        y = modelTop + ((patterns.size() + cols - 1) / cols) * 38 + 20;
        materialTop = y;
        int materialCols = Math.max(1, width / 28);
        for (int i = 0; i < materials.size(); i++) {
            var c = materials.get(i);
            int color = registry.lookupOrThrow(Registries.TRIM_MATERIAL).get(Identifier.parse(c.id())).orElseThrow().value().description().getStyle().getColor() == null
                ? 0xFFFFFF : registry.lookupOrThrow(Registries.TRIM_MATERIAL).get(Identifier.parse(c.id())).orElseThrow().value().description().getStyle().getColor().getValue();
            var tile = new SwatchButton(c.name(), 26, 26, new int[]{color}, b -> trim(Trims.token(p, c.id()))).chosen(c.id().equals(m));
            Tips.set(tile, c.name()); add.accept(at(tile, x + i % materialCols * 28, y + i / materialCols * 28));
        }
        return y + ((materials.size() + materialCols - 1) / materialCols) * 28;
    }
    private void trim(String value) { edit.accept(l -> l.withTrim(value)); rebuild.run(); }
    boolean wheel(double mx, double my, double dy) {
        if (trims || mx < x || mx >= x + width || my < modelTop || my >= bottom || dy == 0) return false;
        page = Math.clamp(page - (int)Math.signum(dy), 0, pages - 1); rebuild.run(); return true;
    }
    void draw(GuiGraphicsExtractor g, int offset) {
        var font = Minecraft.getInstance().font;
        g.text(font, Ui.clip(font, activeLabel, width), x, modelTop - 13 - offset, Theme.ACCENT, false);
        if (trims) g.text(font, "Trim color", x, materialTop - 13 - offset, Theme.MUTED, false);
    }
    private static <T extends AbstractWidget> T at(T w, int x, int y) { w.setPosition(x,y); return w; }
    private static ActionButton button(Consumer<AbstractWidget> add, String title, int x, int y, int width, Runnable action) {
        var b = at(new ActionButton(Component.literal(title), width, 20, ActionButton.Kind.SECONDARY, v -> action.run()),x,y);
        add.accept(b); return b;
    }
}
