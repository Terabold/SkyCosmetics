package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.GeneralSection;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.test.fake.FakeGitHub;
import io.github.terabold.skycosmetics.update.Fetch;
import io.github.terabold.skycosmetics.update.Installer;
import io.github.terabold.skycosmetics.update.Notes;
import io.github.terabold.skycosmetics.update.Releases;
import io.github.terabold.skycosmetics.update.Semver;
import io.github.terabold.skycosmetics.update.Transport;
import io.github.terabold.skycosmetics.update.UpdateCard;
import io.github.terabold.skycosmetics.update.UpdateToast;
import io.github.terabold.skycosmetics.update.Updater;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/**
 * The update checker, against a fake GitHub ({@link FakeGitHub}) and a fake mods folder in the game folder: never the
 * real API. Versions order like SemVer; jars match this Minecraft by name or by the notes; checksums from SHA256SUMS,
 * the notes or GitHub's digest, and a wrong or missing one stops the install; downloads only from GitHub's hosts.
 * Then the whole way through the settings: the card (available, downloading, ready, failed, updated), the quiet line
 * (up to date, skipped, check failed), Check Now with its ETag, the toast once a session, auto-download, and the
 * restart that swaps the jars, with the old one locked as Windows locks it.
 */
public class UpdateTest implements FabricClientGameTest {
    private static final String MC = "26.1.2";
    private static final Map<String, String> INSTALLED = Map.of("minecraft", MC, "fabricloader", "0.19.5",
        "fabric-api", "0.155.2+26.1.2", "java", "25");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // In development no automatic check starts, so nothing reached GitHub before the fake is in place.
        check(ctx.computeOnClient(mc -> Updater.status().kind()) == Updater.Kind.IDLE, "no automatic check in development");
        versions();
        matching();
        checksums();
        notes();
        Path root = ctx.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("sc-update-test"));
        Updater.Env realEnv = Updater.env();
        Path realState = Updater.stateFile();
        Transport realTransport = Updater.transport;
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        try {
            files(root);
            downloads(root);
            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) { // the settings build item icons: a world binds them
                sp.getClientLevel().waitForChunksRender();
                flow(ctx, root);
            }
        } catch (IOException e) {
            throw new AssertionError("[SkyCosmeticsTest] update test files: " + e, e);
        } finally {
            ctx.setScreen(() -> null);
            Updater.testSetup(realTransport, realEnv, realState);
            ctx.runOnClient(mc -> Settings.save());
            ctx.getInput().resizeWindow(size[0], size[1]);
            window(ctx, size[0], size[1], 0);
        }
        System.out.println("[SkyCosmeticsTest] update checks passed");
    }

    // ================================================================ pure checks

    private static void versions() {
        List<String> order = List.of("1.4.0-alpha", "1.4.0-alpha.1", "1.4.0-beta", "1.4.0-beta.2", "1.4.0-beta.11",
            "1.4.0-rc.1", "1.4.0", "1.4.1", "1.5.0-beta.1", "v1.5.0", "1.5.0+26.1.2", "1.10.0", "2.0.0");
        for (int i = 0; i + 1 < order.size(); i++) {
            Semver a = Semver.parse(order.get(i)), b = Semver.parse(order.get(i + 1));
            check(a != null && b != null, "parses " + order.get(i) + " and " + order.get(i + 1));
            boolean same = order.get(i + 1).equals("1.5.0+26.1.2");
            check(same ? a.compareTo(b) == 0 : b.newerThan(a), order.get(i + 1) + " is newer than " + order.get(i));
        }
        check(Semver.parse("1.5").compareTo(Semver.parse("1.5.0")) == 0, "1.5 is 1.5.0");
        check(Semver.parse("1.5.0-beta.2").prerelease() && !Semver.parse("1.5.0").prerelease(), "betas are pre-releases");
        for (String bad : new String[]{"", "1", "1.x.0", "1.2.3.4", "abc", "1.2.3-", "1.2.3-be ta", null}) {
            check(Semver.parse(bad) == null, "not a version: " + bad);
        }
        System.out.println("[SkyCosmeticsTest] update: versions order as SemVer");
    }

    private static Releases.Release rel(String tag, boolean pre, String body, String... assetNames) {
        List<Releases.Asset> assets = new ArrayList<>();
        for (String n : assetNames) assets.add(new Releases.Asset(n, Fetch.DOWNLOADS + tag + "/" + n, 1000, null));
        return new Releases.Release(tag, tag.substring(1), Semver.parse(tag), pre || Semver.parse(tag).prerelease(), tag,
            body, "https://github.com/Terabold/SkyCosmetics/releases/tag/" + tag, "2026-10-05T12:00:00Z", assets);
    }

    private static void matching() {
        check(Releases.mcScore("26.1.2", MC) == 2 && Releases.mcScore("26.1.x", MC) == 1 && Releases.mcScore("26.1", MC) == 1
            && Releases.mcScore("26.1.1", MC) == 1 && Releases.mcScore("26.2", MC) == 0 && Releases.mcScore("26.2.x", MC) == 0
            && Releases.mcScore("26.1, 26.1.1, 26.1.2", MC) == 2 && Releases.mcScore("", MC) == 0
            && Releases.mcScore("26w14a", "26w14a") == 2 && Releases.mcScore("26.1.x", "26w14a") == 0,
            "Minecraft versions match exactly, by line, or not");

        Releases.Release both = rel("v1.5.0", false, "", "skycosmetics-1.5.0+26.2.jar", "skycosmetics-1.5.0+26.1.x.jar",
            "skycosmetics-1.5.0+26.1.2.jar", "skycosmetics-1.5.0-sources.jar", "SHA256SUMS");
        check(Releases.jarFor(both, MC).name().equals("skycosmetics-1.5.0+26.1.2.jar"), "the exact Minecraft jar first");
        check(Releases.jarFor(both, "26.1.1").name().equals("skycosmetics-1.5.0+26.1.x.jar"), "then the line's");
        check(Releases.jarFor(both, "26.3") == null, "no jar for another Minecraft");
        Releases.Release plain = rel("v1.5.1", false, "Fixes\n\nMinecraft: 26.1.x\n", "skycosmetics-1.5.1.jar");
        check(Releases.jarFor(plain, MC) != null, "a plain jar with \"Minecraft: 26.1.x\" in the notes");
        check(Releases.jarFor(rel("v1.5.1", false, "Fixes", "skycosmetics-1.5.1.jar"), MC) == null,
            "a plain jar without a Minecraft line is never offered");
        check(Releases.jarFor(rel("v1.5.1", false, "", "skycosmetics-1.5.0+26.1.2.jar"), MC) == null,
            "a jar of another version is not this release's");

        List<Releases.Release> list = List.of(rel("v1.6.0-beta.1", true, "", "skycosmetics-1.6.0-beta.1+26.1.2.jar"),
            rel("v1.5.2", false, "", "skycosmetics-1.5.2+26.2.jar"), both, rel("v1.4.0", false, "", "skycosmetics-1.4.0+26.1.2.jar"));
        Releases.Offer stable = Releases.pick(list, Semver.parse("1.4.0"), MC, false);
        check(stable != null && stable.version().equals("1.5.0") && stable.newer() == 1, "Stable: 1.5.0, the newest for 26.1.2");
        Releases.Offer betas = Releases.pick(list, Semver.parse("1.4.0"), MC, true);
        check(betas != null && betas.version().equals("1.6.0-beta.1") && betas.newer() == 2, "Betas: the beta, with one more");
        check(Releases.pick(list, Semver.parse("1.6.0-beta.1"), MC, true) == null, "nothing newer than the newest");
        check(Releases.pick(list, Semver.parse("1.5.0-beta.3"), MC, false).version().equals("1.5.0"),
            "a beta user on Stable gets the release");
        // GitHub's JSON: drafts and tags that aren't versions are left out; a beta tag is a pre-release.
        List<Releases.Release> parsed = Releases.parse("""
            [{"tag_name":"nightly","assets":[]},{"tag_name":"v2.0.0","draft":true,"assets":[]},
             {"tag_name":"v1.5.0-beta.1","prerelease":false,"body":null,"html_url":"u","assets":[
               {"name":"skycosmetics-1.5.0-beta.1+26.1.2.jar","size":12,"browser_download_url":"d","digest":"sha256:ab"},
               {"name":"no-url.jar","size":3}]}]""");
        check(parsed.size() == 1 && parsed.getFirst().prerelease() && parsed.getFirst().version().equals("1.5.0-beta.1")
            && parsed.getFirst().body().isEmpty() && parsed.getFirst().assets().size() == 1
            && parsed.getFirst().assets().getFirst().size() == 12, "the release list is read: " + parsed);
        check(Releases.parse(Releases.toJson(parsed).toString()).equals(parsed), "the cache reads back the same");
        System.out.println("[SkyCosmeticsTest] update: jars match this Minecraft");
    }

    private static void checksums() {
        String a = "a".repeat(64), b = "b".repeat(64);
        Releases.Release r = rel("v1.5.0", false, "", "skycosmetics-1.5.0+26.1.2.jar", "skycosmetics-1.5.0+26.2.jar");
        Releases.Asset jar = r.assets().getFirst();
        String sums = b + "  skycosmetics-1.5.0+26.2.jar\n" + a.toUpperCase(Locale.ROOT) + " *skycosmetics-1.5.0+26.1.2.jar\n";
        check(a.equals(Releases.expectedSha(r, jar, sums)), "SHA256SUMS: the line for this jar, either case, binary mark");
        check(Releases.expectedSha(r, jar, null) == null, "no checksum anywhere");
        Releases.Release body = rel("v1.5.0", false, "Notes\n\nsha256: " + a + "\n", "skycosmetics-1.5.0+26.1.2.jar");
        check(a.equals(Releases.expectedSha(body, body.assets().getFirst(), null)), "a sha256: line for the only jar");
        check(Releases.expectedSha(r, jar, null) == null && Releases.expectedSha(
                rel("v1.5.0", false, "sha256: " + a, "skycosmetics-1.5.0+26.1.2.jar", "skycosmetics-1.5.0+26.2.jar"), jar, null) == null,
            "an unnamed sha256: line is ignored when there are two jars");
        Releases.Release named = rel("v1.5.0", false, "SHA-256 skycosmetics-1.5.0+26.1.2.jar: " + a,
            "skycosmetics-1.5.0+26.1.2.jar", "skycosmetics-1.5.0+26.2.jar");
        check(a.equals(Releases.expectedSha(named, named.assets().getFirst(), null)), "a named SHA-256 line");
        Releases.Asset digest = new Releases.Asset(jar.name(), jar.url(), 1000, "sha256:" + a);
        check(a.equals(Releases.expectedSha(r, digest, null)), "GitHub's digest");
        boolean threw = false;
        try {
            Releases.expectedSha(r, digest, b + "  " + jar.name());
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "two checksums that disagree stop the install");
        System.out.println("[SkyCosmeticsTest] update: checksums read");
    }

    private static void notes() {
        List<Notes.Line> lines = Notes.lines("""
            ## 1.5.0
            <!-- release checklist -->
            ### New
            - **Update checker** in [General](https://example.com): `Check Now`.
              - nested item
            * Second _item_

            sha256: %s
            Minecraft: 26.1.x
            **Full Changelog**: https://github.com/x/y/compare/a...b
            """.formatted("c".repeat(64)));
        List<String> text = lines.stream().map(l -> l.text().getString()).toList();
        check(text.equals(List.of("1.5.0", "", "New", "- Update checker in General: Check Now.", "  - nested item",
            "- Second item")), "notes: headings, bullets, links and code as text, checksums left out: " + text);
        StringBuilder bold = new StringBuilder();
        lines.get(3).body().visit((style, part) -> {
            if (style.isBold()) bold.append(part);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        check(bold.toString().equals("Update checker"), "bold stays bold, and only it: " + bold);
        System.out.println("[SkyCosmeticsTest] update: release notes formatted");
    }

    // ================================================================ files

    private static void files(Path root) throws IOException {
        Path mods = fresh(root.resolve("files"));
        Path ours = write(mods.resolve("skycosmetics-1.4.0.jar"), FakeGitHub.jar("skycosmetics", "1.4.0", "~26.1"));
        Path other = write(mods.resolve("othermod-1.0.jar"), FakeGitHub.jar("othermod", "1.0", "~26.1"));
        Path notJar = write(mods.resolve("skycosmetics-notes.txt"), "hello".getBytes(StandardCharsets.UTF_8));

        check(Installer.isOurs(ours) && !Installer.isOurs(other) && !Installer.isOurs(notJar), "a SkyCosmetics jar by its fabric.mod.json");
        check(Installer.remove(other) == Installer.Removal.NOT_OURS && Files.exists(other), "another mod's jar is never removed");
        check(Installer.remove(notJar) == Installer.Removal.NOT_OURS && Files.exists(notJar), "nor a file that isn't a jar");
        check(Installer.remove(mods.resolve("missing.jar")) == Installer.Removal.GONE, "a missing jar is gone");

        // Locked as a running jar is: Windows refuses both delete and rename; elsewhere it goes.
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        try (URLClassLoader running = lock(ours)) {
            Installer.Removal r = Installer.remove(ours);
            check(windows ? r == Installer.Removal.IN_USE && Files.exists(ours) : r == Installer.Removal.REMOVED,
                "the running jar: " + r + (windows ? " (locked on Windows)" : ""));
        }
        if (Files.exists(ours)) check(Installer.remove(ours) == Installer.Removal.REMOVED, "once unlocked it is removed");

        // Leftovers: old parts and set-aside jars of SkyCosmetics only.
        Path part = write(mods.resolve("skycosmetics-1.5.0+26.1.2.jar.part"), new byte[10]);
        Files.setLastModifiedTime(part, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        Path freshPart = write(mods.resolve("skycosmetics-1.6.0+26.1.2.jar.part"), new byte[10]);
        Path old = write(mods.resolve("skycosmetics-1.3.0.jar.old"), FakeGitHub.jar("skycosmetics", "1.3.0", "~26.1"));
        Path otherOld = write(mods.resolve("skycosmetics-fake.jar.old"), FakeGitHub.jar("othermod", "1.0", "~26.1"));
        Path otherPart = write(mods.resolve("othermod-2.0.jar.part"), new byte[10]);
        Files.setLastModifiedTime(otherPart, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        check(Installer.sweep(mods) == 2 && !Files.exists(part) && !Files.exists(old), "old parts and .old jars are swept");
        check(Files.exists(freshPart) && Files.exists(otherOld) && Files.exists(otherPart) && Files.exists(other),
            "a download in progress, and other mods' files, stay");

        // What may go into the mods folder.
        Function<String, String> have = INSTALLED::get;
        Path good = write(mods.resolve("good.jar.part"), FakeGitHub.jar("skycosmetics", "1.5.0", "~26.1"));
        check(Installer.inspect(good, "1.5.0", "1.4.0", have) == null, "the right jar passes");
        check(Installer.inspect(good, "1.5.1", "1.4.0", have).contains("not 1.5.1"), "the wrong version is caught");
        check(Installer.inspect(good, "1.5.0", "1.5.0", have).contains("isn't newer"), "a jar that isn't newer is caught");
        check(Installer.inspect(good, "1.5.0", "1.4.0", id -> id.equals("fabric-api") ? null : INSTALLED.get(id))
            .contains("Fabric API"), "a missing dependency is caught");
        check(Installer.inspect(good, "1.5.0", "1.4.0", id -> id.equals("skinstudio") ? "1.0.0" : INSTALLED.get(id))
            .contains("doesn't work with"), "a mod it breaks is caught");
        Path newMc = write(mods.resolve("newmc.jar.part"), FakeGitHub.jar("skycosmetics", "1.5.0", "~26.2"));
        check(Installer.inspect(newMc, "1.5.0", "1.4.0", have).contains("Minecraft ~26.2"), "a jar for another Minecraft is caught");
        Path otherJar = write(mods.resolve("other.jar.part"), FakeGitHub.jar("othermod", "1.5.0", "~26.1"));
        check(Installer.inspect(otherJar, "1.5.0", "1.4.0", have).contains("isn't SkyCosmetics"), "another mod is caught");
        Path junk = write(mods.resolve("junk.jar.part"), "not a zip".getBytes(StandardCharsets.UTF_8));
        check(Installer.inspect(junk, "1.5.0", "1.4.0", have) != null, "a broken download is caught");
        check(Installer.safeName("skycosmetics-1.5.0+26.1.2.jar") && !Installer.safeName("skycosmetics-1.5.0.JAR")
            && !Installer.safeName("../skycosmetics-1.5.0.jar") && !Installer.safeName("othermod-1.0.jar")
            && !Installer.safeName("skycosmetics-1.5.0.jar.exe"), "only SkyCosmetics' jar names are installed");
        System.out.println("[SkyCosmeticsTest] update: files handled, other mods' left alone");
    }

    private static void downloads(Path root) throws IOException {
        Path dir = fresh(root.resolve("downloads"));
        FakeGitHub gh = new FakeGitHub();
        byte[] jar = FakeGitHub.jar("skycosmetics", "1.5.0", "~26.1");
        JsonObject asset = gh.asset("v1.5.0", "skycosmetics-1.5.0+26.1.2.jar", jar, false);
        String url = asset.get("browser_download_url").getAsString();
        Path to = dir.resolve("a.part");
        long[] last = new long[2];
        String sha = Fetch.download(gh, url, to, jar.length, "test", (d, t) -> {
            last[0] = d;
            last[1] = t;
        }, () -> false);
        check(sha.equals(FakeGitHub.sha256(jar)) && Files.size(to) == jar.length && last[0] == jar.length,
            "a download follows GitHub's redirect and hashes what it wrote");
        check(gh.requests.size() == 2 && gh.requests.get(1).startsWith("https://release-assets.githubusercontent.com/"),
            "through GitHub's file host: " + gh.requests);

        gh.fileHost = "evil.example.com";
        check(fails(() -> Fetch.download(gh, url, dir.resolve("b.part"), jar.length, "test", (d, t) -> {}, () -> false),
            "refused"), "a redirect off GitHub is refused");
        gh.fileHost = "release-assets.githubusercontent.com";
        check(fails(() -> Fetch.download(gh, "https://example.com/skycosmetics-1.5.0.jar", dir.resolve("c.part"), jar.length,
            "test", (d, t) -> {}, () -> false), "not a SkyCosmetics release file"), "only this repository's release files");
        check(fails(() -> Fetch.download(gh, url, dir.resolve("d.part"), jar.length - 10, "test", (d, t) -> {}, () -> false),
            "bigger"), "a file bigger than the release says stops");
        check(fails(() -> Fetch.download(gh, url, dir.resolve("e.part"), jar.length + 10, "test", (d, t) -> {}, () -> false),
            "early"), "a file smaller than the release says stops");
        check(fails(() -> Fetch.download(gh, url, dir.resolve("f.part"), Fetch.MAX_JAR + 1, "test", (d, t) -> {}, () -> false),
            "size"), "an absurd size is refused before downloading");
        for (String bad : new String[]{"http://github.com/x", "https://github.com.evil.com/x",
            "https://objects.githubusercontent.com.evil/x", "file:///C:/x", "https://evil.com/x"}) {
            check(fails(() -> {
                Fetch.checkHost(URI.create(bad));
                return null;
            }, "refused"), "refused host: " + bad);
        }
        for (String ok : new String[]{"https://github.com/x", "https://objects.githubusercontent.com/x",
            "https://release-assets.githubusercontent.com/x"}) {
            check(!fails(() -> {
                Fetch.checkHost(URI.create(ok));
                return null;
            }, ""), "allowed host: " + ok);
        }
        System.out.println("[SkyCosmeticsTest] update: downloads only from GitHub, sizes checked");
    }

    private interface Io<T> {
        T run() throws IOException;
    }

    private static boolean fails(Io<?> io, String messagePart) {
        try {
            io.run();
            return false;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().contains(messagePart);
        }
    }

    // ================================================================ the whole way, through the settings

    private static void flow(ClientGameTestContext ctx, Path root) throws IOException {
        Path mods = fresh(root.resolve("mods"));
        Path oldJar = write(mods.resolve("skycosmetics-1.4.0.jar"), FakeGitHub.jar("skycosmetics", "1.4.0", "~26.1"));
        Path state = root.resolve("updates.json");
        Files.deleteIfExists(state);
        FakeGitHub gh = new FakeGitHub();
        byte[] jar150 = FakeGitHub.jar("skycosmetics", "1.5.0", "~26.1");
        byte[] jar150other = FakeGitHub.jar("skycosmetics", "1.5.0", "~26.2");
        byte[] jarBeta = FakeGitHub.jar("skycosmetics", "1.6.0-beta.1", "~26.1");
        Map<String, byte[]> sums150 = new LinkedHashMap<>();
        sums150.put("skycosmetics-1.5.0+26.1.2.jar", jar150);
        sums150.put("skycosmetics-1.5.0+26.2.jar", jar150other);
        String notes150 = """
            ### New
            - **Update checker:** SkyCosmetics tells you when a new version is out, with these notes, and can install it on restart.
            - Release Channel: Stable or Betas.
            ### Fixed
            - Tooltips beside lists at every GUI scale.
            """;
        JsonArray list = new JsonArray();
        list.add(FakeGitHub.release("v1.6.0-beta.1", true, "- Beta things", gh.asset("v1.6.0-beta.1",
            "skycosmetics-1.6.0-beta.1+26.1.2.jar", jarBeta, true)));
        list.add(FakeGitHub.release("v1.5.0", false, notes150,
            gh.asset("v1.5.0", "skycosmetics-1.5.0+26.1.2.jar", jar150, false),
            gh.asset("v1.5.0", "skycosmetics-1.5.0+26.2.jar", jar150other, false),
            gh.asset("v1.5.0", "SHA256SUMS", FakeGitHub.sums(sums150), false)));
        list.add(FakeGitHub.release("v1.4.0", false, "- Old", gh.asset("v1.4.0", "skycosmetics-1.4.0+26.1.2.jar",
            FakeGitHub.jar("skycosmetics", "1.4.0", "~26.1"), true)));
        gh.releases = list;
        Updater.Env env140 = new Updater.Env(mods, oldJar, "1.4.0", MC, INSTALLED::get);
        Updater.testSetup(gh, env140, state);

        // Check Now finds 1.5.0 (Stable): the card, with its notes and three buttons.
        window(ctx, 1920, 1080, 2);
        openGeneral(ctx);
        check(ctx.computeOnClient(mc -> card(mc).getHeight()) == 0, "no card before a check: it takes no room");
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).rowIds()).containsAll(
            List.of("updateCard", "updateCheck", "updateChannel", "updateAuto", "updateNow")), "General has the update rows");
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> Updater.status().offer().version()).equals("1.5.0"), "Stable offers 1.5.0, not the beta");
        check(ctx.computeOnClient(mc -> card(mc).isCard() && card(mc).text().startsWith("SkyCosmetics 1.5.0 is available")),
            "the card says so: " + ctx.computeOnClient(mc -> card(mc).text()));
        check(ctx.computeOnClient(mc -> card(mc).buttonLabels()).equals(List.of("Download & install on restart", "Release page",
            "Skip this version")), "with its three buttons");
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).toastText()).equals("SkyCosmetics 1.5.0 is available."),
            "Check Now says what it found");
        check(!Updater.toasted(), "no toast for a check you asked for");
        check(gh.requests.size() == 1 && gh.requests.getFirst().startsWith(Fetch.RELEASES)
            && gh.requests.getFirst().contains("ua=SkyCosmetics/1.4.0") && !gh.requests.getFirst().contains("if-none-match"),
            "one request, the release list, with the User-Agent: " + gh.requests);
        cardInPane(ctx);
        ctx.takeScreenshot("skycosmetics-90-update-available");
        window(ctx, 1920, 1080, 4);
        openGeneral(ctx);
        cardInPane(ctx);
        ctx.takeScreenshot("skycosmetics-90-update-available-guiscale-4");
        window(ctx, 854, 480, 0);
        openGeneral(ctx);
        cardInPane(ctx);
        ctx.takeScreenshot("skycosmetics-90-update-available-small-window");
        window(ctx, 1920, 1080, 2);
        openGeneral(ctx);

        // The ETag: a second check sends it, and GitHub's 304 keeps the list.
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        check(gh.requests.size() == 2 && gh.requests.get(1).contains("if-none-match=\"list-1\""), "the second check sends the ETag");

        // Betas: the beta, and "2 new releases".
        ctx.runOnClient(mc -> Updater.setChannel(Updater.Channel.BETAS));
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        check(ctx.computeOnClient(mc -> Updater.status().offer().version()).equals("1.6.0-beta.1"), "Betas offer the beta");
        ctx.runOnClient(mc -> Updater.setChannel(Updater.Channel.STABLE));
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);

        // Skip This Version: a quiet line; Check Now brings it back.
        clickCard(ctx, "Skip this version");
        check(ctx.computeOnClient(mc -> Updater.status().kind() == Updater.Kind.SKIPPED && "1.5.0".equals(Updater.skippedVersion())),
            "1.5.0 is skipped");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> !card(mc).isCard() && card(mc).getHeight() > 0 && card(mc).text().contains("1.5.0 is skipped")),
            "a quiet line says so");
        ctx.waitFor(mc -> read(mc).contains("\"skip\": \"1.5.0\""), 20 * 5);
        ctx.takeScreenshot("skycosmetics-91-update-skipped");
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        check(Updater.skippedVersion().isEmpty(), "Check Now shows a skipped version again");

        // Download: halfway (the fake holds it there), then Cancel; then the whole way.
        CountDownLatch gate = new CountDownLatch(1);
        gh.gate = gate;
        clickCard(ctx, "Download & install on restart");
        ctx.waitFor(mc -> Updater.status().kind() == Updater.Kind.DOWNLOADING && Updater.status().done() > 0, 20 * 10);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> card(mc).buttonLabels()).equals(List.of("Cancel")), "downloading: only Cancel");
        ctx.takeScreenshot("skycosmetics-92-update-downloading");
        clickCard(ctx, "Cancel");
        gate.countDown();
        waitFor(ctx, Updater.Kind.AVAILABLE);
        check(!Files.exists(Installer.part(mods, "skycosmetics-1.5.0+26.1.2.jar")), "a cancelled download leaves nothing");
        gh.gate = null;

        clickCard(ctx, "Download & install on restart");
        waitFor(ctx, Updater.Kind.READY);
        Path newJar = mods.resolve("skycosmetics-1.5.0+26.1.2.jar");
        check(Files.exists(newJar) && FakeGitHub.sha256(Files.readAllBytes(newJar)).equals(FakeGitHub.sha256(jar150)),
            "the checked jar is in the mods folder, next to the old one");
        check(Files.exists(oldJar) && !Files.exists(Installer.part(mods, newJar.getFileName().toString())), "the old one stays for now");
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> card(mc).text().startsWith("SkyCosmetics 1.5.0 installs when you restart")
            && card(mc).buttonLabels().equals(List.of("Release page", "Undo"))), "ready: Release Page and Undo");
        ctx.takeScreenshot("skycosmetics-93-update-ready");
        ctx.waitFor(mc -> readState(state).contains("\"pending\""), 20 * 5);

        // Undo takes it back; download again.
        clickCard(ctx, "Undo");
        waitFor(ctx, Updater.Kind.AVAILABLE);
        check(!Files.exists(newJar) && Files.exists(oldJar), "Undo deletes the new jar");
        clickCard(ctx, "Download & install on restart");
        waitFor(ctx, Updater.Kind.READY);
        ctx.waitFor(mc -> readState(state).contains("\"pending\""), 20 * 5);

        // The game closes with the old jar locked (as the running one is), and starts again on the new version.
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        try (URLClassLoader running = lock(oldJar)) {
            Updater.testStop();
            check(windows == Files.exists(oldJar), "on exit the old jar " + (windows ? "is locked, so it stays" : "is deleted"));
        }
        ctx.setScreen(() -> null);
        Updater.testRestart(new Updater.Env(mods, newJar, "1.5.0", MC, INSTALLED::get));
        check(!Files.exists(oldJar) && Files.exists(newJar), "the next start removes the old jar, which isn't loaded any more");
        check(!readState(state).contains("\"pending\""), "nothing waits any more");
        Updater.Updated up = Updater.updated();
        check(up != null && up.from().equals("1.4.0") && up.to().equals("1.5.0") && up.release() != null,
            "this session knows it was updated, with the notes");
        try (Stream<Path> left = Files.list(mods)) {
            check(left.map(p -> p.getFileName().toString()).toList().equals(List.of("skycosmetics-1.5.0+26.1.2.jar")),
                "one SkyCosmetics jar in the folder");
        }
        openGeneral(ctx);
        check(ctx.computeOnClient(mc -> card(mc).text().startsWith("Updated to SkyCosmetics 1.5.0")), "the card says what's new");
        ctx.takeScreenshot("skycosmetics-94-update-updated");
        clickCard(ctx, "Dismiss");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> card(mc).getHeight()) == 0, "Dismiss puts it away");

        // On 1.5.0, the newest stable: up to date (the quiet line), with the check time.
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.CURRENT);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> card(mc).text().equals("SkyCosmetics 1.5.0 is up to date")), "up to date");
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).toastText()).equals("SkyCosmetics is up to date."),
            "Check Now says so");
        ctx.takeScreenshot("skycosmetics-95-update-current");

        // GitHub's rate limit: the check fails gently.
        gh.apiStatus = 403;
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.ERROR);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> card(mc).text().contains("limit")), "the rate limit is explained");
        ctx.takeScreenshot("skycosmetics-95-update-error");
        gh.apiStatus = 200;

        // Still on 1.4.0 after the restart (the new jar didn't load): it is removed and the reason shown.
        Path mods2 = fresh(root.resolve("mods2"));
        Path old2 = write(mods2.resolve("skycosmetics-1.4.0.jar"), FakeGitHub.jar("skycosmetics", "1.4.0", "~26.1"));
        Files.deleteIfExists(state);
        Updater.Env env2 = new Updater.Env(mods2, old2, "1.4.0", MC, INSTALLED::get);
        Updater.testSetup(gh, env2, state);
        Updater.checkNow();
        waitFor(ctx, Updater.Kind.AVAILABLE);
        Updater.download();
        waitFor(ctx, Updater.Kind.READY);
        ctx.waitFor(mc -> readState(state).contains("\"pending\""), 20 * 5);
        Updater.testRestart(env2);
        check(Files.exists(old2) && !Files.exists(mods2.resolve("skycosmetics-1.5.0+26.1.2.jar")), "a jar that didn't load is removed");
        Updater.checkNow();
        waitFor(ctx, Updater.Kind.FAILED);
        check(Updater.status().message().contains("didn't load"), "and the card says why");

        // A download that doesn't match its checksum: deleted, explained, the release page offered.
        gh.files.put("v1.5.0/skycosmetics-1.5.0+26.1.2.jar", tamper(jar150));
        Files.deleteIfExists(state);
        Updater.testSetup(gh, env2, state);
        openGeneral(ctx);
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        clickCard(ctx, "Download & install on restart");
        waitFor(ctx, Updater.Kind.FAILED);
        ctx.waitTicks(3);
        check(Updater.status().message().contains("checksum"), "a wrong checksum: " + Updater.status().message());
        check(!Files.exists(mods2.resolve("skycosmetics-1.5.0+26.1.2.jar"))
            && !Files.exists(Installer.part(mods2, "skycosmetics-1.5.0+26.1.2.jar")), "nothing is left of it");
        check(ctx.computeOnClient(mc -> card(mc).buttonLabels()).equals(List.of("Release page", "Try again", "Skip this version")),
            "failed: Release Page first, Try Again, Skip");
        ctx.takeScreenshot("skycosmetics-96-update-failed");
        gh.files.put("v1.5.0/skycosmetics-1.5.0+26.1.2.jar", jar150);

        // A release without any checksum is never installed automatically.
        JsonArray noSum = new JsonArray();
        noSum.add(FakeGitHub.release("v1.5.0", false, "- No sums", gh.asset("v1.5.0", "skycosmetics-1.5.0+26.1.2.jar", jar150, false)));
        gh.releases = noSum;
        gh.etag = "\"list-2\"";
        Updater.testSetup(gh, env2, state);
        Updater.checkNow();
        waitFor(ctx, Updater.Kind.AVAILABLE);
        Updater.download();
        waitFor(ctx, Updater.Kind.FAILED);
        check(Updater.status().message().contains("no checksum") && !Files.exists(mods2.resolve("skycosmetics-1.5.0+26.1.2.jar")),
            "no checksum, no install");

        // Not running from a jar (as in development): the card offers only the release page.
        Updater.testSetup(gh, new Updater.Env(null, null, "1.4.0", MC, INSTALLED::get), state);
        openGeneral(ctx);
        checkNow(ctx);
        waitFor(ctx, Updater.Kind.AVAILABLE);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> card(mc).buttonLabels()).equals(List.of("Release page", "Skip this version")),
            "no download where it can't install");

        // The automatic check: one toast a session; with Auto-download it downloads by itself.
        gh.releases = list;
        Files.deleteIfExists(state);
        Updater.testSetup(gh, env2, state);
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> mc.getToastManager().clear());
        Updater.testAutoCheck();
        waitFor(ctx, Updater.Kind.AVAILABLE);
        ctx.waitFor(mc -> mc.getToastManager().getToast(UpdateToast.class, Toast.NO_TOKEN) != null, 20 * 5);
        check(Updater.toasted() && ctx.computeOnClient(mc -> mc.getToastManager().getToast(UpdateToast.class, Toast.NO_TOKEN).text())
            .equals("SkyCosmetics 1.5.0 is available / Update it in /skycosmetics"), "the toast");
        ctx.waitTicks(25);
        ctx.takeScreenshot("skycosmetics-97-update-toast");
        ctx.runOnClient(mc -> mc.getToastManager().clear());
        Updater.testAutoCheck();
        waitFor(ctx, Updater.Kind.AVAILABLE);
        ctx.waitTicks(5);
        check(ctx.computeOnClient(mc -> mc.getToastManager().getToast(UpdateToast.class, Toast.NO_TOKEN)) == null,
            "once a session");

        Updater.testSetup(gh, env2, state);
        Updater.setAutoDownload(true);
        Updater.testAutoCheck();
        waitFor(ctx, Updater.Kind.READY);
        check(Files.exists(mods2.resolve("skycosmetics-1.5.0+26.1.2.jar")), "Auto-download installs it for the next start");
        Updater.setAutoDownload(false);

        // Settings: saved under "updates".
        ctx.runOnClient(mc -> {
            Updater.setChannel(Updater.Channel.BETAS);
            Settings.save();
        });
        ctx.waitFor(mc -> read(mc).contains("\"channel\": \"betas\""), 20 * 5);
        JsonObject saved = JsonParser.parseString(ctx.computeOnClient(UpdateTest::read)).getAsJsonObject().getAsJsonObject("updates");
        check(saved.get("check").getAsBoolean() && !saved.get("autoDownload").getAsBoolean(), "settings.json has the update settings");

        // Mod Menu's badge never checks in development.
        check(Updater.awaitOffer(5000) == null, "Mod Menu's check waits for nothing here");
        for (String r : gh.requests) {
            check(r.startsWith(Fetch.RELEASES) || r.startsWith(Fetch.DOWNLOADS) || r.startsWith("https://release-assets.githubusercontent.com/"),
                "only GitHub was asked: " + r);
        }
        System.out.println("[SkyCosmeticsTest] update: the card, the toast and the install on restart work");
    }

    // ================================================================ helpers

    private static void openGeneral(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new SettingsScreen(null, GeneralSection.ID));
        ctx.waitForScreen(SettingsScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.runOnClient(mc -> mc.getToastManager().clear());
        ctx.waitTicks(3);
    }

    private static UpdateCard card(Minecraft mc) {
        return (UpdateCard) ((SettingsScreen) mc.screen).widget("updateCard");
    }

    /** The card sits inside the pane, and so does every button on it. */
    private static void cardInPane(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            SettingsScreen s = (SettingsScreen) mc.screen;
            int[] pane = s.pane();
            UpdateCard c = card(mc);
            check(c.getX() >= pane[0] && c.getX() + c.getWidth() <= pane[0] + pane[2], "the card is inside the pane");
            for (String label : c.buttonLabels()) {
                int[] at = c.centerOf(label);
                check(at[0] > c.getX() && at[0] < c.getX() + c.getWidth() && at[1] > c.getY() && at[1] < c.getY() + c.getHeight(),
                    "button inside the card: " + label);
            }
        });
    }

    /** A real click on Check Now, scrolled into view. */
    private static void checkNow(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).scrollTo("updateNow"));
        ctx.waitTicks(2);
        int[] at = ctx.computeOnClient(mc -> {
            var w = ((SettingsScreen) mc.screen).widget("updateNow");
            check(w != null && w.getMessage().getString().equals("Check now"), "the Check Now button");
            return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
        });
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(at[0] * scale, at[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);
    }

    /** A real click on one of the card's buttons. */
    private static void clickCard(ClientGameTestContext ctx, String label) {
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).scrollTo("updateCard"));
        ctx.waitTicks(2);
        int[] at = ctx.computeOnClient(mc -> card(mc).centerOf(label));
        check(at != null, "a card button named " + label + ": " + ctx.computeOnClient(mc -> card(mc).buttonLabels()));
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(at[0] * scale, at[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);
    }

    private static void waitFor(ClientGameTestContext ctx, Updater.Kind kind) {
        ctx.waitFor(mc -> Updater.status().kind() == kind, 20 * 15);
    }

    private static String read(Minecraft mc) {
        try {
            return Files.readString(mc.gameDirectory.toPath().resolve("config/skycosmetics/settings.json"));
        } catch (IOException e) {
            return "";
        }
    }

    private static String readState(Path state) {
        try {
            return Files.isRegularFile(state) ? Files.readString(state) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Opens the jar the way a running mod's is open: in a class loader, with a resource read. */
    private static URLClassLoader lock(Path jar) throws IOException {
        URLClassLoader l = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
        try (InputStream in = l.getResourceAsStream("fabric.mod.json")) {
            check(in != null, "the jar opens");
            in.readAllBytes();
        }
        return l;
    }

    private static byte[] tamper(byte[] b) {
        byte[] c = b.clone();
        c[c.length / 2] ^= 0x55;
        return c;
    }

    private static Path fresh(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        return Files.createDirectories(dir);
    }

    private static Path write(Path p, byte[] b) throws IOException {
        Files.write(p, b);
        return p;
    }
}
