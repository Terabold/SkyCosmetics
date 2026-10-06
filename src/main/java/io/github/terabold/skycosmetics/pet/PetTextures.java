package io.github.terabold.skycosmetics.pet;

import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.SkinEntry;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which head textures a pet can show in the world, compared by texture hash.
 *
 * Raw base64 values never match between the repo and the server: Hypixel's
 * carry a timestamp and profile id around the same skin URL. The hash at the
 * end of that URL is what identifies the picture, so that is what is compared.
 */
public final class PetTextures {
    /** Base pets whose low-level form is a different head (Golden Dragon starts as an egg). */
    private static final String[] FORMS = {"_EGG", "_EGG_NPC", "_NPC", "_BABY"};
    private static final int CACHE_MAX = 2048;
    /** Real values are a few hundred characters; a server may send 32k, which is neither decoded nor cached. */
    public static final int MAX_VALUE = 4096;
    private static final Map<String, String> HASHES = new HashMap<>();

    private PetTextures() {}

    /** Texture hash of a head's skin, or null. Main thread only. */
    public static String hashOf(ItemStack s) {
        return hash(Pet.texture(s));
    }

    /** Texture hash of a base64 textures value, or null if it names no skin URL. Cached; main thread only. */
    static String hash(String value) {
        if (value == null || value.length() > MAX_VALUE) return null;
        String h = HASHES.get(value);
        if (h != null) return h.isEmpty() ? null : h;
        h = decode(value);
        if (HASHES.size() >= CACHE_MAX) HASHES.clear();
        HASHES.put(value, h == null ? "" : h);
        return h;
    }

    private static String decode(String value) {
        try {
            String json = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            int i = json.indexOf("/texture/");
            if (i < 0) return null;
            int start = i + 9, end = start;
            while (end < json.length() && Character.digit(json.charAt(end), 16) >= 0) end++;
            return end - start >= 32 ? json.substring(start, end).toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Every texture {@code pet}'s head may show: the item's own texture, the
     * repo heads for its type at every rarity (plus egg or baby forms), and
     * every frame of its skin including the variants that only exist as
     * animation entries (seasons, day and night, factions, baby forms, colours).
     * Built once per pet change, never per frame.
     */
    static Set<String> of(Pet pet, Catalog c) {
        Set<String> out = new HashSet<>();
        if (pet == null) return out;
        add(out, pet.texture);
        if (pet.type != null) {
            for (int i = 0; i <= Pet.TIERS.length; i++) add(out, c.skins.get(pet.type + ";" + i));
            for (String f : FORMS) add(out, c.skins.get(pet.type + f));
        }
        if (pet.skin != null) {
            String id = "PET_SKIN_" + pet.skin;
            String prefix = id + "_";
            add(out, c.skins.get(id));
            // A longer id that is itself a sold skin (ENDERMAN -> ENDERMAN_XENON) is a
            // different skin; only animation-only variants belong to this one.
            for (SkinEntry e : c.skins.values()) {
                if (e.id.startsWith(prefix) && e.kind != SkinEntry.Kind.PET_SKIN) add(out, e);
            }
        } else if (pet.skinned && pet.type != null) {
            // Chat showed a star but no menu has told us which skin: accept any for this type.
            String prefix = "PET_SKIN_" + pet.type;
            for (SkinEntry e : c.skins.values()) if (e.id.startsWith(prefix)) add(out, e);
        }
        return out;
    }

    private static void add(Set<String> out, SkinEntry e) {
        if (e == null) return;
        for (String t : e.textures) add(out, t);
    }

    private static void add(Set<String> out, String texture) {
        String h = hash(texture);
        if (h != null) out.add(h);
    }
}
