package io.github.terabold.skycosmetics.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The files of an update, in the folder the running SkyCosmetics jar sits in. Only ever touches SkyCosmetics' own
 * files: the download ({@code <jar name>.part}), the new jar, and the jar being replaced, each checked to hold
 * SkyCosmetics before it is moved or deleted. Nothing is run.
 *
 * <h2>Why a restart installs it</h2>
 * Windows keeps the running jar locked until the game exits, and nothing may run after that. So the verified new jar
 * goes next to the old one; when the game closes the old one is deleted where the system allows it. Where it doesn't,
 * Fabric Loader loads the newest of two copies of a mod on the next start (the old copy is never opened, so not
 * locked), and the new version deletes the old jar first thing. A jar is only placed when Fabric will pick it: newer,
 * for this Minecraft, its dependencies met.
 */
public final class Installer {
    public static final String MOD_ID = "skycosmetics";

    private Installer() {}

    /** Where a download goes until it is checked. Fabric only loads files ending in ".jar". */
    public static Path part(Path mods, String jarName) {
        return mods.resolve(jarName + ".part");
    }

    /** A release jar name that is safe as a file name in the mods folder, and one Fabric loads (".jar", lower case). */
    public static boolean safeName(String name) {
        return name != null && name.matches("(?i:skycosmetics)-[0-9A-Za-z.+_-]+\\.jar") && !name.contains("..");
    }

    /** The mod id and version a jar's fabric.mod.json declares, or null when it has none it can read. */
    public static String[] idAndVersion(Path jar) {
        JsonObject o = modJson(jar);
        if (o == null) return null;
        return new String[]{str(o.get("id")), str(o.get("version"))};
    }

    /** A jar of SkyCosmetics, by its fabric.mod.json. */
    public static boolean isOurs(Path jar) {
        String[] iv = idAndVersion(jar);
        return iv != null && MOD_ID.equals(iv[0]);
    }

    /**
     * Whether a downloaded jar may go into the mods folder: SkyCosmetics at {@code version}, newer than the running
     * {@code current} as Fabric orders them, and every dependency it declares met by what is installed
     * ({@code installed}: a mod id to its version, null when missing; "minecraft" and "java" included).
     *
     * @return null when it may, else why not (shown to the player)
     */
    public static String inspect(Path jar, String version, String current, Function<String, String> installed) {
        JsonObject o = modJson(jar);
        if (o == null) return "The download isn't a mod.";
        if (!MOD_ID.equals(str(o.get("id")))) return "The download isn't SkyCosmetics.";
        String declared = str(o.get("version"));
        try {
            Version mine = Version.parse(declared), want = Version.parse(version);
            if (mine.compareTo(want) != 0) return "The download is version " + declared + ", not " + version + ".";
            if (current != null && !current.isEmpty() && mine.compareTo(Version.parse(current)) <= 0) {
                return "The download isn't newer than " + current + ".";
            }
        } catch (VersionParsingException e) {
            return "The download's version can't be read.";
        }
        if (o.get("depends") instanceof JsonObject deps) {
            for (Map.Entry<String, JsonElement> d : deps.entrySet()) {
                String need = d.getValue().isJsonArray() ? null : str(d.getValue());
                String have = installed.apply(d.getKey());
                List<String> ranges = new ArrayList<>();
                if (need != null) ranges.add(need);
                else for (JsonElement e : d.getValue().getAsJsonArray()) ranges.add(str(e));
                if (have == null) return "It needs " + name(d.getKey()) + ", which isn't installed.";
                if (!satisfied(ranges, have)) {
                    return "It needs " + name(d.getKey()) + " " + String.join(" or ", ranges) + " (you have " + have + ").";
                }
            }
        }
        if (o.get("breaks") instanceof JsonObject breaks) {
            for (Map.Entry<String, JsonElement> b : breaks.entrySet()) {
                String have = installed.apply(b.getKey());
                if (have != null && b.getValue().isJsonPrimitive() && satisfied(List.of(str(b.getValue())), have)) {
                    return "It doesn't work with " + name(b.getKey()) + ", which is installed.";
                }
            }
        }
        return null;
    }

    private static boolean satisfied(List<String> ranges, String have) {
        try {
            Version v = Version.parse(have);
            for (VersionPredicate p : VersionPredicate.parse(ranges)) if (p.test(v)) return true;
            return false;
        } catch (VersionParsingException e) {
            return true; // a range Fabric can't read here: Fabric decides at start, and keeps the old copy if it fails
        }
    }

    private static String name(String id) {
        return switch (id) {
            case "minecraft" -> "Minecraft";
            case "fabricloader" -> "Fabric Loader";
            case "fabric-api", "fabric" -> "Fabric API";
            case "java" -> "Java";
            default -> id;
        };
    }

    /**
     * Moves a checked download into place as {@code target}. Never over the running jar, nor over a file that isn't
     * SkyCosmetics.
     */
    public static void place(Path part, Path target, Path running) throws IOException {
        if (running != null && Files.exists(running) && Files.isSameFile(target.getParent(), running.getParent())
            && target.getFileName().equals(running.getFileName())) {
            throw new IOException("the new jar has the running jar's name");
        }
        if (Files.exists(target) && !isOurs(target)) throw new IOException(target.getFileName() + " is another mod's file");
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** What became of a jar to remove. */
    public enum Removal {
        /** Deleted. */
        REMOVED,
        /** Renamed to {@code <name>.old}, which Fabric ignores; deleted on a later start. */
        RENAMED,
        /** Locked by the running game: next start. */
        IN_USE,
        /** Not there (any more). */
        GONE,
        /** Not a SkyCosmetics jar: left alone. */
        NOT_OURS
    }

    /** Deletes an old SkyCosmetics jar, or renames it out of Fabric's way when it can't be deleted. */
    public static Removal remove(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return Removal.GONE;
        if (!isOurs(jar)) return Removal.NOT_OURS;
        try {
            Files.delete(jar);
            return Removal.REMOVED;
        } catch (IOException e) {
            try {
                Path old = jar.resolveSibling(jar.getFileName() + ".old");
                Files.move(jar, old, StandardCopyOption.REPLACE_EXISTING);
                return Removal.RENAMED;
            } catch (IOException e2) {
                return Removal.IN_USE;
            }
        }
    }

    /**
     * Left over from earlier updates: downloads that never finished ({@code skycosmetics-*.jar.part}) and old jars
     * set aside ({@code skycosmetics-*.jar.old}). Only SkyCosmetics' names, and an .old only when it is SkyCosmetics.
     *
     * @return how many were deleted
     */
    public static int sweep(Path mods) {
        int n = 0;
        if (mods == null || !Files.isDirectory(mods)) return 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.startsWith(MOD_ID + "-") || !Files.isRegularFile(p)) continue;
                boolean part = name.endsWith(".jar.part"), old = name.endsWith(".jar.old");
                if (!part && !(old && isOurs(p))) continue;
                // Another game sharing this folder may be downloading right now: only parts nobody touched for a while.
                if (part && System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() < 10 * 60_000) continue;
                try {
                    Files.delete(p);
                    n++;
                } catch (IOException ignored) {
                    // in use by something else: next time
                }
            }
        } catch (IOException ignored) {
            // the folder can't be listed: nothing to sweep
        }
        return n;
    }

    private static JsonObject modJson(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return null;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null || e.getSize() > 256 * 1024) return null;
            try (InputStream in = z.getInputStream(e)) {
                byte[] bytes = in.readNBytes(256 * 1024 + 1);
                if (bytes.length > 256 * 1024) return null;
                JsonElement el = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                return el instanceof JsonObject o ? o : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
