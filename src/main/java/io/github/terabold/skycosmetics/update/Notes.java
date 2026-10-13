package io.github.terabold.skycosmetics.update;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A release's notes (GitHub Markdown, usually the CHANGELOG section) as plain lines for the update card: headings
 * in bold, list items with a bullet, bold kept, links, code and emphasis as their text. Checksums, "Minecraft:"
 * lines, comments, images, tables and GitHub's "Full Changelog" line are left out. Built once per release, never per
 * frame.
 */
public final class Notes {
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SKIP = Pattern.compile(
        "(?i)^\\s*(?:[>*_`-]*\\s*sha-?256\\b.*|[>*_`-]*\\s*minecraft(?:\\s+versions?)?\\s*:.*|\\|.*|!\\[.*|\\**full changelog\\**:.*|-{3,}|={3,}|\\*{3,})$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");

    /** Marks around bold text while a line is parsed; never in release notes. */
    private static final char BOLD_ON = '\u0001', BOLD_OFF = '\u0002';

    private Notes() {}

    /**
     * One line of the notes: {@code prefix} is a list item's indent and bullet ("- ", "  2. "), drawn before the
     * first wrapped line so the others hang under the text. An empty line is a gap.
     */
    public record Line(String prefix, Component body) {
        static final Line GAP = new Line("", Component.empty());

        /** The whole line, as one piece of text. */
        public Component text() {
            return Component.literal(prefix).append(body);
        }

        public boolean gap() {
            return prefix.isEmpty() && body.getString().isEmpty();
        }
    }

    /** The notes as lines; an empty list for notes with nothing to show. */
    public static List<Line> lines(String markdown) {
        List<Line> out = new ArrayList<>();
        if (markdown == null) return out;
        String text = COMMENT.matcher(markdown.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        boolean blank = false;
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                blank = !out.isEmpty();
                continue;
            }
            if (SKIP.matcher(line).matches()) continue;
            Line l;
            var h = HEADING.matcher(line.strip());
            var b = BULLET.matcher(line);
            var n = NUMBERED.matcher(line);
            if (h.matches()) {
                l = new Line("", Component.empty().append(inline(h.group(1))).withStyle(ChatFormatting.BOLD));
                if (!out.isEmpty()) blank = true;
            } else if (b.matches()) {
                l = new Line(indent(b.group(1)) + "- ", inline(b.group(2)));
            } else if (n.matches()) {
                l = new Line(indent(n.group(1)) + n.group(2) + ". ", inline(n.group(3)));
            } else {
                l = new Line("", inline(line.strip().replaceFirst("^>\\s?", "")));
            }
            if (l.body().getString().isBlank()) continue;
            if (blank) out.add(Line.GAP);
            blank = false;
            out.add(l);
        }
        return out;
    }

    private static String indent(String spaces) {
        return "  ".repeat(Math.min(3, spaces.replace("\t", "  ").length() / 2));
    }

    /** One line's inline Markdown: **bold** stays bold; links, code, emphasis and HTML tags become their text. */
    static MutableComponent inline(String s) {
        String t = s.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "")
            .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
            .replaceAll("<(https?://[^>]+)>", "$1")
            .replaceAll("</?[a-zA-Z][^>]*>", "")
            .replace("`", "")
            .replaceAll("(\\*\\*|__)(\\S(?:.*?\\S)?)\\1", BOLD_ON + "$2" + BOLD_OFF)
            .replaceAll("(?<![\\w*])([*_])(\\S(?:.*?\\S)?)\\1(?![\\w*])", "$2")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        MutableComponent out = Component.empty();
        boolean bold = false;
        int start = 0;
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : BOLD_ON;
            if (c != BOLD_ON && c != BOLD_OFF) continue;
            if (i > start) {
                MutableComponent part = Component.literal(t.substring(start, i));
                out.append(bold ? part.withStyle(ChatFormatting.BOLD) : part);
            }
            bold = c == BOLD_ON;
            start = i + 1;
        }
        return out;
    }
}
