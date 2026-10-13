package io.github.terabold.skycosmetics.tooltip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.terabold.skycosmetics.Settings;

import java.util.Locale;

/** One bounded settings object; existing tooltip content is never rewritten. */
public final class TooltipSettings {
    public enum Background { SOLID, GRADIENT, PATTERN, IMAGE }
    public enum Direction { HORIZONTAL, VERTICAL, DIAGONAL }
    public enum Pattern { STRIPES, DOTS, GRID }
    public enum ImageFit { COVER, CONTAIN, STRETCH, TILE }
    public enum Scroll { OFF, NORMAL, SMOOTH }

    public static boolean enabled, customBackground, shiftToScroll, autoFit, rarityBorder = true;
    public static boolean customBorder;
    public static int size = 100, maxHeight = 90, maxWidth = 100, padding = 5, radius = 4, borderWidth = 1;
    public static int color = 0xF0181820, colorEnd = 0xF0303048, border = 0xFF22D3EE;
    public static int patternSize = 8, imageOpacity = 40, scrollStep = 30;
    public static Background background = Background.SOLID;
    public static Direction direction = Direction.VERTICAL;
    public static Pattern pattern = Pattern.STRIPES;
    public static ImageFit imageFit = ImageFit.COVER;
    public static Scroll scroll = Scroll.SMOOTH;

    private TooltipSettings() {}

    public static void init() {
        Settings.register("tooltips", TooltipSettings::read, TooltipSettings::write);
    }

    public static void reset() {
        read(defaults());
        TooltipRenderer.reset();
    }

    private static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", false);
        o.addProperty("customBackground", false);
        o.addProperty("customBorder", false);
        o.addProperty("shiftToScroll", false);
        o.addProperty("autoFit", false);
        o.addProperty("rarityBorder", true);
        o.addProperty("size", 100);
        o.addProperty("maxHeight", 90);
        o.addProperty("maxWidth", 100);
        o.addProperty("padding", 5);
        o.addProperty("radius", 4);
        o.addProperty("borderWidth", 1);
        o.addProperty("color", "F0181820");
        o.addProperty("colorEnd", "F0303048");
        o.addProperty("border", "FF22D3EE");
        o.addProperty("patternSize", 8);
        o.addProperty("imageOpacity", 40);
        o.addProperty("scrollStep", 30);
        o.addProperty("background", "SOLID");
        o.addProperty("direction", "VERTICAL");
        o.addProperty("pattern", "STRIPES");
        o.addProperty("imageFit", "COVER");
        o.addProperty("scroll", "SMOOTH");
        return o;
    }

    public static void read(JsonElement value) {
        JsonObject o = Settings.obj(value);
        enabled = Settings.bool(o.get("enabled"), enabled);
        customBackground = Settings.bool(o.get("customBackground"), customBackground);
        customBorder = Settings.bool(o.get("customBorder"), customBackground);
        shiftToScroll = Settings.bool(o.get("shiftToScroll"), shiftToScroll);
        if (!o.has("layoutVersion")) shiftToScroll = false;
        autoFit = Settings.bool(o.get("autoFit"), autoFit);
        rarityBorder = Settings.bool(o.get("rarityBorder"), rarityBorder);
        size = (int) Settings.num(o.get("size"), size, 50, 200);
        maxHeight = (int) Settings.num(o.get("maxHeight"), maxHeight, 30, 100);
        maxWidth = (int) Settings.num(o.get("maxWidth"), maxWidth, 30, 100);
        padding = (int) Settings.num(o.get("padding"), padding, 4, 12);
        radius = (int) Settings.num(o.get("radius"), radius, 0, 12);
        borderWidth = (int) Settings.num(o.get("borderWidth"), borderWidth, 0, 3);
        patternSize = (int) Settings.num(o.get("patternSize"), patternSize, 4, 32);
        imageOpacity = (int) Settings.num(o.get("imageOpacity"), imageOpacity, 0, 100);
        scrollStep = (int) Settings.num(o.get("scrollStep"), scrollStep, 5, 100);
        color = argb(o.get("color"), color);
        colorEnd = argb(o.get("colorEnd"), colorEnd);
        border = argb(o.get("border"), border);
        background = choice(Background.class, o.get("background"), background);
        direction = choice(Direction.class, o.get("direction"), direction);
        pattern = choice(Pattern.class, o.get("pattern"), pattern);
        imageFit = choice(ImageFit.class, o.get("imageFit"), imageFit);
        scroll = choice(Scroll.class, o.get("scroll"), scroll);
    }

    public static JsonObject write() {
        JsonObject o = new JsonObject();
        o.addProperty("layoutVersion", 2);
        o.addProperty("enabled", enabled);
        o.addProperty("customBackground", customBackground);
        o.addProperty("customBorder", customBorder);
        o.addProperty("shiftToScroll", shiftToScroll);
        o.addProperty("autoFit", autoFit);
        o.addProperty("rarityBorder", rarityBorder);
        o.addProperty("size", size);
        o.addProperty("maxHeight", maxHeight);
        o.addProperty("maxWidth", maxWidth);
        o.addProperty("padding", padding);
        o.addProperty("radius", radius);
        o.addProperty("borderWidth", borderWidth);
        o.addProperty("color", hex(color));
        o.addProperty("colorEnd", hex(colorEnd));
        o.addProperty("border", hex(border));
        o.addProperty("patternSize", patternSize);
        o.addProperty("imageOpacity", imageOpacity);
        o.addProperty("scrollStep", scrollStep);
        o.addProperty("background", background.name());
        o.addProperty("direction", direction.name());
        o.addProperty("pattern", pattern.name());
        o.addProperty("imageFit", imageFit.name());
        o.addProperty("scroll", scroll.name());
        return o;
    }

    private static int argb(JsonElement v, int current) {
        String s = Settings.str(v, "").replace("#", "");
        if (!s.matches("[0-9a-fA-F]{8}")) return current;
        return (int) Long.parseLong(s, 16);
    }
    /** Experimental artwork stays readable in old configs, but is paused for the first release. */
    public static Background effectiveBackground() {
        return background == Background.PATTERN || background == Background.IMAGE ? Background.SOLID : background;
    }

    private static String hex(int c) {
        return String.format(Locale.ROOT, "%08X", c);
    }

    private static <T extends Enum<T>> T choice(Class<T> type, JsonElement v, T current) {
        try {
            return Enum.valueOf(type, Settings.str(v, current.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return current;
        }
    }
}
