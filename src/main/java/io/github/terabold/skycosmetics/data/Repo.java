package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Names;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Keeps {@link #get()} on the newest complete NEU repo, plus skins and frame
 * timings learned in game, timings shipped with the mod, and estimated blinks.
 *
 * Sources, best first: -Dskycosmetics.repo, Skyblocker's clone, Firmament's
 * extracted zip, our own zip, then leftovers of an uninstalled mod (see
 * {@link RepoSource}). Both mods rewrite their copy while the game runs, so a
 * copy is read only while it is settled and every reload is a transaction:
 * snapshot, parse into a new catalog off-thread, snapshot again, sanity-check,
 * then swap one volatile reference. Any failure keeps the old catalog, an
 * unchanged commit is never parsed twice, and a copy that fails three times is
 * left alone until it changes.
 *
 * Change signals: Skyblocker's own after-load hook (exact and immediate), plus a
 * probe that reads a few small files every 10 s for the first 3 minutes (when
 * the mods update) and every 60 s after.
 *
 * Everything not volatile below is owned by the single "SkyCosmetics-repo" thread.
 */
public final class Repo {
    private static final String LATEST_SHA = "https://api.github.com/repos/NotEnoughUpdates/NotEnoughUpdates-REPO/commits/master";
    private static final String ARCHIVE = "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/";
    private static final long FAST_POLL_FOR_MS = 180_000;
    private static final long SLOW_POLL_MS = 60_000;
    private static final long DAY_MS = 24 * 3_600_000L;
    private static final long DOWNLOAD_RETRY_MS = 30 * 60_000L;
    /** The repo has ~8.8k items; a copy with far fewer is broken, not small. */
    private static final int MIN_ITEMS = 1000;
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_COMMIT_BYTES = 64 * 1024;
    private static final int MAX_QUICK_RETRIES = 40;
    /** Parse attempts per snapshot: a copy that fails this often is broken, not busy. */
    private static final int MAX_FAILURES = 3;

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SkyCosmetics-repo");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();

    private static volatile Catalog catalog = Catalog.EMPTY;
    /** Raw primary data, before captures or timing overrides; supplemental comparisons must use this snapshot. */
    private static volatile Catalog primary = Catalog.EMPTY;
    /** The repo half of {@link #catalog}, kept so learned skins can be merged again without a re-parse. */
    private static volatile RepoParser.Parsed base;
    private static volatile boolean parsing;
    /** Nothing loaded yet and an installed mod's copy is still being written. */
    private static volatile boolean waiting;
    /** The first look at the sources has finished (before it, "nothing loaded" means "not yet"). */
    private static volatile boolean tried;
    private static volatile long loadMillis = -1;

    private static boolean started;
    private static long startedAt;
    private static long lastPoll;
    private static Captured captured;
    private static Timings timings;
    /** {@code assets/skycosmetics/timings.json}, read once. */
    private static Timings bundled;
    /** Skins and timings published since this version: {@link Remote}. */
    private static Remote.Supplement published;
    private static BlinkGuesser.Store guesses;
    /** What the next {@link #flush} has to save. */
    private static boolean capturedDirty;
    private static boolean timingsDirty;
    private static boolean guessesDirty;
    private static String loadedVersion;
    private static ScheduledFuture<?> retry;
    private static int retries;
    /** Failed parses per snapshot; a broken copy is skipped until it changes (or a forced reload). */
    private static final Map<RepoSource.Snapshot, Integer> FAILURES = new HashMap<>();
    private static long lastDownloadTry;
    private static boolean flushQueued;
    private static final List<Component> NEWS = new ArrayList<>();
    /** Learned timings of skins a look wears, said in their own line. */
    private static final List<Component> TIMING_NEWS = new ArrayList<>();
    /** New skins learned per game session, so a server showing made-up skins cannot push out real ones. */
    private static final int MAX_LEARNED_PER_SESSION = 200;
    private static int learnedThisSession;
    /** Chat lines about learned skins: a few a minute at most; the rest are counted into the next line. */
    private static final int NEWS_PER_MINUTE = 3;
    private static final long[] NEWS_AT = new long[NEWS_PER_MINUTE];
    private static int newsSlot;
    private static int unannounced;

    private Repo() {}

    public static Catalog get() {
        return catalog;
    }

    static Catalog primary() { return primary; }
    static String primaryVersion() { return loadedVersion; }

    /** Runs a bounded inspection where learned stores are owned; callers must not modify them. */
    static <T> java.util.concurrent.CompletableFuture<T> inspectLearned(java.util.function.BiFunction<Captured, Timings, T> read) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> read.apply(
            captured == null ? new Captured() : captured, timings == null ? new Timings() : timings), EXEC);
    }

    /** True while there is nothing to show yet but something is on its way. */
    public static boolean loading() {
        return parsing || catalog.items == 0 && (!tried || waiting || DOWNLOADING.get());
    }

    /** How long the last successful parse took, or -1 before the first. */
    public static long loadMillis() {
        return loadMillis;
    }

    /**
     * Re-reads captured.json and the best settled repo copy, even if its commit
     * is unchanged. {@code onDone} runs on the repo thread, also on failure.
     */
    public static void reload(Runnable onDone) {
        start();
        EXEC.execute(() -> {
            try {
                refresh(true);
            } catch (Throwable e) {
                SkyCosmetics.LOG.error("Repo load failed", e);
            } finally {
                if (onDone != null) guarded(onDone);
            }
        });
    }

    /**
     * The live repo with {@code capturedJson} merged over it the way captured.json
     * is. Saves and swaps nothing; lets tests check the merge rules.
     */
    public static Catalog withLearned(String capturedJson) {
        RepoParser.Parsed b = base;
        if (b == null) return catalog;
        Captured c = Captured.fromJson(JsonParser.parseString(capturedJson).getAsJsonObject());
        c.trim(Looks.usedSkins());
        return Captured.compose(b, c, null, null, null);
    }

    /**
     * For tests: the timings shipped with the mod are {@code json} instead (null: the real ones again), from the
     * next reload on. The file format is the one of the learned timings.json.
     */
    public static void useBundledTimings(String json) {
        EXEC.execute(() -> guarded(() -> bundled = json == null ? Timings.bundled()
            : Timings.fromJson(JsonParser.parseString(json).getAsJsonObject())));
    }

    // ------------------------------------------------------------ signals ---

    private static synchronized void start() {
        if (started) return;
        started = true;
        startedAt = System.currentTimeMillis();
        EXEC.scheduleWithFixedDelay(() -> guarded(Repo::poll), 10, 10, TimeUnit.SECONDS);
        // After every mod's init: loading Skyblocker's class earlier would start its repo load early.
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> hookSkyblocker());
    }

    /**
     * Skyblocker re-runs this after each completed load (startup and
     * /skyblocker updateRepository), once the checkout is complete. Reflection
     * keeps Skyblocker an optional, compile-free dependency.
     */
    private static void hookSkyblocker() {
        if (!FabricLoader.getInstance().isModLoaded("skyblocker") || System.getProperty("skycosmetics.repo") != null) return;
        try {
            Class.forName("de.hysky.skyblocker.utils.NEURepoManager")
                .getMethod("runAsyncAfterLoad", Runnable.class)
                .invoke(null, (Runnable) () -> EXEC.execute(() -> guarded(() -> {
                    retries = 0;
                    refresh(false);
                })));
            SkyCosmetics.LOG.info("Following Skyblocker's NEU repo updates");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            SkyCosmetics.LOG.warn("Could not hook Skyblocker's repo updates ({}); polling only", e.toString());
        }
    }

    private static void poll() {
        long now = System.currentTimeMillis();
        if (now - startedAt > FAST_POLL_FOR_MS && now - lastPoll < SLOW_POLL_MS - 1000) return;
        lastPoll = now;
        refresh(false);
        Remote.maybeFetch(() -> EXEC.execute(() -> guarded(() -> {
            published = Remote.load();
            publish();
        })));
    }

    /** Quick re-check while a copy is mid-update; bounded so a stuck copy falls back to the plain poll. */
    private static void retrySoon() {
        if (retry != null && !retry.isDone() || ++retries > MAX_QUICK_RETRIES) return;
        retry = EXEC.schedule(() -> guarded(() -> refresh(false)), 3, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- reload ---

    private static void refresh(boolean force) {
        try {
            check(force);
        } finally {
            tried = true;
        }
    }

    private static void check(boolean force) {
        if (captured == null || force) captured = Captured.load();
        if (timings == null || force) timings = Timings.load(timings);
        if (bundled == null) bundled = Timings.bundled();
        if (published == null || force) published = Remote.load();
        if (guesses == null || force) guesses = BlinkGuesser.Store.load();
        List<RepoSource.Candidate> all = RepoSource.candidates();
        RepoSource.Candidate pick = null;
        RepoSource.Snapshot snap = null;
        boolean awaited = false;
        // The newest settled copy wins: an installed mod whose own update failed (a stale lock file, no network)
        // must not hold the catalog back while another copy is current. A forced path always wins.
        for (RepoSource.Candidate c : all) {
            RepoSource.Snapshot s = c.probe();
            if (s != null && (force || FAILURES.getOrDefault(s, 0) < MAX_FAILURES)) {
                if (c.kind == RepoSource.Kind.OVERRIDE) { pick = c; snap = s; break; }
                if (pick == null || s.stamp() > snap.stamp()) { pick = c; snap = s; }
            } else if (s == null && c.modLoaded) {
                // Its mod is rewriting (or creating) it: when it settles it is the newest and is read then.
                awaited = true;
            }
        }
        maybeDownload(all);
        if (pick == null) {
            waiting = awaited && base == null;
            if (awaited) retrySoon();
            return;
        }
        waiting = false;
        if (!force && base != null && snap.version().equals(loadedVersion)) {
            retries = 0;
            return;
        }
        load(pick, snap, force);
    }

    private static void load(RepoSource.Candidate pick, RepoSource.Snapshot snap, boolean force) {
        String label = pick.kind.label + "@" + shortSha(snap.version());
        long t0 = System.nanoTime();
        RepoParser.Parsed parsed;
        parsing = true;
        try (RepoSource.Source src = pick.open()) {
            parsed = RepoParser.parse(src, label);
        } catch (IOException | RuntimeException e) {
            SkyCosmetics.LOG.warn("Could not read {} ({}); keeping {}", label, e.toString(), catalog.source);
            if (failed(snap, 1) < MAX_FAILURES) retrySoon();
            return;
        } finally {
            parsing = false;
        }
        if (!snap.equals(pick.probe())) {
            SkyCosmetics.LOG.info("{} changed while it was read; trying again shortly", label);
            retrySoon();
            return;
        }
        String bad = sanity(parsed, pick, force);
        if (bad != null) {
            SkyCosmetics.LOG.warn("Ignoring {}: {}; keeping {}", label, bad, catalog.source);
            failed(snap, MAX_FAILURES); // same files, same verdict: wait for the copy to change
            return;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        base = parsed;
        primary = new Catalog(parsed.label, parsed.items, 0, parsed.skins, parsed.dyes, parsed.names, parsed.dyeOrder);
        loadedVersion = snap.version();
        loadMillis = ms;
        retries = 0;
        FAILURES.clear();
        publish();
        Catalog c = catalog;
        SkyCosmetics.LOG.info("{} in {} ms: {} items, {} skins ({} animated in lore without frames), {} dyes, {} learned, {} timed",
            label, ms, parsed.items, c.skins.size(), parsed.missingFrames, c.dyes.size(), c.learned, TimingLearner.timedSkins());
    }

    /** Counts a failed parse of {@code snap}; returns the failures so far for it. */
    private static int failed(RepoSource.Snapshot snap, int add) {
        if (FAILURES.size() > 16) FAILURES.clear();
        return FAILURES.merge(snap, add, Integer::sum);
    }

    /** Null if {@code p} looks like a whole repo, else why not. */
    private static String sanity(RepoParser.Parsed p, RepoSource.Candidate from, boolean force) {
        if (p.items < (from.kind == RepoSource.Kind.OVERRIDE ? 1 : MIN_ITEMS)) return "only " + p.items + " items";
        if (p.skins.isEmpty() || p.dyes.isEmpty()) return "no skins or no dyes";
        RepoParser.Parsed old = base;
        if (!force && old != null && p.items < old.items * 9 / 10) return p.items + " items, down from " + old.items;
        return null;
    }

    /** Repo plus captured.json and every timing source -> the live catalog. Repo thread only. */
    private static void publish() {
        RepoParser.Parsed b = base;
        if (b == null) return;
        Remote.Supplement p = published == null ? Remote.Supplement.EMPTY : published;
        Catalog c = Captured.compose(b, Remote.merge(p, captured), guesses, Remote.merge(p, bundled), timings);
        catalog = c;
        TimingLearner.index(c);
        BlinkGuesser.index(c, guesses);
        Looks.bump();
    }

    private static String shortSha(String v) {
        return v.length() == 40 ? v.substring(0, 8) : v;
    }

    // ------------------------------------------------------------ learned ---

    /** A skin seen in game that the repo lacks. Saved and merged in a batch shortly after. */
    static void learn(String id, Captured.Still still, Component news) {
        EXEC.execute(() -> guarded(() -> {
            if (captured == null) captured = Captured.load();
            RepoParser.Parsed b = base;
            if (b != null && (b.skins.containsKey(id) || b.names.containsKey(id))) return;
            Captured.Still old = captured.stills.get(id);
            Captured.Still next = still;
            if (old != null) {
                // Upgrade old captures only from the same skin, when its lore now supplies variant metadata.
                if (old.swappable() > 0 || still.swappable() <= 0
                    || !java.util.Objects.equals(SkinLearner.textureHash(old.texture()), SkinLearner.textureHash(still.texture()))) return;
                next = new Captured.Still(old.name(), old.color(), old.kind(), old.texture(), old.at(),
                    still.use(), still.swappable());
            }
            if (!mayLearn()) return;
            captured.stills.put(id, next);
            captured.trim(Looks.usedSkins());
            capturedDirty = true;
            queue(news);
        }));
    }

    /**
     * A recorded preview. A repo entry that already has frames wins, so this is dropped then. {@code same} (may be
     * null) is said instead of {@code news} when nothing new was learned, so a recording someone asked for always
     * ends with a line.
     */
    static void learn(String key, Captured.Anim anim, Component news, Component same) {
        EXEC.execute(() -> guarded(() -> {
            if (captured == null) captured = Captured.load();
            RepoParser.Parsed b = base;
            SkinEntry known = b == null ? null : b.skins.get(key);
            Captured.Anim had = captured.anims.get(key);
            boolean nothingNew = known != null && (known.animated() || anim.textures().length < 2)
                || anim.textures().length < 2 && captured.stills.containsKey(key)
                || had != null && java.util.Arrays.equals(had.textures(), anim.textures())
                    && had.ticks() == anim.ticks() && java.util.Arrays.equals(had.ticksPerTexture(), anim.ticksPerTexture())
                    && had.evidence().ordinal() >= anim.evidence().ordinal();
            if (nothingNew || anim.textures().length > Captured.MAX_FRAMES) {
                if (same != null) say(same);
                return;
            }
            if (!mayLearn()) {
                if (same != null) say(Component.literal("Learned enough skins for one session; restart the game to learn more")
                    .withStyle(ChatFormatting.GRAY));
                return;
            }
            captured.anims.put(key, anim);
            captured.trim(Looks.usedSkins());
            capturedDirty = true;
            queue(news);
        }));
    }

    /**
     * Frame timings measured in game for {@code id} (see {@link TimingLearner}), kept while the live skin still
     * has exactly these {@code textures}. Saved and merged in a batch like learned skins; {@code news} (may be
     * null) is said once they are live.
     */
    static void learnTiming(String id, String[] textures, int[] ticks, Component news) {
        EXEC.execute(() -> guarded(() -> {
            if (timings == null) timings = Timings.load(null);
            SkinEntry live = catalog.skins.get(id);
            if (live == null || !java.util.Arrays.equals(live.textures, textures)) return;
            if (!timings.put(id, Timings.smooth(ticks), Looks.usedSkins())) return;
            timingsDirty = true;
            if (news != null) TIMING_NEWS.add(news);
            queue(null);
        }));
    }

    /**
     * What {@link BlinkGuesser} found for {@code id} with frames {@code sig}: blink ticks, or null for no blink;
     * {@code family}: a blink only if its sibling variants are. Saved and merged in a batch like learned timings.
     */
    static void guessTiming(String id, String sig, int[] ticks, boolean family) {
        EXEC.execute(() -> guarded(() -> {
            if (guesses == null) guesses = BlinkGuesser.Store.load();
            if (!guesses.put(id, sig, ticks, family, Looks.usedSkins())) return;
            guessesDirty = true;
            queue(null);
        }));
    }

    private static boolean mayLearn() {
        if (learnedThisSession < MAX_LEARNED_PER_SESSION) {
            learnedThisSession++;
            return true;
        }
        if (learnedThisSession++ == MAX_LEARNED_PER_SESSION) {
            SkyCosmetics.LOG.warn("Learned {} skins this session; learning more waits for a restart", MAX_LEARNED_PER_SESSION);
        }
        return false;
    }

    /** One save, one catalog rebuild and one Looks.bump() for a whole menu of new skins. */
    private static void queue(Component news) {
        if (news != null) NEWS.add(news);
        if (flushQueued) return;
        flushQueued = true;
        EXEC.schedule(() -> guarded(Repo::flush), 500, TimeUnit.MILLISECONDS);
    }

    /** Saves, publishes, and says what was learned in one chat line: the skin itself, or how many. */
    private static void flush() {
        flushQueued = false;
        if (capturedDirty) captured.save();
        if (timingsDirty) timings.save();
        if (guessesDirty) guesses.save();
        capturedDirty = timingsDirty = guessesDirty = false;
        publish();
        if (!TIMING_NEWS.isEmpty()) {
            say(TIMING_NEWS.size() == 1 ? TIMING_NEWS.getFirst() : Component.literal("Learned the animation timing of "
                + Names.count(TIMING_NEWS.size(), "skin") + " you use").withStyle(ChatFormatting.GRAY));
            TIMING_NEWS.clear();
        }
        int count = NEWS.size() + unannounced;
        if (count == 0) return;
        Component line = count == 1 && NEWS.size() == 1 ? NEWS.getFirst()
            : Component.literal("Learned " + Names.count(count, "skin") + " the repo does not have yet").withStyle(ChatFormatting.GRAY);
        NEWS.clear();
        long now = System.currentTimeMillis();
        if (now - NEWS_AT[newsSlot] < 60_000) {
            unannounced = count; // said in the next line
            return;
        }
        NEWS_AT[newsSlot] = now;
        newsSlot = (newsSlot + 1) % NEWS_PER_MINUTE;
        unannounced = 0;
        say(line);
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(SkyCosmetics.prefix().append(line));
        });
    }

    // ----------------------------------------------------------- download ---

    /**
     * Our own zip is fetched only when neither Skyblocker nor Firmament is
     * installed (or an installed one produced nothing for 3 minutes), and then
     * checked at most once a day. A leftover copy of an uninstalled mod never
     * updates, so it only bridges the gap until the zip exists.
     */
    private static void maybeDownload(List<RepoSource.Candidate> all) {
        long now = System.currentTimeMillis();
        boolean modInstalled = false;
        for (RepoSource.Candidate c : all) modInstalled |= c.modLoaded;
        boolean stranded = base == null && now - startedAt > FAST_POLL_FOR_MS && System.getProperty("skycosmetics.repo") == null;
        if (modInstalled && !stranded) return;
        if (now - lastDownloadTry < DOWNLOAD_RETRY_MS || now - lastChecked() < DAY_MS) return;
        lastDownloadTry = now;
        if (!DOWNLOADING.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            Path ready = null;
            try {
                ready = fetch();
            } catch (Exception e) {
                SkyCosmetics.LOG.warn("NEU repo download failed: {}", e.toString());
            } finally {
                DOWNLOADING.set(false);
            }
            Path part = ready;
            // Swapped on the repo thread so it never replaces a zip that is being parsed.
            EXEC.execute(() -> guarded(() -> {
                if (part != null) install(part);
                refresh(false);
            }));
        }, "SkyCosmetics-download");
        t.setDaemon(true);
        t.start();
    }

    /** When our zip was last downloaded or confirmed current; 0 if there is no zip. */
    private static long lastChecked() {
        Path zip = RepoSource.ownZip();
        try {
            if (!Files.isRegularFile(zip)) return 0;
            long t = Files.getLastModifiedTime(zip).toMillis();
            Path mark = checkedMark();
            return Files.isRegularFile(mark) ? Math.max(t, Files.getLastModifiedTime(mark).toMillis()) : t;
        } catch (IOException e) {
            return 0;
        }
    }

    private static Path checkedMark() {
        return RepoSource.ownZip().resolveSibling("neu-repo.checked");
    }

    /**
     * Asks GitHub for master's commit first, so an up-to-date zip costs one tiny
     * request instead of a 30 MB download. Returns the downloaded .part file, or
     * null when ours is already current.
     */
    private static Path fetch() throws IOException, InterruptedException {
        Path zip = RepoSource.ownZip();
        Files.createDirectories(zip.getParent());
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
        String latest = null;
        try {
            latest = fetchSha(http, URI.create(LATEST_SHA));
        } catch (IOException e) {
            SkyCosmetics.LOG.debug("Could not ask GitHub for the latest repo commit: {}", e.toString());
        }
        Path mark = checkedMark();
        if (latest != null && Files.isRegularFile(zip) && latest.equals(RepoSource.zipSha(zip))) {
            touch(mark);
            return null;
        }
        Path part = zip.resolveSibling("neu-repo.zip.part");
        String url = ARCHIVE + (latest != null ? latest : "refs/heads/master") + ".zip";
        fetchArchive(http, URI.create(url), part);
        touch(mark);
        return part;
    }

    /**
     * {@code http.send} with a deadline for the whole answer. A request's own timeout covers only the headers: a
     * body that stops arriving would otherwise block the download thread (and keep "Loading skins" up) for good.
     */
    static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> body, Duration within)
            throws IOException, InterruptedException {
        var answer = http.sendAsync(request, body);
        try {
            return answer.get(within.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            answer.cancel(true);
            throw new java.net.http.HttpTimeoutException("no complete answer within " + within.toSeconds() + " s");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        } catch (InterruptedException e) {
            answer.cancel(true);
            throw e;
        }
    }

    /** Bounded even when a response is chunked or its Content-Length is incorrect. Download thread only. */
    static String fetchSha(HttpClient http, URI uri) throws IOException, InterruptedException {
        HttpResponse<String> r = send(http, HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github.sha").header("User-Agent", "SkyCosmetics")
            .timeout(Duration.ofSeconds(20)).GET().build(),
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), MAX_COMMIT_BYTES), Duration.ofSeconds(45));
        return r.statusCode() == 200 ? RepoSource.firstSha(r.body()) : null;
    }

    /** Never replace the installed repo here, and remove partial files on HTTP, size or ZIP failures. */
    static void fetchArchive(HttpClient http, URI uri, Path part) throws IOException, InterruptedException {
        try {
            HttpResponse<Path> r = send(http, HttpRequest.newBuilder(uri).header("User-Agent", "SkyCosmetics")
                .timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofFile(part), MAX_ARCHIVE_BYTES), Duration.ofMinutes(15));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + uri);
            try (ZipFile z = new ZipFile(part.toFile())) {
                if (z.size() < MIN_ITEMS) throw new IOException("downloaded zip has only " + z.size() + " entries");
            }
        } catch (IOException | InterruptedException e) {
            try { Files.deleteIfExists(part); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static void install(Path part) {
        Path zip = RepoSource.ownZip();
        try {
            try {
                Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            SkyCosmetics.LOG.info("Downloaded the NEU repo to {}", zip);
        } catch (IOException e) {
            SkyCosmetics.LOG.warn("Could not replace {}: {}", zip, e.toString());
        }
    }

    private static void touch(Path p) throws IOException {
        if (!Files.exists(p)) Files.createFile(p);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            SkyCosmetics.LOG.error("SkyCosmetics repo task failed", e);
        }
    }
}
