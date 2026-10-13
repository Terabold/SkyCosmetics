package io.github.terabold.skycosmetics.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.SkyCosmetics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Checks GitHub Releases for a newer SkyCosmetics and, when asked (or with Auto-download), downloads it and installs
 * it on the next restart. Only the release list is requested (and a release's files when downloading): no token,
 * nothing about the player. All network and file work runs on one background thread; the screens only read
 * {@link #status()}.
 *
 * <ul>
 *   <li>Once per start, ten seconds after the game is up (Mod Menu's update check may ask sooner, on its own
 *       thread). Not in a development environment. A list checked in the last ten minutes is reused, and GitHub's
 *       ETag makes an unchanged list free.</li>
 *   <li>A newer release for this Minecraft in the chosen channel: a toast once per session, and a card at the top of
 *       General with its notes and Download &amp; Install on Restart, Release Page, Skip This Version.</li>
 *   <li>Downloads are checked against the release's SHA-256 and the jar's own fabric.mod.json before they go into
 *       the mods folder; see {@link Installer} for how the restart swaps the jars.</li>
 * </ul>
 * Settings in settings.json ({@code "updates"}); the release list, what waits for a restart and the last version run
 * in {@code config/skycosmetics/updates.json}.
 */
public final class Updater {
    /** Stable: releases only. Betas: test versions too. */
    public enum Channel { STABLE, BETAS }

    public enum Kind {
        /** Not checked this session (or checks are off). */
        IDLE,
        CHECKING,
        /** Nothing newer. */
        CURRENT,
        /** The newest is one you skipped. */
        SKIPPED,
        /** A newer version: {@link Status#offer}. */
        AVAILABLE,
        DOWNLOADING,
        /** Downloaded and checked: installs on restart. */
        READY,
        /** Download or install failed: {@link Status#message}; the release page still works. */
        FAILED,
        /** The check failed: {@link Status#message}. */
        ERROR
    }

    /**
     * What the updater is doing. {@code done}/{@code total} are download bytes; {@code checkedAt} when the release
     * list was last read (epoch ms, 0 never); {@code blocked} why the offer can't be installed from here, or null.
     */
    public record Status(Kind kind, Releases.Offer offer, String message, long checkedAt, long done, long total,
                         String blocked) {
        Status with(Kind k) {
            return new Status(k, offer, message, checkedAt, done, total, blocked);
        }
    }

    /** The version this session runs after an update (ours or by hand), with that release's notes when known. */
    public record Updated(String from, String to, Releases.Release release) {}

    /**
     * Where the game runs: the folder and file of the running jar (null when SkyCosmetics doesn't run from a jar, as
     * in development), the running versions, and what an installed mod's version is ({@code installed}: id to
     * version, null when missing).
     */
    public record Env(Path mods, Path running, String current, String mc, Function<String, String> installed) {}

    private static final long START_DELAY_S = 10, REUSE_MS = 10 * 60_000;
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SkyCosmetics-update");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    // ---------------------------------------------------------------- settings (settings.json "updates")
    private static boolean check = true;
    /** Null: Betas while running a beta, else Stable. */
    private static Channel channel;
    private static boolean autoDownload;
    private static String skipped = "";

    // ---------------------------------------------------------------- environment; tests replace them
    /** The network. Tests put a fake here; nothing else in the updater sends a request. */
    public static volatile Transport transport = new Transport.Http();
    private static volatile Env env = new Env(null, null, "0.0.0", "", id -> null);
    private static Path stateFile;

    // ---------------------------------------------------------------- state
    private static volatile Status status = new Status(Kind.IDLE, null, null, 0, 0, 0, null);
    private static volatile int changes;
    private static volatile Updated updated;
    private static volatile boolean updatedSeen;
    private static final AtomicBoolean cancel = new AtomicBoolean();
    private static boolean toasted, autoStarted;
    /** Why the update from last session didn't load; shown with the next offer. */
    private static String failedOnStart;
    private static long retryAt;
    // updates.json
    private static volatile String etag;
    private static volatile long checkedAt;
    private static volatile JsonArray cached;
    private static volatile JsonObject pending;
    private static volatile String lastRun;

    private Updater() {}

    // ================================================================ lifecycle

    /** From the client init, before Io's stop hook: reads the state, finishes an install from last time, hooks start and stop. */
    public static void init() {
        Settings.register("updates", Updater::readSettings, Updater::writeSettings);
        stateFile = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/updates.json");
        env = detect();
        try {
            loadState();
            startup();
        } catch (RuntimeException e) {
            Io.failed("Finishing the last update", e);
        }
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            if (updated != null && check) toast(() -> UpdateToast.updated(updated.to()));
            if (autoAllowed()) EXEC.schedule(() -> run(() -> autoCheck()), START_DELAY_S, TimeUnit.SECONDS);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            try {
                stop();
            } catch (RuntimeException e) {
                Io.failed("Installing the update", e);
            }
        });
    }

    /** The running jar and versions, from Fabric. */
    private static Env detect() {
        FabricLoader fl = FabricLoader.getInstance();
        ModContainer me = fl.getModContainer(Installer.MOD_ID).orElse(null);
        String current = me == null ? "0.0.0" : me.getMetadata().getVersion().getFriendlyString();
        Path jar = null;
        if (me != null && me.getOrigin().getKind() == ModOrigin.Kind.PATH && me.getOrigin().getPaths().size() == 1) {
            Path p = me.getOrigin().getPaths().getFirst().toAbsolutePath();
            if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) jar = p;
        }
        Function<String, String> installed = id -> fl.getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse(null);
        String mc = installed.apply("minecraft");
        return new Env(jar == null ? null : jar.getParent(), jar, current, mc == null ? "" : mc, installed);
    }

    /** Automatic checks: on, and not in a development environment (whose version is never a release). */
    private static boolean autoAllowed() {
        return check && (!FabricLoader.getInstance().isDevelopmentEnvironment()
            || "check".equals(System.getProperty("skycosmetics.updates")));
    }

    /**
     * What last session left: an update waiting for this start. The new version running: the old jar goes (it isn't
     * loaded, so not locked) and the card says what changed. Still the old one: the new jar didn't load, so it is
     * removed and the reason shown. Then leftovers are swept.
     */
    static void startup() {
        Env e = env;
        if (pending != null) {
            String to = str(pending, "version"), from = str(pending, "from");
            Path jar = path(pending, "jar"), old = path(pending, "old");
            Semver running = Semver.parse(e.current()), want = Semver.parse(to);
            boolean isNew = running != null && want != null && running.compareTo(want) >= 0;
            if (isNew) {
                if (old != null && !same(old, e.running())) log("Removed the old jar: " + Installer.remove(old));
                if (jar != null && !same(jar, e.running()) && Files.exists(jar)) Installer.remove(jar); // a copy beside a newer one
            } else if (jar != null && !same(jar, e.running())) {
                Installer.Removal r = Installer.remove(jar);
                log("SkyCosmetics " + to + " didn't load; removed it (" + r + ")");
                failedOnStart = "SkyCosmetics " + to + " didn't load, so it was removed. Get it from the release page.";
            }
            pending = null;
            saveState(true);
            if (isNew && !from.isEmpty()) lastRun = from;
        }
        Semver was = Semver.parse(lastRun), now = Semver.parse(e.current());
        if (was != null && now != null && now.newerThan(was)) updated = new Updated(lastRun, e.current(), release(e.current()));
        if (lastRun == null || !lastRun.equals(e.current())) {
            lastRun = e.current();
            saveState(false);
        }
        int swept = Installer.sweep(e.mods());
        if (swept > 0) log("Removed " + swept + " leftover update file(s)");
    }

    /**
     * The game is closing: a download in progress stops, and an update that is ready replaces the running jar where
     * the system allows it (Windows doesn't; then the next start finishes it).
     */
    static void stop() {
        cancel.set(true);
        if (pending == null) return;
        Path old = path(pending, "old"), jar = path(pending, "jar");
        if (old == null || jar == null || !Files.isRegularFile(jar)) return;
        Installer.Removal r = Installer.remove(old);
        log("Installing " + str(pending, "version") + " on exit: old jar " + r);
        if (r == Installer.Removal.REMOVED || r == Installer.Removal.RENAMED) saveState(true);
    }

    // ================================================================ checking

    /** The settings' Check Now: always asks GitHub, and shows a skipped version again. */
    public static void checkNow() {
        if (busy()) return;
        set(new Status(Kind.CHECKING, status.offer(), null, checkedAt, 0, 0, null));
        EXEC.execute(() -> run(() -> check(true)));
    }

    private static boolean busy() {
        Kind k = status.kind();
        return k == Kind.CHECKING || k == Kind.DOWNLOADING;
    }

    private static void autoCheck() {
        if (!check || busy() || autoStarted) return;
        autoStarted = true;
        set(new Status(Kind.CHECKING, null, null, checkedAt, 0, 0, null));
        check(false);
    }

    /** On the update thread. */
    private static void check(boolean manual) {
        long now = System.currentTimeMillis();
        List<Releases.Release> list = null;
        String error = null;
        boolean reuse = !manual && cached != null && now - checkedAt < REUSE_MS;
        if (reuse || now < retryAt) {
            list = cachedList();
            if (list == null) error = "GitHub's limit for checks is reached. Try again later.";
        } else {
            try {
                Fetch.Listing r = Fetch.releases(transport, cached == null ? null : etag, userAgent());
                if (r.status() == 200) {
                    list = Releases.parse(r.body());
                    cached = Releases.toJson(list);
                    etag = r.etag();
                    checkedAt = now;
                    saveState(false);
                } else if (r.status() == 304 && cached != null) {
                    list = cachedList();
                    checkedAt = now;
                    saveState(false);
                } else if (r.status() == 403 || r.status() == 429) {
                    retryAt = r.retryAt() > now ? r.retryAt() : now + 15 * 60_000;
                    error = "GitHub's limit for checks is reached. Try again later.";
                } else {
                    error = "GitHub answered with an error (HTTP " + r.status() + ").";
                }
            } catch (IOException e) {
                log("Update check failed: " + e);
                error = "Couldn't reach GitHub.";
            } catch (RuntimeException e) {
                Io.failed("Reading GitHub's release list", e);
                error = "GitHub's answer couldn't be read.";
            }
        }
        if (list == null) {
            set(new Status(Kind.ERROR, null, error, checkedAt, 0, 0, null));
            return;
        }
        Env e = env;
        Releases.Offer offer = Releases.pick(list, Semver.parse(e.current()), e.mc(), betas());
        if (offer == null) {
            set(new Status(Kind.CURRENT, null, null, checkedAt, 0, 0, null));
            return;
        }
        if (manual && offer.version().equals(skipped)) {
            skipped = "";
            Minecraft.getInstance().execute(Settings::save);
        }
        String blocked = blocker(offer);
        if (pending != null && offer.version().equals(str(pending, "version")) && Files.isRegularFile(path(pending, "jar"))) {
            set(new Status(Kind.READY, offer, null, checkedAt, 0, 0, null));
            return;
        }
        if (failedOnStart != null) {
            set(new Status(Kind.FAILED, offer, failedOnStart, checkedAt, 0, 0, blocked));
            failedOnStart = null;
            return;
        }
        if (offer.version().equals(skipped)) {
            set(new Status(Kind.SKIPPED, offer, null, checkedAt, 0, 0, blocked));
            return;
        }
        set(new Status(Kind.AVAILABLE, offer, null, checkedAt, 0, 0, blocked));
        if (!manual && autoDownload && blocked == null) {
            download(offer);
        } else if (!manual) {
            toast(() -> UpdateToast.available(offer.version()));
        }
    }

    private static List<Releases.Release> cachedList() {
        try {
            return cached == null ? null : Releases.parse(cached.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A cached release by version, for the notes after an update; null when not known. */
    private static Releases.Release release(String version) {
        List<Releases.Release> list = cachedList();
        if (list == null) return null;
        Semver v = Semver.parse(version);
        for (Releases.Release r : list) if (v != null && r.semver().compareTo(v) == 0) return r;
        return null;
    }

    /** Why this game can't install the offer itself (then only the release page is offered), or null. */
    static String blocker(Releases.Offer offer) {
        Env e = env;
        if (e.running() == null || e.mods() == null) return "SkyCosmetics isn't running from a jar in a mods folder here.";
        if (!Installer.safeName(offer.jar().name())) return "The release's file name isn't one SkyCosmetics installs.";
        if (!offer.jar().url().startsWith(Fetch.DOWNLOADS)) return "The release's file isn't on GitHub.";
        if (!Files.isWritable(e.mods())) return "The mods folder can't be written to.";
        return null;
    }

    // ================================================================ downloading

    /** Download &amp; Install on Restart. */
    public static void download() {
        Status s = status;
        if (s.offer() == null || busy() || s.kind() == Kind.READY) return;
        String blocked = blocker(s.offer());
        if (blocked != null) {
            set(new Status(Kind.FAILED, s.offer(), blocked, s.checkedAt(), 0, 0, blocked));
            return;
        }
        download(s.offer());
    }

    private static void download(Releases.Offer offer) {
        cancel.set(false);
        set(new Status(Kind.DOWNLOADING, offer, null, checkedAt, 0, offer.jar().size(), null));
        EXEC.execute(() -> run(() -> install(offer)));
    }

    /** Stops a download; the offer stays. */
    public static void cancel() {
        if (status.kind() == Kind.DOWNLOADING) cancel.set(true);
    }

    /** On the update thread: download, check, place; READY or FAILED. */
    private static void install(Releases.Offer offer) {
        Env e = env;
        Path part = Installer.part(e.mods(), offer.jar().name());
        try {
            Releases.Release r = offer.release();
            Releases.Asset sumsAsset = r.asset(Releases.SUMS);
            String sums = sumsAsset == null ? null : Fetch.text(transport, sumsAsset.url(), userAgent());
            String expected;
            try {
                expected = Releases.expectedSha(r, offer.jar(), sums);
            } catch (IllegalStateException ex) {
                fail(offer, "The release's checksums don't agree, so it isn't installed.");
                return;
            }
            if (expected == null) {
                fail(offer, "This release has no checksum, so it isn't installed automatically.");
                return;
            }
            String got = Fetch.download(transport, offer.jar().url(), part, offer.jar().size(), userAgent(),
                (done, total) -> progress(offer, done, total), cancel::get);
            if (!got.equals(expected)) {
                Files.deleteIfExists(part);
                log("Checksum mismatch for " + offer.jar().name() + ": expected " + expected + ", got " + got);
                fail(offer, "The download didn't match its checksum, so it was deleted.");
                return;
            }
            String why = Installer.inspect(part, offer.version(), e.current(), e.installed());
            if (why != null) {
                Files.deleteIfExists(part);
                fail(offer, why);
                return;
            }
            Path target = e.mods().resolve(offer.jar().name());
            Installer.place(part, target, e.running());
            JsonObject p = new JsonObject();
            p.addProperty("version", offer.version());
            p.addProperty("from", e.current());
            p.addProperty("jar", target.toString());
            p.addProperty("old", e.running().toString());
            p.addProperty("sha256", got);
            pending = p;
            saveState(false);
            log("Downloaded SkyCosmetics " + offer.version() + " to " + target.getFileName() + "; it installs on restart");
            set(new Status(Kind.READY, offer, null, checkedAt, offer.jar().size(), offer.jar().size(), null));
            if (autoDownload) toast(() -> UpdateToast.ready(offer.version()));
        } catch (Fetch.Cancelled ex) {
            deleteQuietly(part);
            set(new Status(Kind.AVAILABLE, offer, null, checkedAt, 0, 0, null));
        } catch (IOException ex) {
            deleteQuietly(part);
            log("Update download failed: " + ex);
            fail(offer, "The download failed: " + ex.getMessage());
        }
    }

    /** Every read: a jar is a few dozen reads, and the card lays out at most once a frame. */
    private static void progress(Releases.Offer offer, long done, long total) {
        if (!cancel.get()) set(new Status(Kind.DOWNLOADING, offer, null, checkedAt, done, total, null));
    }

    private static void fail(Releases.Offer offer, String why) {
        set(new Status(Kind.FAILED, offer, why, checkedAt, 0, 0, status.blocked()));
        if (autoDownload) toast(() -> UpdateToast.failed(offer.version()));
    }

    /** Takes back a download that waits for a restart: the new jar is deleted and the offer stands again. */
    public static void undo() {
        Status s = status;
        if (s.kind() != Kind.READY || busy()) return;
        EXEC.execute(() -> run(() -> {
            if (pending != null) {
                Path jar = path(pending, "jar");
                if (jar != null && !same(jar, env.running())) log("Took back the update: " + Installer.remove(jar));
                pending = null;
                saveState(false);
            }
            set(new Status(Kind.AVAILABLE, s.offer(), null, checkedAt, 0, 0, blocker(s.offer())));
        }));
    }

    /** Skip This Version: no card or toast for it; a newer one shows again, and so does Check Now. */
    public static void skip() {
        Status s = status;
        if (s.offer() == null || busy() || s.kind() == Kind.READY) return;
        skipped = s.offer().version();
        Settings.save();
        set(s.with(Kind.SKIPPED));
    }

    /** The "Updated to" card's Dismiss. */
    public static void dismissUpdated() {
        updatedSeen = true;
        changes++;
    }

    // ================================================================ reading

    public static Status status() {
        return status;
    }

    /** Goes up on every change of {@link #status} or the updated notice: a widget that shows them rebuilds then. */
    public static int changes() {
        return changes;
    }

    /** This session's version, when it is newer than last session's and the card wasn't dismissed. */
    public static Updated updated() {
        return updatedSeen ? null : updated;
    }

    public static String current() {
        return env.current();
    }

    public static boolean checkEnabled() {
        return check;
    }

    public static void setCheck(boolean on) {
        check = on;
        if (on && status.kind() == Kind.IDLE && autoAllowed()) EXEC.execute(() -> run(Updater::autoCheck));
        changes++;
    }

    /** Test builds do not opt ordinary users into beta updates. */
    public static Channel channel() {
        if (channel != null) return channel;
        return Channel.STABLE;
    }

    public static void setChannel(Channel c) {
        channel = c;
    }

    private static boolean betas() {
        return channel() == Channel.BETAS;
    }

    public static boolean autoDownload() {
        return autoDownload;
    }

    public static void setAutoDownload(boolean on) {
        autoDownload = on;
    }

    /**
     * For Mod Menu's update badge, on its own thread: the startup check (started now if it hasn't), waited for up to
     * {@code waitMs}. Null when checks are off or nothing newer is known.
     */
    public static Releases.Offer awaitOffer(long waitMs) {
        if (!autoAllowed()) return null;
        if (status.kind() == Kind.IDLE) EXEC.execute(() -> run(Updater::autoCheck));
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until && (status.kind() == Kind.IDLE || status.kind() == Kind.CHECKING)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Status s = status;
        return s.kind() == Kind.AVAILABLE || s.kind() == Kind.DOWNLOADING || s.kind() == Kind.READY
            || s.kind() == Kind.FAILED ? s.offer() : null;
    }

    // ================================================================ settings and state files

    private static void readSettings(JsonElement v) {
        JsonObject o = Settings.obj(v);
        check = Settings.bool(o.get("check"), check);
        autoDownload = Settings.bool(o.get("autoDownload"), autoDownload);
        String c = Settings.str(o.get("channel"), channel == null ? "auto" : channel.name().toLowerCase(Locale.ROOT));
        channel = switch (c) {
            case "stable" -> Channel.STABLE;
            case "betas" -> Channel.BETAS;
            default -> null;
        };
        skipped = Settings.str(o.get("skip"), skipped);
    }

    private static JsonElement writeSettings() {
        JsonObject o = new JsonObject();
        o.addProperty("check", check);
        o.addProperty("channel", channel == null ? "auto" : channel.name().toLowerCase(Locale.ROOT));
        o.addProperty("autoDownload", autoDownload);
        o.addProperty("skip", skipped);
        return o;
    }

    private static void loadState() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return;
        JsonObject o;
        try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            o = Io.parse(r).getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            Io.setAside(stateFile, "Could not read updates.json", e);
            return;
        }
        etag = str(o, "etag");
        checkedAt = o.get("checked") instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0;
        cached = o.get("releases") instanceof JsonArray a ? a : null;
        pending = o.get("pending") instanceof JsonObject p ? p : null;
        lastRun = o.get("lastRun") instanceof JsonPrimitive p ? p.getAsString() : null;
    }

    /** {@code now}: written before returning (the stop hook runs after Io's writer may be gone). */
    private static void saveState(boolean now) {
        if (stateFile == null) return;
        JsonObject o = new JsonObject();
        if (etag != null && !etag.isEmpty()) o.addProperty("etag", etag);
        o.addProperty("checked", checkedAt);
        if (cached != null) o.add("releases", cached.deepCopy());
        if (pending != null) o.add("pending", pending.deepCopy());
        if (lastRun != null) o.addProperty("lastRun", lastRun);
        if (now) Io.write(stateFile, o);
        else Io.writeAsync(stateFile, o);
    }

    // ================================================================ helpers

    private static void set(Status s) {
        status = s;
        changes++;
    }

    /** A toast once per session, built and shown on the render thread. */
    private static void toast(Supplier<UpdateToast> t) {
        if (toasted) return;
        toasted = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                mc.getToastManager().addToast(t.get());
            } catch (RuntimeException e) {
                Io.failed("Showing the update toast", e);
            }
        });
    }

    /** Update-thread work never dies silently: a failure leaves an error status. */
    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Io.failed("Checking for updates", e);
            Status s = status;
            if (s.kind() == Kind.CHECKING) set(new Status(Kind.ERROR, null, "Something went wrong.", checkedAt, 0, 0, null));
            else if (s.kind() == Kind.DOWNLOADING) set(new Status(Kind.FAILED, s.offer(), "Something went wrong.", checkedAt, 0, 0, null));
        }
    }

    private static String userAgent() {
        return "SkyCosmetics/" + env.current();
    }

    private static void log(String s) {
        SkyCosmetics.LOG.info("[updates] {}", s);
    }

    private static boolean same(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            return Files.exists(a) && Files.exists(b) ? Files.isSameFile(a, b) : a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // swept on the next start
        }
    }

    private static String str(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p ? p.getAsString() : "";
    }

    private static Path path(JsonObject o, String key) {
        String s = str(o, key);
        try {
            return s.isEmpty() ? null : Path.of(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================ tests

    /**
     * Puts the updater in a test's world: its fake network, mods folder and versions, no saved state, and the
     * settings' defaults (checks on, the channel by version, no auto-download, nothing skipped).
     */
    public static void testSetup(Transport t, Env e, Path state) {
        transport = t;
        env = e;
        stateFile = state;
        check = true;
        channel = null;
        autoDownload = false;
        skipped = "";
        autoStarted = false;
        cancel.set(false);
        status = new Status(Kind.IDLE, null, null, 0, 0, 0, null);
        etag = null;
        checkedAt = 0;
        cached = null;
        pending = null;
        lastRun = null;
        updated = null;
        updatedSeen = false;
        failedOnStart = null;
        retryAt = 0;
        toasted = false;
        changes++;
    }

    /** A new session in the test's world: reads the state file and finishes what the last one left. */
    public static void testRestart(Env e) {
        env = e;
        pending = null;
        lastRun = null;
        updated = null;
        updatedSeen = false;
        loadState();
        startup();
        status = new Status(Kind.IDLE, null, null, 0, 0, 0, null);
        changes++;
    }

    /** The game closing, in the test's world. */
    public static void testStop() {
        stop();
    }

    /** The automatic check, now. */
    public static void testAutoCheck() {
        autoStarted = false;
        set(new Status(Kind.CHECKING, null, null, checkedAt, 0, 0, null));
        EXEC.execute(() -> run(() -> check(false)));
    }

    public static Env env() {
        return env;
    }

    public static Path stateFile() {
        return stateFile;
    }

    public static String skippedVersion() {
        return skipped;
    }

    /** Whether this session showed its update toast. */
    public static boolean toasted() {
        return toasted;
    }
}
