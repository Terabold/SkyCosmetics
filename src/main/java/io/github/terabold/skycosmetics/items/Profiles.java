package io.github.terabold.skycosmetics.items;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which SkyBlock profile you are on, so "My Items" does not mix profiles.
 * Hypixel says it in chat on every join and profile switch:
 * "You are playing on profile: Apple" (optionally "(Co-op)"). Read only.
 */
public final class Profiles {
    private static final Pattern PLAYING_ON = Pattern.compile("^You are playing on profile: (\\w+)(?: \\(.+\\))?$");
    private static final Pattern SWITCHED = Pattern.compile("^Your profile was changed to: (\\w+)(?: \\(.+\\))?$");

    private static String current;

    private Profiles() {}

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) read(msg); });
        // Chat mods (SkyHanni, Skyblocker...) may hide the line; it still counts.
        ClientReceiveMessageEvents.GAME_CANCELED.register((msg, overlay) -> { if (!overlay) read(msg); });
    }

    private static void read(Component msg) {
        String s = ChatFormatting.stripFormatting(msg.getString()).trim();
        Matcher m = PLAYING_ON.matcher(s);
        if (!m.matches()) m = SWITCHED.matcher(s);
        if (m.matches() && !m.group(1).equals(current)) {
            current = m.group(1);
            OwnedItems.profileChanged();
        }
    }

    /** The current profile name ("Apple"), or null until Hypixel has said it this session. */
    public static String current() {
        return current;
    }
}
