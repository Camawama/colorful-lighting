#version 330 core

// Colorful Lighting override of HBM Modernized's block_lit.fsh. When the vertex shader flags
// colored data (clColored), the lightmap is sampled per color channel like Colorful Lighting's
// patched core shaders; otherwise this is byte-for-byte HBM's original lighting.

in vec2 texCoord;
in vec2 lightmapUV;
in vec4 clBlockSky;
in float clColored;
in float vertexDistance;
in float vFadeAlpha;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;

out vec4 fragColor;

void main() {
    vec4 baseColor = texture(Sampler0, texCoord);

    // Vanilla dynamic lightmap: encodes sky darken, client brightness (gamma),
    // night vision, darkness, and dimension tint - same as block models.
    vec3 lm;
    if (clColored > 0.5) {
        // Colored block light: one lightmap fetch per color channel along the block axis,
        // plus the sky row, combined the same way as colored_light.glsl.
        vec3 sky = texture(Sampler2, vec2(8.0 / 256.0, (clBlockSky.a + 8.0) / 256.0)).rgb;
        vec3 blockL = vec3(
            texture(Sampler2, vec2((clBlockSky.r + 8.0) / 256.0, 8.0 / 256.0)).r,
            texture(Sampler2, vec2((clBlockSky.g + 8.0) / 256.0, 8.0 / 256.0)).r,
            texture(Sampler2, vec2((clBlockSky.b + 8.0) / 256.0, 8.0 / 256.0)).r);
        lm = sky + blockL * max(0.1, 1.0 - sky.r);
    } else {
        lm = texture(Sampler2, lightmapUV).rgb;
    }
    vec3 lit = baseColor.rgb * lm;
    lit *= 0.8;

    float alpha = baseColor.a * vFadeAlpha;
    if (alpha < 0.01) {
        discard;
    }

    float fogFactor = clamp((FogEnd - vertexDistance) / (FogEnd - FogStart), 0.0, 1.0);
    vec3 colorWithFog = mix(FogColor.rgb, lit, fogFactor);

    fragColor = vec4(colorWithFog, alpha);
}
