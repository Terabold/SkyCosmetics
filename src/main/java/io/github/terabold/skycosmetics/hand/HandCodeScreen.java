package io.github.terabold.skycosmetics.hand;

import io.github.terabold.skycosmetics.gui.hub.*;
import io.github.terabold.skycosmetics.gui.ui.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Share codes: copy your viewmodel as one line of text, or apply one somebody sent you. */
final class HandCodeScreen extends Screen {
    private static final int HEIGHT = 172;
    private final Screen parent;
    private String code = "", status = "";
    private int x, y, w;
    HandCodeScreen(Screen parent) { super(Component.literal("Share viewmodels")); this.parent = parent; }
    private void button(String label, int bx, int by, int bw, Runnable action) {
        var b = addRenderableWidget(new ActionButton(Component.literal(label), bw, 22, ActionButton.Kind.SECONDARY, v -> action.run()));
        b.setPosition(bx, by);
    }
    @Override protected void init() {
        w = Math.min(440, width - 24); x = (width - w) / 2; y = Math.max(8, (height - HEIGHT) / 2);
        var input = addRenderableWidget(new TextField(font, x + 12, y + 60, w - 24, 22, Component.literal("SkyCosmetics viewmodel code")));
        input.setMaxLength(32 * 1024); input.setHint(Component.literal("Paste a skyhand1: code")); input.setValue(code); input.setResponder(v -> code = v);
        int bw = (w - 32) / 3;
        button("Apply code", x + 12, y + 88, bw, () -> {
            var setup = HandCode.decode(code);
            if (setup == null) { status = "Invalid or unsupported viewmodel code."; return; }
            Hand.apply(setup); Hand.save(); status = "Viewmodel applied. Your previous setup is preserved.";
        });
        button("Paste", x + 16 + bw, y + 88, bw, () -> {
            String value = minecraft.keyboardHandler.getClipboard();
            if (value.length() > 32 * 1024) { status = "This code is too long."; return; }
            code = value; input.setValue(value);
        });
        button("Copy current", x + 20 + 2 * bw, y + 88, bw, () -> {
            minecraft.keyboardHandler.setClipboard(HandCode.code(Hand.setup())); status = "Current viewmodel code copied.";
        });
        button("Done", x + w - 92, y + HEIGHT - 34, 80, this::onClose); setInitialFocus(input);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Shapes.round(g, x, y, w, HEIGHT, Theme.RADIUS, Theme.BODY);
        g.centeredText(font, title, x + w / 2, y + 12, Theme.TEXT);
        g.text(font, "Codes share SkyCosmetics viewmodels only.", x + 12, y + 36, Theme.MUTED, false);
        if (!status.isEmpty()) {
            int sy = y + 118;
            for (var line : font.split(Component.literal(status), w - 24)) { g.text(font, line, x + 12, sy, Theme.MUTED, false); sy += 9; }
        }
        super.extractRenderState(g, mx, my, dt);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
