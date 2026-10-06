package io.github.terabold.skycosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One background thread for every config write, so saving looks, pets or the
 * item list never stalls a frame. Writes are atomic (temp file + move) and run
 * in submission order, so the last save always wins.
 *
 * Also where a file that could not be read is set aside, so the next save never
 * writes over it, and where caught failures are logged without flooding the log.
 */
public final class Io {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SkyCosmetics-io");
        t.setDaemon(true);
        return t;
    });
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Unreadable files that could not be set aside either: never written this session. */
    private static final Set<Path> KEEP = ConcurrentHashMap.newKeySet();
    /** Chat lines waiting for the player to be in a world. */
    private static final Queue<Component> NOTICES = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long FAILED_EVERY_MS = 60_000;
    /** A server item deeper or bigger than this is not saved: it is junk, and might not read back. */
    private static final int MAX_DEPTH = 64;
    private static final int MAX_CHARS = 64 * 1024;
    private static boolean noticeHook;

    private Io() {}

    /** Serialises {@code json} off-thread. Build the JSON on the caller's thread; it must not be mutated afterwards. */
    public static void writeAsync(Path file, JsonElement json) {
        WRITER.execute(() -> write(file, json));
    }

    public static void write(Path file, JsonElement json) {
        if (KEEP.contains(file)) {
            SkyCosmetics.LOG.warn("Not saving {}: it could not be read or set aside", file);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING);
                 Writer w = Channels.newWriter(ch, StandardCharsets.UTF_8)) {
                GSON.toJson(json, w);
                w.flush();
                ch.force(true); // on disk before it replaces the old file: a crash cannot leave an empty one
            }
            replace(tmp, file);
        } catch (Exception e) {
            SkyCosmetics.LOG.error("Could not save {}", file, e);
        }
    }

    /** Windows refuses to replace a file another program is reading (a virus scan, an editor): try again shortly. */
    private static void replace(Path tmp, Path file) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt == 10) throw e;
                Thread.sleep(50);
            }
        }
    }

    /**
     * Parses a file SkyCosmetics wrote. Older versions saved whole server items, which may nest
     * deeper than Gson's default limit of 255 (the network allows 512), so the limit is raised.
     */
    public static JsonElement parse(Reader r) {
        JsonReader jr = new JsonReader(r);
        jr.setNestingLimit(1024);
        return JsonParser.parseReader(jr);
    }

    /**
     * True if {@code json} (an encoded server item) is small enough to save: at most 64 levels
     * deep and about 64k characters. Hypixel's items are a few levels deep and a few kB.
     */
    public static boolean fits(JsonElement json) {
        return json != null && room(json, 0, MAX_CHARS) >= 0;
    }

    /** {@code left} minus the size of {@code e}; negative once over budget or too deep. */
    private static int room(JsonElement e, int depth, int left) {
        if (left < 0 || depth > MAX_DEPTH) return -1;
        if (e instanceof JsonObject o) {
            for (Map.Entry<String, JsonElement> m : o.entrySet()) {
                left = room(m.getValue(), depth + 1, left - m.getKey().length() - 4);
                if (left < 0) return -1;
            }
            return left - 2;
        }
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) {
                left = room(c, depth + 1, left - 1);
                if (left < 0) return -1;
            }
            return left - 2;
        }
        return left - (e.isJsonPrimitive() ? e.getAsString().length() + 2 : 4);
    }

    /** Lets pending writes finish when the game closes. */
    public static void flush() {
        WRITER.shutdown();
        try {
            WRITER.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------- failures ---

    /**
     * {@code file} could not be read, or not all of it: moves it to
     * {@code <name>.broken-<yyyyMMdd-HHmmss>.json}, so the next save starts a new file instead
     * of writing over it, logs why, and tells you in chat once you are in a world. If it cannot
     * be moved it is copied; if that fails too, it is never written this session. Any thread.
     *
     * @param problem the start of the chat line, such as "Could not read looks.json"
     */
    public static void setAside(Path file, String problem, Throwable why) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        String stamp = LocalDateTime.now().format(STAMP);
        Path aside = null;
        for (int i = 0; i < 10 && aside == null; i++) {
            Path to = file.resolveSibling(base + ".broken-" + stamp + (i == 0 ? "" : "-" + i) + ".json");
            if (Files.exists(to)) continue; // set aside twice in one second
            try {
                Files.move(file, to);
                aside = to;
            } catch (FileAlreadyExistsException e) {
                // taken meanwhile: try the next name
            } catch (IOException e) {
                try {
                    Files.copy(file, to); // in use elsewhere: a copy keeps it just as well
                    aside = to;
                } catch (IOException e2) {
                    break;
                }
            }
        }
        if (aside == null) {
            KEEP.add(file);
            SkyCosmetics.LOG.error("{} ({}); it could not be set aside either, so it is not saved over", problem, file, why);
            notice(problem + ". It is left as it is: nothing is saved to it until you restart.");
            return;
        }
        SkyCosmetics.LOG.warn("{}; set it aside as {}", problem, aside, why);
        notice(problem + ", so it was set aside as " + aside.getFileName() + ".");
    }

    /** A warning shown in chat the next time you are in a world. Any thread. */
    public static void notice(String text) {
        NOTICES.add(Component.literal(text).withStyle(ChatFormatting.YELLOW));
        synchronized (Io.class) {
            if (noticeHook) return;
            noticeHook = true;
        }
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            for (Component c; (c = NOTICES.poll()) != null; ) mc.player.sendSystemMessage(SkyCosmetics.prefix().append(c));
        });
    }

    /**
     * A failure caught so that it cannot reach Minecraft's rendering, ticking or packet
     * handling. Logged with its stack trace the first time per {@code what}, then at most
     * once a minute, so a bad item drawn every frame cannot flood the log. Any thread.
     */
    public static void failed(String what, Throwable e) {
        long now = System.currentTimeMillis();
        Long last = FAILED.get(what);
        if (last != null && now - last < FAILED_EVERY_MS) return;
        FAILED.put(what, now);
        if (last == null) SkyCosmetics.LOG.error("{} failed; skipped", what, e);
        else SkyCosmetics.LOG.warn("{} failed again: {}", what, e.toString());
    }
}
