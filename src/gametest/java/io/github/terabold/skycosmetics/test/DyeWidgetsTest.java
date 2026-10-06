package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.AnimatedDyeEditor;
import io.github.terabold.skycosmetics.gui.ColorPicker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/**
 * Dyes: the Fairy colour cycles are gone from the catalogue, custom animated
 * dye ids round-trip and blend as intended, and the colour picker and animated
 * dye editor render and respond to real mouse and keyboard input.
 */
final class DyeWidgetsTest {
    private DyeWidgetsTest() {}

    /** Pure data checks; run on the client thread once the repo is loaded. */
    static void checkData() {
        Catalog c = Repo.get();
        check(c.dye("FAIRY_HELMET") == null && c.dye("FAIRY_BOOTS") == null, "Fairy color cycles are not dyes");
        long animatedRepo = c.dyeTab.stream().filter(DyeEntry::animated).count();
        check(c.dyeTab.stream().filter(DyeEntry::animated).allMatch(d -> d.id.startsWith("DYE_")),
            "every animated catalog dye is a DYE_ item");
        check(c.dye("INK_SACK-4") != null && c.dye("INK_SACK-4").vanillaItem != null, "vanilla dyes are kept");
        System.out.println("[SkyCosmeticsTest] dye catalog: " + c.dyeTab.size() + " dyes, " + animatedRepo + " animated");

        DyeEntry aurora = c.dye("DYE_AURORA");
        check(aurora.ticksPerColor == 2 && aurora.pieceOffset == 5, "Hypixel dyes keep 2 ticks and a 5-color piece offset");
        check(aurora.rgbAt(4, 1) == aurora.colors[2 + 5], "Hypixel ripple timing unchanged");

        int[] rgb = {0xFF0000, 0x00FF00, 0x0000FF};
        String id = DyeEntry.customAnimatedId(3, true, rgb);
        check(id.equals("anim:3:blend:#FF0000,#00FF00,#0000FF"), "anim id format: " + id);
        check(DyeEntry.isCustomAnimated(id) && !DyeEntry.isCustomAnimated("DYE_AURORA")
            && !DyeEntry.isCustomAnimated(null), "isCustomAnimated");
        DyeEntry.AnimSpec spec = DyeEntry.parseCustomAnimated(id);
        check(spec != null && spec.ticksPerStep() == 3 && spec.blend() && Arrays.equals(spec.keyframes(), rgb)
            && spec.id().equals(id), "anim id round trip");
        DyeEntry.AnimSpec loose = DyeEntry.parseCustomAnimated("ANIM:3:Blend:ff0000, 00ff00 ,#0000ff");
        check(loose != null && loose.id().equals(id), "case, spaces and '#' are optional");
        for (String bad : new String[]{"anim:0:blend:#FF0000", "anim:201:step:#FF0000", "anim:2:wave:#FF0000",
            "anim:2:blend:", "anim:2:blend:#GG0000", "anim:x:step:#FF0000", "anim:2:step", "anim:2:step:#FF0000,"}) {
            check(DyeEntry.parseCustomAnimated(bad) == null && c.dye(bad) == null, "malformed id rejected: " + bad);
        }

        int[] blend = spec.colors();
        check(blend.length == 3 * DyeEntry.BLEND_STEPS, "blend makes 10 colors per keyframe pair");
        check(blend[0] == 0xFF0000 && blend[10] == 0x00FF00 && blend[20] == 0x0000FF, "keyframes stay exact");
        int mid = blend[5];
        check((mid >> 16 & 0xFF) > 0xB0 && (mid >> 8 & 0xFF) > 0x90 && (mid & 0xFF) < 0x20,
            "OkLab red-green midpoint stays bright: " + Integer.toHexString(mid));
        for (int j = 1; j < DyeEntry.BLEND_STEPS; j++) {
            check((blend[20 + j] & 0xFF) <= (blend[20 + j - 1] & 0xFF) && (blend[20 + j] >> 16 & 0xFF) >= (blend[20 + j - 1] >> 16 & 0xFF),
                "blue fades back to red monotonically");
        }
        for (int col : new int[]{0x123456, 0xFF00AA, 0x808080, 0x000000, 0xFFFFFF, 0x00FF88}) {
            int same = DyeEntry.mixOklab(col, col, 0.5f);
            for (int s = 0; s <= 16; s += 8) {
                check(Math.abs((same >> s & 0xFF) - (col >> s & 0xFF)) <= 1, "OkLab round trip " + Integer.toHexString(col));
            }
        }

        DyeEntry d = c.dye(id);
        check(d != null && d == c.dye(id), "Catalog caches anim dyes");
        check(d.animated() && d.ticksPerColor == 3 && d.colors.length == 30 && d.pieceOffset == 1, "blend entry timing");
        check(d.rgbAt(0, 0) == d.colors[0] && d.rgbAt(2, 0) == d.colors[0] && d.rgbAt(3, 0) == d.colors[1]
            && d.rgbAt(0, 3) == d.colors[3] && d.rgbAt(89, 0) == d.colors[29] && d.rgbAt(90, 0) == d.colors[0],
            "rgbAt honors per-entry ticks and offset");
        DyeEntry step = c.dye(DyeEntry.customAnimatedId(20, false, rgb));
        check(step.colors.length == 3 && step.pieceOffset == 0 && step.rgbAt(39, 3) == 0x00FF00 && step.rgbAt(40, 0) == 0x0000FF,
            "step entry: keyframes as is, all pieces together");
        DyeEntry single = c.dye("anim:2:blend:#123456");
        check(single != null && !single.animated() && single.rgbAt(77, 2) == 0x123456, "one keyframe is a still color");
        System.out.println("[SkyCosmeticsTest] custom animated dye checks passed");
    }

    /** A custom animated dye on real armour: the render copy is tinted with the dye's current colour. */
    static void checkOnArmour(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String id = DyeEntry.customAnimatedId(100, false, new int[]{0x11AA33, 0xAA3311});
        sp.getServer().runCommand("item replace entity @a hotbar.5 with minecraft:leather_boots[minecraft:custom_data={id:\"FARM_SUIT_BOOTS\",uuid:\"t-anim\"}]");
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> Looks.put(false, "t-anim", new Looks.Look(null, id, null, null, "anim boots")));
        ctx.waitTicks(2);
        boolean ok = ctx.computeOnClient(mc -> {
            DyeEntry dye = Repo.get().dye(id);
            long before = Util.getMillis() / 50;
            ItemStack shown = Cosmetics.apply(mc.player.getInventory().getItem(5), true);
            long after = Util.getMillis() / 50;
            DyedItemColor tint = shown.get(DataComponents.DYED_COLOR);
            return tint != null && (tint.rgb() == dye.rgbAt(before, 0) || tint.rgb() == dye.rgbAt(after, 0));
        });
        System.out.println("[SkyCosmeticsTest] custom animated dye on boots: " + ok);
        check(ok, "custom animated dye tints armor");
    }

    /** Shows both widgets, drags, clicks and types into them, and screenshots before and after. */
    static void run(ClientGameTestContext ctx) {
        ctx.setScreen(WidgetScreen::new);
        ctx.waitTicks(5);
        ctx.takeScreenshot("skycosmetics-09b-dye-widgets");

        WidgetScreen s = ctx.computeOnClient(mc -> (WidgetScreen) mc.screen);
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int px = s.picker.x(), py = s.picker.y(), ph = s.picker.height();
        int start = s.picker.rgb();

        // Drag across the square: top-left (pale) towards the right (saturated), then let go.
        ctx.getInput().setCursorPos((px + 4) * scale, (py + 4) * scale);
        ctx.waitTicks(1);
        ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        for (int i = 1; i <= 5; i++) {
            ctx.getInput().moveCursor(18 * scale, 3 * scale);
            ctx.waitTicks(1);
        }
        ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(2);
        int dragged = ctx.computeOnClient(mc -> s.picker.rgb());
        System.out.println("[SkyCosmeticsTest] picker drag: " + hex(start) + " -> " + hex(dragged) + ", commits=" + s.commits);
        check(dragged != start && s.commits == 1 && ColorPicker.recentColours().contains(dragged),
            "dragging the square changes the color and commits once on release");
        check(s.changes > 1, "drag reports live changes: " + s.changes);

        // Hex box: the first click selects it all, so typing replaces the value; Enter commits.
        int hexY = py + ph - 22;
        ctx.getInput().setCursorPos((px + 30) * scale, hexY * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> s.picker.isEditing()), "clicking the hex box focuses it");
        ctx.getInput().typeChars("#00FF88");
        ctx.waitTicks(1);
        int live = ctx.computeOnClient(mc -> s.picker.rgb());
        System.out.println("[SkyCosmeticsTest] picker hex while typing: " + hex(live) + ", changes=" + s.changes);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        ctx.waitTicks(2);
        int typed = ctx.computeOnClient(mc -> s.picker.rgb());
        System.out.println("[SkyCosmeticsTest] picker hex: " + hex(typed));
        check(typed == 0x00FF88 && !ctx.computeOnClient(mc -> s.picker.isEditing()) && s.commits == 2,
            "typing a hex value sets the color and Enter commits");

        // Editor: add a keyframe, switch to Step, drag the speed to the fast end.
        int ex = s.editor.x0, ey = s.editor.y0;
        String before = s.editor.widget.dyeId();
        click(ctx, scale, ex + 2 * 17 + 7, ey + 7);
        String added = ctx.computeOnClient(mc -> s.editor.widget.dyeId());
        check(DyeEntry.parseCustomAnimated(added).keyframes().length == 3, "+ adds a keyframe: " + added);
        int sideW = Math.clamp(s.editor.w * 2 / 5, 70, 110), sideX = ex + s.editor.w - sideW;
        int toggleY = ey + 14 + 8 + 10;
        click(ctx, scale, sideX + 6, toggleY + 6);
        int speedY = toggleY + 14 + 9 + 8;
        click(ctx, scale, sideX + sideW - 1, speedY + 4);
        String edited = ctx.computeOnClient(mc -> s.editor.widget.dyeId());
        System.out.println("[SkyCosmeticsTest] editor: " + before + " -> " + edited + ", last committed " + s.lastAnim);
        DyeEntry.AnimSpec spec = DyeEntry.parseCustomAnimated(edited);
        check(spec != null && !spec.blend() && spec.ticksPerStep() == 1 && spec.keyframes().length == 3
            && edited.equals(s.lastAnim), "editor: Step toggle, fastest speed, 3 keyframes, committed");
        // Selecting a chip loads its colour into the editor's picker.
        click(ctx, scale, ex + 7, ey + 7);
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-09c-dye-widgets-edited");
        // Right-click removes a keyframe (never below two).
        ctx.getInput().setCursorPos((ex + 17 + 7) * scale, (ey + 7) * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(1);
        String removed = ctx.computeOnClient(mc -> s.editor.widget.dyeId());
        check(DyeEntry.parseCustomAnimated(removed).keyframes().length == 2 && removed.equals(s.lastAnim),
            "right-click removes a keyframe, but keeps two: " + removed);
        // The live preview must also cope with a blend dye at the slowest speed.
        ctx.runOnClient(mc -> s.editor.widget.load(DyeEntry.customAnimatedId(100, true,
            new int[]{0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x000000})));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> s.editor.widget.load("DYE_AURORA"));
        String fromRepo = ctx.computeOnClient(mc -> s.editor.widget.dyeId());
        System.out.println("[SkyCosmeticsTest] editor from DYE_AURORA: " + fromRepo);
        check(DyeEntry.parseCustomAnimated(fromRepo).keyframes().length == 6, "a Hypixel dye loads as 6 keyframes");
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-09d-dye-editor-from-aurora");
        ctx.setScreen(() -> null);
    }

    private static void click(ClientGameTestContext ctx, double scale, int x, int y) {
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }

    /** Hosts both widgets the way a real screen would: forwards every mouse and key event. */
    static final class WidgetScreen extends Screen {
        final ColorPicker picker = new ColorPicker(0, 0, 150, 130);
        final EditorBox editor = new EditorBox();
        int changes;
        int commits;
        String lastAnim;

        WidgetScreen() {
            super(Component.literal("Dye widgets"));
            picker.setOnChange(c -> changes++);
            picker.setOnCommit(c -> commits++);
            editor.widget.setOnCommit(id -> lastAnim = id);
        }

        @Override
        protected void init() {
            int pw = Math.min(150, width / 3), h = Math.min(170, height - 40);
            picker.setSize(pw, Math.min(130, h));
            picker.setPosition(12, 26);
            editor.place(12 + pw + 20, 26, width - (12 + pw + 20) - 12, h);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            g.text(font, "ColorPicker", 12, 12, 0xFFE8E8EE);
            g.text(font, "AnimatedDyeEditor  " + editor.widget.dyeId(), editor.x0, 12, 0xFFE8E8EE);
            picker.render(g, mouseX, mouseY);
            editor.widget.render(g, mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
            boolean a = picker.mouseClicked(e.x(), e.y(), e.button());
            boolean b = editor.widget.mouseClicked(e.x(), e.y(), e.button());
            return a || b || super.mouseClicked(e, doubleClick);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
            boolean a = picker.mouseDragged(e.x(), e.y(), e.button());
            boolean b = editor.widget.mouseDragged(e.x(), e.y(), e.button());
            return a || b || super.mouseDragged(e, dx, dy);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent e) {
            boolean a = picker.mouseReleased(e.x(), e.y(), e.button());
            boolean b = editor.widget.mouseReleased(e.x(), e.y(), e.button());
            return a || b || super.mouseReleased(e);
        }

        @Override
        public boolean keyPressed(KeyEvent e) {
            if (picker.keyPressed(e) || editor.widget.keyPressed(e)) return true;
            return super.keyPressed(e);
        }

        @Override
        public boolean charTyped(CharacterEvent e) {
            if (picker.charTyped(e) || editor.widget.charTyped(e)) return true;
            return super.charTyped(e);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    /** The editor plus the box it was placed in, so the test can aim clicks at its parts. */
    static final class EditorBox {
        final AnimatedDyeEditor widget = new AnimatedDyeEditor(0, 0, 240, 150);
        int x0, y0, w, h;

        void place(int x, int y, int width, int height) {
            x0 = x;
            y0 = y;
            w = width;
            h = height;
            widget.setSize(width, height);
            widget.setPosition(x, y);
        }
    }
}
