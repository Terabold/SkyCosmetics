package io.github.terabold.skycosmetics.compat;

import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.DyeEntry;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What other mods' values are in SkyCosmetics' terms: a repo skin for a texture, an animated dye for keyframes. */
final class Values {
    private static Catalog indexed;
    private static Map<String, SkinEntry> byTexture = Map.of();

    private Values() {}

    /** The repo skin whose first frame is {@code texture} ("Diamond Knight Skin" for a pasted value), or null. */
    static SkinEntry skinByTexture(String texture) {
        if (texture == null) return null;
        Catalog c = Repo.get();
        if (c != indexed) { // built once per catalog, only when another mod's skin is shown
            Map<String, SkinEntry> m = new HashMap<>();
            for (SkinEntry e : c.skins.values()) if (e.textures.length > 0) m.putIfAbsent(e.textures[0], e);
            byTexture = m;
            indexed = c;
        }
        return byTexture.get(texture);
    }

    /**
     * A SkyCosmetics animated dye close to keyframes at {@code times} (0..1 of the cycle) over {@code seconds}:
     * evenly spaced and blended, played forth and back when {@code back}. Null when there is nothing to animate.
     */
    static String animatedDye(int[] colors, float[] times, boolean back, float seconds) {
        if (colors.length == 0) return null;
        Integer[] order = new Integer[colors.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(times[a], times[b]));
        List<Integer> keys = new ArrayList<>();
        for (Integer i : order) keys.add(colors[i] & 0xFFFFFF);
        if (back) for (int i = keys.size() - 2; i > 0; i--) keys.add(keys.get(i));
        while (keys.size() > DyeEntry.MAX_KEYFRAMES) keys.removeLast();
        int[] rgbs = keys.stream().mapToInt(Integer::intValue).toArray();
        float ticks = Float.isFinite(seconds) && seconds > 0 ? seconds * 20 : 40;
        int perStep = Math.round(ticks / (rgbs.length * (float) DyeEntry.BLEND_STEPS));
        return DyeEntry.customAnimatedId(Math.max(1, perStep), true, rgbs);
    }
}
