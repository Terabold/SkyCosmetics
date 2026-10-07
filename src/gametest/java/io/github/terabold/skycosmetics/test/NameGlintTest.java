package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.gui.ColorPicker;
import io.github.terabold.skycosmetics.gui.ColorPopup;
import io.github.terabold.skycosmetics.gui.GradientPopup;
import io.github.terabold.skycosmetics.gui.GradientPresets;
import io.github.terabold.skycosmetics.gui.NameBox;
import io.github.terabold.skycosmetics.gui.SpeedSlider;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.StringDecomposer;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.click;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.layout;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.widget;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/**
 * The 1.3 Name & Glint tab: colour and style buttons format only the selected
 * letters (string rules checked directly, then with real clicks), the box
 * shows the styled name or the tinted codes, the codes tooltip, Hypixel's own
 * name and Reset name storing nothing, the name colour pop-up, the glint
 * toggle, speed and colour stored in the look, a name saved when the server
 * closes the studio, and the name above the hotbar. Screenshots at several sizes.
 */
final class NameGlintTest {
    private static final String UUID = "t-aote";
    private static final String HYPIXEL = "&9Aspect of the End";

    private NameGlintTest() {}

    /** Names.format: only the selected letters change, the formatting after them comes back. */
    static void checkNames() {
        edit("Golden Axe", 0, 10, "&c", "&cGolden Axe", 2, 12, "the whole name");
        edit("&6Big Golden Axe", 6, 12, "&c", "&6Big &cGolden&6 Axe", 8, 14, "a middle word gets the color back after it");
        edit("&6&lGolden Axe", 4, 10, "&c", "&c&lGolden&6&l Axe", 4, 10, "a color keeps bold and restores color and bold");
        edit("&6Big Golden Axe", 6, 12, "&l", "&6Big &lGolden&6 Axe", 8, 14, "bold on a word, then not bold");
        edit("&lGolden", 2, 8, "&l", "Golden", 0, 6, "bold on bold letters takes it off");
        edit("Golden Axe", 7, 10, "&#ff55aa", "Golden &#FF55AAAxe", 15, 18, "a hex color on the last word");
        edit("&#123456Big Golden Axe", 12, 18, "&c", "&#123456Big &cGolden&#123456 Axe", 14, 20, "a hex color comes back");
        edit("&6Golden &9Axe", 0, 14, "&c", "&cGolden Axe", 2, 12, "colors inside the selection give way");
        edit("&6Big Golden Axe", 6, 12, "&r", "&6Big &rGolden&6 Axe", 8, 14, "plain letters in a gold name");
        edit("Golden", 3, 3, "&c", "Gol&cden", 5, 5, "no selection: inserted at the cursor");
        edit("&6Gold", 1, 1, "&l", "&6&lGold", 4, 4, "a cursor inside a code inserts after it");
        edit("&6Big &lAxe", 7, 11, "&c", "&6Big &c&lAxe", 10, 13, "a selection starting inside a code");
        check("#FF55AA".equals(Names.colourAt("&6Big &#FF55AAAxe", 6)) && "#FFAA00".equals(Names.colourAt("&6Gold", 0))
            && Names.colourAt("Plain", 0) == null, "colourAt");

        // Gradients: one &[...] code however long the selection, styles kept, the formatting after it restored.
        int[] redBlue = {0xFF0000, 0x0000FF};
        gradient("Golden Axe", 0, 10, redBlue, "&[#FF0000>#0000FF]Golden Axe", 18, 28, "the whole name");
        gradient("&6Big Golden Axe", 6, 12, redBlue, "&6Big &[#FF0000>#0000FF]Golden &6Axe", 24, 30,
            "a middle word, gold around it");
        gradient("&6&lGolden Axe", 4, 10, new int[]{0xFF0000, 0x00FF00}, "&[#FF0000>#00FF00]&lGolden &6&lAxe", 20, 26,
            "bold stays on every letter");
        gradient("&cRe&9d", 0, 7, new int[]{0xFFFFFF, 0x000000}, "&[#FFFFFF>#000000]Red", 18, 21,
            "codes inside the selection give way");
        gradient("Big Axe", 0, 7, new int[]{0xFF5555, 0xFFAA00, 0x5555FF}, "&[#FF5555>#FFAA00>#5555FF]Big Axe", 26, 33,
            "three colors");
        String once = Names.gradient("Hyperion", 0, 8, redBlue).text();
        check(Names.gradient(once, 0, once.length(), 0x00FF00, 0xFFFF00).text().equals("&[#00FF00>#FFFF00]Hyperion"),
            "a second gradient replaces the first one");
        check(Names.gradient("Axe", 1, 1, redBlue).text().equals("Axe") && Names.gradient("A  B", 1, 3, redBlue).text()
            .equals("A  B"), "no letters selected: nothing changes");
        // Drawn: evenly from the first colour to the last over the letters, a space as the letter after it.
        List<String> drawn = letters(Names.parse("&[#FF0000>#0000FF]Hi yo&6!"));
        check(drawn.equals(List.of("Hff0000", "iaa0055", " 5500aa", "y5500aa", "off", "!ffaa00")), "a gradient drawn: " + drawn);
        // Edits inside a gradient keep it smooth; the letters left of a cut keep their colours.
        String g = once + " Sword";
        g = Names.gradient(g, 0, g.length(), redBlue).text();
        check(g.equals("&[#FF0000>#0000FF]Hyperion Sword"), "one gradient over two words: " + g);
        edit(g, 27, 32, "&l", "&[#FF0000>#0000FF]Hyperion &lSword", 29, 34, "bold inside a gradient adds only &l");
        edit(g, 27, 32, "&c", "&[#FF0000>#6A0095]Hyperion &cSword", 29, 34, "a color cuts the gradient where it starts");
        edit(g, 18, 26, "&l", "&[#FF0000>#6A0095]&lHyperion&[#5500AA>#0000FF] Sword", 20, 28,
            "bold on the first word: the rest carries on");
        // Names made before gradient codes: a code on every letter becomes one gradient code.
        check(Names.compact("&#FF0000G&#DF0020o&#BF0040l&#9F0060d&#800080e&#60009Fn&#4000BF A&#2000DFx&#0000FFe")
            .equals("&[#FF0000>#0000FF]Golden Axe"), "an old gradient compacts");
        check(Names.compact("&#FF5555B&#FF7733i&#FF9911g&#DD9933 A&#997799x&#5555FFe").equals("&[#FF5555>#FFAA00>#5555FF]Big Axe"),
            "an old three-color gradient compacts");
        check(Names.compact("&6Big &lBold&6 Axe").equals("&6Big &lBold&6 Axe") && Names.compact("&#FF0000R&#00FF00G&#0000FFB")
            .equals("&#FF0000R&#00FF00G&#0000FFB"), "names that are not gradients stay as they are");
        MutableComponent rainbow = Component.empty();
        for (int i = 0; i < 6; i++) rainbow.append(Component.literal("Rainbow".substring(i, i + 1)).withColor(Names.mix(redBlue, i / 5f)));
        check(Names.toCodes(rainbow).equals("&[#FF0000>#0000FF]Rainbo"), "another mod's letter-by-letter gradient: " + Names.toCodes(rainbow));
        check(Names.codeAt("&[#FF0000>#00FF00]x", 0) == 18 && Names.codeAt("&[chroma]x", 0) == 9 && Names.codeAt("&[#FF0000]x", 0) == 0
            && Names.codeAt("&[#FF0000>#00FF0]x", 0) == 0 && Names.codeAt("&[#FF0000>#00FF00", 0) == 0, "gradient and chroma codes");
        check(Names.format("Hyperion", 0, 8, Names.chroma()).text().equals("&[chroma]Hyperion") && Names.animated("&[chroma]Hi")
            && !Names.animated("&[#FF0000>#00FF00]Hi"), "chroma");
        check("#5500AA".equals(Names.colourAt(g, g.indexOf('S'))), "colourAt in a gradient: " + Names.colourAt(g, g.indexOf('S')));

        // Hypixel's name as codes: every letter keeps its colour and styles, and they end where the name ends them.
        Style plain = Style.EMPTY.withItalic(false);
        codes(Component.empty().append(Component.literal("Bold ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal("Axe")), "&6&lBold &rAxe", "an uncolored part after a gold bold one");
        codes(Component.empty().append(Component.literal("Big ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal("Bold").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" Axe").withStyle(ChatFormatting.GOLD)), "&6Big &lBold&6 Axe", "bold in the middle only");
        codes(Component.literal("§6Big §lBold§r Axe").withStyle(plain), "&6Big &lBold&r Axe", "§ codes in the text");
        codes(Component.empty().append(Component.literal("Pink ").withColor(0xFF55AA))
            .append(Component.literal("Sea").withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)), "&#FF55AAPink &9&nSea",
            "a hex color, then a named one with underline");
        codes(Component.empty().append("Plain ").append(Component.literal("Red").withStyle(ChatFormatting.RED)),
            "Plain &cRed", "plain, then red");
        codes(Component.literal("Aspect of the End").withStyle(plain.withColor(ChatFormatting.BLUE)), HYPIXEL, "Hypixel's usual name");
        System.out.println("[SkyCosmeticsTest] name formatting checks passed");
    }

    private static void codes(Component name, String want, String what) {
        String got = Names.toCodes(name);
        check(got.equals(want), what + ": " + got);
        check(letters(Names.parse(got)).equals(letters(name)), what + ": the name looks the same after the round trip");
    }

    /** Each letter as drawn: the letter, its colour and its styles. */
    private static List<String> letters(Component c) {
        List<String> out = new ArrayList<>();
        StringDecomposer.iterateFormatted(c, Style.EMPTY, (i, st, cp) -> {
            out.add(Character.toString(cp) + (st.getColor() == null ? "-" : Integer.toHexString(st.getColor().getValue()))
                + (st.isBold() ? "l" : "") + (st.isItalic() ? "o" : "") + (st.isUnderlined() ? "n" : "")
                + (st.isStrikethrough() ? "m" : "") + (st.isObfuscated() ? "k" : ""));
            return true;
        });
        return out;
    }

    private static void edit(String raw, int from, int to, String code, String text, int a, int b, String what) {
        Names.Edit e = Names.format(raw, from, to, code);
        check(e.equals(new Names.Edit(text, a, b)), what + ": " + e);
        check(Names.parse(e.text()).getString().equals(Names.parse(raw).getString()), what + ": the letters stay the same");
    }

    private static void gradient(String raw, int from, int to, int[] stops, String text, int a, int b, String what) {
        Names.Edit e = Names.gradient(raw, from, to, stops);
        check(e.equals(new Names.Edit(text, a, b)), "gradient, " + what + ": " + e);
        check(Names.parse(e.text()).getString().equals(Names.parse(raw).getString()), "gradient, " + what
            + ": the letters stay the same");
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a hotbar.5 with minecraft:diamond_sword[minecraft:custom_data="
            + "{id:\"ASPECT_OF_THE_END\",uuid:\"" + UUID + "\"},minecraft:custom_name={text:\"Aspect of the End\","
            + "color:\"blue\",italic:false},minecraft:enchantment_glint_override=true]");
        ctx.waitTicks(3);
        window(ctx, 1280, 720, 2);
        StudioLayoutTest.open(ctx, mc -> mc.player.getInventory().getItem(5));
        if (!ctx.tryClickScreenButton("Name & Glint")) ctx.clickScreenButton("Name");
        ctx.waitTicks(3);

        selection(ctx);
        preview(ctx);
        codesHelp(ctx, "skycosmetics-34-name-codes-help");
        resetName(ctx);
        nameColour(ctx);
        gradientBuilder(ctx);
        glint(ctx);
        sizes(ctx);
        closedByServer(ctx);
        hotbarName(ctx);

        window(ctx, 854, 480, 0);
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> Looks.put(false, UUID, null));
        sp.getServer().runCommand("item replace entity @a hotbar.5 with minecraft:air");
        ctx.waitTicks(2);
    }

    /** The box starts styled with Hypixel's name; colour and style buttons format just the selected letters. */
    private static void selection(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            check(box != null && box.getValue().equals(HYPIXEL), "the box starts with Hypixel's name: " + value(mc));
            check(!box.isFocused(), "the box opens showing the styled name");
        });
        ctx.takeScreenshot("skycosmetics-30-name-styled");

        // A click on the styled name puts the cursor before the letter under the mouse, not where the raw text
        // with its codes has that x; a double-click selects that word, without codes.
        int[] of = ctx.computeOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            return new int[]{box.getX() + 4 + mc.font.width("Aspect ") + 2, box.getY() + box.getHeight() / 2};
        });
        click(ctx, of[0], of[1]);
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            check(box.isFocused() && box.getCursorPosition() == 9 && box.selectionStart() == 9,
                "a click on the styled 'o' of 'of' puts the cursor before it: " + box.getCursorPosition());
        });
        unfocus(ctx);
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(of[0] * scale, of[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            check(box.selectionStart() == 9 && box.selectionEnd() == 11, "a double-click selects 'of': "
                + box.selectionStart() + "-" + box.selectionEnd());
        });
        unfocus(ctx);

        ctx.runOnClient(mc -> screen(mc).nameBox().select(9, 11)); // "of"
        press(ctx, mc -> square(mc, ChatFormatting.RED));
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            check(box.getValue().equals("&9Aspect &cof&9 the End"), "red on the selected word only: " + box.getValue());
            check(box.isFocused() && box.selectionStart() == 11 && box.selectionEnd() == 13,
                "the box keeps the keyboard and the same letters selected");
        });
        press(ctx, mc -> widget(mc, "Bold"));
        check(ctx.computeOnClient(NameGlintTest::value).equals("&9Aspect &c&lof&9 the End"), "bold on the same letters");
        ctx.waitTicks(10);
        check("&9Aspect &c&lof&9 the End".equals(name(ctx)), "the name is saved once typing pauses: " + name(ctx));
        ctx.takeScreenshot("skycosmetics-31-name-focused-codes");

        unfocus(ctx);
        ctx.takeScreenshot("skycosmetics-32-name-styled-after-edit");
    }

    /** A click on empty space gives the box up: it shows the styled name again. */
    private static void unfocus(ClientGameTestContext ctx) {
        int[] label = ctx.computeOnClient(mc -> new int[]{screen(mc).nameBox().getX() + 40, screen(mc).nameBox().getY() - 6});
        click(ctx, label[0], label[1]);
        check(ctx.computeOnClient(mc -> !screen(mc).nameBox().isFocused()), "a click elsewhere unfocuses the box");
    }

    /** The preview tooltip shows the custom name with exactly its colours, bold and hex colours. */
    private static void preview(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Component title = screen(mc).preview().title();
            check(part(title, "of", TextColor.fromLegacyFormat(ChatFormatting.RED), true)
                && part(title, "Aspect ", TextColor.fromLegacyFormat(ChatFormatting.BLUE), false),
                "the preview name is red and bold only on 'of': " + title);
            Looks.put(false, UUID, Looks.byUuid(UUID).withName("&6Golden &#FF55AA&lTunic"));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            Component title = screen(mc).preview().title();
            check(title.getString().equals("Golden Tunic") && part(title, "Golden ", TextColor.fromLegacyFormat(ChatFormatting.GOLD), false)
                && part(title, "Tunic", TextColor.fromRgb(0xFF55AA), true), "the preview shows hex colors and bold: " + title);
            Looks.put(false, UUID, Looks.byUuid(UUID).withName("&9Aspect &c&lof&9 the End"));
        });
        ctx.takeScreenshot("skycosmetics-33-name-preview");
    }

    /**
     * The gradient builder: + after the presets opens it on the selected letters; a color added and changed shows
     * in its preview; Apply puts one gradient code on those letters only; Save Preset keeps it beside the presets
     * (settings.json), and a right-click there removes it. The name box shows the code tinted when focused and the
     * gradient when not.
     */
    private static void gradientBuilder(ClientGameTestContext ctx) {
        ctx.tryClickScreenButton("Reset Name");
        ctx.waitTicks(2);
        int saved = ctx.computeOnClient(mc -> GradientPresets.list().size());
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            mc.screen.setFocused(box);
            box.select(2, 8); // "Aspect"
        });
        press(ctx, mc -> labelled(mc, "+").getLast());
        GradientPopup gp = ctx.computeOnClient(mc -> screen(mc).gradientPopup());
        check(gp != null && !gp.wholeName() && gp.stops().length == 2, "+ opens the builder on the selected letters");
        int[] plus = gp.chipAt(2);
        click(ctx, plus[0], plus[1]);
        check(gp.stops().length == 3 && gp.selected() == 2, "+ adds a third color and picks it");
        click(ctx, gp.picker().x() + 6, gp.picker().y() + 6);
        ctx.waitTicks(2);
        int[] stops = gp.stops();
        check(stops[2] != Names.mix(new int[]{stops[1], 0xFFFFFF}, 0.5f), "the picker changes the picked color");
        ctx.takeScreenshot("skycosmetics-39-gradient-builder");
        for (int[] size : new int[][]{{1920, 1080, 2}, {1920, 1080, 3}, {854, 480, 2}}) {
            window(ctx, size[0], size[1], size[2]);
            ctx.takeScreenshot("skycosmetics-39-gradient-builder-" + size[0] + "x" + size[1] + "-guiscale-" + size[2]);
        }
        window(ctx, 1280, 720, 2);
        GradientPopup again = ctx.computeOnClient(mc -> screen(mc).gradientPopup());
        check(again == gp, "the builder stays open through a resize");
        int[] apply = ctx.computeOnClient(mc -> gp.applyAt());
        click(ctx, apply[0], apply[1]);
        ctx.waitTicks(2);
        String v = ctx.computeOnClient(NameGlintTest::value);
        check(v.equals(Names.token(gp.stops()) + "Aspect &9of the End"), "Apply puts one gradient code on 'Aspect': " + v);
        int[] save = ctx.computeOnClient(mc -> gp.saveAt());
        click(ctx, save[0], save[1]);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> GradientPresets.list().size()) == saved + 1, "Save Preset keeps it");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(12);
        check(ctx.computeOnClient(mc -> screen(mc).gradientPopup() == null), "Esc closes the builder");
        check(v.equals(name(ctx)), "the name is saved: " + name(ctx));
        try {
            String json = Files.readString(FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/settings.json"));
            String token = Names.token(gp.stops());
            check(json.contains("nameGradients") && json.contains(token.substring(2, token.length() - 1)),
                "the preset is in settings.json: " + token);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        // The box: focused, the code tinted in its colors; not focused, the gradient itself.
        ctx.runOnClient(mc -> mc.screen.setFocused(screen(mc).nameBox()));
        ctx.waitTicks(1);
        ctx.takeScreenshot("skycosmetics-39b-gradient-code-focused");
        unfocus(ctx);
        ctx.takeScreenshot("skycosmetics-39c-gradient-name-styled");
        for (int[] size : new int[][]{{1920, 1080, 2}, {1920, 1080, 3}, {854, 480, 2}}) {
            window(ctx, size[0], size[1], size[2]);
            ctx.runOnClient(mc -> mc.screen.setFocused(screen(mc).nameBox()));
            ctx.waitTicks(1);
            ctx.takeScreenshot("skycosmetics-39b-gradient-code-focused-" + size[0] + "x" + size[1] + "-guiscale-" + size[2]);
        }
        window(ctx, 1280, 720, 2);
        unfocus(ctx);

        AbstractWidget mine = ctx.computeOnClient(mc -> labelled(mc, "Your Gradient").getLast());
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos((mine.getX() + 3) * scale, (mine.getY() + 3) * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> GradientPresets.list().size()) == saved, "a right-click removes a saved gradient");
        ctx.clickScreenButton("Reset Name");
        ctx.waitTicks(2);
    }

    /** The (i) beside the name lists every code. */
    private static void codesHelp(ClientGameTestContext ctx, String screenshot) {
        int[] info = ctx.computeOnClient(mc -> {
            AbstractWidget w = labelled(mc, "i").getLast();
            return new int[]{w.getX() + 5, w.getY() + 5};
        });
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(info[0] * scale, info[1] * scale);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> screen(mc).codesShown()), "hovering the (i) shows the codes");
        ctx.takeScreenshot(screenshot);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);
    }

    /** Hypixel's own name stores nothing; Reset name takes the custom name off and shows Hypixel's again. */
    private static void resetName(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> screen(mc).nameBox().setValue(HYPIXEL));
        ctx.waitTicks(10);
        check(name(ctx) == null, "typing Hypixel's own name stores no custom name");
        ctx.runOnClient(mc -> screen(mc).nameBox().setValue("&dPink Blade"));
        ctx.waitTicks(10);
        check("&dPink Blade".equals(name(ctx)), "typing sets a custom name");
        ctx.clickScreenButton("Reset Name");
        ctx.waitTicks(2);
        check(name(ctx) == null && ctx.computeOnClient(NameGlintTest::value).equals(HYPIXEL),
            "Reset name removes the custom name and refills Hypixel's");
    }

    /**
     * Any colour on the selected letters, live. Where the tab has room the picker is open under the style
     * buttons: a click or a drag recolours the selection without piling up codes, and with the box not being
     * typed in it colours the whole name. Elsewhere "Custom Color…" opens the shared pop-up.
     */
    private static void nameColour(ClientGameTestContext ctx) {
        ColorPicker inline = ctx.computeOnClient(mc -> screen(mc).namePicker());
        if (inline != null) {
            inlineColour(ctx, inline);
            return;
        }
        ctx.runOnClient(mc -> screen(mc).nameBox().select(2, 8)); // "Aspect"
        press(ctx, mc -> labelled(mc, "Custom Color…").getFirst());
        ColorPopup p = ctx.computeOnClient(mc -> screen(mc).popup());
        check(p != null && !p.isAnimated(), "Custom color... opens the single-color pop-up");
        click(ctx, p.picker().x() + 30, p.picker().y() + 12);
        ctx.waitTicks(2);
        String v = ctx.computeOnClient(NameGlintTest::value);
        check(v.equals("&" + p.value() + "Aspect&9 of the End"), "the picked color goes on 'Aspect' only: " + v);
        ctx.takeScreenshot("skycosmetics-35-name-custom-color");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> screen(mc).popup() == null) && v.equals(name(ctx)),
            "closing the pop-up saves the name: " + name(ctx));
        ctx.clickScreenButton("Reset Name");
        ctx.waitTicks(2);
    }

    private static void inlineColour(ClientGameTestContext ctx, ColorPicker p) {
        ctx.runOnClient(mc -> {
            NameBox box = screen(mc).nameBox();
            mc.screen.setFocused(box);
            box.select(2, 8); // "Aspect"
        });
        click(ctx, p.x() + 30, p.y() + 12);
        ctx.waitTicks(2);
        String v = ctx.computeOnClient(NameGlintTest::value);
        check(v.equals("&" + ColorPicker.hex(p.rgb()) + "Aspect&9 of the End"), "a click in the open picker colors 'Aspect': " + v);
        check(ctx.computeOnClient(mc -> screen(mc).nameBox().isFocused() && screen(mc).popup() == null),
            "the box keeps the keyboard, and no pop-up opens");

        // A drag: the same letters follow the mouse, one code for them all the way.
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos((p.x() + 10) * scale, (p.y() + 30) * scale);
        ctx.waitTicks(1);
        ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        for (int i = 1; i <= 4; i++) {
            ctx.getInput().setCursorPos((p.x() + 10 + i * 25) * scale, (p.y() + 30 - i * 5) * scale);
            ctx.waitTicks(1);
        }
        ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(2);
        String dragged = ctx.computeOnClient(NameGlintTest::value);
        check(dragged.equals("&" + ColorPicker.hex(p.rgb()) + "Aspect&9 of the End") && !dragged.equals(v),
            "a drag recolors the same letters with one code: " + dragged);
        ctx.waitTicks(10);
        check(dragged.equals(name(ctx)), "the name is saved once the drag ends: " + name(ctx));
        ctx.takeScreenshot("skycosmetics-35-name-open-picker");

        // Not typing in the box: the picker colours the whole name.
        unfocus(ctx);
        click(ctx, p.x() + 60, p.y() + 20);
        ctx.waitTicks(2);
        String whole = ctx.computeOnClient(NameGlintTest::value);
        check(whole.equals("&" + ColorPicker.hex(p.rgb()) + "Aspect of the End"), "with the box unfocused, the whole name: " + whole);
        check(ctx.computeOnClient(mc -> !screen(mc).nameBox().isFocused()), "and the box shows the styled name");
        ctx.clickScreenButton("Reset Name");
        ctx.waitTicks(2);
        check(name(ctx) == null, "Reset name after the picker");
    }

    /** Glint on/off, colour, speed and strength land in the look; each reset puts null back. */
    private static void glint(ClientGameTestContext ctx) {
        check(label(ctx, "Glint: On"), "Hypixel's sword already glints: the toggle says On");
        ctx.clickScreenButton("Glint: On");
        ctx.waitTicks(2);
        check("off".equals(look(ctx).glint()) && label(ctx, "Glint: Off"), "the toggle turns the glint off");
        ctx.clickScreenButton("Glint: Off");
        ctx.waitTicks(2);
        check(look(ctx).glint() == null && label(ctx, "Glint: On"), "back on Hypixel's glint stores nothing");
        ctx.clickScreenButton("Glint: On");
        ctx.waitTicks(2);
        press(ctx, mc -> resets(mc).get(0));
        check(look(ctx).glint() == null, "the glint reset");

        press(ctx, mc -> screen(mc).glintSquare("Red"));
        check("#FF3B3B".equals(look(ctx).glintColor()), "Red glint: " + look(ctx).glintColor());
        ctx.takeScreenshot("skycosmetics-36-glint-red");
        press(ctx, mc -> screen(mc).glintSquare("Purple (default)"));
        check(look(ctx).glintColor() == null, "Purple is Minecraft's own color: nothing stored");

        press(ctx, mc -> labelled(mc, "Custom Color…").getLast());
        ColorPopup p = ctx.computeOnClient(mc -> screen(mc).popup());
        check(p != null, "the glint Custom color... opens the pop-up");
        click(ctx, p.picker().x() + 40, p.picker().y() + 20);
        ctx.waitTicks(6);
        check(p.value().equals(look(ctx).glintColor()), "the glint color applies live: " + look(ctx).glintColor());
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        press(ctx, mc -> resets(mc).get(3));
        check(look(ctx).glintColor() == null, "the color reset");

        check(label(ctx, "Glint speed: Default"), "the speed starts on Minecraft's own");
        int[] slider = ctx.computeOnClient(mc -> {
            SpeedSlider w = (SpeedSlider) labelled(mc, "Glint speed: Default").getFirst();
            return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.trackX(), w.trackW()};
        });
        int x = slider[4] + slider[5] * 3 / 4;
        click(ctx, x, slider[1] + slider[3] / 2);
        ctx.waitTicks(1);
        float want = Math.round((0.1f + (x - slider[4]) / (float) slider[5] * 3.9f) * 10) / 10f;
        Float speed = look(ctx).glintSpeed();
        check(speed != null && Math.abs(speed - want) < 0.11f && label(ctx, String.format(Locale.ROOT, "Glint speed: %.1fx", speed)),
            "the slider sets the speed when released: " + speed + ", want ~" + want);
        click(ctx, slider[0] + slider[2] - 1, slider[1] + slider[3] / 2);
        ctx.waitTicks(1);
        check(Float.valueOf(4f).equals(look(ctx).glintSpeed()), "the slider stops at 4x: " + look(ctx).glintSpeed());
        press(ctx, mc -> resets(mc).get(1));
        check(look(ctx).glintSpeed() == null && label(ctx, "Glint speed: Default"), "the speed reset");

        // Strength, beside speed: saved once the drag ends, 0.25x to 3x, its own reset.
        check(label(ctx, "Glint strength: Default"), "the strength starts on the glint's own");
        int[] s2 = ctx.computeOnClient(mc -> {
            AbstractWidget w = labelled(mc, "Glint strength: Default").getFirst();
            return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
        });
        click(ctx, s2[0] + s2[2] - 1, s2[1] + s2[3] / 2);
        ctx.waitTicks(1);
        check(Float.valueOf(3f).equals(look(ctx).glintStrength()) && label(ctx, "Glint strength: 3.0x"),
            "the strength slider stops at 3x: " + look(ctx).glintStrength());
        click(ctx, s2[0], s2[1] + s2[3] / 2);
        ctx.waitTicks(1);
        check(Float.valueOf(0.25f).equals(look(ctx).glintStrength()) && label(ctx, "Glint strength: 0.25x"),
            "and starts at 0.25x: " + look(ctx).glintStrength());
        press(ctx, mc -> resets(mc).get(2));
        check(look(ctx).glintStrength() == null && label(ctx, "Glint strength: Default"), "the strength reset");
    }

    /** The tab with a styled name, a red 2x glint at several sizes: everything inside the window, nothing overlapping. */
    private static void sizes(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> Looks.put(false, UUID, new Looks.Look(null, null, "&9Aspect &c&lof&9 the End", "on",
            "#FF3B3B", 2f, 1.5f, "test")));
        ctx.getInput().setCursorPos(0, 0); // no tooltip over the layout
        int[][] sizes = {{1280, 720, 2}, {1920, 1080, 3}, {854, 480, 0}, {1920, 1080, 4}, {1920, 1080, 1},
            {1920, 1080, 2}, {1366, 768, 2}};
        String[] names = {"guiscale-2", "guiscale-3", "small-window", "1080p-guiscale-4", "1080p-guiscale-1",
            "1080p-guiscale-2", "1366x768-guiscale-2"};
        for (int i = 0; i < sizes.length; i++) {
            window(ctx, sizes[i][0], sizes[i][1], sizes[i][2]);
            ctx.waitTicks(3);
            String size = names[i];
            ctx.runOnClient(mc -> {
                layout(mc, size);
                roomy(mc, size);
            });
            ctx.takeScreenshot("skycosmetics-37-name-glint-" + size);
        }
        // A common 1920x1080 at GUI scale 3: the big layout with the picker open, using the height.
        window(ctx, 1920, 1080, 3);
        ctx.runOnClient(mc -> {
            StudioScreen s = screen(mc);
            check(s.namePicker() != null, "1920x1080, GUI 3: the name's color picker is open");
            int bottom = 0;
            int edge = labelled(mc, "i").getLast().getRight(); // the middle column ends with the name's (i)
            for (AbstractWidget w : widgets(mc)) {
                if (w.getX() >= s.nameBox().getX() && w.getRight() <= edge) bottom = Math.max(bottom, w.getBottom());
            }
            check(bottom > mc.screen.height * 3 / 4, "the tab uses the height: last row ends at " + bottom + " of " + mc.screen.height);
            check(s.nameBox().getHeight() >= 20 && screen(mc).glintSquare("Red").getHeight() >= 18, "taller rows");
        });
        ctx.takeScreenshot("skycosmetics-37-name-glint-1080p-guiscale-3-picker");
        codesHelp(ctx, "skycosmetics-37-name-codes-help-guiscale-3");
        window(ctx, 1920, 1080, 2);
        codesHelp(ctx, "skycosmetics-37-name-codes-help-1080p-guiscale-2");

        // A short window lists the codes in two columns.
        window(ctx, 854, 480, 0);
        codesHelp(ctx, "skycosmetics-37-name-codes-help-small-window");

        // 330 x 240: too short for the whole tab. It scrolls; rows out of view are hidden, never drawn off the panel.
        window(ctx, 660, 480, 2);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> !screen(mc).glintSquare("White").visible), "a short window hides the lowest row");
        ctx.runOnClient(mc -> layout(mc, "narrow"));
        ctx.takeScreenshot("skycosmetics-37-name-glint-narrow-top");
        int[] mid = ctx.computeOnClient(mc -> new int[]{screen(mc).glintSquare("Red").getX() + 30, mc.screen.height / 2,
            screen(mc).nameBox().getY()});
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(mid[0] * scale, mid[1] * scale);
        for (int i = 0; i < 8; i++) {
            ctx.getInput().scroll(-1);
            ctx.waitTicks(1);
        }
        ctx.runOnClient(mc -> {
            check(screen(mc).glintSquare("White").visible && screen(mc).nameBox().getY() < mid[2],
                "the wheel scrolls the tab up to the lowest row");
            layout(mc, "narrow scrolled");
        });
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);
        ctx.takeScreenshot("skycosmetics-37-name-glint-narrow-scrolled");
    }

    /** The open picker, when there is one, sits inside the window under the style buttons and over no widget. */
    private static void roomy(Minecraft mc, String size) {
        ColorPicker p = screen(mc).namePicker();
        if (p == null) return;
        check(p.x() >= 0 && p.y() >= 0 && p.x() + p.width() <= mc.screen.width && p.y() + p.height() <= mc.screen.height,
            size + ": the open picker is inside the window");
        for (AbstractWidget w : widgets(mc)) {
            boolean overlap = p.x() < w.getRight() && w.getX() < p.x() + p.width() && p.y() < w.getBottom() && w.getY() < p.y() + p.height();
            check(!overlap, size + ": the open picker overlaps '" + w.getMessage().getString() + "'");
        }
    }

    /** A name still waiting to be saved is saved when the server replaces the screen, not only on Esc or Done. */
    private static void closedByServer(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            screen(mc).nameBox().setValue("&aClosed Blade");
            mc.setScreen(null); // what Hypixel closing the menu under the studio does
            check("&aClosed Blade".equals(Looks.byUuid(UUID).name()), "the typed name is saved: " + Looks.byUuid(UUID).name());
        });
    }

    /** The name above the hotbar is the custom one, while getHoverName keeps Hypixel's for other mods. */
    private static void hotbarName(ClientGameTestContext ctx) {
        window(ctx, 1280, 720, 2);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(5));
        ctx.waitTicks(4);
        ctx.takeScreenshot("skycosmetics-38-hotbar-custom-name");
        ctx.runOnClient(mc -> {
            String hover = mc.player.getInventory().getItem(5).getHoverName().getString();
            check(hover.equals("Aspect of the End"), "getHoverName keeps Hypixel's name: " + hover);
            mc.player.getInventory().setSelectedSlot(0);
        });
    }

    // ------------------------------------------------------------ helpers ---

    private static StudioScreen screen(Minecraft mc) {
        return (StudioScreen) mc.screen;
    }

    private static String value(Minecraft mc) {
        NameBox box = screen(mc).nameBox();
        return box == null ? null : box.getValue();
    }

    private static Looks.Look look(ClientGameTestContext ctx) {
        Looks.Look l = ctx.computeOnClient(mc -> Looks.byUuid(UUID));
        return l == null ? Looks.Look.NONE : l;
    }

    private static String name(ClientGameTestContext ctx) {
        return look(ctx).name();
    }

    private static boolean label(ClientGameTestContext ctx, String label) {
        return ctx.computeOnClient(mc -> widget(mc, label) != null);
    }

    /** Clicks the middle of a widget like a player would. */
    private static void press(ClientGameTestContext ctx, Function<Minecraft, AbstractWidget> find) {
        int[] at = ctx.computeOnClient(mc -> {
            AbstractWidget w = find.apply(mc);
            check(w != null, "widget to click is on screen");
            return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
        });
        click(ctx, at[0], at[1]);
        ctx.waitTicks(1);
    }

    private static List<AbstractWidget> widgets(Minecraft mc) {
        List<AbstractWidget> out = new ArrayList<>();
        for (var c : mc.screen.children()) if (c instanceof AbstractWidget w && w.visible) out.add(w);
        return out;
    }

    /** Widgets with this label, top to bottom. */
    private static List<AbstractWidget> labelled(Minecraft mc, String label) {
        List<AbstractWidget> out = new ArrayList<>(widgets(mc).stream()
            .filter(w -> w.getMessage().getString().equals(label)).toList());
        out.sort(Comparator.comparingInt(AbstractWidget::getY));
        return out;
    }

    /** The ↺ buttons: glint on/off, speed, colour. */
    private static List<AbstractWidget> resets(Minecraft mc) {
        return labelled(mc, "↺");
    }

    private static AbstractWidget square(Minecraft mc, ChatFormatting f) {
        TextColor want = TextColor.fromLegacyFormat(f);
        return widgets(mc).stream().filter(w -> w.getMessage().getString().equals("■")
            && want.equals(w.getMessage().getStyle().getColor())).findFirst().orElse(null);
    }

    private static boolean part(Component c, String text, TextColor colour, boolean bold) {
        return c.toFlatList().stream().anyMatch(p -> p.getString().equals(text) && colour.equals(p.getStyle().getColor())
            && p.getStyle().isBold() == bold);
    }
}
