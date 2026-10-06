package io.github.terabold.skycosmetics;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * SkyCosmetics was called Skin Studio before 1.4.0, with the mod id "skinstudio". On the first start after the
 * rename, the old config folder is copied (never moved or deleted, so an older build still finds its files) and
 * the old open key is carried over once. The file formats did not change.
 */
public final class Migration {
    static final String OLD_ID = "skinstudio";
    private static final String OLD_KEY = "key_key." + OLD_ID + ".open:";
    private static final String NEW_KEY = "key_key." + SkyCosmetics.MOD_ID + ".open:";

    private Migration() {}

    /** At mod init, before anything reads {@code config/skycosmetics} or Minecraft first saves options.txt. */
    public static void run() {
        Path config = FabricLoader.getInstance().getConfigDir();
        Path from = config.resolve(OLD_ID), to = config.resolve(SkyCosmetics.MOD_ID);
        try {
            int files = copyConfig(from, to);
            if (files > 0) SkyCosmetics.LOG.info("Copied {} file(s) from {} to {}; the old folder is left as it was", files, from, to);
        } catch (IOException | RuntimeException e) {
            SkyCosmetics.LOG.error("Could not copy the old config from {} to {}; starting with empty settings", from, to, e);
        }
        String key = oldKey(FabricLoader.getInstance().getGameDir().resolve("options.txt"));
        if (key != null) ClientLifecycleEvents.CLIENT_STARTED.register(mc -> applyKey(mc, key));
    }

    /**
     * Copies the folder {@code from} to {@code to} when only {@code from} exists, through a staging folder that is
     * renamed at the end, so a copy cut short is redone on the next start instead of leaving half a config.
     *
     * @return the number of files copied; 0 when there was nothing to do
     */
    public static int copyConfig(Path from, Path to) throws IOException {
        if (Files.exists(to) || !Files.isDirectory(from)) return 0;
        Path staging = to.resolveSibling(to.getFileName() + ".migrating");
        int files = 0;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = staging.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    files++;
                }
            }
        }
        try {
            Files.move(staging, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, to);
        }
        return files;
    }

    /**
     * The old open key's saved value ("key.keyboard.k"), or null when there is none, it was unbound, or the new
     * key was ever saved (then it has been carried over already, or the player set it).
     */
    public static String oldKey(Path optionsTxt) {
        List<String> lines;
        try {
            lines = Files.readAllLines(optionsTxt, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // no options.txt yet: a new install
        }
        return oldKey(lines);
    }

    public static String oldKey(List<String> lines) {
        String old = null;
        for (String line : lines) {
            if (line.startsWith(NEW_KEY)) return null;
            if (line.startsWith(OLD_KEY)) old = line.substring(OLD_KEY.length()).trim();
        }
        return old == null || old.isEmpty() || old.equals(InputConstants.UNKNOWN.getName()) ? null : old;
    }

    private static void applyKey(Minecraft mc, String saved) {
        try {
            KeyMapping open = SkyCosmetics.openKey();
            if (open == null || !open.isUnbound()) return;
            open.setKey(InputConstants.getKey(saved));
            KeyMapping.resetMapping();
            mc.options.save();
            SkyCosmetics.LOG.info("Carried the open key over from Skin Studio: {}", saved);
        } catch (RuntimeException e) {
            Io.failed("Carrying the open key over", e);
        }
    }
}
