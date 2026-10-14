package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Skins, variants and animation timings published for SkyCosmetics between releases: one small file from the
 * SkyCosmetics-data repository, rebuilt every hour from Hypixel's public item and auction data, the NEU repo and
 * timings measured in game and reviewed. New skins show up without a mod update.
 *
 * It only adds to the catalog: a skin the NEU repo has keeps the repo's entry, and a timing learned in your own
 * game still wins over a published one. The request is a plain GET of a public file (no account, name or id is
 * sent), answered from cache while unchanged (ETag), at most every {@link #EVERY_MS}. What arrives is read like
 * captured.json and timings.json: Mojang skin textures only, bounded sizes, anything else skipped.
 */
public final class Remote {
    static final String URL = "https://raw.githubusercontent.com/Terabold/SkyCosmetics-data/main/v1/supplement.json";
    static final String FORMAT = "skycosmetics-supplement";
    private static final long EVERY_MS = 3 * 3_600_000L, RETRY_MS = 20 * 60_000L;
    /** The file is a few hundred kB; a bigger answer is not ours. */
    private static final long MAX_BYTES = 8L << 20;

    /** "Download New Skins" in the studio settings. */
    public static boolean enabled = true;

    /** What the file holds: skins as captured.json has them, and timings. */
    record Supplement(Captured skins, Timings timings, long generatedAt) {
        static final Supplement EMPTY = new Supplement(new Captured(), new Timings(), 0);
    }

    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static long lastTry, waitMs = RETRY_MS;

    private Remote() {}

    static void init() {
        Settings.register("skinUpdates", v -> enabled = Settings.bool(v, enabled), () -> new com.google.gson.JsonPrimitive(enabled));
    }

    static Path file() {
        return RepoSource.ownZip().resolveSibling("published-skins.json");
    }

    private static Path etagFile() {
        return RepoSource.ownZip().resolveSibling("published-skins.etag");
    }

    /** The saved supplement; empty when there is none or it cannot be read (it is downloaded again then). */
    static Supplement load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return Supplement.EMPTY;
        try {
            if (Files.size(f) > MAX_BYTES) throw new IOException("too large");
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            SkyCosmetics.LOG.warn("Could not read the published skins ({}); downloading them again", e.toString());
            try { Files.deleteIfExists(etagFile()); } catch (IOException ignored) { /* a full download follows anyway */ }
            return Supplement.EMPTY;
        }
    }

    static Supplement parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!FORMAT.equals(Settings.str(root.get("format"), null)) || Settings.num(root.get("version"), 0, 0, 1e9) != 1) {
            throw new IllegalArgumentException("not a version 1 supplement");
        }
        Captured skins = Captured.fromJson(root);
        JsonObject timings = new JsonObject();
        if (root.get("timings") instanceof JsonObject t) timings.add("skins", t);
        long at = (long) Settings.num(root.get("generatedAt"), 0, 0, Long.MAX_VALUE);
        return new Supplement(skins, Timings.fromJson(timings), at);
    }

    /**
     * Downloads the file when it is due, on a thread of its own; {@code changed} runs (on that thread) only when a
     * new version was saved.
     */
    static void maybeFetch(Runnable changed) {
        long now = System.currentTimeMillis();
        if (!enabled || System.getProperty("skycosmetics.repo") != null || now - lastTry < waitMs) return;
        if (now - lastChecked() < EVERY_MS || !FETCHING.compareAndSet(false, true)) return;
        lastTry = now;
        Thread t = new Thread(() -> {
            try {
                if (fetch()) changed.run();
                waitMs = RETRY_MS;
            } catch (IOException | RuntimeException e) {
                // A missing file (404) will not appear in minutes: ask again with the normal interval.
                waitMs = String.valueOf(e.getMessage()).contains("HTTP 404") ? EVERY_MS : RETRY_MS;
                SkyCosmetics.LOG.info("Could not check for newly published skins: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                FETCHING.set(false);
            }
        }, "SkyCosmetics-skins");
        t.setDaemon(true);
        t.start();
    }

    private static long lastChecked() {
        try {
            Path e = etagFile();
            return Files.isRegularFile(e) ? Files.getLastModifiedTime(e).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** True when a new version was saved. */
    private static boolean fetch() throws IOException, InterruptedException {
        Path f = file(), tag = etagFile();
        Files.createDirectories(f.getParent());
        String etag = Files.isRegularFile(tag) && Files.isRegularFile(f) ? Files.readString(tag, StandardCharsets.UTF_8).strip() : "";
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
        var request = HttpRequest.newBuilder(URI.create(URL)).header("User-Agent", "SkyCosmetics").timeout(Duration.ofSeconds(30)).GET();
        if (!etag.isEmpty() && etag.length() < 200) request.header("If-None-Match", etag);
        HttpResponse<byte[]> r = http.send(request.build(),
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
        if (r.statusCode() == 304) {
            Files.writeString(tag, etag, StandardCharsets.UTF_8); // touch: checked now
            return false;
        }
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        String body = new String(r.body(), StandardCharsets.UTF_8);
        Supplement s = parse(body); // throws on anything that is not a supplement: nothing is saved then
        Path part = f.resolveSibling(f.getFileName() + ".part");
        Files.writeString(part, body, StandardCharsets.UTF_8);
        try {
            Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, f, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(tag, r.headers().firstValue("etag").orElse(""), StandardCharsets.UTF_8);
        SkyCosmetics.LOG.info("Published skins updated: {} skins, {} animations, {} timings",
            s.skins().stills.size(), s.skins().anims.size(), s.timings().skins.size());
        return true;
    }

    /** {@code local} with the published skins under it: what you learned yourself is kept where both have an id. */
    static Captured merge(Supplement published, Captured local) {
        Captured out = new Captured();
        out.stills.putAll(published.skins().stills);
        out.anims.putAll(published.skins().anims);
        if (local != null) {
            out.stills.putAll(local.stills);
            out.anims.putAll(local.anims);
        }
        return out;
    }

    /** The shipped timings with the published ones over them. */
    static Timings merge(Supplement published, Timings bundled) {
        Timings out = new Timings();
        if (bundled != null) out.skins.putAll(bundled.skins);
        out.skins.putAll(published.timings().skins);
        return out;
    }
}
