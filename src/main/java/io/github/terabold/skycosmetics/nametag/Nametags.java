package io.github.terabold.skycosmetics.nametag;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.render.Chroma;
import io.github.terabold.skycosmetics.Names;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.util.LightCoordsUtil;
import java.util.Optional;
import java.util.Map;
import java.util.WeakHashMap;
import static io.github.terabold.skycosmetics.nametag.NametagSettings.*;

public final class Nametags {
    static boolean previewModel;
    static float previewFit = 1;
    // Weak entity keys release disconnected worlds; values never retain an entity or world.
    private static final Map<Entity,NametagState.Cache> CACHES=new WeakHashMap<>();
    public record DrawStyle(boolean shadow, int normalAlpha, int faintAlpha, int backgroundColor, Plate plate, float opacity, boolean seeThrough) {}
    private static final Map<Component, DrawStyle> DRAW = new java.util.IdentityHashMap<>();
    public static DrawStyle drawStyle(Component text) { return p.enabled ? DRAW.get(text) : null; }
    private Nametags() {}
    public static void init() { Settings.register("nametags",NametagSettings::read,NametagSettings::write); NametagSection.register(); NametagOverlay.init(); TabNames.register(); }
    public static int kind(Entity e) { return e instanceof Player?0:e instanceof ArmorStand?2:1; }
    public static boolean selected(Entity e) {
        if(!p.enabled)return false;
        return switch(p.scope) {
            case SELF -> e==Minecraft.getInstance().player;
            case PLAYERS -> e instanceof Player;
            case LIVING -> e instanceof LivingEntity && !(e instanceof ArmorStand);
            case ALL -> true;
        };
    }
    public static boolean hideHud(int kind) {
        return Minecraft.getInstance().options.hideGui && (kind==0?p.hidePlayersF1:kind==2?p.hideArmorStandsF1:p.hideEntitiesF1);
    }
    public static boolean ownVisible(Entity e) {
        var mc=Minecraft.getInstance();
        // The settings model uses the real label attachment, independently of world self-visibility.
        if (previewModel) return e == mc.player && p.enabled && !p.hide && p.opacity >= .02;
        return selected(e)&&e==mc.player&&p.own&&!p.hide&&!hideHud(0)&&!e.isInvisible()
            && (mc.screen instanceof InventoryScreen?p.inventory:!mc.options.getCameraType().isFirstPerson());
    }
    /** Every entity, every frame: a failure here leaves that label vanilla's and is reported, never thrown. */
    public static void extract(Entity e,EntityRenderState s) {
        var d=((NametagState)s).skycosmetics$tag();
        try { extract(e,d); }
        catch(RuntimeException ex) { d.selected=false; d.tab=null; io.github.terabold.skycosmetics.Io.failed("Reading a nametag",ex); }
    }
    private static void extract(Entity e,NametagState.Data d) {
        d.selected=selected(e); d.own=e==Minecraft.getInstance().player; d.kind=kind(e);
        d.inventory=d.own&&Minecraft.getInstance().screen instanceof InventoryScreen;
        d.preview=previewModel && d.own;
        d.previewFit=d.preview ? previewFit : 1;
        if(d.selected) {
            d.cache=CACHES.computeIfAbsent(e,key->new NametagState.Cache());
            d.cache.own=d.own;
            d.cache.username=e instanceof Player player ? player.getGameProfile().name() : e.getName().getString();
        }
        d.tab = null;
        if (d.selected && p.tabNames && e instanceof Player player) d.tab = TabNames.of(player.getUUID(), player.getGameProfile().name());
    }
    public static Component style(Component source, boolean own, String username) {
        // Scope controls layout/visibility, never permission to rename or recolor somebody else.
        if ((!own && p.font == NameFont.SERVER) || username == null || username.isEmpty()) return source;
        if (p.text == Text.ORIGINAL && p.ownName.isEmpty() && p.font == NameFont.SERVER) return source;
        source = readable(source);
        String plain = source.getString();
        int start = wordAt(plain, username, 0);
        if (start < 0) return source; // Unknown external label: preserve it instead of guessing.
        int end = start + username.length();
        return Component.empty().append(slice(source, 0, start)).append(name(slice(source, start, end), own, username, true))
            .append(slice(source, end, plain.length()));
    }

    /** The name put in place of {@code originalName}: yours with your display name and colors, in the chosen font when {@code withFont}. */
    private static Component name(Component originalName, boolean own, String username, boolean withFont) {
        Component base = !own || p.ownName.isEmpty() ? originalName : Names.live(p.ownName.replace("{name}", username));
        if (withFont && p.font != NameFont.SERVER) {
            FontDescription font = p.font == NameFont.MINECRAFT ? FontDescription.DEFAULT : UniformFont.VALUE;
            MutableComponent fonted = Component.empty();
            base.visit((style, text) -> {
                fonted.append(Component.literal(text).setStyle(style.withFont(font)));
                return Optional.empty();
            }, Style.EMPTY);
            base = fonted;
        }
        if(!own || p.text==Text.ORIGINAL)return base;
        MutableComponent result=Component.empty();int[] index={0};int length=Math.max(1,base.getString().codePointCount(0,base.getString().length())-1);
        int[] colors = {p.color, p.colorEnd};
        base.visit((style,text)->{
            text.codePoints().forEach(cp->{
                int i=index[0]++,color=switch(p.text) {
                    case SOLID -> p.color;
                    case GRADIENT -> Names.mix(colors,bend((float)i/length));
                    case CHROMA -> Chroma.nameColor(i);
                    default -> 0xFFFFFF;
                };
                result.append(Component.literal(new String(Character.toChars(cp))).setStyle(style.withColor(color&0xFFFFFF)));
            });return Optional.empty();
        },Style.EMPTY);
        return result;
    }

    /** A gradient position bent so the two colors meet at the balance point rather than in the middle. */
    public static float bend(float t) {
        double mid = Math.clamp(p.gradientMid, .1, .9);
        float at = Math.clamp(t, 0f, 1f);
        return Math.abs(mid - .5) < 1e-3 ? at : (float) Math.pow(at, Math.log(.5) / Math.log(mid));
    }

    /** Where {@code word} first stands on its own in {@code text} at or after {@code from} ("Steve" is not in "Steve2"); -1 if nowhere. */
    private static int wordAt(String text, String word, int from) {
        for (int at = text.indexOf(word, from); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            if ((at == 0 || !wordChar(text.charAt(at - 1))) && (end == text.length() || !wordChar(text.charAt(end)))) return at;
        }
        return -1;
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * A chat line or a tab-list name with your username in it: everywhere it stands as a word, your name as your
     * nametag has it (display name, colors), in the server's font, keeping the name's click and hover. Lines without
     * it are returned as they are, after one search. Never throws.
     */
    public static Component ownName(Component line) {
        if (line == null || !p.enabled || !p.chatTab || p.text == Text.ORIGINAL && p.ownName.isEmpty()) return line;
        try {
            String username = Minecraft.getInstance().getUser().getName();
            String plain = line.getString();
            if (username.isEmpty() || plain.length() > 4096 || plain.indexOf(username) < 0) return line;
            Component src = readable(line);
            plain = src.getString();
            MutableComponent out = Component.empty();
            int from = 0, found = 0;
            for (int at = wordAt(plain, username, 0); at >= 0 && found < 8; at = wordAt(plain, username, from), found++) {
                int end = at + username.length();
                out.append(slice(src, from, at)).append(withEvents(name(slice(src, at, end), true, username, false), styleAt(src, at)));
                from = end;
            }
            return found == 0 ? line : out.append(slice(src, from, plain.length()));
        } catch (RuntimeException e) {
            io.github.terabold.skycosmetics.Io.failed("Showing your name in chat or the tab list", e);
            return line;
        }
    }

    /** The style of the character at {@code index}. */
    private static Style styleAt(Component source, int index) {
        Style[] found = {Style.EMPTY};
        int[] offset = {0};
        source.visit((style, text) -> {
            if (index < offset[0] + text.length()) { found[0] = style; return Optional.of(Boolean.TRUE); }
            offset[0] += text.length();
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    /** {@code name} with the click, hover and insertion of the text it replaces (Hypixel's name links and cards). */
    private static Component withEvents(Component name, Style from) {
        if (from.getClickEvent() == null && from.getHoverEvent() == null && from.getInsertion() == null) return name;
        MutableComponent out = Component.empty();
        name.visit((style, text) -> {
            out.append(Component.literal(text).setStyle(style.withClickEvent(from.getClickEvent())
                .withHoverEvent(from.getHoverEvent()).withInsertion(from.getInsertion())));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
    /** Legacy section-sign codes may split a username inside a literal component. Keep its inherited font. */
    private static Component readable(Component source) {
        if (source.getString().indexOf('§') < 0) return source;
        MutableComponent out = Component.empty();
        net.minecraft.util.StringDecomposer.iterateFormatted(source, Style.EMPTY, (i, style, cp) -> {
            out.append(Component.literal(new String(Character.toChars(cp))).setStyle(style));
            return true;
        });
        return out;
    }
    private static final class UniformFont {
        static final FontDescription VALUE = new FontDescription.Resource(net.minecraft.resources.Identifier.withDefaultNamespace("uniform"));
    }
    /** Retain font, hover and decoration styles supplied by the server and other mods. */
    private static Component slice(Component source, int from, int to) {
        MutableComponent out = Component.empty(); int[] offset = {0};
        source.visit((style, text) -> {
            int begin = Math.max(0, from - offset[0]), end = Math.min(text.length(), to - offset[0]);
            if (begin < end) out.append(Component.literal(text.substring(begin, end)).setStyle(style));
            offset[0] += text.length(); return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
    public static Component styled(NametagState.Cache d,Component source) {
        long tick=d.own&&(p.text==Text.CHROMA||ownAnimated)?System.nanoTime()/50_000_000L:0;
        if(d.styled==null||!source.equals(d.source)||d.revision!=revision||d.tick!=tick||d.chromaVersion!=Chroma.version()) {
            d.source=source.copy();d.styled=style(source,d.own,d.username);d.revision=revision;d.tick=tick;d.chromaVersion=Chroma.version();
        }
        return d.styled;
    }
    /** Decorate the render-state copy, then let vanilla and every other mod's submission hooks run. */
    public static boolean prepare(EntityRenderState s, PoseStack pose, CameraRenderState camera) {
        if(!p.enabled)return true;
        var d=((NametagState)s).skycosmetics$tag(); if(!d.selected)return true;
        if(p.hide||hideHud(d.kind)||s.distanceToCameraSq>p.distance*p.distance)return false;
        if(s.nameTagAttachment==null)return true;
        float alpha=(float)p.opacity;
        if(p.fade)alpha*=1-(float)Math.clamp((Math.sqrt(s.distanceToCameraSq)-Math.min(p.fadeStart,p.distance-1))/Math.max(1,p.distance-p.fadeStart),0,1);
        if(alpha<.02f)return false;
        // Prepare before touching the pose stack so a malformed external label can fall back cleanly.
        if(s.nameTag!=null&&s.nameTag.getString().length()>512)return true;
        // A player's tab-list name when it names them: servers often show more there (level, rank color, icons).
        Component source = s.nameTag != null && d.tab != null && d.tab.getString().length() <= 256
            && TabNames.names(d.tab.getString(), d.cache.username) ? d.tab : s.nameTag;
        Component name=source==null?null:styled(d.cache,source);
        // Real sneaking, read before "visible surfaces only" borrows vanilla's sneaking path below.
        boolean sneaking = s.isDiscrete;
        if (DRAW.size() >= 1024) DRAW.clear();
        if (name != null) mark(s, name, alpha, d.cache, sneaking);
        if (s.scoreText != null && p.scores) {
            if (d.cache.score == null) d.cache.score = new NametagState.Cache();
            mark(s, s.scoreText, alpha, d.cache.score, sneaking);
        }
        s.nameTag = name;
        if (!p.scores) s.scoreText = null;
        if (p.visibleOnly) s.isDiscrete = true;
        if (p.fullBright || d.preview) s.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        var a = s.nameTagAttachment;
        float scale = (float)((d.inventory ? Math.min(1,p.scale) : p.scale) * (sneaking ? p.crouchScale : 1)) * d.previewFit;
        pose.translate(a.x, a.y + .5 + (d.inventory ? -.3 : p.height), a.z);
        pose.mulPose(camera.orientation);
        pose.scale(scale, scale, scale);
        pose.mulPose(new org.joml.Quaternionf(camera.orientation).conjugate());
        pose.translate(-a.x, -a.y - .5, -a.z);
        return true;
    }
    private static void mark(EntityRenderState s, Component text, float alpha, NametagState.Cache cache, boolean sneaking) {
        boolean preview = ((NametagState)s).skycosmetics$tag().preview;
        boolean crouched = sneaking && !preview;
        if (crouched) alpha *= 128f / 255f;
        int background = p.background ? (int)((p.backgroundColor >>> 24) * alpha) << 24 | p.backgroundColor & 0xFFFFFF : 0;
        boolean custom = p.background;
        if (custom && (cache.plate == null || cache.plateRevision != revision || !text.equals(cache.plateText))) {
            int width = Minecraft.getInstance().font.width(text);
            if (cache.plate == null || cache.plateRevision != revision || cache.plateWidth != width) cache.plate = new Plate(width);
            cache.plateWidth = width; cache.plateRevision = revision; cache.plateText = text;
        }
        // Native crouching has no shadow. The GUI sample has no occluders: neither native pass should dim its
        // configured text opacity. Sneaking hides a label behind walls in vanilla and must here too: showing it
        // through walls would reveal a hiding player.
        DRAW.put(text, new DrawStyle(p.shadow && !crouched, (int)(alpha*255), (int)(alpha*(preview || crouched ? 255 : 128)), background,
            custom ? cache.plate : null, alpha, !p.visibleOnly && !sneaking));
    }
}
