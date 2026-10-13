package io.github.terabold.skycosmetics.test.fake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.update.Fetch;
import io.github.terabold.skycosmetics.update.Transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GitHub for the update tests: the release list (with an ETag and a rate limit to switch on), and release files
 * behind a redirect to GitHub's file host, as the real one serves them. Every request is recorded, so a test can
 * check that nothing else was asked for. Never touches the network.
 */
public final class FakeGitHub implements Transport {
    public final List<String> requests = new CopyOnWriteArrayList<>();
    /** Release files by "tag/name". */
    public final Map<String, byte[]> files = new ConcurrentHashMap<>();
    public volatile JsonArray releases = new JsonArray();
    public volatile String etag = "\"list-1\"";
    /** 200, or 403 for GitHub's rate limit. */
    public volatile int apiStatus = 200;
    /** Where github.com's download links redirect. */
    public volatile String fileHost = "release-assets.githubusercontent.com";
    /** Set: a jar download stops halfway until it is counted down (to see the progress, or cancel). */
    public volatile CountDownLatch gate;

    @Override
    public Response get(URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        String inm = headers.get("If-None-Match");
        requests.add(uri + (inm == null ? "" : " if-none-match=" + inm) + " ua=" + headers.get("User-Agent"));
        if (uri.toString().equals(Fetch.RELEASES)) {
            if (apiStatus != 200) {
                long reset = System.currentTimeMillis() / 1000 + 600;
                return new Response(apiStatus, Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)),
                    stream("{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8)));
            }
            if (etag != null && etag.equals(inm)) return new Response(304, Map.of("etag", etag), stream(new byte[0]));
            return new Response(200, etag == null ? Map.of() : Map.of("etag", etag),
                stream(releases.toString().getBytes(StandardCharsets.UTF_8)));
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String prefix = "/Terabold/SkyCosmetics/releases/download/";
        if ("github.com".equals(uri.getHost()) && path.startsWith(prefix)) {
            return new Response(302, Map.of("location", "https://" + fileHost + "/files/" + path.substring(prefix.length())),
                stream(new byte[0]));
        }
        if (fileHost.equals(uri.getHost()) && path.startsWith("/files/")) {
            byte[] b = files.get(path.substring("/files/".length()));
            if (b == null) return new Response(404, Map.of(), stream(new byte[0]));
            CountDownLatch g = gate;
            return new Response(200, Map.of("content-length", Integer.toString(b.length)),
                g == null || !path.endsWith(".jar") ? stream(b) : new Gated(b, g));
        }
        return new Response(404, Map.of(), stream(new byte[0]));
    }

    private static InputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    /** Serves the first half, then waits for the gate. */
    private static final class Gated extends InputStream {
        private final byte[] data;
        private final CountDownLatch gate;
        private int pos;

        Gated(byte[] data, CountDownLatch gate) {
            this.data = data;
            this.gate = gate;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= data.length) return -1;
            if (pos >= data.length / 2) {
                try {
                    if (!gate.await(30, TimeUnit.SECONDS)) throw new IOException("the test never opened the gate");
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            int end = pos < data.length / 2 ? Math.min(data.length / 2, pos + len) : Math.min(data.length, pos + len);
            int n = end - pos;
            System.arraycopy(data, pos, b, off, n);
            pos = end;
            return n;
        }
    }

    // ---------------------------------------------------------------- building releases

    /** A release in GitHub's JSON. Its files must be added with {@link #asset}. */
    public static JsonObject release(String tag, boolean prerelease, String body, JsonObject... assets) {
        JsonObject o = new JsonObject();
        o.addProperty("tag_name", tag);
        o.addProperty("name", "SkyCosmetics " + tag.substring(1));
        o.addProperty("draft", false);
        o.addProperty("prerelease", prerelease);
        o.addProperty("body", body);
        o.addProperty("html_url", "https://github.com/Terabold/SkyCosmetics/releases/tag/" + tag);
        o.addProperty("published_at", "2026-10-05T12:00:00Z");
        JsonArray a = new JsonArray();
        for (JsonObject asset : assets) a.add(asset);
        o.add("assets", a);
        return o;
    }

    /** A release file: served at its download link, listed with its size (and GitHub's digest when asked). */
    public JsonObject asset(String tag, String name, byte[] bytes, boolean digest) {
        files.put(tag + "/" + name, bytes);
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("size", bytes.length);
        o.addProperty("browser_download_url", Fetch.DOWNLOADS + tag + "/" + name.replace("+", "%2B"));
        if (digest) o.addProperty("digest", "sha256:" + sha256(bytes));
        return o;
    }

    /** A SHA256SUMS file for these jars, in sha256sum's format. */
    public static byte[] sums(Map<String, byte[]> jars) {
        StringBuilder b = new StringBuilder();
        jars.forEach((name, bytes) -> b.append(sha256(bytes)).append("  ").append(name).append('\n'));
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A jar with only a fabric.mod.json (and some filler, so a download takes a few reads). */
    public static byte[] jar(String id, String version, String minecraft) {
        String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"environment\":\"client\","
            + "\"depends\":{\"fabricloader\":\">=0.19.3\",\"minecraft\":\"" + minecraft + "\",\"java\":\">=25\",\"fabric-api\":\"*\"},"
            + "\"breaks\":{\"skinstudio\":\"*\"}}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(json.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("filler.bin"));
            byte[] filler = new byte[300_000];
            new java.util.Random(version.hashCode()).nextBytes(filler); // random: stays big when zipped
            z.write(filler);
            z.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
