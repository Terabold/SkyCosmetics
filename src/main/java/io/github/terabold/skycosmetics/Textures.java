package io.github.terabold.skycosmetics;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Texture value -> head profile, plus "is this texture actually on the GPU yet".
 *
 * The readiness check is what makes swaps flicker-free: the renderer draws a
 * Steve head for any profile whose skin is still downloading, so nothing is
 * swapped to a texture until the same cache the renderer uses reports it loaded.
 * A value Minecraft cannot read gets a default skin (Steve, Alex...) for good;
 * that never counts as loaded, so such a head keeps Hypixel's look.
 */
public final class Textures {
    public static final String CUSTOM_PREFIX = "tex:";
    /** The render cache expires entries 5 minutes after last access; re-touch well inside that. */
    private static final long TRUST_MS = 30_000;
    /** Half of it: while drawn, each frame of an animation is looked up again at least every 45 s. */
    private static final long WARM_NS = TRUST_MS / 2 * 1_000_000;

    private static final Map<String, ResolvableProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    /** Downloads started here and not finished yet: the studio asks for more while few are running. */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private static final class State {
        CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>> future;
        long checkedAt;
    }

    private Textures() {}

    /** One shared, already-resolved profile per texture, so caches key on identity-equal objects. */
    public static ResolvableProfile profile(String value) {
        return PROFILES.computeIfAbsent(value, v -> {
            PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", readable(v))));
            UUID id = UUID.nameUUIDFromBytes(v.getBytes(StandardCharsets.UTF_8));
            return ResolvableProfile.createResolved(new GameProfile(id, "", props));
        });
    }

    /** True once the skin for {@code value} is downloaded and uploaded. Starts the load if needed. */
    public static boolean ready(String value) {
        long now = System.currentTimeMillis();
        State s = STATES.computeIfAbsent(value, v -> new State());
        if (s.future != null && !s.future.isDone()) return false; // still downloading; it completes by itself
        if (s.future != null && now - s.checkedAt < TRUST_MS) return loaded(s.future);
        s.future = Minecraft.getInstance().playerSkinRenderCache().lookup(profile(value));
        s.checkedAt = now;
        if (!s.future.isDone()) {
            IN_FLIGHT.incrementAndGet();
            s.future.whenComplete((r, e) -> IN_FLIGHT.decrementAndGet());
        }
        return s.future.isDone() && loaded(s.future);
    }

    /** How many textures are still downloading. */
    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    private static boolean loaded(CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>> f) {
        if (f.isCompletedExceptionally() || f.isCancelled()) return false;
        PlayerSkinRenderCache.RenderInfo info = f.getNow(Optional.empty()).orElse(null);
        return info != null && !info.playerSkin().body().equals(DefaultPlayerSkin.get(info.gameProfile()).body());
    }

    /**
     * {@code value} as Minecraft's strict decoder reads it. Some heads in the repo carry the wrong
     * number of '=' at the end (fine for 1.8, a default skin today); those get the right padding.
     */
    private static String readable(String value) {
        try {
            Base64.getDecoder().decode(value);
            return value;
        } catch (IllegalArgumentException e) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '=') end--;
            try {
                return Base64.getEncoder().encodeToString(Base64.getDecoder().decode(value.substring(0, end)));
            } catch (IllegalArgumentException again) {
                return value; // not base64 at all: a default skin, which ready() refuses
            }
        }
    }

    /** Kick off every frame's download now so the first animation cycle is already smooth. */
    public static void preload(SkinEntry e) {
        if (e == null) return;
        for (String t : e.textures) ready(t);
    }

    /**
     * Called whenever an animated skin is drawn; every 15 s it looks at all its frames again. A blink frame is on
     * screen for a tick or two per cycle, so on its own it is only checked when due: after minutes out of view (the
     * render cache had let it go) that check starts a reload and the blink is skipped. This keeps every frame's
     * cache entry alive while the skin is drawn, and reloads the frames together, ahead of time.
     */
    public static void keepWarm(SkinEntry e) {
        if (e.textures.length > 1 && e.warmDue(System.nanoTime(), WARM_NS)) preload(e);
    }

    /**
     * Accepts a base64 texture value, a textures.minecraft.net URL, or a bare
     * 64-hex texture hash. Returns the base64 value, or null if unrecognised.
     */
    public static String normalise(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.startsWith(CUSTOM_PREFIX)) s = s.substring(CUSTOM_PREFIX.length());
        if (s.isEmpty()) return null;
        String hash = null;
        int slash = s.lastIndexOf("/texture/");
        if (slash >= 0) hash = s.substring(slash + 9);
        else if (s.matches("[0-9a-fA-F]{40,80}")) hash = s;
        if (hash != null) {
            if (!hash.matches("[0-9a-fA-F]{40,80}")) return null;
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                + hash.toLowerCase(Locale.ROOT) + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
            return decoded.contains("textures.minecraft.net") ? s : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
