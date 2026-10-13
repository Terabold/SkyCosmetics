package io.github.terabold.skycosmetics.hand;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Share codes: a Hand setup as one line of text. The format is this mod's own and versioned by its prefix, so a
 * code keeps working across updates; a pasted code is size-capped and read like a hand-edited settings file.
 */
public final class HandCode {
    static final String CODE_PREFIX = "skyhand1:";
    private static final int MAX_CODE = 32 * 1024, MAX_JSON = 256 * 1024;

    private HandCode() {}

    /** The setup as a line of text to paste anywhere: a prefix, then the compressed JSON in URL-safe Base64. */
    public static String code(JsonObject setup) {
        byte[] json = setup.toString().getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(json);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } finally {
            d.end();
        }
    }

    /**
     * A setup from a pasted code, or null when it isn't one. Codes come from other players, so the size is capped
     * before and after inflating, and the setup is read like any settings file (clamped, rules capped).
     */
    public static JsonObject decode(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (!t.startsWith(CODE_PREFIX) || t.length() > MAX_CODE) return null;
        Inflater inf = new Inflater();
        try {
            byte[] raw = Base64.getUrlDecoder().decode(t.substring(CODE_PREFIX.length()).replaceAll("\\s", ""));
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && !inf.finished()) return null;
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON) return null;
            }
            var bounded = new com.google.gson.stream.JsonReader(new java.io.StringReader(out.toString(StandardCharsets.UTF_8)));
            bounded.setNestingLimit(24);
            JsonElement e = JsonParser.parseReader(bounded);
            return e instanceof JsonObject o ? o : null;
        } catch (IllegalArgumentException | DataFormatException | com.google.gson.JsonParseException e) {
            return null;
        } catch (RuntimeException e) {
            Io.failed("Reading a hand share code", e);
            return null;
        } finally {
            inf.end();
        }
    }
}
