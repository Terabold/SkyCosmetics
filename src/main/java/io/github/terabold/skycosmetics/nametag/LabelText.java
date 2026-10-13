package io.github.terabold.skycosmetics.nametag;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.terabold.skycosmetics.mixin.RenderSetupAccessor;
import io.github.terabold.skycosmetics.mixin.RenderTypeAccessor;
import io.github.terabold.skycosmetics.mixin.TextureBindingAccessor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Translucent label text that blends each pixel once. Bold draws every glyph twice, italic glyphs overlap their
 * neighbours and shadows sit under the text, so a half-transparent name normally comes out patchy: wherever two
 * quads overlap it is blended twice. Here a depth-only pass first marks the glyphs, then a color pass drawn a hair
 * nearer passes the depth test once per pixel, so a gradient keeps one even opacity.
 */
final class LabelText {
    enum Pass { PRIME_SEE_THROUGH, PRIME_NORMAL, PAINT }

    private record Pipelines(RenderPipeline primeSeeThrough, RenderPipeline primeNormal, RenderPipeline paint) {
        RenderPipeline of(Pass pass) {
            return switch (pass) { case PRIME_SEE_THROUGH -> primeSeeThrough; case PRIME_NORMAL -> primeNormal; case PAINT -> paint; };
        }
    }

    private static Pipelines text, intensity;
    /** Per vanilla text type: its three variants, or null when the type is not one this can copy. */
    private static final Map<RenderType, RenderType[]> VARIANTS = new IdentityHashMap<>();
    private static final VertexConsumer DISCARD = (VertexConsumer) Proxy.newProxyInstance(LabelText.class.getClassLoader(),
        new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> method.getReturnType() == VertexConsumer.class ? proxy : null);

    private LabelText() {}

    /** Buffers that route the font's glyphs into the given pass; anything else is dropped from a depth-only pass. */
    static MultiBufferSource buffers(MultiBufferSource buffers, Pass pass) {
        return type -> {
            RenderType[] v = variants(type);
            if (v != null) return buffers.getBuffer(v[pass.ordinal()]);
            return pass == Pass.PAINT ? buffers.getBuffer(type) : DISCARD;
        };
    }

    private static RenderType[] variants(RenderType type) {
        if (VARIANTS.containsKey(type)) return VARIANTS.get(type);
        RenderType[] made = null;
        RenderPipeline base = type.pipeline();
        Pipelines set = base == RenderPipelines.TEXT || base == RenderPipelines.TEXT_SEE_THROUGH ? text()
            : base == RenderPipelines.TEXT_INTENSITY || base == RenderPipelines.TEXT_INTENSITY_SEE_THROUGH ? intensity() : null;
        Identifier atlas = set == null ? null : atlas(type);
        if (atlas != null) {
            made = new RenderType[Pass.values().length];
            for (Pass pass : Pass.values()) {
                made[pass.ordinal()] = RenderTypeAccessor.skycosmetics$create("skycosmetics_label_" + pass.name().toLowerCase(java.util.Locale.ROOT),
                    RenderSetup.builder(set.of(pass)).withTexture("Sampler0", atlas).useLightmap().bufferSize(type.bufferSize()).createRenderSetup());
            }
        }
        if (VARIANTS.size() > 256) VARIANTS.clear(); // font atlases are few; a resource reload replaces them
        VARIANTS.put(type, made);
        return made;
    }

    private static Identifier atlas(RenderType type) {
        RenderSetup setup = ((RenderTypeAccessor) type).skycosmetics$state();
        Object binding = ((RenderSetupAccessor) (Object) setup).skycosmetics$textures().get("Sampler0");
        return binding == null ? null : ((TextureBindingAccessor) binding).skycosmetics$location();
    }

    private static Pipelines text() {
        if (text == null) text = pipelines(RenderPipelines.TEXT, "text");
        return text;
    }

    private static Pipelines intensity() {
        if (intensity == null) intensity = pipelines(RenderPipelines.TEXT_INTENSITY, "text_intensity");
        return intensity;
    }

    private static Pipelines pipelines(RenderPipeline base, String name) {
        ColorTargetState none = new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE);
        return new Pipelines(
            copy(base, name + "_prime_see_through", none, new DepthStencilState(CompareOp.ALWAYS_PASS, true)),
            copy(base, name + "_prime", none, new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)),
            // Vanilla's polygon-offset text uses the same bias; strictly-less lets only the first quad over a pixel draw.
            copy(base, name + "_paint", base.getColorTargetState(), new DepthStencilState(CompareOp.LESS_THAN, true, -1f, -10f)));
    }

    private static RenderPipeline copy(RenderPipeline base, String name, ColorTargetState color, DepthStencilState depth) {
        var b = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("skycosmetics", "pipeline/label_" + name))
            .withVertexShader(base.getVertexShader()).withFragmentShader(base.getFragmentShader())
            .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
            .withColorTargetState(color).withCull(base.isCull()).withPolygonMode(base.getPolygonMode())
            .withDepthStencilState(depth);
        base.getShaderDefines().flags().forEach(b::withShaderDefine);
        base.getShaderDefines().values().forEach((k, v) -> {
            try { b.withShaderDefine(k, Integer.parseInt(v)); }
            catch (NumberFormatException e) { b.withShaderDefine(k, Float.parseFloat(v)); }
        });
        base.getSamplers().forEach(b::withSampler);
        base.getUniforms().forEach(u -> {
            if (u.textureFormat() == null) b.withUniform(u.name(), u.type());
            else b.withUniform(u.name(), u.type(), u.textureFormat());
        });
        return b.build();
    }
}
