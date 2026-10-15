package io.github.terabold.skycosmetics;

import io.github.terabold.skycosmetics.items.OwnedItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your names where chat or a tooltip line names one of your renamed items the way Hypixel does: "You summoned your
 * Black Cat!", or "Helmet: Renowned Sorrow Helmet" in a loadout. Only lines added to the chat box and tooltip lines
 * about to be drawn change, at the very end, so every mod (and this one) still reads Hypixel's text. A name two of
 * your items share, with different names of yours, is left alone: there is no telling which one is meant.
 */
public final class ChatNames {
    private static final Pattern LEVEL = Pattern.compile("^\\[Lvl \\d+] ");
    private static final long EVERY_MS = 2000;
    private static final int MIN_LENGTH = 4, MAX_NAMES = 512, MAX_LINE = 4096;

    private static Map<String, Component> names = Map.of();
    private static Pattern any;
    private static long builtAt;

    private record Piece(String text, Style style) {}

    private ChatNames() {}

    /** The line with your names in it, or the line itself (most lines: one regex search). Never throws. */
    public static Component apply(Component line) {
        if (!Settings.chatNames || line == null) return line;
        try {
            build();
            if (any == null) return line;
            String plain = line.getString();
            if (plain.length() > MAX_LINE || !any.matcher(plain).find()) return line;
            return replace(line);
        } catch (RuntimeException e) {
            Io.failed("Showing your item names in chat", e);
            return line;
        }
    }

    /** Tooltip lines: all but the first, which is the item's own name and already yours when it should be. */
    public static List<Component> applyLore(List<Component> lines) {
        if (!Settings.chatNames || lines == null || lines.size() < 2) return lines;
        List<Component> out = null;
        for (int i = 1; i < lines.size(); i++) {
            Component was = lines.get(i), now = apply(was);
            if (now != was) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(i, now);
            }
        }
        return out == null ? lines : out;
    }

    /** Your renamed items, from what you carry and wear and My Items; rebuilt every couple of seconds. */
    private static void build() {
        long now = System.currentTimeMillis();
        if (now - builtAt < EVERY_MS) return;
        builtAt = now;
        Map<String, Component> found = new HashMap<>();
        Set<String> shared = new HashSet<>();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) add(inv.getItem(i), found, shared);
        }
        for (OwnedItems.Owned o : OwnedItems.list()) {
            if (found.size() >= MAX_NAMES) break;
            if (named(Looks.byUuid(o.uuid)) || named(Looks.byType(o.type))) add(o.stack(), found, shared); // decode only those
        }
        shared.forEach(found::remove);
        names = Map.copyOf(found);
        if (names.isEmpty()) { any = null; return; }
        List<String> keys = new ArrayList<>(names.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed()); // the longest name wins where two overlap
        StringBuilder b = new StringBuilder();
        for (String k : keys) b.append(b.isEmpty() ? "" : "|").append(Pattern.quote(k));
        any = Pattern.compile(b.toString());
    }

    private static boolean named(Looks.Look look) {
        return look != null && look.name() != null && !look.name().isEmpty();
    }

    private static void add(ItemStack s, Map<String, Component> found, Set<String> shared) {
        if (s == null || s.isEmpty()) return;
        Component custom = Cosmetics.customName(s);
        if (custom == null) return;
        String original = ChatFormatting.stripFormatting(Cosmetics.originalName(s).getString());
        String mine = custom.getString();
        if (original == null || original.isBlank() || original.equals(mine)) return;
        // Chat names a pet without its level ("your Black Cat"), unless your name keeps the level too.
        if (!LEVEL.matcher(mine).find()) original = LEVEL.matcher(original).replaceFirst("");
        original = original.trim();
        if (original.length() < MIN_LENGTH) return;
        Component had = found.putIfAbsent(original, custom);
        if (had != null && !had.getString().equals(mine)) shared.add(original);
    }

    private static Component replace(Component line) {
        List<Piece> pieces = new ArrayList<>();
        line.visit((style, text) -> {
            if (!text.isEmpty()) pieces.add(new Piece(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        StringBuilder all = new StringBuilder();
        int[] starts = new int[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            starts[i] = all.length();
            all.append(pieces.get(i).text());
        }
        MutableComponent out = Component.empty();
        Matcher m = any.matcher(all);
        int done = 0;
        boolean changed = false;
        while (m.find()) {
            if (!edge(all, m.start() - 1, true) || !edge(all, m.end(), false)) continue; // part of a longer word
            emit(out, pieces, starts, done, m.start());
            int at = pieceAt(starts, m.start());
            Style where = pieces.get(at).style();
            // Your name in its own style, still clickable and hoverable where the original was.
            out.append(Component.empty().withStyle(Style.EMPTY.withClickEvent(where.getClickEvent())
                .withHoverEvent(where.getHoverEvent()).withInsertion(where.getInsertion())).append(names.get(m.group()).copy()));
            done = m.end();
            changed = true;
        }
        if (!changed) return line;
        emit(out, pieces, starts, done, all.length());
        return out;
    }

    /** Whether the character next to a match (or the text's end) keeps it a whole name. */
    private static boolean edge(CharSequence s, int i, boolean before) {
        if (i < 0 || i >= s.length()) return true;
        if (before && i > 0 && s.charAt(i - 1) == '§') return true; // a formatting code right before the name
        return !Character.isLetterOrDigit(s.charAt(i));
    }

    private static int pieceAt(int[] starts, int pos) {
        int i = 0;
        while (i + 1 < starts.length && starts[i + 1] <= pos) i++;
        return i;
    }

    /** Text {@code from..to} in its own styles, a cut piece keeping the formatting codes in force where it was cut. */
    private static void emit(MutableComponent out, List<Piece> pieces, int[] starts, int from, int to) {
        for (int i = 0; i < pieces.size() && from < to; i++) {
            String text = pieces.get(i).text();
            int s = starts[i], e = s + text.length();
            if (e <= from || s >= to) continue;
            int a = Math.max(s, from) - s, b = Math.min(e, to) - s;
            String part = (a > 0 ? codesAt(text, a) : "") + text.substring(a, b);
            out.append(Component.literal(part).withStyle(pieces.get(i).style()));
        }
    }

    /** The legacy formatting codes in force at {@code end} of {@code text}: the last color and the formats after it. */
    private static String codesAt(String text, int end) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i + 1 < end; i++) {
            if (text.charAt(i) != '§') continue;
            ChatFormatting f = ChatFormatting.getByCode(text.charAt(i + 1));
            if (f == null) continue;
            if (f.isColor() || f == ChatFormatting.RESET) codes.setLength(0);
            if (f != ChatFormatting.RESET) codes.append('§').append(text.charAt(i + 1));
            i++;
        }
        return codes.toString();
    }

    /** Build again on the next line: a setting changed. */
    public static void rebuild() {
        builtAt = 0;
    }
}
