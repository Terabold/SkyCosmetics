package io.github.terabold.skycosmetics.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.SkyCosmetics;
import io.github.terabold.skycosmetics.mixin.BufferSourceAccessor;
import io.github.terabold.skycosmetics.mixin.ItemFeatureRendererAccessor;
import io.github.terabold.skycosmetics.mixin.RenderTypeAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-item enchant glint colour, speed and strength.
 *
 * Vanilla draws every glint with a few fixed render types: one texture, one
 * speed and strength from Options. A look with a glint colour, speed or
 * strength gets its own copies of those types - same pipeline, a tinted or
 * brightened copy of the glint texture and/or a texture transform running at
 * the look's speed. Speed and strength are multiples of Minecraft's Glint Speed
 * and Glint Strength settings. Items: the layer's glint is submitted as custom
 * geometry with our type and vanilla's is switched off for that layer.
 * Armour: the armour layer asks for our type instead.
 *
 * Our types are fixed buffers of the main buffer source, like vanilla's glint
 * types: glint tests depth EQUAL, so it must be drawn after the surface under
 * it, and fixed buffers are flushed after the shared one in every endBatch
 * (vanilla's and ImmediatelyFast's).
 *
 * Strength is baked into the texture copy: the glint shader adds each texel's
 * colour times itself (blend SRC_COLOR, ONE) times the Glint Strength setting,
 * so scaling the texels by a strength is exactly that setting times the
 * strength, until a texel reaches full brightness (then its hue is kept). To
 * brighten a pack's glint that is all but invisible, Minecraft's own texture is
 * brightened instead, since there is nothing to scale.
 *
 * Cost: one null check per item layer without a style. A style's type is made
 * once; a texture copy once per colour and strength, shared by every speed and
 * kind that samples the same glint texture. At most {@link #CAP} types are kept (never
 * one drawn in the last second, never more than twice that), released at the
 * next tick with their textures, and all are dropped on resource reload. Items
 * drawn by special renderers (shields, tridents) keep vanilla's glint; skulls
 * have none.
 */
public final class Glints {
    private static final int CAP = 32;
    private static final Supplier<GpuSampler> SAMPLER = () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
    private static final QuadInstance QUAD = new QuadInstance();

    public enum Kind {
        ITEM(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, "item", TextureTransform.GLINT_TEXTURING, 8f),
        ITEM_TRANSLUCENT(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, "item", TextureTransform.GLINT_TEXTURING, 8f),
        ARMOR(ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR, "armor", TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING, 0.16f);

        final Identifier texture;
        /** Names the tinted copies; kinds with the same texture share them. */
        final String textureName;
        final TextureTransform texturing;
        final float scale;

        Kind(Identifier texture, String textureName, TextureTransform texturing, float scale) {
            this.texture = texture;
            this.textureName = textureName;
            this.texturing = texturing;
            this.scale = scale;
        }
    }

    /**
     * A look's glint colour (rgb, -1 = the glint's own), speed and strength. Speed is a multiple of the
     * player's Glint Speed setting (Accessibility), so 1 looks like Minecraft's own and a setting of 0
     * keeps every glint still; 0 = no speed of its own. Strength is a multiple of the Glint Strength
     * setting; 1 = as drawn. Interned.
     */
    public static final class Style {
        public final int rgb;
        public final float speed;
        public final float strength;
        private final Variant[] variants = new Variant[Kind.values().length];

        private Style(int rgb, float speed, float strength) {
            this.rgb = rgb;
            this.speed = speed;
            this.strength = strength;
        }

        @Override
        public String toString() {
            return (rgb < 0 ? "default" : String.format(Locale.ROOT, "#%06X", rgb)) + (speed > 0 ? " x" + speed : "")
                + (strength != 1 ? " strength" + strength : "");
        }
    }

    /** Duck on ItemStackRenderState.LayerRenderState: the style its stack was drawn with. */
    public interface Layer {
        void skycosmetics$glint(Style style, ItemDisplayContext context);
    }

    /** One style's render type for one kind, with its texture copy (null when it uses vanilla's). */
    private static final class Variant {
        final Style style;
        final Kind kind;
        final RenderType type;
        final Tint tint;
        long usedAt;

        Variant(Style style, Kind kind, RenderType type, Tint tint) {
            this.style = style;
            this.kind = kind;
            this.type = type;
            this.tint = tint;
        }
    }

    /** A tinted and/or brightened copy of one glint texture, shared by every variant (speed, kind) drawing it. */
    private static final class Tint {
        final String key;
        final Identifier id;
        int users;

        Tint(String key, Identifier id) {
            this.key = key;
            this.id = id;
        }
    }

    private static final Map<Long, Style> STYLES = new ConcurrentHashMap<>();
    private static final List<Variant> LIVE = new ArrayList<>();
    private static final List<Variant> RELEASE = new ArrayList<>();
    private static final Map<String, Tint> TINTS = new HashMap<>();
    private static final Map<Identifier, Base> BASE = new HashMap<>();
    private static Style current;
    private static boolean failed;
    private static int itemDraws;
    private static int armourUses;

    private Glints() {}

    public static void init() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, "glint"), (ResourceManagerReloadListener) rm -> reset());
        // Between frames: nothing evicted can still be waiting in a buffer.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> releasePending());
    }

    /** The style for a glint colour and speed at the glint's own strength. */
    public static Style style(String color, Float speed) {
        return style(color, speed, null);
    }

    /**
     * The style for a look's glintColor ("#RRGGBB"), glintSpeed and glintStrength, or null when all
     * three are Minecraft's (a strength of 1 included).
     */
    public static Style style(String color, Float speed, Float strength) {
        int rgb = -1;
        if (color != null && color.length() == 7 && color.charAt(0) == '#') {
            try {
                rgb = Integer.parseInt(color.substring(1), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        Float clamped = speed == null ? null : Looks.clampSpeed(speed);
        Float bright = strength == null ? null : Looks.clampStrength(strength);
        // 0.05 steps: a slider can't make a new render type per pixel dragged.
        int sp = clamped == null ? 0 : Math.max(2, Math.round(clamped * 20));
        int st = bright == null ? 20 : Math.round(bright * 20);
        if (rgb < 0 && sp == 0 && st == 20) return null;
        // Interning only saves duplicates; a colour picker dragged for an hour must not grow it forever.
        if (STYLES.size() > 1024) STYLES.clear();
        int r = rgb;
        return STYLES.computeIfAbsent(((long) (rgb + 1) << 16) | (sp << 8) | st, k -> new Style(r, sp / 20f, st / 20f));
    }

    // ------------------------------------------------------ item models ---

    /** While ItemModelResolver builds a stack's layers: tags them, and keys the GUI item atlas on the style. */
    public static Style enter(ItemStackRenderState output, Style style) {
        Style outer = current;
        current = style;
        if (style != null) output.appendModelIdentityElement(style);
        return outer;
    }

    public static void leave(Style outer) {
        current = outer;
    }

    public static Style current() {
        return current;
    }

    /**
     * Submits a layer's glint in the style's colour, speed and strength. Returns
     * false (draw vanilla's) when there is nothing to draw or the types can't be made.
     */
    public static boolean submit(PoseStack pose, SubmitNodeCollector out, Style s, List<BakedQuad> quads,
                                 ItemStackRenderState.FoilType foil, ItemDisplayContext ctx) {
        if (quads.isEmpty()) return false;
        boolean solid = false, translucent = false;
        for (BakedQuad q : quads) {
            if (translucent(q)) translucent = true;
            else solid = true;
        }
        RenderType a = solid ? type(s, Kind.ITEM) : null;
        RenderType b = translucent ? type(s, Kind.ITEM_TRANSLUCENT) : null;
        if (solid && a == null || translucent && b == null) return false;
        boolean special = foil == ItemStackRenderState.FoilType.SPECIAL;
        ItemDisplayContext c = ctx != null ? ctx : ItemDisplayContext.NONE;
        if (a != null) out.submitCustomGeometry(pose, a, new Quads(quads, special, c, translucent ? Boolean.FALSE : null));
        if (b != null) out.submitCustomGeometry(pose, b, new Quads(quads, special, c, solid ? Boolean.TRUE : null));
        return true;
    }

    /** Vanilla's choice between glint and glintTranslucent for this quad (Fabulous item targets). */
    private static boolean translucent(BakedQuad q) {
        return ItemFeatureRenderer.getFoilRenderType(q.materialInfo().itemRenderType(), true) != RenderTypes.glint();
    }

    /** The glint half of ItemFeatureRenderer.renderItem; {@code only} picks solid/translucent quads when mixed. */
    private record Quads(List<BakedQuad> quads, boolean special, ItemDisplayContext ctx, Boolean only)
        implements SubmitNodeCollector.CustomGeometryRenderer {
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            try {
                VertexConsumer c = special
                    ? new SheetedDecalTextureGenerator(consumer, ItemFeatureRendererAccessor.skycosmetics$decalPose(ctx, pose), 1 / 128f)
                    : consumer;
                for (BakedQuad q : quads) {
                    if (only == null || translucent(q) == only) c.putBakedQuad(pose, q, QUAD);
                }
                itemDraws++;
            } catch (RuntimeException e) {
                Io.failed("Drawing an item glint", e);
            }
        }
    }

    // ---------------------------------------------------------- armour ---

    /** The armour glint type for a worn piece's style, or null for vanilla's. */
    public static RenderType armour(Style s) {
        if (s == null) return null;
        RenderType t = type(s, Kind.ARMOR);
        if (t != null) armourUses++;
        return t;
    }

    // ----------------------------------------------------------- types ---

    private static RenderType type(Style s, Kind k) {
        Variant v = s.variants[k.ordinal()];
        if (v == null) {
            if (failed || !RenderSystem.isOnRenderThread()) return null;
            v = create(s, k);
            if (v == null) return null;
        }
        v.usedAt = Util.getMillis();
        return v.type;
    }

    private static Variant create(Style s, Kind k) {
        evict();
        // Everything kept was drawn this second: draw Minecraft's glint rather than grow without bound.
        if (LIVE.size() >= 2 * CAP) return null;
        Tint tint = null;
        try {
            if (s.rgb >= 0 || s.strength != 1) tint = tint(k, s);
            RenderSetup.RenderSetupBuilder b = RenderSetup.builder(RenderPipelines.GLINT)
                .withTexture("Sampler0", tint != null ? tint.id : k.texture, SAMPLER)
                .setTextureTransform(s.speed > 0 ? texturing(k.scale, s.speed) : k.texturing);
            if (k == Kind.ITEM_TRANSLUCENT) b.setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET);
            if (k == Kind.ARMOR) b.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING);
            RenderType type = RenderTypeAccessor.skycosmetics$create("skycosmetics_glint_" + k.name().toLowerCase(Locale.ROOT)
                + "_" + s.toString().replace(' ', '_'), b.createRenderSetup());
            fixedBuffers().putIfAbsent(type, new ByteBufferBuilder(type.bufferSize()));
            Variant v = new Variant(s, k, type, tint);
            s.variants[k.ordinal()] = v;
            LIVE.add(v);
            return v;
        } catch (Exception e) {
            // Never break rendering: from now on every glint is Minecraft's own, until a resource reload.
            failed = true;
            if (tint != null) unuse(tint);
            SkyCosmetics.LOG.error("Glint styles disabled: could not build glint {} for {}", k, s, e);
            return null;
        }
    }

    /** The kind's glint texture in the style's colour and strength, made (one upload) on first use. */
    private static Tint tint(Kind k, Style s) throws IOException {
        int rgb = s.rgb;
        float strength = s.strength;
        String key = "glint/" + k.textureName + (rgb >= 0 ? String.format(Locale.ROOT, "_%06x", rgb) : "")
            + (strength != 1 ? "_strength" + Math.round(strength * 100) : "");
        Tint t = TINTS.get(key);
        if (t == null) {
            Minecraft mc = Minecraft.getInstance();
            NativeImage base = base(mc, k.texture, strength > 1);
            Identifier id = Identifier.fromNamespaceAndPath(SkyCosmetics.MOD_ID, key);
            mc.getTextureManager().register(id, new DynamicTexture(id::toString, base.mappedCopy(argb -> tint(argb, rgb, strength))));
            t = new Tint(key, id);
            TINTS.put(key, t);
        }
        t.users++;
        return t;
    }

    private static void unuse(Tint t) {
        if (--t.users > 0) return;
        TINTS.remove(t.key);
        Minecraft.getInstance().getTextureManager().release(t.id);
    }

    /** Vanilla's glint texturing (TextureTransform.setupGlintTexturing), its Glint Speed setting times the look's speed. */
    private static TextureTransform texturing(float scale, float speed) {
        return new TextureTransform("skycosmetics_glint_texturing", () -> {
            double setting = Minecraft.getInstance().gameRenderer.getGameRenderState().optionsRenderState.glintSpeed;
            long t = (long) (Util.getMillis() * setting * speed * 8.0);
            float u = (t % 110000L) / 110000f;
            float v = (t % 30000L) / 30000f;
            return new Matrix4f().translation(-u, v, 0f).rotateZ((float) Math.PI / 18).scale(scale);
        });
    }

    /**
     * One texel: its hue swapped for the look's colour ({@code rgb} -1 keeps its own) at the same
     * brightness, then times {@code strength}, capped where the brightest channel is full so the hue
     * stays. Alpha is kept: the shader drops texels under 0.1 either way.
     */
    static int tint(int argb, int rgb, float strength) {
        int r = ARGB.red(argb), g = ARGB.green(argb), b = ARGB.blue(argb);
        if (rgb >= 0) {
            int v = Math.max(r, Math.max(g, b));
            r = ARGB.red(rgb) * v / 255;
            g = ARGB.green(rgb) * v / 255;
            b = ARGB.blue(rgb) * v / 255;
        }
        if (strength != 1) {
            int max = Math.max(r, Math.max(g, b));
            float f = max == 0 ? 0 : Math.min(strength, 255f / max);
            r = Math.round(r * f);
            g = Math.round(g * f);
            b = Math.round(b * f);
        }
        return ARGB.color(ARGB.alpha(argb), r, g, b);
    }

    /**
     * A glint texture as the current resource packs have it, read once per reload. To brighten
     * ({@code brighten}), Minecraft's own when the pack's adds under 1/32 of its light: a pack that
     * hides the glint leaves nothing to scale.
     */
    private static NativeImage base(Minecraft mc, Identifier id, boolean brighten) throws IOException {
        Base b = BASE.get(id);
        if (b == null) {
            try (InputStream in = mc.getResourceManager().open(id)) {
                b = new Base(NativeImage.read(in));
            }
            BASE.put(id, b);
        }
        if (!brighten) return b.pack;
        if (b.bright == null) b.bright = brightBase(mc, id, b.pack);
        return b.bright;
    }

    private static NativeImage brightBase(Minecraft mc, Identifier id, NativeImage pack) {
        try {
            IoSupplier<InputStream> own = mc.getVanillaPackResources().getResource(PackType.CLIENT_RESOURCES, id);
            if (own == null) return pack;
            NativeImage img;
            try (InputStream in = own.get()) {
                img = NativeImage.read(in);
            }
            if (light(pack) * 32 < light(img)) return img;
            img.close();
        } catch (IOException | RuntimeException e) {
            SkyCosmetics.LOG.warn("Could not read Minecraft's own glint {}: {}", id, e.toString());
        }
        return pack;
    }

    /** The light a glint texture adds per texel: the blend squares each colour, and alpha under 0.1 is dropped. */
    private static double light(NativeImage img) {
        double sum = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getPixel(x, y);
                if (ARGB.alpha(p) < 26) continue;
                sum += ARGB.red(p) * ARGB.red(p) + ARGB.green(p) * ARGB.green(p) + ARGB.blue(p) * ARGB.blue(p);
            }
        }
        return sum / Math.max(1, img.getWidth() * img.getHeight());
    }

    /** One glint texture as the packs have it, and the one strengths above 1 brighten (see {@link #base}). */
    private static final class Base {
        final NativeImage pack;
        NativeImage bright;

        Base(NativeImage pack) {
            this.pack = pack;
        }

        void close() {
            if (bright != null && bright != pack) bright.close();
            pack.close();
        }
    }

    private static SequencedMap<RenderType, ByteBufferBuilder> fixedBuffers() {
        return ((BufferSourceAccessor) Minecraft.getInstance().renderBuffers().bufferSource()).skycosmetics$fixedBuffers();
    }

    // -------------------------------------------------------- lifetime ---

    /** Over the cap: drop the least recently drawn variants, but none drawn in the last second. */
    private static void evict() {
        long now = Util.getMillis();
        while (LIVE.size() >= CAP) {
            Variant oldest = null;
            for (Variant v : LIVE) {
                if (now - v.usedAt > 1000 && (oldest == null || v.usedAt < oldest.usedAt)) oldest = v;
            }
            if (oldest == null) return;
            retire(oldest);
        }
    }

    private static void retire(Variant v) {
        LIVE.remove(v);
        if (v.style.variants[v.kind.ordinal()] == v) v.style.variants[v.kind.ordinal()] = null;
        RELEASE.add(v);
    }

    private static void releasePending() {
        if (RELEASE.isEmpty()) return;
        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        for (Variant v : RELEASE) {
            try {
                source.endBatch(v.type);
                ByteBufferBuilder buf = fixedBuffers().remove(v.type);
                if (buf != null) buf.close();
                if (v.tint != null) unuse(v.tint);
            } catch (RuntimeException e) {
                SkyCosmetics.LOG.warn("Could not release glint {}: {}", v.type, e.toString());
            }
        }
        RELEASE.clear();
    }

    /** Resource reload: the glint textures may have changed; rebuild everything on next use. */
    private static void reset() {
        for (Variant v : new ArrayList<>(LIVE)) retire(v);
        releasePending();
        BASE.values().forEach(Base::close);
        BASE.clear();
        failed = false;
    }

    // ----------------------------------------------------------- tests ---

    /**
     * Item glints drawn with a style's type and armour pieces given one (since
     * start), live variants, our types among the fixed buffers, and texture copies.
     */
    public static int[] stats() {
        int fixed = 0;
        for (RenderType t : fixedBuffers().keySet()) if (t.toString().contains("skycosmetics_glint_")) fixed++;
        return new int[]{itemDraws, armourUses, LIVE.size(), fixed, TINTS.size()};
    }

    /** The texture copy a style uses for {@code kind}, or null (not made yet, or Minecraft's own). */
    public static Identifier texture(Style s, Kind kind) {
        Variant v = s == null ? null : s.variants[kind.ordinal()];
        return v == null || v.tint == null ? null : v.tint.id;
    }

    /** The render type a style currently uses for {@code kind}, or null if none is made yet. */
    public static RenderType typeOf(Style s, Kind kind) {
        Variant v = s == null ? null : s.variants[kind.ordinal()];
        return v == null ? null : v.type;
    }
}
