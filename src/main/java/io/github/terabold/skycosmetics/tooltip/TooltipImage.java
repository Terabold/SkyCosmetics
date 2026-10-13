package io.github.terabold.skycosmetics.tooltip;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Io;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/** One local PNG, decoded off-thread only on load/import; never reads the filesystem while drawing. */
public final class TooltipImage {
    private record Image(int w, int h, int[] pixels) {}
    private static Image image;
    private static int version;
    private static boolean busy;
    private static String status = "No image";

    private TooltipImage() {}

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/tooltip-background.png");
    }

    public static void load() { read(file(), false); }
    public static void importFile(Path source) { read(source, true); }

    /** Removes only our local copy; the image that was imported is left alone. */
    public static void remove() {
        if (busy) return;
        busy = true;
        status = "Removing…";
        CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(file());
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((unused, error) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (error != null) {
                status = "Could not remove image";
                Io.failed("Removing a tooltip background image", error);
                return;
            }
            image = null;
            version++;
            status = "No image";
            if (TooltipSettings.background == TooltipSettings.Background.IMAGE) TooltipSettings.background = TooltipSettings.Background.SOLID;
            io.github.terabold.skycosmetics.Settings.save();
        }));
    }

    private static void read(Path source, boolean copy) {
        if (busy) return;
        busy = true;
        status = "Loading…";
        CompletableFuture.supplyAsync(() -> {
            try {
                if (!copy && !Files.isRegularFile(source)) return null;
                long size = Files.size(source);
                if (size < 24 || size > 4 * 1024 * 1024) throw new IOException("Choose a PNG under 4 MB");
                byte[] bytes;
                try (var in = Files.newInputStream(source)) { bytes = in.readNBytes(4 * 1024 * 1024 + 1); }
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("Choose a PNG under 4 MB");
                Image decoded = decode(bytes);
                if (copy) {
                    Files.createDirectories(file().getParent());
                    Path temp = file().resolveSibling("tooltip-background.png.tmp");
                    Files.write(temp, bytes);
                    try {
                        Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return decoded;
            } catch (IOException | RuntimeException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (error != null) {
                status = "Could not load PNG (max 1024 × 1024, 4 MB)";
                Io.failed("Loading a tooltip background image", error);
                return; // Keep the previous working image and file.
            }
            image = loaded;
            version++;
            status = loaded == null ? "No image" : loaded.w() + " × " + loaded.h() + " PNG";
            if (copy) {
                TooltipSettings.background = TooltipSettings.Background.IMAGE;
                io.github.terabold.skycosmetics.Settings.save();
            }
        }));
    }

    private static Image decode(byte[] bytes) throws IOException {
        if (bytes.length < 24) throw new IOException("Not a PNG");
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getLong() != 0x89504E470D0A1A0AL || header.getInt(12) != 0x49484452) throw new IOException("Not a PNG");
        int w = header.getInt(16), h = header.getInt(20);
        if (w < 1 || h < 1 || w > 1024 || h > 1024) throw new IOException("Choose a PNG up to 1024 × 1024");
        try (NativeImage pixels = NativeImage.read(new ByteArrayInputStream(bytes))) {
            if (pixels.getWidth() != w || pixels.getHeight() != h) throw new IOException("Invalid PNG dimensions");
            int[] colors = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) colors[y * w + x] = pixels.getPixel(x, y);
            return new Image(w, h, colors);
        }
    }

    static int sample(float x, float y, int w, int h, TooltipSettings.ImageFit fit) {
        Image img = image;
        if (img == null) return 0;
        float u, v;
        if (fit == TooltipSettings.ImageFit.TILE) {
            u = x % img.w(); v = y % img.h();
        } else if (fit == TooltipSettings.ImageFit.STRETCH) {
            u = x * img.w() / w; v = y * img.h() / h;
        } else {
            float ratio = fit == TooltipSettings.ImageFit.COVER ? Math.max(w / (float) img.w(), h / (float) img.h())
                : Math.min(w / (float) img.w(), h / (float) img.h());
            u = (x - (w - img.w() * ratio) / 2) / ratio;
            v = (y - (h - img.h() * ratio) / 2) / ratio;
            if (u < 0 || v < 0 || u >= img.w() || v >= img.h()) return 0;
        }
        return img.pixels()[Math.clamp((int) v, 0, img.h() - 1) * img.w() + Math.clamp((int) u, 0, img.w() - 1)];
    }

    public static boolean busy() { return busy; }
    public static boolean loaded() { return image != null; }
    public static int version() { return version; }
    public static String status() { return status; }
}
