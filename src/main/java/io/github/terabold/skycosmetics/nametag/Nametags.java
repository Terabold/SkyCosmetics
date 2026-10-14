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
    public static void init() { Settings.register("nametags",NametagSettings::read,NametagSettings::write); NametagSection.register(); NametagOverlay.init(); }
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
    public static void extract(Entity e,EntityRenderState s) {
        var d=((NametagState)s).skycosmetics$tag();
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
        if (d.selected && p.tabNames && e instanceof Player player) {
            var connection = Minecraft.getInstance().getConnection();
            var info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
            d.tab = info == null ? null : info.getTabListDisplayName();
        }
    }
    public static Component style(Component source, boolean own, String username) {
        // Scope controls layout/visibility, never permission to rename or recolor somebody else.
        if ((!own && p.font == NameFont.SERVER) || username == null || username.isEmpty()) return source;
        if (p.text == Text.ORIGINAL && p.ownName.isEmpty() && p.font == NameFont.SERVER) return source;
        source = readable(source);
        String plain = source.getString();
        int start = plain.indexOf(username);
        if (start < 0) return source; // Unknown external label: preserve it instead of guessing.
        int end = start + username.length();
        if (start > 0 && Character.isLetterOrDigit(plain.charAt(start - 1))
            || end < plain.length() && Character.isLetterOrDigit(plain.charAt(end))) return source;
        Component originalName = slice(source, start, end);
        Component base = !own || p.ownName.isEmpty() ? originalName : Names.live(p.ownName.replace("{name}", username));
        if (p.font != NameFont.SERVER) {
            FontDescription font = p.font == NameFont.MINECRAFT ? FontDescription.DEFAULT : UniformFont.VALUE;
            MutableComponent withFont = Component.empty();
            base.visit((style, text) -> {
                withFont.append(Component.literal(text).setStyle(style.withFont(font)));
                return Optional.empty();
            }, Style.EMPTY);
            base = withFont;
        }
        Component prefix = slice(source, 0, start), suffix = slice(source, end, plain.length());
        if(!own || p.text==Text.ORIGINAL)return Component.empty().append(prefix).append(base).append(suffix);
        MutableComponent result=Component.empty();int[] index={0};int length=Math.max(1,base.getString().codePointCount(0,base.getString().length())-1);
        int[] colors = {p.color, p.colorEnd};
        base.visit((style,text)->{
            text.codePoints().forEach(cp->{
                int i=index[0]++,color=switch(p.text) {
                    case SOLID -> p.color;
                    case GRADIENT -> Names.mix(colors,(float)i/length);
                    case CHROMA -> Chroma.nameColor(i);
                    default -> 0xFFFFFF;
                };
                result.append(Component.literal(new String(Character.toChars(cp))).setStyle(style.withColor(color&0xFFFFFF)));
            });return Optional.empty();
        },Style.EMPTY);
        return Component.empty().append(prefix).append(result).append(suffix);
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
            && d.tab.getString().contains(d.cache.username) ? d.tab : s.nameTag;
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
