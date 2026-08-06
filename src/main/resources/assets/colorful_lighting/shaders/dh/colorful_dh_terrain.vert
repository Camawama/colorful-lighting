#version 150 core

// Colorful Lighting replacement for Distant Horizons' terrain vertex shader (DH 3.1.2 & 3.2.0).
// Same inputs, same clip-space output; instead of baking the lightmap colour per vertex it forwards
// the raw sky/block light and albedo so the fragment shader can recolour block light per fragment.

in uvec4 vPosition; // xyz: block pos local to the buffer, w: meta (low byte lights, bits 8-13 micro offset)
in vec4 color;      // albedo
// DH 3.2 only (attribute array disabled under 3.1.2, where these bytes are unused padding):
// y = face normal index, zw = block texture tile id (little endian)
in uvec4 irisData;

out vec3 vertexWorldPos;   // camera-relative world position
out float vertexYPos;      // absolute world Y
out vec4 vertexAlbedo;
out vec2 vertexLightCoord; // lightmap coords: x = high nibble (block light), y = low nibble (sky light)
out vec3 vBlockPos;        // buffer-local position, fract() repeats per block (DH 3.2 texture UVs)
flat out uint vNormalIndex;
flat out uint vTextureTileId;

uniform mat4 uCombinedMatrix; // dhProjection * dhModelView
uniform vec3 uModelOffset;    // buffer min corner minus exact camera position
uniform float uWorldYOffset;
uniform float uMircoOffset;

void main()
{
    vertexWorldPos = vec3(vPosition.xyz) + uModelOffset;
    vertexYPos = float(vPosition.y) + uWorldYOffset;

    uint meta = vPosition.a;

    // micro offset, identical to DH: 2 bits per axis, 0b01 positive, 0b11 negative (y unused)
    uint mirco = (meta & 0xFF00u) >> 8u;
    float mx = (mirco & 1u) != 0u ? uMircoOffset : 0.0;
    mx = (mirco & 2u) != 0u ? -mx : mx;
    float mz = (mirco & 16u) != 0u ? uMircoOffset : 0.0;
    mz = (mirco & 32u) != 0u ? -mz : mz;
    vertexWorldPos.x += mx;
    vertexWorldPos.z += mz;

    // Same decode as DH's standard.vert. Note DH's names are swapped: the high nibble it calls
    // "skyLight" is actually block light (the lightmap's u axis), the low nibble is sky light.
    uint lights = meta & 0xFFu;
    float highNibble = (float(lights / 16u) + 0.5) / 16.0;
    float lowNibble = (mod(float(lights), 16.0) + 0.5) / 16.0;
    vertexLightCoord = vec2(highNibble, lowNibble);

    vertexAlbedo = color;

    vBlockPos = vec3(vPosition.xyz);
    vNormalIndex = irisData.y;
    vTextureTileId = irisData.z | (irisData.w << 8u);

    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);
}
