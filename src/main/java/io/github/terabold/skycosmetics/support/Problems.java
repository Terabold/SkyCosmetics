package io.github.terabold.skycosmetics.support;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What went wrong in SkyCosmetics this session, for "Report a bug": every failure {@code Io.failed} catches (a bad
 * item, a renderer another mod changed...), once per kind with a count. Kept in memory only; nothing is sent
 * anywhere. The first one of a session is mentioned once in chat, unless turned off.
 */
public final class Problems {
    /** One kind of failure: where, what was thrown, and our part of the stack. */
    public record Problem(String what, String error, List<String> stack, int count, long first, long last) {}

    private static final int MAX = 50, STACK = 6;
    private static final Map<String, Problem> SEEN = new ConcurrentHashMap<>();
    private static final AtomicBoolean TOLD = new AtomicBoolean();
    /** "Tell me when something goes wrong" in General. */
    public static boolean alerts = true;

    private Problems() {}

    public static void init() {
        Settings.register("problemAlerts", v -> alerts = Settings.bool(v, alerts), () -> new com.google.gson.JsonPrimitive(alerts));
    }

    /** Any thread. */
    public static void record(String what, Throwable e) {
        long now = System.currentTimeMillis();
        SEEN.compute(what, (k, had) -> {
            if (had != null) return new Problem(k, had.error, had.stack, had.count + 1, had.first, now);
            if (SEEN.size() >= MAX) return null;
            return new Problem(k, summary(e), stack(e), 1, now, now);
        });
        if (alerts && TOLD.compareAndSet(false, true)) Minecraft.getInstance().execute(() -> tell(what));
    }

    public static List<Problem> list() {
        List<Problem> out = new ArrayList<>(SEEN.values());
        out.sort(Comparator.comparingLong(Problem::first));
        return out;
    }

    public static int count() {
        return SEEN.size();
    }

    private static void tell(String what) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) { TOLD.set(false); return; } // say it once a world is open
        Component report = Component.literal("[Report it]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
            .withClickEvent(new ClickEvent.CopyToClipboard(Report.build()))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copies a report of what went wrong; then open Settings → General → Report a bug."))));
        mc.player.sendSystemMessage(SkyCosmetics.prefix().append(Component.literal("Something went wrong (" + what + "). The game keeps running; ")
            .withStyle(ChatFormatting.GRAY)).append(report));
    }

    private static String summary(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        if (m != null && m.length() > 200) m = m.substring(0, 200) + "...";
        String s = e.getClass().getName() + (m == null ? "" : ": " + m);
        Throwable c = e.getCause();
        return c != null && c != e ? s + " (caused by " + c.getClass().getSimpleName() + ")" : s;
    }

    /** The top frame, then up to a few of SkyCosmetics' own frames: enough to find the line, nothing of the player's. */
    private static List<String> stack(Throwable e) {
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < st.length && out.size() < STACK; i++) {
            String c = st[i].getClassName();
            if (i == 0 || c.startsWith("io.github.terabold.skycosmetics")) out.add(c.replace("io.github.terabold.skycosmetics.", "")
                + "." + st[i].getMethodName() + ":" + st[i].getLineNumber());
        }
        return out;
    }
}
