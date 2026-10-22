package io.github.terabold.skycosmetics.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The three requests the updater makes, over a {@link Transport}: the release list (with its ETag, so an unchanged
 * list costs GitHub nothing), a release's SHA256SUMS, and its jar. Files come only from this repository's release
 * downloads on github.com and the GitHub hosts those redirect to, over HTTPS; a redirect anywhere else stops the
 * download.
 */
public final class Fetch {
    public static final String REPO = "Terabold/SkyCosmetics";
    public static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=10";
    /** Every release file's URL starts with this. */
    public static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    public static final String PAGE = "https://github.com/" + REPO + "/releases";
    /** Where GitHub's release downloads redirect to. */
    private static final Set<String> HOSTS = Set.of("github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com");
    private static final Duration API_TIMEOUT = Duration.ofSeconds(8), FILE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REDIRECTS = 5, MAX_LIST = 2 * 1024 * 1024, MAX_SUMS = 64 * 1024;
    /** Bigger than any SkyCosmetics jar will be; a release that says more is not downloaded. */
    public static final long MAX_JAR = 64L * 1024 * 1024;

    private Fetch() {}

    /**
     * The release list. {@code status} 200 has {@code body} and maybe {@code etag}; 304 means the cached list is
     * current. {@code retryAt} (epoch ms, or 0) is when GitHub's rate limit allows another try.
     */
    public record Listing(int status, String body, String etag, long retryAt) {}

    public static Listing releases(Transport t, String etag, String userAgent) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("User-Agent", userAgent);
        if (etag != null && !etag.isEmpty()) h.put("If-None-Match", etag);
        try (Transport.Response r = t.get(URI.create(RELEASES), h, API_TIMEOUT)) {
            long retry = retryAt(r);
            if (r.status() == 304) return new Listing(304, null, etag, 0);
            if (r.status() != 200) return new Listing(r.status(), null, null, retry);
            return new Listing(200, new String(read(r.body(), MAX_LIST), StandardCharsets.UTF_8), r.header("etag"), 0);
        }
    }

    /** When to try again after a 403 or 429 for the rate limit (from Retry-After or X-RateLimit-Reset), else 0. */
    private static long retryAt(Transport.Response r) {
        if (r.status() != 403 && r.status() != 429) return 0;
        long now = System.currentTimeMillis();
        try {
            String after = r.header("retry-after");
            if (after != null) return now + Long.parseLong(after.strip()) * 1000;
            String reset = r.header("x-ratelimit-reset");
            if (reset != null && "0".equals(r.header("x-ratelimit-remaining"))) return Long.parseLong(reset.strip()) * 1000;
        } catch (NumberFormatException ignored) {
            // no usable time: the caller waits its default
        }
        return r.status() == 429 ? now + 60_000 : 0;
    }

    /** A small release file, such as SHA256SUMS, as text. */
    public static String text(Transport t, String url, String userAgent) throws IOException {
        try (Transport.Response r = open(t, url, userAgent)) {
            return new String(read(r.body(), MAX_SUMS), StandardCharsets.UTF_8);
        }
    }

    /** Download progress, called on the download's thread: bytes so far and the expected total. */
    public interface Progress {
        void at(long bytes, long total);
    }

    /**
     * Downloads a release file to {@code to}, at most {@code size} bytes (the size GitHub lists), and returns its
     * SHA-256 in lower-case hex. {@code cancelled} is asked between reads. The file is left behind on failure for
     * the caller to delete.
     */
    public static String download(Transport t, String url, Path to, long size, String userAgent, Progress progress,
                                  BooleanSupplier cancelled) throws IOException {
        if (size <= 0 || size > MAX_JAR) throw new IOException("the release lists an unexpected size (" + size + " bytes)");
        MessageDigest sha = sha256();
        Thread reader = Thread.currentThread();
        AtomicLong lastBytes = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean(), done = new AtomicBoolean();
        try (Transport.Response r = open(t, url, userAgent);
             OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                 StandardOpenOption.WRITE)) {
            // A read can block for good (a dropped connection with no reset): Cancel, or no bytes for a while,
            // closes the body and wakes the read, so the update thread is never stuck.
            ScheduledFuture<?> watch = WATCHDOG.scheduleWithFixedDelay(() -> {
                boolean stall = System.nanoTime() - lastBytes.get() > STALL_NANOS;
                if (!cancelled.getAsBoolean() && !stall) return;
                synchronized (done) {
                    if (done.get()) return; // the download already ended: never interrupt what the thread does next
                    stalled.set(stall);
                    try { r.close(); } catch (IOException | RuntimeException ignored) { }
                    reader.interrupt();
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
            try {
                InputStream in = r.body();
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                progress.at(0, size);
                for (int n; (n = in.read(buf)) != -1; ) {
                    if (cancelled.getAsBoolean()) throw new Cancelled();
                    lastBytes.set(System.nanoTime());
                    got += n;
                    if (got > size) throw new IOException("the download is bigger than the release says");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    progress.at(got, size);
                }
                if (got != size) throw new IOException("the download stopped early (" + got + " of " + size + " bytes)");
            } catch (IOException e) {
                if (cancelled.getAsBoolean()) throw new Cancelled();
                if (stalled.get()) throw new IOException("the download stopped receiving data", e);
                throw e;
            } finally {
                synchronized (done) { done.set(true); }
                watch.cancel(false);
                Thread.interrupted(); // the update thread goes on to its next task uninterrupted
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** How long a download may receive nothing before it is given up. */
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread th = new Thread(r, "SkyCosmetics-download-watch");
        th.setDaemon(true);
        return th;
    });

    /** The download was cancelled. */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) != -1; ) sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** A release file's response, after the redirects, each checked; 200 or an exception. */
    private static Transport.Response open(Transport t, String url, String userAgent) throws IOException {
        if (url == null || !url.startsWith(DOWNLOADS)) throw new IOException("not a SkyCosmetics release file: " + url);
        URI at = URI.create(url);
        Map<String, String> h = Map.of("User-Agent", userAgent, "Accept", "application/octet-stream");
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkHost(at);
            Transport.Response r = t.get(at, h, FILE_TIMEOUT);
            int s = r.status();
            if (s == 200) return r;
            r.close();
            if (s == 301 || s == 302 || s == 303 || s == 307 || s == 308) {
                String loc = r.header("location");
                if (loc == null) throw new IOException("a redirect without a location");
                at = at.resolve(loc);
                continue;
            }
            throw new IOException("GitHub answered HTTP " + s);
        }
        throw new IOException("too many redirects");
    }

    /** Only HTTPS, only GitHub's own hosts. */
    public static void checkHost(URI uri) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOSTS.contains(host)) {
            throw new IOException("refused to download from " + uri.getScheme() + "://" + host);
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("the answer is too big");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has it
        }
    }
}
