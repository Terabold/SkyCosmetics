package io.github.terabold.skycosmetics.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Pure validation of catalog textures and legacy saved skin data; no observation or recording. */
public final class SkinData {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_;:.-]{1,128}");
    private static final Pattern SKIN_URL = Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{32,128}");
    private static final int MAX_TEXTURE = 2048;
    private static final int MAX_NAME = 64;

    private SkinData() {}

    /**
     * The hash at the end of a head texture's skin URL ({@code textures.minecraft.net/texture/<hash>}), lower case;
     * null if there is none. Raw values differ between the repo and the server (timestamps, profile ids); the hash
     * names the picture.
     */
    public static String textureHash(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXTURE) return null;
        try {
            String json = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            int i = json.indexOf("/texture/");
            if (i < 0) return null;
            int start = i + 9, end = start;
            while (end < json.length() && Character.digit(json.charAt(end), 16) >= 0) end++;
            return end - start >= 32 ? json.substring(start, end).toLowerCase(java.util.Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A bounded SkyBlock-shaped catalog id. */
    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * True for a head texture value whose skin is a Mojang texture hash
     * ({@code textures.minecraft.net/texture/<hex>}), at most 2048 characters:
     * the only kind of texture accepted from catalog and saved skin data.
     */
    public static boolean mojangTexture(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXTURE) return false;
        try {
            String json = new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            if (!(JsonParser.parseString(json) instanceof JsonObject o && o.get("textures") instanceof JsonObject t
                && t.get("SKIN") instanceof JsonObject skin)) return false;
            JsonElement url = skin.get("url");
            return url != null && url.isJsonPrimitive() && SKIN_URL.matcher(url.getAsString()).matches();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A saved skin name: at most 64 characters. */
    static String clip(String name) {
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }

}
