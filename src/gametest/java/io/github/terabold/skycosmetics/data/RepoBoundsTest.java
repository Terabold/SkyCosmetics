package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.test.Report;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Reject oversized directory and compressed entries before parsing; keep ordinary reads working. */
public final class RepoBoundsTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext ctx) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("test-repo-bounds");
        Path item = dir.resolve("items/LARGE.json"), zip = dir.resolve("large.zip");
        try {
            Files.createDirectories(item.getParent());
            downloads(dir);
            byte[] oversized = new byte[4 * 1024 * 1024 + 1]; Files.write(item, oversized);
            try (RepoSource.Source src = new RepoSource.Candidate(RepoSource.Kind.OVERRIDE, dir, false).open()) {
                reject(() -> src.read("items/LARGE.json")); reject(() -> src.read("../outside.json"));
                Files.writeString(item, "{}"); check(src.read("items/LARGE.json").length == 2);
            }
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("root/items/LARGE.json")); out.write(oversized); out.closeEntry();
                out.putNextEntry(new ZipEntry("root/items/SMALL.json")); out.write(new byte[]{'{', '}'}); out.closeEntry();
            }
            try (RepoSource.Source src = new RepoSource.Candidate(RepoSource.Kind.ZIP, zip, false).open()) {
                reject(() -> src.read("items/LARGE.json"));
                check(src.read("items/SMALL.json").length == 2 && src.items().size() == 2);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("bounded repository reads", e);
        }
        finally {
            try { Files.deleteIfExists(item); Files.deleteIfExists(zip); Files.deleteIfExists(item.getParent()); Files.deleteIfExists(dir); }
            catch (IOException e) { throw new AssertionError("fixture cleanup", e); }
        }
        System.out.println("[SkyCosmeticsTest] bounded repository entries and downloads passed");
    }

    /** Exercise the real HTTP body handlers with chunked responses, including cleanup after cancellation. */
    private static void downloads(Path dir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Path part = dir.resolve("download.zip.part"), installed = dir.resolve("installed.zip");
        String sha = "ab".repeat(20);
        byte[] archive;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < 1000; i++) {
                zip.putNextEntry(new ZipEntry("root/items/ITEM_" + i + ".json")); zip.write(new byte[]{'{', '}'}); zip.closeEntry();
            }
            zip.finish(); archive = bytes.toByteArray();
        }
        server.createContext("/sha", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(sha.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        server.createContext("/large-sha", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(new byte[64 * 1024 + 1]); }
            catch (IOException expectedDisconnect) { }
        });
        server.createContext("/archive", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(archive); }
        });
        server.createContext("/large-archive", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                byte[] block = new byte[64 * 1024];
                for (int i = 0; i < 2048; i++) exchange.getResponseBody().write(block);
                exchange.getResponseBody().write(1);
            } catch (IOException expectedDisconnect) { }
        });
        server.createContext("/invalid", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write(new byte[]{1, 2, 3}); }
        });
        server.createContext("/error", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(503, 0); exchange.getResponseBody().write(archive); }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (HttpClient http = HttpClient.newHttpClient()) {
            Files.writeString(installed, "keep the previous repo");
            check(sha.equals(Repo.fetchSha(http, base.resolve("/sha"))));
            reject(() -> Repo.fetchSha(http, base.resolve("/large-sha")));
            Repo.fetchArchive(http, base.resolve("/archive"), part);
            check(Files.size(part) == archive.length); Files.delete(part);
            for (String route : new String[]{"/large-archive", "/invalid", "/error"}) {
                reject(() -> { Repo.fetchArchive(http, base.resolve(route), part); return null; });
                check(!Files.exists(part) && Files.readString(installed).equals("keep the previous repo"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new AssertionError("repository download interrupted", e);
        } finally {
            server.stop(0); Files.deleteIfExists(part); Files.deleteIfExists(installed);
        }
    }
    @FunctionalInterface private interface Read { Object run() throws IOException, InterruptedException; }
    private static void reject(Read read) throws IOException, InterruptedException {
        try { read.run(); } catch (IOException expected) { return; }
        throw new AssertionError("oversized or escaping entry was accepted");
    }
    private static void check(boolean ok) { Report.check(ok, "normal entry must remain readable"); }
}
