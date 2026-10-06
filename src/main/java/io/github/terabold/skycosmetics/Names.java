package io.github.terabold.skycosmetics;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.StringDecomposer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Custom item names typed with colour codes: {@code &6Golden &lAxe} or hex
 * {@code &#FF55AA}. Codes behave like Minecraft's: a colour clears bold and
 * italic, {@code &r} clears everything. Names are never italic unless asked.
 */
public final class Names {
    /** Style codes in the order they are written back: bold, italic, underline, strikethrough, obfuscated. */
    private static final String STYLES = "lonmk";

    private Names() {}

    public static MutableComponent parse(String raw) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY.withItalic(false);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            int n = codeAt(raw, i);
            if (n == 0) {
                run.append(raw.charAt(i));
                continue;
            }
            flush(out, run, style);
            if (n == 8) {
                style = Style.EMPTY.withItalic(false).withColor(TextColor.fromRgb(Integer.parseInt(raw.substring(i + 2, i + 8), 16)));
            } else {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1)));
                if (f == ChatFormatting.RESET) style = Style.EMPTY.withItalic(false);
                else if (f.isColor()) style = Style.EMPTY.withItalic(false).withColor(f);
                else style = style.applyFormat(f);
            }
            i += n - 1;
        }
        flush(out, run, style);
        return out;
    }

    /**
     * The reverse of {@link #parse}: a name as editable & codes, so users can start from Hypixel's own name.
     * Letter by letter as the game draws it (§ codes in the text too), with the fewest codes that give each
     * letter the same colour and styles again, ending a colour or style where the name does.
     */
    public static String toCodes(Component name) {
        StringBuilder b = new StringBuilder();
        Fmt[] cur = {Fmt.PLAIN};
        StringDecomposer.iterateFormatted(name, Style.EMPTY, (i, style, cp) -> {
            Fmt f = Fmt.of(style);
            b.append(change(cur[0], f)).appendCodePoint(cp);
            cur[0] = f;
            return true;
        });
        return b.toString();
    }

    /** Length of the code starting at raw[i]: 2, 8 for {@code &#RRGGBB}, or 0 when none starts there. */
    public static int codeAt(String raw, int i) {
        if (i < 0 || i + 1 >= raw.length() || raw.charAt(i) != '&' && raw.charAt(i) != '§') return 0;
        char k = Character.toLowerCase(raw.charAt(i + 1));
        if (k != '#') return ChatFormatting.getByCode(k) != null ? 2 : 0;
        if (i + 8 > raw.length()) return 0;
        for (int j = i + 2; j < i + 8; j++) {
            if (Character.digit(raw.charAt(j), 16) < 0) return 0;
        }
        return 8;
    }

    /** The colour the code at raw[i] sets (0xRRGGBB), or -1 for a style code or {@code &r}. */
    public static int codeColour(String raw, int i) {
        int n = codeAt(raw, i);
        if (n == 8) return Integer.parseInt(raw.substring(i + 2, i + 8), 16);
        if (n == 0) return -1;
        Integer c = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1))).getColor();
        return c == null ? -1 : c;
    }

    // ------------------------------------------------- selection edits ---

    /** A name after {@link #format}: the new text and where the same letters are now. */
    public record Edit(String text, int from, int to) {}

    /** The formatting of one letter: a colour ("c", "#RRGGBB" or null) and style bits in {@link #STYLES} order. */
    private record Fmt(String colour, int styles) {
        static final Fmt PLAIN = new Fmt(null, 0);

        /** A drawn letter's formatting; a colour that is one of the 16 named ones gets its short code. */
        static Fmt of(Style s) {
            TextColor c = s.getColor();
            String colour = null;
            if (c != null) {
                for (ChatFormatting f : ChatFormatting.values()) {
                    if (f.isColor() && f.getColor() == c.getValue()) colour = String.valueOf(f.getChar());
                }
                if (colour == null) colour = String.format(Locale.ROOT, "#%06X", c.getValue() & 0xFFFFFF);
            }
            return new Fmt(colour, (s.isBold() ? 1 : 0) | (s.isItalic() ? 2 : 0) | (s.isUnderlined() ? 4 : 0)
                | (s.isStrikethrough() ? 8 : 0) | (s.isObfuscated() ? 16 : 0));
        }

        /** This formatting followed by the code at raw[i] of length n. */
        Fmt then(String raw, int i, int n) {
            if (n == 8) return new Fmt("#" + raw.substring(i + 2, i + 8).toUpperCase(Locale.ROOT), 0);
            ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1)));
            if (f == ChatFormatting.RESET) return PLAIN;
            if (f.isColor()) return new Fmt(String.valueOf(f.getChar()), 0);
            return new Fmt(colour, styles | 1 << STYLES.indexOf(f.getChar()));
        }
    }

    /**
     * Applies one code ({@code &c}, {@code &#RRGGBB}, {@code &l}... or
     * {@code &r}) to the letters in raw[from, to) and to nothing else: the code
     * goes in where the selection starts, and the formatting that was active
     * after it (colour and styles, since a colour code clears styles) is put
     * back where it ends. A colour keeps the letters' styles; a style that
     * every selected letter already has is taken off; {@code &r} makes them
     * plain. Codes inside or right next to the selection are rewritten as
     * needed. With no letter selected the code is inserted at {@code to}, as
     * typing it would.
     */
    public static Edit format(String raw, int from, int to, String code) {
        int n = raw.length();
        int a = Math.clamp(Math.min(from, to), 0, n), b = Math.clamp(Math.max(from, to), 0, n);
        if (codeAt(code, 0) != code.length()) return new Edit(raw, a, b);
        // len[i]: length of the code starting at i, 0 for a letter, -1 inside a code; at[i]: formatting before i.
        int[] len = new int[n];
        Fmt[] at = new Fmt[n + 1];
        Fmt f = Fmt.PLAIN;
        for (int i = 0; i < n; ) {
            int c = codeAt(raw, i);
            at[i] = f;
            if (c == 0) {
                i++;
                continue;
            }
            len[i] = c;
            for (int j = i + 1; j < i + c; j++) {
                len[j] = -1;
                at[j] = f;
            }
            f = f.then(raw, i, c);
            i += c;
        }
        at[n] = f;
        // Never split a code.
        while (a > 0 && a < n && len[a] < 0) a--;
        while (b < n && len[b] < 0) b++;

        boolean letters = false, all = true;
        Fmt op = Fmt.PLAIN.then(code, 0, code.length());
        int bit = op.colour == null ? op.styles : 0;
        for (int i = a; i < b; i++) {
            if (len[i] != 0) continue;
            letters = true;
            all &= (at[i].styles & bit) != 0;
        }
        if (!letters) return new Edit(raw.substring(0, b) + code + raw.substring(b), b + code.length(), b + code.length());

        // Take in the codes touching the selection: they only style its letters or are restated after it.
        while (a > 0) {
            int s = a - 1;
            while (s > 0 && len[s] < 0) s--;
            if (len[s] <= 0) break;
            a = s;
        }
        while (b < n && len[b] > 0) b += len[b];

        StringBuilder out = new StringBuilder(raw.substring(0, a));
        Fmt cur = at[a];
        int selFrom = -1, selTo = -1;
        for (int i = a; i < b; i++) {
            if (len[i] != 0) continue;
            Fmt want = op.equals(Fmt.PLAIN) ? Fmt.PLAIN
                : op.colour != null ? new Fmt(op.colour, at[i].styles)
                : new Fmt(at[i].colour, all ? at[i].styles & ~bit : at[i].styles | bit);
            out.append(change(cur, want));
            if (selFrom < 0) selFrom = out.length();
            out.append(raw.charAt(i));
            selTo = out.length();
            cur = want;
        }
        if (b < n) out.append(change(cur, at[b]));
        out.append(raw, b, n);
        return new Edit(out.toString(), selFrom, selTo);
    }

    /**
     * Colours the letters in raw[from, to) letter by letter from the first of {@code stops} (0xRRGGBB) to
     * the last, keeping each letter's styles: a gradient name. A space takes the colour of the letter after
     * it, so it costs no code. Built from {@link #format} one letter at a time: each step absorbs the code
     * the step before left behind to restore the old colour, so only the final restore code stays.
     */
    public static Edit gradient(String raw, int from, int to, int... stops) {
        int a = Math.min(from, to), b = Math.max(from, to);
        int[] at = letters(raw);
        List<Integer> shown = new ArrayList<>(); // ordinals of the selected letters a colour step goes to
        int first = -1;
        for (int k = 0; k < at.length; k++) {
            if (at[k] < a || at[k] >= b) continue;
            if (first < 0) first = k;
            char ch = raw.charAt(at[k]);
            if (!Character.isWhitespace(ch) && !Character.isLowSurrogate(ch)) shown.add(k);
        }
        if (shown.isEmpty() || stops.length == 0) return new Edit(raw, a, b);
        String text = raw;
        int start = first, selFrom = -1, selTo = -1;
        for (int i = 0; i < shown.size(); i++) {
            int k = shown.get(i);
            int[] now = letters(text);
            int width = Character.charCount(text.codePointAt(now[k])); // never split a surrogate pair
            int rgb = mix(stops, shown.size() == 1 ? 0 : i / (float) (shown.size() - 1));
            Edit e = format(text, now[start], now[k] + width, String.format(Locale.ROOT, "&#%06X", rgb));
            text = e.text();
            if (selFrom < 0) selFrom = e.from();
            selTo = e.to();
            start = k + width;
        }
        return new Edit(text, selFrom, selTo);
    }

    /** The colour at {@code t} (0 to 1) along {@code stops}, blended evenly between neighbours; 0xRRGGBB. */
    public static int mix(int[] stops, float t) {
        if (stops.length == 1) return stops[0] & 0xFFFFFF;
        float s = Math.clamp(t, 0f, 1f) * (stops.length - 1);
        int i = Math.min((int) s, stops.length - 2);
        float f = s - i;
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c0 = stops[i] >> shift & 0xFF, c1 = stops[i + 1] >> shift & 0xFF;
            out |= Math.round(c0 + (c1 - c0) * f) << shift;
        }
        return out;
    }

    /** Where each letter of raw (a character that is not part of a code) is. */
    private static int[] letters(String raw) {
        int[] out = new int[raw.length()];
        int k = 0;
        for (int i = 0; i < raw.length(); ) {
            int c = codeAt(raw, i);
            if (c == 0) out[k++] = i++;
            else i += c;
        }
        return Arrays.copyOf(out, k);
    }

    /** The colour of the first letter at or after pos as "#RRGGBB", or null when it has none. */
    public static String colourAt(String raw, int pos) {
        Fmt f = Fmt.PLAIN;
        for (int i = 0; i < raw.length(); ) {
            int c = codeAt(raw, i);
            if (c == 0 && i >= pos) break;
            if (c > 0) f = f.then(raw, i, c);
            i += Math.max(1, c);
        }
        if (f.colour == null) return null;
        if (f.colour.startsWith("#")) return f.colour;
        return String.format(Locale.ROOT, "#%06X", ChatFormatting.getByCode(f.colour.charAt(0)).getColor());
    }

    /** The fewest codes that turn formatting {@code from} into {@code to}: only new styles, unless something ends. */
    private static String change(Fmt from, Fmt to) {
        if (from.equals(to)) return "";
        StringBuilder b = new StringBuilder();
        int had = from.styles;
        if (!Objects.equals(from.colour, to.colour) || (from.styles & ~to.styles) != 0) {
            b.append(to.colour == null ? "&r" : "&" + to.colour);
            had = 0;
        }
        for (int i = 0; i < STYLES.length(); i++) {
            if ((to.styles & ~had & 1 << i) != 0) b.append('&').append(STYLES.charAt(i));
        }
        return b.toString();
    }

    /** "1 skin", "3 skins": a count with its noun, plural unless it is one. */
    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (run.isEmpty()) return;
        out.append(Component.literal(run.toString()).withStyle(style));
        run.setLength(0);
    }
}
