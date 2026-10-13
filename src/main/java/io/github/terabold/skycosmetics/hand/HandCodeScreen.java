package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.gui.hub.*;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.EnumMap;

/** Imports are explicit conversions; share codes contain only this mod's bounded viewmodel data. */
final class HandCodeScreen extends Screen {
    private final Screen parent;
    private final EnumMap<HandImport.Source, HandImport.Found> found = new EnumMap<>(HandImport.Source.class);
    private boolean scanned;
    private String code = "", status = "";
    private int x, y, w;
    HandCodeScreen(Screen parent) { super(Component.literal("Import and share viewmodels")); this.parent = parent; }
    private void button(String label, int bx, int by, int bw, Runnable action, boolean active) {
        var b = addRenderableWidget(new ActionButton(Component.literal(label), bw, 22, ActionButton.Kind.SECONDARY, v -> action.run()));
        b.setPosition(bx, by); b.active = active;
    }
    @Override protected void init() {
        w = Math.min(440, width - 24); x = (width - w) / 2; y = Math.max(8, (height - 228) / 2);
        if (!scanned) {
            for (var source : HandImport.Source.values()) { var result = HandImport.find(source); if (result != null) found.put(source, result); }
            scanned = true;
        }
        var input = addRenderableWidget(new TextField(font, x + 12, y + 60, w - 24, 22, Component.literal("SkyCosmetics viewmodel code")));
        input.setMaxLength(32 * 1024); input.setHint(Component.literal("Paste a skyhand1: code")); input.setValue(code); input.setResponder(v -> code = v);
        int bw = (w - 32) / 3;
        button("Apply code", x + 12, y + 88, bw, () -> {
            var setup = HandImport.decode(code);
            if (setup == null) { status = "Invalid or unsupported viewmodel code."; return; }
            Hand.apply(setup); Hand.save(); status = "Viewmodel applied. Your previous setup is preserved.";
        }, true);
        button("Paste", x + 16 + bw, y + 88, bw, () -> {
            String value = minecraft.keyboardHandler.getClipboard();
            if (value.length() > 32 * 1024) { status = "This code is too long."; return; }
            code = value; input.setValue(value);
        }, true);
        button("Copy current", x + 20 + 2 * bw, y + 88, bw, () -> {
            minecraft.keyboardHandler.setClipboard(HandImport.code(Hand.setup())); status = "Current viewmodel code copied.";
        }, true);
        int i = 0;
        for (var source : HandImport.Source.values()) {
            button(source.name, x + 12 + i++ * (bw + 4), y + 150, bw, () -> {
                var result = found.get(source); Hand.apply(result.setup().deepCopy()); Hand.save();
                status = "Converted " + source.name + " settings." + (result.on() ? " Disable its viewmodel to avoid stacking." : "");
            }, found.containsKey(source));
        }
        button("Done", x + w - 92, y + 194, 80, this::onClose, true); setInitialFocus(input);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Shapes.round(g, x, y, w, 228, Theme.RADIUS, Theme.BODY);
        g.centeredText(font, title, x + w / 2, y + 12, Theme.TEXT);
        g.text(font, "Codes share SkyCosmetics viewmodels only.", x + 12, y + 36, Theme.MUTED, false);
        g.text(font, "Import from another mod's local config", x + 12, y + 126, Theme.TEXT, false);
        if (!status.isEmpty()) {
            int sy = y + 176;
            for (var line : font.split(Component.literal(status), w - 24)) { g.text(font, line, x + 12, sy, Theme.MUTED, false); sy += 9; }
        }
        super.extractRenderState(g, mx, my, dt);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
