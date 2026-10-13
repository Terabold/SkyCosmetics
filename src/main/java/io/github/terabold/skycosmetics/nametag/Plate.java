package io.github.terabold.skycosmetics.nametag;

import com.mojang.blaze3d.vertex.*;

/** Immutable cached scanline geometry. Rounding is calculated only when dimensions/settings change. */
public final class Plate {
    private final float[] edges;
    private final int top, bottom;
    private final int width;
    public Plate(int width) {
        this.width = width;
        var p=NametagSettings.p;
        float left=-width/2f-(float)p.paddingX, right=width/2f+(float)p.paddingX;
        float y0=-1-(float)p.paddingY, y1=9+(float)p.paddingY;
        float radius=p.rounded?(float)Math.min(p.radius,Math.min((right-left)/2,(y1-y0)/2)):0;
        int strips=radius==0 && (!p.gradientBackground || p.backgroundColor==p.backgroundEnd)
            ? 1 : Math.max(1,(int)Math.ceil((y1-y0)*2));
        edges=new float[strips*4];
        for(int i=0;i<strips;i++) {
            float a=y0+(y1-y0)*i/strips,b=y0+(y1-y0)*(i+1)/strips;
            float cy=(a+b)/2, d=Math.max(0,radius-Math.min(cy-y0,y1-cy));
            float inset=radius==0?0:radius-(float)Math.sqrt(Math.max(0,radius*radius-d*d));
            edges[i*4]=left+inset; edges[i*4+1]=a; edges[i*4+2]=right-inset; edges[i*4+3]=b;
        }
        top=p.backgroundColor;bottom=p.gradientBackground?p.backgroundEnd:top;
    }
    public int width() { return width; }
    /** Backgrounds never write depth: transparent plates must not erase later glass or cover glyphs. */
    public static net.minecraft.client.renderer.rendertype.RenderType renderType(boolean seeThrough) {
        return seeThrough ? net.minecraft.client.renderer.rendertype.RenderTypes.textBackgroundSeeThrough() : DepthTested.TYPE;
    }
    private static final class DepthTested {
        static final net.minecraft.client.renderer.rendertype.RenderType TYPE = create();
        private static net.minecraft.client.renderer.rendertype.RenderType create() {
            var base = net.minecraft.client.renderer.RenderPipelines.TEXT_BACKGROUND;
            var builder = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                .withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycosmetics", "pipeline/nametag_background"))
                .withVertexShader(base.getVertexShader()).withFragmentShader(base.getFragmentShader())
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withColorTargetState(base.getColorTargetState()).withCull(base.isCull()).withPolygonMode(base.getPolygonMode())
                .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(base.getDepthStencilState().depthTest(), false));
            base.getSamplers().forEach(builder::withSampler);
            base.getUniforms().forEach(u -> {
                if (u.textureFormat() == null) builder.withUniform(u.name(), u.type());
                else builder.withUniform(u.name(), u.type(), u.textureFormat());
            });
            return io.github.terabold.skycosmetics.mixin.RenderTypeAccessor.skycosmetics$create("skycosmetics_nametag_background",
                net.minecraft.client.renderer.rendertype.RenderSetup.builder(builder.build()).useLightmap().createRenderSetup());
        }
    }
    /** Emit cached geometry into the label's matching visibility pass. */
    public void draw(org.joml.Matrix4fc pose, VertexConsumer v, float x, float y, float alpha, int light) {
            for(int i=0;i<edges.length;i+=4) {
                float t=(float)i/Math.max(4,edges.length-4);
                int c=mix(top,bottom,t,alpha);
                v.addVertex(pose,x+edges[i],y+edges[i+1],.01f).setColor(c).setLight(light);
                v.addVertex(pose,x+edges[i],y+edges[i+3],.01f).setColor(c).setLight(light);
                v.addVertex(pose,x+edges[i+2],y+edges[i+3],.01f).setColor(c).setLight(light);
                v.addVertex(pose,x+edges[i+2],y+edges[i+1],.01f).setColor(c).setLight(light);
            }
    }
    private static int mix(int a,int b,float t,float alpha) {
        int out=0;
        for(int shift=0;shift<=24;shift+=8) {
            int channel=Math.round(((a>>>shift)&255)*(1-t)+((b>>>shift)&255)*t);
            if(shift==24)channel=Math.round(channel*alpha);
            out|=channel<<shift;
        }
        return out;
    }
}
