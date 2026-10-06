package io.github.terabold.skycosmetics.data;

import io.github.terabold.skycosmetics.Textures;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

/**
 * One selectable head look: a single texture, or an animation of several.
 *
 * Every Hypixel skull in the repo becomes one of these. Animated skins keep all
 * their frames and the per-frame tick count from {@code animatedskulls.json}, so
 * they cycle at the same speed as on Hypixel.
 */
public final class SkinEntry {
    public enum Kind { SKIN, PET_SKIN, VARIANT, HEAD, CUSTOM }

    /** What a cosmetic skin goes on, from its "can be applied to" lore line: the Helmet and Orb filters. */
    public enum Use { HELMET, ORB, OTHER }

    public final String id;
    public final String name;
    /** ARGB colour for the name, taken from the item's rarity colour code. */
    public final int color;
    public final Kind kind;
    /** Base64 texture property values, one per frame. */
    public final String[] textures;
    /** Game ticks each frame stays on screen (per frame: some skins blink), 1 to {@link #MAX_FRAME_TICKS}. */
    public final int[] frameTicks;
    private final long cycle;
    /** Parent skin id for colour variants (Knight Skin -> its 15 colours), else null. */
    public final String parent;
    final String searchKey;
    /** False for duplicate-texture heads: still resolvable by id, just not shown twice in the grid. */
    public boolean listed = true;
    /**
     * The item's lore says the skin is animated but animatedskulls.json has no
     * frames for it yet, so only its still texture is known. The UI badges these;
     * previewing the skin in game lets {@link PreviewRecorder} fill the gap.
     */
    public boolean missingFrames;
    /** Learned in game ({@code captured.json}) rather than read from the repo. */
    public boolean learned;
    /** Helmet unless the lore says power orbs, backpacks, or nothing you wear (minion and barn skins). */
    public Use use = Use.HELMET;

    /** One hour: a longer frame is a broken entry (repo or captured.json), not an animation. */
    public static final int MAX_FRAME_TICKS = 72_000;

    private ItemStack[] icons;

    public static SkinEntry still(String id, String name, int color, Kind kind, String texture, String parent) {
        return new SkinEntry(id, name, color, kind, new String[]{texture}, new int[]{1}, parent);
    }

    public SkinEntry(String id, String name, int color, Kind kind, String[] textures, int[] frameTicks, String parent) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.kind = kind;
        this.textures = textures;
        this.frameTicks = new int[frameTicks.length];
        long c = 0;
        for (int i = 0; i < frameTicks.length; i++) c += this.frameTicks[i] = Math.clamp(frameTicks[i], 1, MAX_FRAME_TICKS);
        this.cycle = Math.max(1, c);
        this.parent = parent;
        // "animated" finds every animated skin, now that the grid has no Animated filter.
        this.searchKey = (name + " " + id.replace('_', ' ') + (textures.length > 1 ? " animated" : ""))
            .toLowerCase(Locale.ROOT);
    }

    public boolean animated() {
        return textures.length > 1;
    }

    public int frameAt(long tick) {
        if (!animated()) return 0;
        long t = tick % cycle;
        for (int i = 0; i < frameTicks.length; i++) {
            t -= frameTicks[i];
            if (t < 0) return i;
        }
        return 0;
    }

    /** A plain player head wearing frame {@code frame}, for the picker grid. Built once per frame. */
    public ItemStack icon(int frame) {
        if (icons == null) icons = new ItemStack[textures.length];
        ItemStack s = icons[frame];
        if (s == null) {
            s = new ItemStack(Items.PLAYER_HEAD);
            s.set(DataComponents.PROFILE, Textures.profile(textures[frame]));
            icons[frame] = s;
        }
        return s;
    }
}
