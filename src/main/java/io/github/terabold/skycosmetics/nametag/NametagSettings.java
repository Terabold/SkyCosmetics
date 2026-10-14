package io.github.terabold.skycosmetics.nametag;

import com.google.gson.*;
import io.github.terabold.skycosmetics.Settings;

/** Configuration work happens on load/save, never in an entity rendering hook. */
public final class NametagSettings {
    public enum Scope { SELF, PLAYERS, LIVING, ALL }
    public enum Text { ORIGINAL, SOLID, GRADIENT, CHROMA }
    public enum NameFont { SERVER, MINECRAFT, UNIFORM }
    public static final class Profile {
        public boolean enabled, hide, inventory, visibleOnly, shadow, fullBright;
        public boolean own = true, hidePlayersF1 = true, hideEntitiesF1 = true, hideArmorStandsF1 = true;
        public boolean scores = true, styleScores, background = true, rounded = true, gradientBackground, tabNames = true;
        public Scope scope = Scope.PLAYERS;
        public Text text = Text.ORIGINAL;
        public NameFont font = NameFont.SERVER;
        public double scale = 1, height = 0, crouchScale = 1, distance = 64, fadeStart = 48;
        public boolean fade;
        public double opacity = 1, paddingX = 1, paddingY = 0, radius = 0;
        public int color = 0xFFFFFFFF, colorEnd = 0xFF7ADFFF;
        public int backgroundColor = 0x50000000, backgroundEnd = 0x50253645;
        public String ownName = "";
    }
    public static Profile p = new Profile();
    public static long revision;
    public static boolean ownAnimated;
    private NametagSettings() {}
    public static void changed() { revision++; ownAnimated=io.github.terabold.skycosmetics.Names.animated(p.ownName); }
    public static void reset() { p = new Profile(); changed(); }
    public static void read(JsonElement value) {
        JsonObject o = Settings.obj(value);
        for (var f : Profile.class.getFields()) {
            JsonElement e = o.get(f.getName()); if (e == null || e.isJsonNull()) continue;
            try {
                Object old = f.get(p);
                if (old instanceof Boolean b) f.set(p, Settings.bool(e,b));
                else if (old instanceof Integer i) f.set(p,(int)Settings.num(e,i,Integer.MIN_VALUE,Integer.MAX_VALUE));
                else if (old instanceof Double d) f.set(p,Settings.num(e,d,-10,64));
                else if (old instanceof String s) f.set(p,Settings.str(e,s).substring(0,Math.min(128,Settings.str(e,s).length())));
                else if (old instanceof Enum<?> en) {
                    try { f.set(p, en.getDeclaringClass().getMethod("valueOf",String.class).invoke(null,Settings.str(e,en.name()))); }
                    catch (ReflectiveOperationException ignored) { }
                }
            } catch (IllegalAccessException ex) { throw new IllegalStateException(ex); }
        }
        p.scale=Math.clamp(p.scale,.25,3); p.height=Math.clamp(p.height,-10,10);
        p.crouchScale=Math.clamp(p.crouchScale,.25,1); p.distance=Math.clamp(p.distance,1,64);
        p.fadeStart=Math.clamp(p.fadeStart,0,64); p.opacity=Math.clamp(p.opacity,0,1);
        p.paddingX=Math.clamp(p.paddingX,0,10); p.paddingY=Math.clamp(p.paddingY,0,10); p.radius=Math.clamp(p.radius,0,10);
        if (!o.has("paddingVersion") && p.paddingX == 3 && p.paddingY == 2) { p.paddingX = 1; p.paddingY = 0; }
        changed();
    }
    public static JsonObject write() {
        JsonObject o=new JsonObject(); o.addProperty("paddingVersion", 1);
        for(var f:Profile.class.getFields()) try {
            Object v=f.get(p);
            if(v instanceof Number n)o.addProperty(f.getName(),n);
            else if(v instanceof Boolean b)o.addProperty(f.getName(),b);
            else if(v instanceof Enum<?> e)o.addProperty(f.getName(),e.name());
            else if(v instanceof String s)o.addProperty(f.getName(),s);
        }catch(IllegalAccessException ex){throw new IllegalStateException(ex);}
        return o;
    }
}
