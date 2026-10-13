package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.gui.hub.*;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary management keeps naming, replacement and old saved setups out of the main settings pane. */
final class HandPresetScreen extends Screen {
    private final Screen parent;
    private String selected, name = "", status = "";
    private Hand.Preset undo;
    private int page, x, y, w;
    HandPresetScreen(Screen parent, String selected) {
        super(Component.literal("Saved viewmodel setups")); this.parent = parent; this.selected = selected;
    }
    private ActionButton button(String label, int bx, int by, int bw, Runnable action, boolean active) {
        var b = addRenderableWidget(new ActionButton(Component.literal(label), bw, 22, ActionButton.Kind.SECONDARY, v -> action.run()));
        b.setPosition(bx, by); b.active = active; return b;
    }
    @Override protected void init() {
        w = Math.min(420, width - 24); x = (width - w) / 2; y = Math.max(8, (height - 246) / 2);
        var presets = Hand.presets();
        var preset = presets.stream().filter(p -> p.name().equals(selected)).findFirst().orElse(null);
        if (preset == null) {
            selected = null; int pages = Math.max(1, (presets.size() + 5) / 6); page = Math.clamp(page, 0, pages - 1);
            for (int i = page * 6; i < Math.min(presets.size(), (page + 1) * 6); i++) {
                var p = presets.get(i); int local = i - page * 6;
                button(p.name(), x + 12 + local % 2 * ((w - 28) / 2 + 4), y + 42 + local / 2 * 34,
                    (w - 28) / 2, () -> { selected = p.name(); status = ""; rebuildWidgets(); }, true);
            }
            button("Previous", x + 12, y + 152, 90, () -> { page--; rebuildWidgets(); }, page > 0);
            button("Next", x + 106, y + 152, 90, () -> { page++; rebuildWidgets(); }, page + 1 < pages);
        } else {
            name = preset.name();
            var input = addRenderableWidget(new TextField(font, x + 12, y + 44, w - 24, 24, Component.literal("Setup name")));
            input.setMaxLength(Hand.MAX_NAME); input.setValue(name);
            var rename = button("Rename", x + 12, y + 78, (w - 28) / 2, () -> {
                String next = Hand.cleanName(name, "");
                if (next.isEmpty() || Hand.presets().stream().anyMatch(p -> p.name().equals(next) && !p.name().equals(selected))) return;
                Hand.removePreset(selected); Hand.restorePreset(new Hand.Preset(next, preset.setup().deepCopy()));
                selected = next; Hand.save(); status = "Setup renamed."; rebuildWidgets();
            }, true);
            input.setResponder(value -> {
                name = value; String clean = Hand.cleanName(value, "");
                rename.active = !clean.isEmpty() && Hand.presets().stream().noneMatch(p -> p.name().equals(clean) && !p.name().equals(selected));
            });
            button("Replace with current", x + 16 + (w - 28) / 2, y + 78, (w - 28) / 2, () -> {
                undo = preset; Hand.savePreset(selected); Hand.save(); status = "Saved your current viewmodel in this slot."; rebuildWidgets();
            }, true);
            button("Apply", x + 12, y + 110, (w - 32) / 3, () -> { Hand.apply(preset.setup().deepCopy()); Hand.save(); status = "Setup applied."; }, true);
            button("Copy code", x + 16 + (w - 32) / 3, y + 110, (w - 32) / 3, () -> {
                minecraft.keyboardHandler.setClipboard(HandCode.code(preset.setup())); status = "Setup code copied.";
            }, true);
            button("Delete", x + 20 + 2 * ((w - 32) / 3), y + 110, (w - 32) / 3, () -> {
                undo = Hand.removePreset(selected); selected = null; Hand.save(); status = "Setup deleted. Undo is available."; rebuildWidgets();
            }, true);
            button("All setups", x + 12, y + 152, 110, () -> { selected = null; rebuildWidgets(); }, true);
            setInitialFocus(input);
        }
        button("Undo", x + 12, y + 208, 80, () -> {
            Hand.removePreset(undo.name()); Hand.restorePreset(undo); selected = undo.name(); undo = null;
            Hand.save(); status = "Previous saved setup restored."; rebuildWidgets();
        }, undo != null);
        button("Done", x + w - 92, y + 208, 80, this::onClose, true);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Shapes.round(g, x, y, w, 246, Theme.RADIUS, Theme.BODY);
        g.centeredText(font, title, x + w / 2, y + 12, Theme.TEXT);
        int sy = y + 180;
        for (var line : font.split(Component.literal(status), w - 24)) { g.text(font, line, x + 12, sy, Theme.MUTED, false); sy += 9; }
        super.extractRenderState(g, mx, my, dt);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
