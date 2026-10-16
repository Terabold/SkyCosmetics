package io.github.terabold.skycosmetics.nametag;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player's name as the tab list shows it ("[413] Name" with rank color and icons). Hypixel often puts it on a
 * display entry of its own rather than the player's, so every listed entry is searched for one naming the player as
 * a whole word; the last one found is remembered for the session, so a player the tab list no longer has room for
 * keeps their level and colors. Read only; the entries are looked at again at most twice a second.
 */
final class TabNames {
    private record Entry(String plain, Component text) {}

    private static final int MAX_LINE = 256, MAX_REMEMBERED = 512;
    private static List<Entry> entries = List.of();
    private static long at;
    private static final Map<String, Component> found = new HashMap<>();
    private static final Map<String, Component> remembered = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Component> e) { return size() > MAX_REMEMBERED; }
    };

    private TabNames() {}

    /** The tab list's text for this player, or null when it has none and never had this session. */
    static Component of(UUID id, String username) {
        if (username == null || username.isEmpty()) return null;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(id);
        Component own = info == null ? null : info.getTabListDisplayName();
        if (own != null && names(own.getString(), username)) return remember(username, own);
        refresh();
        Component hit = found.computeIfAbsent(username, TabNames::search);
        return hit != null ? remember(username, hit) : remembered.get(username);
    }

    private static Component remember(String username, Component text) {
        remembered.put(username, text);
        return text;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - at < 500) return;
        at = now;
        found.clear();
        List<Entry> out = new ArrayList<>();
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                Component text = info.getTabListDisplayName();
                if (text == null) continue;
                String plain = ChatFormatting.stripFormatting(text.getString());
                if (plain != null && plain.length() <= MAX_LINE) out.add(new Entry(plain, text));
            }
        }
        entries = out;
    }

    private static Component search(String username) {
        for (Entry e : entries) if (names(e.plain(), username)) return e.text();
        return null;
    }

    /** Whether {@code line} has {@code username} as a whole word ("Steve" is not in "Steve2"). */
    static boolean names(String line, String username) {
        String plain = ChatFormatting.stripFormatting(line);
        if (plain == null) return false;
        for (int at = plain.indexOf(username); at >= 0; at = plain.indexOf(username, at + 1)) {
            int end = at + username.length();
            boolean before = at == 0 || !word(plain.charAt(at - 1)), after = end == plain.length() || !word(plain.charAt(end));
            if (before && after) return true;
        }
        return false;
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Where a player's name comes from, for {@code /skycosmetics debug names}. */
    private static String source(UUID id, String username) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        Component own = info == null ? null : info.getTabListDisplayName();
        if (own != null && names(own.getString(), username)) return "own tab entry";
        refresh();
        if (search(username) != null) return "a tab line";
        return remembered.containsKey(username) ? "remembered from earlier" : "nothing: plain name";
    }

    /** {@code /skycosmetics debug names}: the nearest players and where each name comes from; copied too. */
    static void register() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("skycosmetics").then(
                net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("debug").then(
                    net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("names").executes(ctx -> {
                        var mc = Minecraft.getInstance();
                        if (mc.level == null || mc.player == null) return 0;
                        StringBuilder report = new StringBuilder("Nametag sources:\n");
                        mc.level.players().stream()
                            .sorted(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)))
                            .limit(12)
                            .forEach(p -> {
                                String name = p.getGameProfile().name();
                                Component tab = of(p.getUUID(), name);
                                report.append(name).append(": ").append(source(p.getUUID(), name))
                                    .append(tab == null ? "" : " -> " + ChatFormatting.stripFormatting(tab.getString())).append('\n');
                            });
                        mc.keyboardHandler.setClipboard(report.toString());
                        for (String line : report.toString().split("\n")) {
                            ctx.getSource().sendFeedback(io.github.terabold.skycosmetics.SkyCosmetics.prefix()
                                .append(Component.literal(line).withStyle(ChatFormatting.GRAY)));
                        }
                        ctx.getSource().sendFeedback(io.github.terabold.skycosmetics.SkyCosmetics.prefix()
                            .append(Component.literal("Copied to your clipboard.").withStyle(ChatFormatting.GRAY)));
                        return 1;
                    })))));
    }

    /** A world change: the next lobby has other players. Remembered names stay for the session. */
    static void clear() {
        entries = List.of();
        found.clear();
        at = 0;
    }
}
