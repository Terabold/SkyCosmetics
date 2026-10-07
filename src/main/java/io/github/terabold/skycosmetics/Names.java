package io.github.terabold.skycosmetics;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.util.StringDecomposer;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Custom item names typed with colour codes: {@code &6Golden &lAxe} or hex
 * {@code &#FF55AA}. Codes behave like Minecraft's: a colour clears bold and
 * italic, {@code &r} clears everything. Names are never italic unless asked.
 *
 * Two colour codes of our own: a gradient {@code &[#FF0000>#00FF00]} (2 to {@link #MAX_STOPS} colours, spread
 * evenly over the letters up to the next colour code, {@code &r} or the end; a space takes the next letter's
 * colour) and {@code &[chroma]}, a rainbow that moves. A gradient is one short code however long the name.
 * Names that colour every letter on its own still work, and {@link #compact} turns them into gradients.
 */
public final class Names {
    /** Style codes in the order they are written back: bold, italic, underline, strikethrough, obfuscated. */
    private static final String STYLES = "lonmk";
    /** A gradient has at most this many colours. */
    public static final int MAX_STOPS = 8;
    /** The chroma code without its "&", as {@link Fmt} stores it. */
    private static final String CHROMA = "[chroma]";
    /** Letters of a fitted gradient may be this far (per channel) from the colours they replace. */
    private static final int TOLERANCE = 3;
    /** Chroma: a full turn of the rainbow takes this long, and each letter is this far along it. */
    private static final int CHROMA_MS = 2400;
    private static final float CHROMA_STEP = 0.055f;
    private static final Style BASE = Style.EMPTY.withItalic(false);

    private Names() {}

    // ------------------------------------------------------------ parse ---

    public static MutableComponent parse(String raw) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = null;
        for (Letter l : decode(raw)) {
            Style s = l.style();
            if (!s.equals(style)) {
                flush(out, run, style);
                style = s;
            }
            run.append(l.text);
        }
        flush(out, run, style);
        return out;
    }

    /** Whether the name moves (it has {@code &[chroma]}): draw it from {@link #live}. */
    public static boolean animated(String raw) {
        if (raw == null) return false;
        for (int i = raw.indexOf('['); i > 0; i = raw.indexOf('[', i + 1)) {
            if (codeAt(raw, i - 1) > 0 && raw.regionMatches(true, i, CHROMA, 0, CHROMA.length())) return true;
        }
        return false;
    }

    private static final Map<String, MutableComponent> LIVE = new HashMap<>();
    private static long liveTick = -1;

    /**
     * {@link #parse} for a name that may move: built at most once per game tick for each name, however many
     * items and frames draw it. Any thread may ask (other mods read names off the render thread).
     */
    public static synchronized Component live(String raw) {
        long tick = liveTick();
        if (tick != liveTick) {
            liveTick = tick;
            LIVE.clear();
        }
        MutableComponent c = LIVE.get(raw);
        if (c == null) {
            c = parse(raw);
            LIVE.put(raw, c);
        }
        return c;
    }

    /** The game tick {@link #live} builds names for: a moving name changes when it does. */
    public static long liveTick() {
        return Util.getMillis() / 50;
    }

    /**
     * The reverse of {@link #parse}: a name as editable & codes, so users can start from Hypixel's own name.
     * Letter by letter as the game draws it (§ codes in the text too), with the fewest codes that give each
     * letter the same colour and styles again, ending a colour or style where the name does. Letters coloured
     * one by one in a smooth run become one gradient code.
     */
    public static String toCodes(Component name) {
        List<Letter> out = new ArrayList<>();
        StringDecomposer.iterateFormatted(name, Style.EMPTY, (i, style, cp) -> {
            Fmt f = Fmt.of(style);
            out.add(new Letter(Character.toString(cp), f.colour, f.styles, rgbOf(f.colour), -1));
            return true;
        });
        return encode(out, true).text;
    }

    /**
     * The same name with the fewest codes: letters coloured one by one in a smooth run (a gradient made before
     * gradient codes existed) become one gradient code. A name without codes comes back as it is.
     */
    public static String compact(String raw) {
        if (raw == null || raw.indexOf('&') < 0 && raw.indexOf('§') < 0) return raw;
        List<Letter> ls = decode(raw);
        return ls.isEmpty() ? raw : encode(ls, true).text;
    }

    /**
     * Length of the code starting at raw[i]: 2, 8 for {@code &#RRGGBB}, the whole {@code &[...]} for a
     * gradient or chroma, or 0 when none starts there.
     */
    public static int codeAt(String raw, int i) {
        if (i < 0 || i + 1 >= raw.length() || raw.charAt(i) != '&' && raw.charAt(i) != '§') return 0;
        char k = Character.toLowerCase(raw.charAt(i + 1));
        if (k == '[') return tokenAt(raw, i);
        if (k != '#') return ChatFormatting.getByCode(k) != null ? 2 : 0;
        if (i + 8 > raw.length()) return 0;
        for (int j = i + 2; j < i + 8; j++) {
            if (Character.digit(raw.charAt(j), 16) < 0) return 0;
        }
        return 8;
    }

    /** {@code &[chroma]} or {@code &[#RRGGBB>#RRGGBB...]} at raw[i]: its length, or 0. */
    private static int tokenAt(String raw, int i) {
        int j = i + 2;
        if (raw.regionMatches(true, j, "chroma]", 0, 7)) return 9;
        for (int n = 1; n <= MAX_STOPS; n++) {
            if (j + 8 > raw.length() || raw.charAt(j) != '#') return 0;
            for (int d = j + 1; d < j + 7; d++) if (Character.digit(raw.charAt(d), 16) < 0) return 0;
            char c = raw.charAt(j + 7);
            if (c == ']') return n >= 2 ? j + 8 - i : 0;
            if (c != '>') return 0;
            j += 8;
        }
        return 0;
    }

    /** The colours of the gradient code at raw[i] (0xRRGGBB), or null when none starts there (chroma neither). */
    public static int[] stopsAt(String raw, int i) {
        int n = codeAt(raw, i);
        if (n <= 9 || raw.charAt(i + 1) != '[') return null;
        int[] out = new int[(n - 2) / 8];
        for (int k = 0; k < out.length; k++) out[k] = Integer.parseInt(raw.substring(i + 3 + k * 8, i + 9 + k * 8), 16);
        return out;
    }

    /** Whether the code at raw[i] is {@code &[chroma]}. */
    public static boolean chromaAt(String raw, int i) {
        return codeAt(raw, i) == 9 && raw.charAt(i + 1) == '[';
    }

    /** A gradient code for these colours (0xRRGGBB), {@code &[#FF0000>#00FF00]}; one colour gives a hex code. */
    public static String token(int... stops) {
        if (stops.length == 1) return String.format(Locale.ROOT, "&#%06X", stops[0] & 0xFFFFFF);
        StringBuilder b = new StringBuilder("&[");
        for (int i = 0; i < stops.length; i++) {
            if (i > 0) b.append('>');
            b.append(String.format(Locale.ROOT, "#%06X", stops[i] & 0xFFFFFF));
        }
        return b.append(']').toString();
    }

    /** The chroma code. */
    public static String chroma() {
        return "&" + CHROMA;
    }

    /**
     * The colour the code at raw[i] sets (0xRRGGBB): a gradient's first colour, chroma's colour now; -1 for a
     * style code or {@code &r}.
     */
    public static int codeColour(String raw, int i) {
        int n = codeAt(raw, i);
        if (n == 0) return -1;
        if (raw.charAt(i + 1) == '[') {
            int[] stops = stopsAt(raw, i);
            return stops != null ? stops[0] : chromaRgb(0);
        }
        if (n == 8) return Integer.parseInt(raw.substring(i + 2, i + 8), 16);
        Integer c = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1))).getColor();
        return c == null ? -1 : c;
    }

    /** The colour of chroma's {@code index}th letter now (0xRRGGBB). */
    public static int chromaRgb(int index) {
        float phase = (Util.getMillis() % CHROMA_MS) / (float) CHROMA_MS;
        float hue = index * CHROMA_STEP - phase;
        return Mth.hsvToRgb(hue - (float) Math.floor(hue), 0.7f, 1f) & 0xFFFFFF;
    }

    // ------------------------------------------------- letters and codes ---

    /**
     * One letter as drawn. {@code colour} is as written: "c", "#RRGGBB", {@link #CHROMA} or null; a gradient's
     * letter has its own "#RRGGBB". {@code rgb} is that colour (-1 for none); {@code span} the gradient the letter
     * is still part of (null if none); {@code start} where the letter is in the text (-1: not from text).
     */
    private static final class Letter {
        final String text;
        final int start;
        String colour;
        int styles, rgb;
        Span span;

        Letter(String text, String colour, int styles, int rgb, int start) {
            this.text = text;
            this.colour = colour;
            this.styles = styles;
            this.rgb = rgb;
            this.start = start;
        }

        boolean visible() {
            return !text.isBlank();
        }

        Style style() {
            Style s = BASE;
            if (colour != null) {
                ChatFormatting f = colour.length() == 1 ? ChatFormatting.getByCode(colour.charAt(0)) : null;
                if (f != null) s = s.withColor(f);
                else if (rgb >= 0) s = s.withColor(TextColor.fromRgb(rgb));
            }
            if ((styles & 1) != 0) s = s.withBold(true);
            if ((styles & 2) != 0) s = s.withItalic(true);
            if ((styles & 4) != 0) s = s.withUnderlined(true);
            if ((styles & 8) != 0) s = s.withStrikethrough(true);
            if ((styles & 16) != 0) s = s.withObfuscated(true);
            return s;
        }
    }

    /** A gradient code's colours and its letters, in order. */
    private static final class Span {
        final int[] stops;
        final List<Letter> letters = new ArrayList<>();

        Span(int[] stops) {
            this.stops = stops;
        }

        /** Colours the letters evenly from the first visible one to the last; a space as the letter after it. */
        void resolve() {
            int visible = 0;
            for (Letter l : letters) if (l.visible()) visible++;
            int v = 0;
            for (Letter l : letters) {
                int at = Math.min(v, Math.max(0, visible - 1));
                l.rgb = mix(stops, visible <= 1 ? 0 : at / (float) (visible - 1));
                l.colour = String.format(Locale.ROOT, "#%06X", l.rgb);
                if (l.visible()) v++;
            }
        }
    }

    /** Every letter of raw with its formatting; gradient and chroma letters get their own colours. */
    private static List<Letter> decode(String raw) {
        List<Letter> out = new ArrayList<>();
        Fmt f = Fmt.PLAIN;
        Span span = null;
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < raw.length(); ) {
            int n = codeAt(raw, i);
            if (n == 0) {
                int cp = raw.codePointAt(i);
                Letter l = new Letter(Character.toString(cp), f.colour, f.styles, rgbOf(f.colour), i);
                if (span != null) {
                    l.span = span;
                    span.letters.add(l);
                }
                out.add(l);
                i += Character.charCount(cp);
                continue;
            }
            if (setsColour(raw, i, n)) { // any colour code or &r ends a gradient; a style code does not
                int[] stops = stopsAt(raw, i);
                span = stops != null ? new Span(stops) : null;
                if (span != null) spans.add(span);
            }
            f = f.then(raw, i, n);
            i += n;
        }
        for (Span s : spans) s.resolve();
        chroma(out);
        return out;
    }

    /** Chroma letters' colours now: along the whole name, so a chroma code stated again carries on seamlessly. */
    private static void chroma(List<Letter> ls) {
        int v = 0;
        for (Letter l : ls) {
            if (CHROMA.equals(l.colour)) l.rgb = chromaRgb(v);
            if (l.visible()) v++;
        }
    }

    /** Whether the code at raw[i] (n long) is a colour or {@code &r}, not a style. */
    private static boolean setsColour(String raw, int i, int n) {
        if (n != 2) return true;
        ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1)));
        return f == ChatFormatting.RESET || f.isColor();
    }

    /** A written colour's RGB, -1 for none; a gradient or chroma before its letters are coloured: white. */
    private static int rgbOf(String colour) {
        if (colour == null) return -1;
        if (colour.startsWith("[")) return 0xFFFFFF;
        if (colour.startsWith("#")) return Integer.parseInt(colour.substring(1), 16);
        Integer c = ChatFormatting.getByCode(colour.charAt(0)).getColor();
        return c == null ? -1 : c;
    }

    /** Encoded text, and where each letter of it is ({@code at[n]} is the end). */
    private record Encoded(String text, int[] at) {}

    /**
     * Writes letters back with the fewest codes: a gradient still whole is written as it was; with {@code fit},
     * any other smooth run of colours becomes a gradient code when that is shorter.
     */
    private static Encoded encode(List<Letter> ls, boolean fit) {
        StringBuilder b = new StringBuilder();
        int n = ls.size();
        int[] at = new int[n + 1];
        Fmt cur = Fmt.PLAIN;
        for (int i = 0; i < n; ) {
            Letter l = ls.get(i);
            int end = -1;
            int[] stops = null;
            if (l.span != null && l.span.letters.getFirst() == l) {
                int j = i;
                while (j < n && ls.get(j).span == l.span) j++;
                if (j - i == l.span.letters.size() && keepsStyles(ls, i, j)) {
                    end = j;
                    stops = l.span.stops;
                }
            }
            // A run of one colour carries on as it is; anything else may start a gradient.
            boolean same = i > 0 && ls.get(i - 1).visible() && ls.get(i - 1).rgb == l.rgb && Objects.equals(cur.colour, l.colour);
            if (stops == null && fit && !same) {
                int[] found = fitRun(ls, i, cur);
                if (found != null) {
                    end = found[0];
                    stops = Arrays.copyOfRange(found, 1, found.length);
                }
            }
            if (stops == null && free(l) && l.styles == cur.styles) { // its colour never shows: no code for it
                at[i] = b.length();
                b.append(l.text);
                i++;
                continue;
            }
            if (stops == null) {
                Fmt want = new Fmt(l.colour, l.styles);
                b.append(change(cur, want));
                at[i] = b.length();
                b.append(l.text);
                cur = want;
                i++;
                continue;
            }
            // The gradient code, then only styles that start: nothing in the run takes one away.
            b.append(token(stops));
            int have = 0;
            for (int k = i; k < end; k++) {
                Letter x = ls.get(k);
                for (int s = 0; s < STYLES.length(); s++) {
                    if ((x.styles & ~have & 1 << s) != 0) b.append('&').append(STYLES.charAt(s));
                }
                have |= x.styles;
                at[k] = b.length();
                b.append(x.text);
            }
            cur = new Fmt("\0", have); // no letter has this colour: whatever comes next states its own
            i = end;
        }
        at[n] = b.length();
        return new Encoded(b.toString(), at);
    }

    /** No letter in ls[from, to) loses a style the letter before it had (that would take a colour code). */
    private static boolean keepsStyles(List<Letter> ls, int from, int to) {
        for (int k = from + 1; k < to; k++) if ((ls.get(k - 1).styles & ~ls.get(k).styles) != 0) return false;
        return true;
    }

    /**
     * The smooth run of colours from ls[i] that one gradient code saves the most on, against codes letter by
     * letter: {end, stops...}, or null. It may start on a space whose colour never shows; it ends on a letter.
     */
    private static int[] fitRun(List<Letter> ls, int i, Fmt cur) {
        List<Integer> colours = new ArrayList<>();
        int[] best = null;
        int most = 0;
        for (int k = i; k < ls.size(); k++) {
            Letter l = ls.get(k);
            if (k > i && (ls.get(k - 1).styles & ~l.styles) != 0) break;
            if (!l.visible()) {
                if (colours.isEmpty() && !free(l)) return null;
                continue;
            }
            if (l.colour == null || CHROMA.equals(l.colour) || l.rgb < 0) break;
            colours.add(l.rgb);
            if (colours.size() < 3) continue;
            int[] stops = fit(colours);
            if (stops == null) break;
            int save = plainCost(ls, i, k + 1, cur) - gradientCost(ls, i, k + 1, stops.length);
            if (save > most && !oneColour(colours)) {
                most = save;
                best = new int[stops.length + 1];
                best[0] = k + 1;
                System.arraycopy(stops, 0, best, 1, stops.length);
            }
        }
        return best;
    }

    /** A space whose colour never shows: not underlined, struck through or scrambled. */
    private static boolean free(Letter l) {
        return !l.visible() && (l.styles & 0b11100) == 0;
    }

    private static boolean oneColour(List<Integer> cs) {
        for (int c : cs) if (c != cs.getFirst()) return false;
        return true;
    }

    /** The codes ls[from, to) takes letter by letter after {@code cur}, as {@link #encode} writes them. */
    private static int plainCost(List<Letter> ls, int from, int to, Fmt cur) {
        int n = 0;
        Fmt f = cur;
        for (int k = from; k < to; k++) {
            Letter l = ls.get(k);
            if (free(l) && l.styles == f.styles) continue;
            Fmt want = new Fmt(l.colour, l.styles);
            n += change(f, want).length();
            f = want;
        }
        return n;
    }

    /** The codes ls[from, to) takes as one gradient of {@code stops} colours: the code, then styles as they start. */
    private static int gradientCost(List<Letter> ls, int from, int to, int stops) {
        int n = 8 * stops + 2, have = 0;
        for (int k = from; k < to; k++) {
            n += 2 * Integer.bitCount(ls.get(k).styles & ~have);
            have |= ls.get(k).styles;
        }
        return n;
    }

    /** The fewest evenly spread colours (up to {@link #MAX_STOPS}) whose gradient gives these, or null. */
    private static int[] fit(List<Integer> cs) {
        int m = cs.size();
        for (int k = 2; k <= Math.min(MAX_STOPS, m); k++) {
            double spacing = (m - 1) / (double) (k - 1);
            int[] stops = new int[k];
            for (int j = 0; j < k; j++) stops[j] = stopAt(cs, j * spacing, spacing);
            boolean ok = true;
            for (int v = 0; v < m && ok; v++) ok = close(mix(stops, v / (float) (m - 1)), cs.get(v));
            if (ok) return stops;
        }
        return null;
    }

    /** The run's colour at position p; between two letters, carried on along the straight stretch beside it. */
    private static int stopAt(List<Integer> cs, double p, double spacing) {
        int lo = (int) Math.floor(p + 1e-9);
        if (p - lo < 1e-6) return cs.get(lo);
        int hi = lo + 1, out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double v;
            if (lo - 1 >= p - spacing - 1e-6) { // two letters on the stretch that ends at p
                int a = cs.get(lo - 1) >> shift & 0xFF, c = cs.get(lo) >> shift & 0xFF;
                v = c + (c - a) * (p - lo);
            } else if (hi + 1 < cs.size() && hi + 1 <= p + spacing + 1e-6) { // two on the one that starts there
                int c = cs.get(hi) >> shift & 0xFF, d = cs.get(hi + 1) >> shift & 0xFF;
                v = c - (d - c) * (hi - p);
            } else {
                int c = cs.get(lo) >> shift & 0xFF, d = cs.get(hi) >> shift & 0xFF;
                v = c + (d - c) * (p - lo);
            }
            out |= (int) Math.clamp(Math.round(v), 0, 255) << shift;
        }
        return out;
    }

    private static boolean close(int a, int b) {
        for (int shift = 16; shift >= 0; shift -= 8) {
            if (Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)) > TOLERANCE) return false;
        }
        return true;
    }

    // ------------------------------------------------- selection edits ---

    /** A name after {@link #format}: the new text and where the same letters are now. */
    public record Edit(String text, int from, int to) {}

    /**
     * The formatting of one letter: a colour ("c", "#RRGGBB", a gradient's "[#...]", {@link #CHROMA} or null)
     * and style bits in {@link #STYLES} order.
     */
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
            if (raw.charAt(i + 1) == '[') {
                String body = raw.substring(i + 1, i + n);
                return new Fmt(body.equalsIgnoreCase(CHROMA) ? CHROMA : body.toUpperCase(Locale.ROOT), 0);
            }
            if (n == 8) return new Fmt("#" + raw.substring(i + 2, i + 8).toUpperCase(Locale.ROOT), 0);
            ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1)));
            if (f == ChatFormatting.RESET) return PLAIN;
            if (f.isColor()) return new Fmt(String.valueOf(f.getChar()), 0);
            return new Fmt(colour, styles | 1 << STYLES.indexOf(f.getChar()));
        }
    }

    /**
     * Applies one code ({@code &c}, {@code &#RRGGBB}, {@code &l}..., {@code &r}, a gradient or chroma) to the
     * letters in raw[from, to) and to nothing else: the code goes in where the selection starts, and the
     * formatting that was active after it (colour and styles, since a colour code clears styles) is put back
     * where it ends. A colour keeps the letters' styles; a style that every selected letter already has is
     * taken off; {@code &r} makes them plain. Codes inside or right next to the selection are rewritten as
     * needed. With no letter selected the code is inserted at {@code to}, as typing it would.
     */
    public static Edit format(String raw, int from, int to, String code) {
        int n = raw.length();
        int a = Math.clamp(Math.min(from, to), 0, n), b = Math.clamp(Math.max(from, to), 0, n);
        if (codeAt(code, 0) != code.length()) return new Edit(raw, a, b);
        // len[i]: length of the code starting at i, 0 for a letter, -1 inside a code; at[i]: formatting before i.
        int[] len = new int[n];
        Fmt[] at = new Fmt[n + 1];
        Fmt f = Fmt.PLAIN;
        boolean tokens = code.charAt(1) == '[';
        for (int i = 0; i < n; ) {
            int c = codeAt(raw, i);
            at[i] = f;
            if (c == 0) {
                i++;
                continue;
            }
            tokens |= raw.charAt(i + 1) == '[';
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
        if (tokens) return byLetter(raw, a, b, op, bit, all);

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
     * {@link #format} where a gradient or chroma takes part: the letters are formatted one by one, then the whole
     * name is written again with the fewest codes, so a gradient the edit cuts stays as smooth as it was.
     */
    private static Edit byLetter(String raw, int a, int b, Fmt op, int bit, boolean all) {
        List<Letter> ls = decode(raw);
        int first = -1, last = -1;
        for (int k = 0; k < ls.size(); k++) {
            int s = ls.get(k).start;
            if (s < a || s >= b) continue;
            if (first < 0) first = k;
            last = k;
        }
        boolean colour = op.colour != null || op.equals(Fmt.PLAIN);
        Span grad = op.colour != null && op.colour.startsWith("[#") ? new Span(stopsAt("&" + op.colour, 0)) : null;
        Set<Span> cut = new HashSet<>(); // gradients that lose letters: the rest keep their colours, not the code
        for (int k = first; k <= last; k++) {
            Letter l = ls.get(k);
            if (!colour) {
                l.styles = all ? l.styles & ~bit : l.styles | bit;
                continue;
            }
            if (l.span != null) cut.add(l.span);
            l.span = null;
            if (op.equals(Fmt.PLAIN)) {
                l.colour = null;
                l.styles = 0;
                l.rgb = -1;
            } else if (grad != null) {
                l.span = grad;
                grad.letters.add(l);
            } else {
                l.colour = op.colour;
                l.rgb = rgbOf(op.colour);
            }
        }
        for (Letter l : ls) if (l.span != null && cut.contains(l.span)) l.span = null;
        if (grad != null) grad.resolve();
        chroma(ls);
        Encoded e = encode(ls, true);
        return new Edit(e.text, e.at[first], e.at[last] + ls.get(last).text.length());
    }

    /**
     * A gradient over the letters in raw[from, to), from the first of {@code stops} (0xRRGGBB) to the last,
     * keeping each letter's styles: one {@code &[...]} code, however many letters. A space takes the colour of
     * the letter after it.
     */
    public static Edit gradient(String raw, int from, int to, int... stops) {
        int a = Math.min(from, to), b = Math.max(from, to);
        if (stops.length == 0) return new Edit(raw, a, b);
        boolean any = false;
        for (Letter l : decode(raw)) any |= l.start >= a && l.start < b && l.visible();
        if (!any) return new Edit(raw, a, b);
        int[] st = stops.length == 1 ? new int[]{stops[0], stops[0]}
            : stops.length > MAX_STOPS ? Arrays.copyOf(stops, MAX_STOPS) : stops;
        return format(raw, a, b, token(st));
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

    /** The colour of the first letter at or after pos as "#RRGGBB" (a gradient's letter: its own), or null. */
    public static String colourAt(String raw, int pos) {
        for (Letter l : decode(raw)) {
            if (l.start < pos) continue;
            return l.rgb < 0 ? null : String.format(Locale.ROOT, "#%06X", l.rgb);
        }
        int rgb = -1; // past the last letter: the colour the codes at the end set
        for (int i = 0; i < raw.length(); ) {
            int c = codeAt(raw, i);
            if (c > 0 && setsColour(raw, i, c)) rgb = codeColour(raw, i);
            i += Math.max(1, c);
        }
        return rgb < 0 ? null : String.format(Locale.ROOT, "#%06X", rgb);
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
