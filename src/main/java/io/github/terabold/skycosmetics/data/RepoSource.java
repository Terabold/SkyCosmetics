package io.github.terabold.skycosmetics.data;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where the NEU repo can be read from, and whether a copy is safe to read right now.
 *
 * Skyblocker and Firmament both rewrite their copy while the game runs and
 * neither does it atomically. Skyblocker's JGit reset writes the branch ref,
 * then the changed files, then {@code .git/index} last. Firmament deletes the
 * whole tree, extracts the new zip in place and writes {@code loaded-repo-sha.txt}
 * last. So a copy counts only once its own "last write" marker is at least as
 * new as the tree; anything else is mid-update and is left alone.
 *
 * Everything here reads through java.nio, which opens files with delete sharing
 * on Windows, so reading never makes the owning mod's delete or rename fail.
 */
final class RepoSource {
    enum Kind {
        OVERRIDE("override"), SKYBLOCKER("Skyblocker"), FIRMAMENT("Firmament"), ZIP("zip");

        final String label;

        Kind(String label) { this.label = label; }
    }

    /**
     * A settled copy: its commit sha (or a stand-in when there is none) and the
     * time of its completion marker. Two equal snapshots taken around a parse
     * prove nothing was rewritten underneath it.
     */
    record Snapshot(String version, long stamp) {}

    /** One readable repo: sorted item paths and raw file bytes. */
    interface Source extends Closeable {
        /** Commit sha when known, for the log. */
        String version();

        /** "items/NAME.json" paths, sorted so every source parses in the same order. */
        List<String> items() throws IOException;

        byte[] read(String path) throws IOException;

        @Override
        default void close() throws IOException {}
    }

    private static final Pattern SHA = Pattern.compile("\\b([0-9a-f]{40})\\b");

    /** One place a repo may live. */
    static final class Candidate {
        final Kind kind;
        final Path path;
        /** Its owning mod is installed, so the copy is (or will soon be) kept fresh. */
        final boolean modLoaded;

        Candidate(Kind kind, Path path, boolean modLoaded) {
            this.kind = kind;
            this.path = path;
            this.modLoaded = modLoaded;
        }

        /** Null when missing or mid-update. Never throws. */
        Snapshot probe() {
            try {
                return switch (kind) {
                    case OVERRIDE, SKYBLOCKER -> gitProbe(path);
                    case FIRMAMENT -> firmamentProbe(path);
                    case ZIP -> zipProbe(path);
                };
            } catch (IOException | RuntimeException e) {
                return null; // a file vanished between two reads: it is being rewritten
            }
        }

        Source open() throws IOException {
            return switch (kind) {
                case OVERRIDE, SKYBLOCKER -> dir(path, gitSha(path));
                case FIRMAMENT -> dir(path.resolve("repo-extracted"), firstSha(read(path.resolve("loaded-repo-sha.txt"))));
                case ZIP -> zip(path);
            };
        }

        @Override
        public String toString() {
            return kind.label + " (" + path + ")";
        }
    }

    private RepoSource() {}

    /**
     * Every place to look, best first: an explicit -Dskycosmetics.repo, then copies
     * whose mod is installed (Skyblocker before Firmament), then our own zip, then
     * leftovers of an uninstalled mod, which nothing keeps fresh any more.
     * SkyHanni's copy is never used: its commit file is written before the files
     * reach disk.
     */
    static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>(4);
        String forced = System.getProperty("skycosmetics.repo");
        if (forced != null && !forced.isBlank()) {
            out.add(new Candidate(Kind.OVERRIDE, Path.of(forced), true));
            return out;
        }
        FabricLoader fl = FabricLoader.getInstance();
        Path game = fl.getGameDir();
        Candidate sb = new Candidate(Kind.SKYBLOCKER, game.resolve("config/skyblocker/item-repo"), fl.isModLoaded("skyblocker"));
        Candidate fm = new Candidate(Kind.FIRMAMENT, game.resolve(".firmament"), fl.isModLoaded("firmament"));
        for (Candidate c : List.of(sb, fm)) if (c.modLoaded) out.add(c);
        out.add(new Candidate(Kind.ZIP, ownZip(), false));
        for (Candidate c : List.of(sb, fm)) if (!c.modLoaded) out.add(c);
        return out;
    }

    static Path ownZip() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/neu-repo.zip");
    }

    // ------------------------------------------------------------- probes ---

    /** Both files the parser cannot do without, plus the items folder. */
    private static boolean complete(Path root) {
        return Files.isDirectory(root.resolve("items"))
            && Files.isRegularFile(root.resolve("constants/animatedskulls.json"))
            && Files.isRegularFile(root.resolve("constants/dyes.json"));
    }

    /**
     * Skyblocker's clone. Settled when there is no index.lock and the index,
     * written last by a checkout, is at least as new as the branch ref, written
     * first. A folder without .git (a hand-made override) uses its mtimes.
     */
    private static Snapshot gitProbe(Path root) throws IOException {
        if (!complete(root)) return null;
        Path git = root.resolve(".git");
        if (!Files.isDirectory(git)) {
            long t = Math.max(mtime(root.resolve("items")), mtime(root.resolve("constants")));
            return new Snapshot("mtime-" + t, t);
        }
        if (Files.exists(git.resolve("index.lock"))) return null;
        Ref ref = headRef(git);
        if (ref == null) return null;
        long index = mtime(git.resolve("index"));
        return index < ref.time ? null : new Snapshot(ref.sha, index);
    }

    private record Ref(String sha, long time) {}

    /**
     * HEAD's commit. The loose ref file comes first: Skyblocker's packed-refs
     * keeps a stale refs/heads/master next to the live loose one, so it is only
     * a fallback for when no loose file exists.
     */
    private static Ref headRef(Path git) throws IOException {
        Path headFile = git.resolve("HEAD");
        String head = read(headFile).trim();
        if (!head.startsWith("ref: ")) {
            String sha = firstSha(head);
            return sha == null ? null : new Ref(sha, mtime(headFile));
        }
        String name = head.substring(5).trim();
        if (!name.startsWith("refs/") || name.contains("..")) return null;
        Path loose = git.resolve(name);
        if (Files.isRegularFile(loose)) {
            String sha = firstSha(read(loose));
            return sha == null ? null : new Ref(sha, mtime(loose));
        }
        Path packed = git.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) return null;
        for (String line : read(packed).split("\n")) {
            if (line.startsWith("#") || line.startsWith("^")) continue;
            int sp = line.indexOf(' ');
            if (sp == 40 && line.substring(sp + 1).trim().equals(name)) return new Ref(line.substring(0, 40), mtime(packed));
        }
        return null;
    }

    private static String gitSha(Path root) {
        try {
            Path git = root.resolve(".git");
            Ref r = Files.isDirectory(git) ? headRef(git) : null;
            return r == null ? null : r.sha;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Firmament's extracted zip. Complete only when loaded-repo-sha.txt, written
     * after extraction, is at least as new as the tree and its top folders.
     * During the delete-then-extract window the file still holds the OLD sha, so
     * the mtime comparison is what rejects a half-extracted tree.
     */
    private static Snapshot firmamentProbe(Path dir) throws IOException {
        Path root = dir.resolve("repo-extracted");
        Path shaFile = dir.resolve("loaded-repo-sha.txt");
        if (!Files.isRegularFile(shaFile) || !complete(root)) return null;
        String sha = firstSha(read(shaFile));
        if (sha == null) return null;
        long t = mtime(shaFile);
        if (t < mtime(root) || t < mtime(root.resolve("items")) || t < mtime(root.resolve("constants"))) return null;
        return new Snapshot(sha, t);
    }

    /** Our own zip is replaced by an atomic rename, so it is always complete. */
    private static Snapshot zipProbe(Path zip) throws IOException {
        if (!Files.isRegularFile(zip)) return null;
        long t = mtime(zip);
        String sha = zipSha(zip);
        return new Snapshot(sha != null ? sha : "zip-" + Files.size(zip) + "-" + t, t);
    }

    /**
     * GitHub archive zips carry the commit sha as the zip comment. It sits in the
     * end-of-central-directory record, so only the file's tail is read.
     */
    static String zipSha(Path zip) {
        try (FileChannel ch = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = ch.size();
            int len = (int) Math.min(size, 22 + 0xFFFF);
            ByteBuffer buf = ByteBuffer.allocate(len);
            ch.read(buf, size - len);
            byte[] b = buf.array();
            for (int i = len - 22; i >= 0; i--) {
                if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06) {
                    int n = (b[i + 20] & 0xFF) | (b[i + 21] & 0xFF) << 8;
                    if (i + 22 + n > len) return null;
                    return firstSha(new String(b, i + 22, n, StandardCharsets.ISO_8859_1));
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static String firstSha(String s) {
        if (s == null) return null;
        Matcher m = SHA.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static long mtime(Path p) throws IOException {
        return Files.getLastModifiedTime(p).toMillis();
    }

    // ------------------------------------------------------------ readers ---

    private static Source dir(Path root, String sha) {
        return new Source() {
            public String version() { return sha; }

            public List<String> items() throws IOException {
                List<String> out = new ArrayList<>(10_000);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(root.resolve("items"), "*.json")) {
                    for (Path p : ds) out.add("items/" + p.getFileName());
                }
                out.sort(null);
                return out;
            }

            public byte[] read(String path) throws IOException {
                return Files.readAllBytes(root.resolve(path));
            }
        };
    }

    /** GitHub zips nest everything under one top folder; paths here are relative to it. */
    private static Source zip(Path zipPath) throws IOException {
        ZipFile z = new ZipFile(zipPath.toFile());
        Map<String, ZipEntry> entries = new HashMap<>(16_384);
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            int i = e.getName().indexOf('/');
            entries.put(i < 0 ? e.getName() : e.getName().substring(i + 1), e);
        }
        String sha = firstSha(z.getComment());
        return new Source() {
            public String version() { return sha; }

            public List<String> items() {
                List<String> out = new ArrayList<>(10_000);
                for (String n : entries.keySet()) {
                    if (n.startsWith("items/") && n.endsWith(".json") && n.indexOf('/', 6) < 0) out.add(n);
                }
                out.sort(null);
                return out;
            }

            public byte[] read(String path) throws IOException {
                ZipEntry e = entries.get(path);
                if (e == null) throw new NoSuchFileException(path + " in " + zipPath);
                try (InputStream in = z.getInputStream(e)) {
                    return in.readAllBytes();
                }
            }

            public void close() throws IOException {
                z.close();
            }
        };
    }
}
