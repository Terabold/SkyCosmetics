package io.github.terabold.skycosmetics.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub's release list, and which release (and which of its jars) this game should get.
 *
 * <h2>The release convention</h2>
 * <ul>
 *   <li>Tag {@code v<version>}, SemVer: {@code v1.5.0}, {@code v1.5.0-beta.1}. A version with a "-" part is a
 *       beta (marked pre-release on GitHub too); only "Include betas" offers it.</li>
 *   <li>Jar {@code skycosmetics-<version>+<minecraft>.jar}, e.g. {@code skycosmetics-1.5.0+26.1.2.jar}: the Minecraft
 *       version it was built for. It is offered to that version and to the rest of its line (26.1, 26.1.1...), which
 *       is what the jar's own {@code "minecraft": "~26.1"} allows; {@code +26.1.x} names the line itself. A release
 *       may carry one jar per Minecraft version.</li>
 *   <li>A jar without {@code +<minecraft>} is offered only when the notes have a line such as
 *       {@code Minecraft: 26.1.x} (or {@code Minecraft: 26.1, 26.1.1, 26.1.2}).</li>
 *   <li>Checksum: a {@code SHA256SUMS} asset in {@code sha256sum} format ({@code <hex>  <jar name>}), or a line
 *       {@code sha256: <hex>} in the notes (only for a release with one jar). GitHub's own asset digest counts too.
 *       Without one the jar is never installed automatically.</li>
 * </ul>
 */
public final class Releases {
    public static final String SUMS = "SHA256SUMS";
    private static final Pattern JAR = Pattern.compile("(?i)skycosmetics-([^+]+?)(?:\\+([^/\\\\]+))?\\.jar");
    private static final Pattern MC_LINE = Pattern.compile("(?im)^[\\s>*_`-]*minecraft(?:\\s+versions?)?\\s*:\\s*(.+)$");
    private static final Pattern SUM_LINE = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*?)\\s*$");
    private static final Pattern BODY_SUM = Pattern.compile(
        "(?im)^[\\s>*_`-]*sha-?256(?:\\s+(?:for\\s+)?`?([^\\s:`=]+)`?)?\\s*[:=]\\s*`?([0-9a-fA-F]{64})\\b");

    private Releases() {}

    /** One file of a release. {@code digest} is GitHub's "sha256:..." when it reports one, else null. */
    public record Asset(String name, String url, long size, String digest) {}

    /**
     * @param version    the tag without its "v", as written
     * @param prerelease marked pre-release on GitHub, or a version with a "-" part
     * @param url        the release's page
     */
    public record Release(String tag, String version, Semver semver, boolean prerelease, String name, String body,
                          String url, String published, List<Asset> assets) {
        public Release {
            assets = List.copyOf(assets);
            body = body == null ? "" : body;
        }

        public Asset asset(String name) {
            for (Asset a : assets) if (a.name().equals(name)) return a;
            return null;
        }
    }

    /**
     * What to offer: the newest release this game can take, the jar for it, and how many newer releases there are
     * in all (more than one: "and 2 more since yours").
     */
    public record Offer(Release release, Asset jar, int newer) {
        public String version() {
            return release.version();
        }
    }

    /** GitHub's JSON list of releases; drafts and tags that aren't versions are left out. Throws on JSON that isn't a list. */
    public static List<Release> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) throw new IllegalArgumentException("not a list of releases");
        List<Release> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray()) {
            if (!(e instanceof JsonObject o) || bool(o, "draft")) continue;
            String tag = str(o, "tag_name");
            Semver v = Semver.parse(tag);
            if (v == null) continue;
            List<Asset> assets = new ArrayList<>();
            if (o.get("assets") instanceof JsonArray arr) {
                for (JsonElement ae : arr) {
                    if (!(ae instanceof JsonObject a)) continue;
                    String name = str(a, "name"), url = str(a, "browser_download_url");
                    if (name.isEmpty() || url.isEmpty()) continue;
                    long size = a.get("size") != null && a.get("size").isJsonPrimitive() ? a.get("size").getAsLong() : -1;
                    String digest = str(a, "digest");
                    assets.add(new Asset(name, url, size, digest.isEmpty() ? null : digest));
                }
            }
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            out.add(new Release(tag, version, v, bool(o, "prerelease") || v.prerelease(), str(o, "name"), str(o, "body"),
                str(o, "html_url"), str(o, "published_at"), assets));
        }
        return out;
    }

    /** The same list as GitHub's JSON, for the cache: only what {@link #parse} reads. */
    public static JsonArray toJson(List<Release> releases) {
        JsonArray arr = new JsonArray();
        for (Release r : releases) {
            JsonObject o = new JsonObject();
            o.addProperty("tag_name", r.tag());
            o.addProperty("prerelease", r.prerelease());
            o.addProperty("name", r.name());
            o.addProperty("body", r.body());
            o.addProperty("html_url", r.url());
            o.addProperty("published_at", r.published());
            JsonArray assets = new JsonArray();
            for (Asset a : r.assets()) {
                JsonObject ao = new JsonObject();
                ao.addProperty("name", a.name());
                ao.addProperty("browser_download_url", a.url());
                ao.addProperty("size", a.size());
                if (a.digest() != null) ao.addProperty("digest", a.digest());
                assets.add(ao);
            }
            o.add("assets", assets);
            arr.add(o);
        }
        return arr;
    }

    /**
     * The newest release newer than {@code current} that has a jar for Minecraft {@code mc}; betas only when
     * {@code betas}. Null when there is none.
     */
    public static Offer pick(List<Release> releases, Semver current, String mc, boolean betas) {
        Release best = null;
        Asset bestJar = null;
        int newer = 0;
        for (Release r : releases) {
            if (r.prerelease() && !betas) continue;
            if (current != null && !r.semver().newerThan(current)) continue;
            Asset jar = jarFor(r, mc);
            if (jar == null) continue;
            newer++;
            if (best == null || r.semver().newerThan(best.semver())) {
                best = r;
                bestJar = jar;
            }
        }
        return best == null ? null : new Offer(best, bestJar, newer);
    }

    /**
     * The release's jar for Minecraft {@code mc}: one built for exactly that version first, then one for its line,
     * then a jar without a version when the notes name a matching one. Its version must be the release's.
     */
    public static Asset jarFor(Release r, String mc) {
        Asset best = null;
        int bestScore = 0;
        int plain = 0;
        Asset plainJar = null;
        for (Asset a : r.assets()) {
            Matcher m = JAR.matcher(a.name());
            if (!m.matches()) continue;
            Semver v = Semver.parse(m.group(1));
            if (v == null || v.compareTo(r.semver()) != 0) continue; // -sources, -dev, or another version's jar
            if (m.group(2) == null) {
                plain++;
                plainJar = a;
                continue;
            }
            int score = mcScore(m.group(2), mc);
            if (score > bestScore) {
                best = a;
                bestScore = score;
            }
        }
        if (best != null) return best;
        if (plain == 1 && mcScore(bodyMinecraft(r.body()), mc) > 0) return plainJar;
        return null;
    }

    /** The versions a "Minecraft: ..." line of the notes names, or "". */
    static String bodyMinecraft(String body) {
        Matcher m = MC_LINE.matcher(body == null ? "" : body);
        return m.find() ? m.group(1).replaceAll("[`*_]", " ") : "";
    }

    /**
     * How well a list of Minecraft versions ("26.1.2", "26.1.x", "26.1, 26.1.1") fits the running one: 2 when it
     * names it, 1 when it names its line or another version of it, 0 when it doesn't fit.
     */
    public static int mcScore(String spec, String running) {
        if (spec == null || running == null) return 0;
        int best = 0;
        String line = line(running);
        for (String t : spec.toLowerCase(Locale.ROOT).split("[,;\\s]+")) {
            if (t.isEmpty()) continue;
            if (t.equals(running)) return 2;
            if (line == null) continue;
            if (t.endsWith(".x") ? t.substring(0, t.length() - 2).equals(line) : line.equals(line(t))) best = 1;
        }
        return best;
    }

    /** "26.1" for 26.1, 26.1.2 and the like; null for a snapshot or anything else. */
    private static String line(String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2 || p.length > 3) return null;
        for (String s : p) if (!s.matches("\\d+")) return null;
        return p[0] + "." + p[1];
    }

    /** The checksum a SHA256SUMS file gives for {@code name}, lower case; null when it has none. */
    public static String sumFor(String sums, String name) {
        if (sums == null) return null;
        for (String line : sums.split("\\R")) {
            Matcher m = SUM_LINE.matcher(line.strip());
            if (m.matches() && m.group(2).equals(name)) return m.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * The jar's SHA-256 as the release states it: from {@code sums} (the SHA256SUMS asset's text, or null), a
     * "sha256:" line in the notes, and GitHub's digest. Null when none states one.
     *
     * @throws IllegalStateException when two of them disagree
     */
    public static String expectedSha(Release r, Asset jar, String sums) {
        List<String> found = new ArrayList<>();
        String fromSums = sumFor(sums, jar.name());
        if (fromSums != null) found.add(fromSums);
        long jars = r.assets().stream().filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith(".jar")).count();
        Matcher m = BODY_SUM.matcher(r.body());
        while (m.find()) {
            String name = m.group(1);
            if (name == null ? jars == 1 : name.equals(jar.name())) found.add(m.group(2).toLowerCase(Locale.ROOT));
        }
        if (jar.digest() != null && jar.digest().toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            String d = jar.digest().substring(7).toLowerCase(Locale.ROOT);
            if (d.matches("[0-9a-f]{64}")) found.add(d);
        }
        if (found.isEmpty()) return null;
        for (String s : found) if (!s.equals(found.getFirst())) throw new IllegalStateException("the release's checksums disagree");
        return found.getFirst();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
