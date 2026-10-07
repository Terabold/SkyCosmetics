package io.github.terabold.skycosmetics.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.gui.hub.TextField;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * The name field. Unfocused, it shows the name the way the game will:
 * colours and styles, no codes. Focused, it shows the raw text with every
 * {@code &} code tinted in the colour it sets, so the codes are easy to find
 * and the text stays readable (the letters keep the normal colour, and only
 * colour changes are used, so the cursor still lines up).
 *
 * It also remembers the selection, which EditBox keeps to itself, so the
 * colour and style buttons can format just the selected letters.
 */
public class NameBox extends TextField {
    /** Style codes and {@code &r} set no colour; they are drawn in this one. */
    private static final int STYLE_CODE = 0x9A9AB4;

    private final Font font;
    private int highlight;
    /** Tint of each character of the value it was built for: built once per edit, not per frame. */
    private String tintedFor;
    private Style[] tints = new Style[0];
    private String styledFor;
    private int styledWidth;
    private FormattedCharSequence styled;
    /** Where the focusing click put the cursor, and when; -1 once used. */
    private int focusClick = -1;
    private long focusClickAt;

    public NameBox(Font font, int x, int y, int w, int h) {
        super(font, x, y, w, h, Component.literal("Name"));
        this.font = font;
        addFormatter(this::tint);
    }

    /** Start of the selection (or the cursor when nothing is selected). */
    public int selectionStart() {
        return Math.min(getCursorPosition(), highlight);
    }

    /** End of the selection (or the cursor when nothing is selected). */
    public int selectionEnd() {
        return Math.max(getCursorPosition(), highlight);
    }

    /** Selects value[from, to), cursor at the end. */
    public void select(int from, int to) {
        moveCursorTo(from, false);
        moveCursorTo(to, true);
    }

    @Override
    public void setHighlightPos(int pos) {
        super.setHighlightPos(pos);
        highlight = Mth.clamp(pos, 0, getValue().length());
    }

    /**
     * The click that focuses the box lands on the styled name, but EditBox would map it through the raw
     * text with its codes. So that click is mapped through the letters as drawn, and the double-click's
     * second click (on the raw view by then) selects the word the first one hit, without its codes.
     */
    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (!isFocused() && !getValue().isEmpty()) {
            int i = styledIndexAt(Mth.floor(event.x()) - getX() - 4);
            moveCursorTo(0, false); // the raw view then starts where the styled one did
            moveCursorTo(i, false);
            focusClick = i;
            focusClickAt = Util.getMillis();
            return;
        }
        if (doubleClick && focusClick >= 0 && Util.getMillis() - focusClickAt < 500) {
            String v = getValue();
            int from = v.lastIndexOf(' ', focusClick - 1) + 1, to = v.indexOf(' ', focusClick);
            while (from < focusClick && Names.codeAt(v, from) > 0) from += Names.codeAt(v, from);
            select(from, to < 0 ? v.length() : to);
        } else {
            super.onClick(event, doubleClick);
        }
        focusClick = -1;
    }

    /** The raw index before the letter drawn at {@code x} in the styled view (after that letter's codes). */
    private int styledIndexAt(int x) {
        String v = getValue();
        Component text = Names.parse(v);
        // Letters cut off by the "..." are not drawn: a click there goes to the end.
        int shown = font.width(text) <= getInnerWidth() ? Integer.MAX_VALUE : getInnerWidth() - font.width("...");
        int[] w = {0}, letter = {-1}, k = {0};
        Language.getInstance().getVisualOrder(text).accept((pos, style, cp) -> {
            int end = w[0] + font.width(FormattedCharSequence.codepoint(cp, style));
            if (end > x || end > shown) {
                letter[0] = end > shown ? -1 : k[0];
                return false;
            }
            w[0] = end;
            k[0]++;
            return true;
        });
        if (letter[0] < 0) return v.length();
        for (int i = 0, n = 0; i < v.length(); ) {
            int c = Names.codeAt(v, i);
            if (c > 0) {
                i += c;
                continue;
            }
            if (n++ == letter[0]) return i;
            i += Character.charCount(v.codePointAt(i));
        }
        return v.length();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (isFocused() || getValue().isEmpty() || !isVisible()) {
            super.extractWidgetRenderState(g, mouseX, mouseY, delta);
            return;
        }
        background(g);
        g.text(font, styled(), getX() + 4, getY() + (getHeight() - 8) / 2, 0xFFFFFFFF, true); // as tooltips draw it
        if (isHovered()) g.requestCursor(CursorTypes.IBEAM);
    }

    /** The name as the game shows it, cut with "..." to the box; rebuilt only when the text or width changes. */
    private FormattedCharSequence styled() {
        String v = getValue();
        if (!v.equals(styledFor) || getInnerWidth() != styledWidth) {
            styledFor = v;
            styledWidth = getInnerWidth();
            styled = fit(font, Names.parse(v), styledWidth);
        }
        return styled;
    }

    /** A styled text cut to {@code px} with "...", ready to draw. */
    static FormattedCharSequence fit(Font font, Component text, int px) {
        FormattedText cut = font.width(text) <= px ? text
            : FormattedText.composite(font.substrByWidth(text, Math.max(0, px - font.width("..."))), FormattedText.of("..."));
        return Language.getInstance().getVisualOrder(cut);
    }

    /** EditBox's formatter: the visible part of the value, starting at {@code offset}, with its codes tinted. */
    private FormattedCharSequence tint(String text, int offset) {
        String v = getValue();
        if (!v.equals(tintedFor)) {
            tintedFor = v;
            tints = tints(v);
        }
        Style[] t = tints;
        return sink -> {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                Style s = offset + i < t.length ? t[offset + i] : Style.EMPTY;
                if (!sink.accept(i, s, cp)) return false;
                i += Character.charCount(cp);
            }
            return true;
        };
    }

    private static Style[] tints(String raw) {
        Style[] out = new Style[raw.length()];
        for (int i = 0; i < raw.length(); ) {
            int n = Names.codeAt(raw, i);
            if (n == 0) {
                out[i++] = Style.EMPTY;
                continue;
            }
            int rgb = Names.codeColour(raw, i);
            Style s = Style.EMPTY.withColor(TextColor.fromRgb(rgb < 0 ? STYLE_CODE : readable(rgb)));
            for (int j = i; j < i + n; j++) out[j] = s;
            i += n;
        }
        return out;
    }

    /** Lifts colours too dark for the dark box (&0 Black) just enough to be seen. */
    private static int readable(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        if (Math.max(r, Math.max(g, b)) >= 0x50) return rgb;
        return (r + 0x80) / 2 << 16 | (g + 0x80) / 2 << 8 | (b + 0x80) / 2;
    }
}
