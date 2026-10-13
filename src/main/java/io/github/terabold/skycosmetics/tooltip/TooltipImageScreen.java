package io.github.terabold.skycosmetics.tooltip;

import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.TextField;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Drag a PNG in or paste its path; the file is copied locally and never uploaded. */
public final class TooltipImageScreen extends Screen {
    private final Screen parent;
    private TextField path;
    private int x, y, w;
    private ActionButton load, remove;

    public TooltipImageScreen(Screen parent) {
        super(TooltipSection.text("image.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        w = Math.min(340, width - 24);
        x = (width - w) / 2;
        y = Math.max(12, (height - 220) / 2);
        var choose = addRenderableWidget(new ActionButton(Component.literal("Choose PNG image…"), w - 24, true, b -> chooseFile()));
        choose.setPosition(x + 12, y + 50);
        path = addRenderableWidget(new TextField(font, x + 12, y + 78, w - 24, 20, TooltipSection.text("image.path")));
        path.setMaxLength(1024);
        path.setHint(TooltipSection.text("image.path"));
        load = addRenderableWidget(new ActionButton(TooltipSection.text("image.load"), (w - 30) / 2, false, b -> {
            try {
                String s = path.getValue().strip();
                if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
                if (!s.isEmpty()) TooltipImage.importFile(Path.of(s));
            } catch (java.nio.file.InvalidPathException e) {
                path.setTextColor(Theme.WARN);
            }
        }));
        load.setPosition(x + 12, y + 104);
        ActionButton back = addRenderableWidget(new ActionButton(TooltipSection.text("image.done"), (w - 30) / 2, true, b -> onClose()));
        back.setPosition(x + 18 + (w - 30) / 2, y + 104);
        remove = addRenderableWidget(new ActionButton(TooltipSection.text("image.remove"), w - 24, false, b -> TooltipImage.remove()));
        remove.setPosition(x + 12, y + 130);
    }

    private void chooseFile() {
        if (TooltipImage.busy()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.png")).flip();
            String selected = TinyFileDialogs.tinyfd_openFileDialog("Choose tooltip background", "", filters, "PNG image", false);
            if (selected != null) TooltipImage.importFile(Path.of(selected));
        } catch (RuntimeException | LinkageError e) {
            io.github.terabold.skycosmetics.Io.failed("Opening the image file picker", e);
            path.setHint(Component.literal("Drop a PNG or paste its path here"));
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (!files.isEmpty()) TooltipImage.importFile(files.getFirst());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        Shapes.round(g, x, y, w, 200, Theme.RADIUS, Theme.BODY);
        Shapes.frame(g, x, y, w, 200, Theme.RADIUS, Theme.LINE);
        g.centeredText(font, title, width / 2, y + 12, Theme.ACCENT);
        g.centeredText(font, TooltipSection.text("image.drop"), width / 2, y + 30, Theme.TEXT);
        g.centeredText(font, TooltipSection.text("image.limit"), width / 2, y + 158, Theme.MUTED);
        g.centeredText(font, Component.literal(TooltipImage.status()), width / 2, y + 176, Theme.TEXT);
        load.active = !TooltipImage.busy() && !path.getValue().isBlank();
        remove.active = !TooltipImage.busy() && TooltipImage.loaded();
        super.extractRenderState(g, mx, my, dt);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
