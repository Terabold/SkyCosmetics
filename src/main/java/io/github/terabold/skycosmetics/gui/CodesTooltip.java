package io.github.terabold.skycosmetics.gui;

import io.github.terabold.skycosmetics.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The (i) beside the name: one usage line, then every code with its name
 * drawn in the colour or style it gives, and a swatch before each colour
 * (a colour too dark to read on black names itself in light gray).
 * Two columns when one would not fit the screen. Built once; only the column
 * choice depends on the screen.
 */
final class CodesTooltip implements ClientTooltipComponent {
    private static final int LINE_H = 10, GAP = 12;
    private static final int CODE = 0xFFAAAAAA;
    /** The swatch between a colour's code and its name; every name starts at {@code NAME_X} (the hex code is longer). */
    private static final int SWATCH = 7, NAME_X = 26;
    private static final int SWATCH_EDGE = 0xFF8C8C9A;
    /** The name of a colour too dark for the tooltip's black (Black, Dark Blue); its swatch shows the real one. */
    private static final int DARK_NAME = 0xFFB4B4BE;

    /** {@code rgb} is the colour's, or -1 for a style. */
    private record Entry(String code, Component name, int rgb, boolean dark) {}

    private static final List<Entry> ENTRIES = entries();
    private static List<ClientTooltipComponent> cached;
    private static boolean cachedTwo;

    private final int columns, rows;
    private int colW = -1;

    private CodesTooltip(int columns) {
        this.columns = columns;
        this.rows = (ENTRIES.size() + columns - 1) / columns;
    }

    /** The usage line and the codes, in two columns when one column would be taller than the screen. */
    static List<ClientTooltipComponent> lines(int screenHeight) {
        boolean two = (ENTRIES.size() + 2) * LINE_H + 16 > screenHeight;
        if (cached == null || cachedTwo != two) {
            cachedTwo = two;
            cached = List.of(ClientTooltipComponent.create(Component.literal("A code styles all the text after it")
                .withStyle(ChatFormatting.AQUA).getVisualOrderText()), new CodesTooltip(two ? 2 : 1));
        }
        return cached;
    }

    @Override
    public int getHeight(Font font) {
        return rows * LINE_H;
    }

    @Override
    public int getWidth(Font font) {
        return columns * columnWidth(font) + (columns - 1) * GAP;
    }

    private int columnWidth(Font font) {
        if (colW < 0) {
            for (Entry e : ENTRIES) colW = Math.max(colW, nameX(font, e) + font.width(e.name) + 1);
        }
        return colW;
    }

    private static int nameX(Font font, Entry e) {
        return Math.max(NAME_X, font.width(e.code));
    }

    @Override
    public void extractText(GuiGraphicsExtractor g, Font font, int x, int y) {
        int step = columnWidth(font) + GAP;
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            int ex = x + i / rows * step, ey = y + i % rows * LINE_H;
            g.text(font, e.code, ex, ey, CODE, true);
            int nx = ex + nameX(font, e);
            if (e.rgb >= 0) {
                int sx = nx - SWATCH - 3;
                g.fill(sx, ey, sx + SWATCH, ey + SWATCH, SWATCH_EDGE);
                g.fill(sx + 1, ey + 1, sx + SWATCH - 1, ey + SWATCH - 1, 0xFF000000 | e.rgb);
            }
            if (e.dark) g.text(font, e.name.getString(), nx, ey, DARK_NAME, true);
            else g.text(font, e.name, nx, ey, 0xFFFFFFFF, true);
        }
    }

    private static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (ChatFormatting f : ChatFormatting.values()) {
            if (!f.isColor()) continue;
            int rgb = f.getColor();
            boolean dark = (rgb >> 16 & 0xFF) * 3 + (rgb >> 8 & 0xFF) * 6 + (rgb & 0xFF) < 0x100;
            out.add(new Entry("&" + f.getChar(), Component.literal(title(f)).withStyle(f), rgb, dark));
        }
        out.add(style("l", "Bold", Style.EMPTY.withBold(true)));
        out.add(style("o", "Italic", Style.EMPTY.withItalic(true)));
        out.add(style("n", "Underline", Style.EMPTY.withUnderlined(true)));
        out.add(style("m", "Strikethrough", Style.EMPTY.withStrikethrough(true)));
        out.add(new Entry("&k", Component.literal("Magic ")
            .append(Component.literal("magic").withStyle(ChatFormatting.OBFUSCATED)), -1, false));
        out.add(style("r", "Plain", Style.EMPTY));
        MutableComponent any = spread("any color", i -> Mth.hsvToRgb(i, 0.6f, 1f) & 0xFFFFFF);
        out.add(new Entry("&#RRGGBB ", any, -1, false));
        out.add(new Entry("&[#…>#…] ", spread("gradient", i -> Names.mix(new int[]{0xFF5FA8, 0xFFB13B}, i)), -1, false));
        out.add(new Entry("&[chroma] ", spread("moving rainbow", i -> Mth.hsvToRgb(i, 0.6f, 1f) & 0xFFFFFF), -1, false));
        return List.copyOf(out);
    }

    /** {@code word} with each letter in {@code colour} of how far along the word it is (0 to 1). */
    private static MutableComponent spread(String word, java.util.function.Function<Float, Integer> colour) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < word.length(); i++) {
            out.append(Component.literal(String.valueOf(word.charAt(i))).withColor(colour.apply(i / (float) word.length())));
        }
        return out;
    }

    private static Entry style(String code, String name, Style style) {
        return new Entry("&" + code, Component.literal(name).withStyle(style), -1, false);
    }

    /** "dark_blue" -> "Dark Blue". */
    static String title(ChatFormatting f) {
        StringBuilder b = new StringBuilder();
        for (String w : f.getName().split("_")) {
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
