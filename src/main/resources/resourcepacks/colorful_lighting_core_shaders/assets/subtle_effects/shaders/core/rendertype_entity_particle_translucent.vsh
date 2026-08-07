#version 150

// Colorful Lighting override of Subtle Effects' model-particle vertex shader (used by the
// splash model and other ModelParticles). The original resolves the lightmap with
// texelFetch(Sampler2, UV2 / 16, 0), which lands far outside the 16x16 lightmap for colored
// packed light values and samples black. Only the lightMapColor line changes; the in/out
// interface must stay identical to Subtle Effects' fragment shader.

#moj_import <colorful_lighting:colored_light.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = length((ModelViewMat * vec4(Position, 1.0)).xyz);
    vertexColor = Color;
    lightMapColor = sample_lightmap_colored(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}
