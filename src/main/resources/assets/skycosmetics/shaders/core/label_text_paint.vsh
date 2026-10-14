#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// Vanilla's text vertex shader, with the glyph pulled a fixed hair toward the camera: the paint pass of a styled
// nametag must land strictly in front of the depth its prime pass wrote. Polygon offset does that on most drivers
// but adds nothing on some (Mesa's software renderer), which hid every glyph there; this works on all of them.
// 2e-6 in clip space is at least 16 steps of a 24-bit or float depth buffer, far too little to show through anything.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position.z -= 2.0e-6 * gl_Position.w;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
}
