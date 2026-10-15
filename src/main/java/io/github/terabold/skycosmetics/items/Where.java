package io.github.terabold.skycosmetics.items;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * Where you are on Hypixel, from what it already shows: SkyBlock's sidebar title says "SKYBLOCK"; in Dungeons a
 * sidebar line says "The Catacombs"; the tab list's "Area:" line names the island, and the Rift's sidebar has its
 * "ф" places and Motes. Read only; looked up at most twice a second, since the draw paths ask for every slot.
 */
public final class Where {
    /** Islands with an inventory of their own. */
    public enum Island { MAIN, RIFT, SAFARI }

    private static long at;
    private static boolean skyBlock, dungeon;
    private static Island island = Island.MAIN;
    private static String testArea;

    private Where() {}

    public static boolean skyBlock() {
        refresh();
        return skyBlock;
    }

    public static boolean dungeon() {
        refresh();
        return dungeon;
    }

    /** The Rift or the Safari, whose inventories are their own; anywhere else MAIN. */
    public static Island island() {
        refresh();
        return island;
    }

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - at < 500) return;
        at = now;
        skyBlock = dungeon = false;
        island = Island.MAIN;
        if (testArea != null) {
            skyBlock = true;
            island = islandOf(testArea);
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Scoreboard board = mc.level.getScoreboard();
        Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;
        skyBlock = plain(sidebar.getDisplayName()).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
        if (!skyBlock) return;
        for (PlayerScoreEntry e : board.listPlayerScores(sidebar)) {
            if (e.isHidden()) continue;
            // A line is its team's prefix and suffix around a blank holder name.
            String line = plain(PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName())).trim();
            if (line.contains("The Catacombs")) dungeon = true;
            if (line.startsWith("ф ") || line.startsWith("Motes:")) island = Island.RIFT;
            else if (island == Island.MAIN && line.contains("Safari")) island = Island.SAFARI;
        }
        if (island != Island.MAIN || mc.getConnection() == null) return;
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String line = plain(name).trim();
            if (line.startsWith("Area:")) { island = islandOf(line.substring(5).trim()); return; }
        }
    }

    private static Island islandOf(String area) {
        return area.contains("Rift") ? Island.RIFT : area.contains("Safari") ? Island.SAFARI : Island.MAIN;
    }

    private static String plain(Component c) {
        String s = ChatFormatting.stripFormatting(c.getString());
        return s == null ? "" : s;
    }

    /** For tests: forget the last look, so the next question looks again. */
    public static void testRefresh() {
        at = 0;
    }

    /** For tests: be on SkyBlock in this area ("Safari", "The Rift"), or null to read the game again. */
    public static void testArea(String area) {
        testArea = area;
        at = 0;
    }
}
