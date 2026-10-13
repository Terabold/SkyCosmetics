#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
uniform sampler2D ChromaPalette;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 mask = texture(Sampler0, texCoord0) * ColorModulator;
    if (mask.a < 0.1) discard;
    vec4 settings = texelFetch(ChromaPalette, ivec2(0, 1), 0);
    float time = GameTime * 1200.0 / 2.4 * settings.r * 4.0;
    float phase = (settings.b > 0.5 ? (texCoord0.x + texCoord0.y) * settings.g * 2.0 : 0.0)
        + (settings.a > 0.5 ? time : -time);
    vec3 tint = texture(ChromaPalette, vec2(fract(phase), 0.25)).rgb;
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(tint * max(max(mask.r, mask.g), mask.b) * fade, mask.a);
}
