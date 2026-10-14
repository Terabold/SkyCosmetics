package io.github.terabold.skycosmetics.items;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * Where you are on Hypixel, from the sidebar it shows: SkyBlock's title says "SKYBLOCK", and in Dungeons a line says
 * "The Catacombs". Read only; looked up at most twice a second, since the draw paths ask for every slot.
 */
public final class Where {
    private static long at;
    private static boolean skyBlock, dungeon;

    private Where() {}

    public static boolean skyBlock() {
        refresh();
        return skyBlock;
    }

    public static boolean dungeon() {
        refresh();
        return dungeon;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - at < 500) return;
        at = now;
        skyBlock = dungeon = false;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Scoreboard board = level.getScoreboard();
        Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;
        skyBlock = plain(sidebar.getDisplayName()).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
        if (!skyBlock) return;
        for (PlayerScoreEntry e : board.listPlayerScores(sidebar)) {
            if (e.isHidden()) continue;
            // A line is its team's prefix and suffix around a blank holder name.
            PlayerTeam team = board.getPlayersTeam(e.owner());
            if (plain(PlayerTeam.formatNameForTeam(team, e.ownerName())).contains("The Catacombs")) { dungeon = true; return; }
        }
    }

    private static String plain(Component c) {
        String s = ChatFormatting.stripFormatting(c.getString());
        return s == null ? "" : s;
    }

    /** For tests: forget the last look, so the next question looks again. */
    public static void testRefresh() {
        at = 0;
    }
}
