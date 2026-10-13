package io.github.terabold.skycosmetics.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** The GPU advances glint colors; a tiny palette texture is uploaded only when its settings change. */
final class ChromaGlint {
    static final Identifier PALETTE = Identifier.fromNamespaceAndPath("skycosmetics", "chroma/palette");
    static final RenderPipeline PIPELINE = pipeline();
    private static int uploaded = -1, uploads;

    private ChromaGlint() {}

    private static RenderPipeline pipeline() {
        RenderPipeline original = RenderPipelines.GLINT;
        var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("skycosmetics", "pipeline/chroma_glint"))
            .withVertexShader(original.getVertexShader()).withFragmentShader(Identifier.fromNamespaceAndPath("skycosmetics", "core/chroma_glint"))
            .withVertexFormat(original.getVertexFormat(), original.getVertexFormatMode()).withCull(original.isCull())
            .withColorTargetState(original.getColorTargetState()).withDepthStencilState(original.getDepthStencilState())
            .withPolygonMode(original.getPolygonMode());
        for (String sampler : original.getSamplers()) builder.withSampler(sampler);
        builder.withSampler("ChromaPalette");
        for (var uniform : original.getUniforms()) {
            if (uniform.textureFormat() == null) builder.withUniform(uniform.name(), uniform.type());
            else builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
        }
        return builder.build();
    }

    static void ensurePalette() {
        if (uploaded == Chroma.version()) return;
        NativeImage pixels = new NativeImage(512, 2, false);
        int meta = (Chroma.reverse ? 255 : 0) << 24 | (int) Math.round(Chroma.speed / 4 * 255) << 16
            | (int) Math.round(Chroma.spread / 25 * 255) << 8 | (Chroma.wave ? 255 : 0);
        for (int i = 0; i < 512; i++) {
            pixels.setPixel(i, 0, 0xFF000000 | Chroma.color(i / 512.0));
            pixels.setPixel(i, 1, meta);
        }
        Minecraft.getInstance().getTextureManager().register(PALETTE, new DynamicTexture(() -> "SkyCosmetics chroma palette", pixels));
        uploaded = Chroma.version();
        uploads++;
    }

    static void reset() { uploaded = -1; }
    static int uploads() { return uploads; }
}
