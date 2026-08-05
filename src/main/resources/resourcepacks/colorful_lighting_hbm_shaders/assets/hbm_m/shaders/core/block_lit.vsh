#version 330 core

// Colorful Lighting override of HBM Modernized's block_lit.vsh. Identical to HBM's original
// except the corner light samples may carry colored block light: LightSampleCache is patched to
// encode colored samples as blockU = 32768 | r4<<8 | g4<<4 | b4 (vanilla blockU stays 0..240, so
// >= 32000 safely identifies encoded values; skyV is always plain 0..240). Corners are decoded
// BEFORE interpolation; when no corner is encoded the output is bit-identical to the original.
// This file is only loaded when the installed HBM ships the exact shader it was derived from
// (see InternalPackRegistration), so an HBM update safely falls back to stock behavior.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV0;

#ifdef USE_INSTANCING
#ifdef USE_VERTEX_BONE_ID
layout(location = 3) in int BoneId;
layout(location = 4) in vec3 InstPos;
layout(location = 5) in vec4 InstRot;
layout(location = 6) in vec3 InstBboxMin;
layout(location = 7) in vec4 InstBboxSize;
#ifdef USE_SLICED_LIGHT
layout(location = 8)  in vec4 InstLightS0C01;
layout(location = 9)  in vec4 InstLightS0C23;
layout(location = 10) in vec4 InstLightS1C01;
layout(location = 11) in vec4 InstLightS1C23;
layout(location = 12) in vec4 InstLightS2C01;
layout(location = 13) in vec4 InstLightS2C23;
layout(location = 14) in vec4 InstLightS3C01;
layout(location = 15) in vec4 InstLightS3C23;
#else
layout(location = 8)  in vec4 InstLightC01;  // corner0.uv, corner1.uv
layout(location = 9)  in vec4 InstLightC23;
layout(location = 10) in vec4 InstLightC45;
layout(location = 11) in vec4 InstLightC67;
#endif
#else
layout(location = 3)  in vec3 InstPos;
layout(location = 4)  in vec4 InstRot;
layout(location = 5)  in vec3 InstBboxMin;
layout(location = 6)  in vec3 InstBboxSize;
#ifdef USE_SLICED_LIGHT
layout(location = 7)  in vec4 InstLightS0C01;
layout(location = 8)  in vec4 InstLightS0C23;
layout(location = 9)  in vec4 InstLightS1C01;
layout(location = 10) in vec4 InstLightS1C23;
layout(location = 11) in vec4 InstLightS2C01;
layout(location = 12) in vec4 InstLightS2C23;
layout(location = 13) in vec4 InstLightS3C01;
layout(location = 14) in vec4 InstLightS3C23;
layout(location = 15) in float InstFadeAlpha;
#else
layout(location = 7)  in vec4 InstLightC01;  // corner0.uv, corner1.uv
layout(location = 8)  in vec4 InstLightC23;
layout(location = 9)  in vec4 InstLightC45;
layout(location = 10) in vec4 InstLightC67;
layout(location = 11) in float InstFadeAlpha;
#endif
#endif
#endif

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float FadeAlpha;

uniform vec3 BboxMin;
uniform vec3 BboxSize;
#ifdef USE_SLICED_LIGHT
uniform vec4 LightS0C01;
uniform vec4 LightS0C23;
uniform vec4 LightS1C01;
uniform vec4 LightS1C23;
uniform vec4 LightS2C01;
uniform vec4 LightS2C23;
uniform vec4 LightS3C01;
uniform vec4 LightS3C23;
#else
uniform vec4 LightC01;
uniform vec4 LightC23;
uniform vec4 LightC45;
uniform vec4 LightC67;
#endif

out vec2 texCoord;
// Vanilla lightmap UV: sampled from Sampler2 in block_lit.fsh (used when clColored == 0).
out vec2 lightmapUV;
// Colorful Lighting: interpolated colored block light (rgb) + sky, all on the 0..240 grid.
out vec4 clBlockSky;
out float clColored;
out float vertexDistance;
out vec3 fragNormal;
out float vFadeAlpha;

#ifdef USE_INSTANCING
mat4 quatToMat4(vec4 q) {
    float xx = q.x * q.x;
    float yy = q.y * q.y;
    float zz = q.z * q.z;
    float xy = q.x * q.y;
    float xz = q.x * q.z;
    float yz = q.y * q.z;
    float wx = q.w * q.x;
    float wy = q.w * q.y;
    float wz = q.w * q.z;

    return mat4(
        1.0 - 2.0 * (yy + zz), 2.0 * (xy + wz),       2.0 * (xz - wy),       0.0,
        2.0 * (xy - wz),       1.0 - 2.0 * (xx + zz), 2.0 * (yz + wx),       0.0,
        2.0 * (xz + wy),       2.0 * (yz - wx),       1.0 - 2.0 * (xx + yy), 0.0,
        0.0,                   0.0,                   0.0,                   1.0
    );
}
#endif

// Decodes one (blockU, skyV) corner into (blockR, blockG, blockB, sky) on the 0..240 grid.
// Vanilla corners become gray (r = g = b = blockU), so all-vanilla interpolation reproduces
// the original scalar math channel for channel.
vec4 cl_decodeCorner(vec2 uv) {
    if (uv.x >= 32000.0) {
        int e = int(uv.x + 0.5) - 32768;
        return vec4(
            float((e >> 8) & 15) * 16.0,
            float((e >> 4) & 15) * 16.0,
            float(e & 15) * 16.0,
            uv.y);
    }
    return vec4(uv.x, uv.x, uv.x, uv.y);
}

float cl_any4(vec4 a, vec4 b, vec4 c, vec4 d) {
    float m = max(max(max(a.x, a.z), max(b.x, b.z)), max(max(c.x, c.z), max(d.x, d.z)));
    return m >= 32000.0 ? 1.0 : 0.0;
}

// Trilinear blend of decoded corner (rgb, sky) samples on the 0..240 lightmap grid.
vec4 trilinearLightRgb(vec3 w, vec4 c01, vec4 c23, vec4 c45, vec4 c67) {
    vec4 c0 = cl_decodeCorner(c01.xy);
    vec4 c1 = cl_decodeCorner(c01.zw);
    vec4 c2 = cl_decodeCorner(c23.xy);
    vec4 c3 = cl_decodeCorner(c23.zw);
    vec4 c4 = cl_decodeCorner(c45.xy);
    vec4 c5 = cl_decodeCorner(c45.zw);
    vec4 c6 = cl_decodeCorner(c67.xy);
    vec4 c7 = cl_decodeCorner(c67.zw);

    vec4 x00 = mix(c0, c1, w.x);
    vec4 x10 = mix(c2, c3, w.x);
    vec4 x01 = mix(c4, c5, w.x);
    vec4 x11 = mix(c6, c7, w.x);
    vec4 y0  = mix(x00, x10, w.y);
    vec4 y1  = mix(x01, x11, w.y);
    return mix(y0, y1, w.z);
}

#ifdef USE_SLICED_LIGHT
vec4 bilinearLightRgb(vec2 wxz, vec4 c01, vec4 c23) {
    vec4 c00 = cl_decodeCorner(c01.xy);
    vec4 c10 = cl_decodeCorner(c01.zw);
    vec4 c01v = cl_decodeCorner(c23.xy);
    vec4 c11 = cl_decodeCorner(c23.zw);

    vec4 x0 = mix(c00, c10, wxz.x);
    vec4 x1 = mix(c01v, c11, wxz.x);
    return mix(x0, x1, wxz.y);
}
#endif

void main() {
    mat4 modelView;
    vec3 bboxMin;
    vec3 bboxSize;
    vec4 lc01;
    vec4 lc23;
    vec4 lc45;
    vec4 lc67;

#ifdef USE_INSTANCING
    mat4 rotMatrix = quatToMat4(InstRot);
    mat4 translation = mat4(1.0);
    translation[3] = vec4(InstPos, 1.0);
    mat4 instBase = translation * rotMatrix;
    modelView = instBase;
    bboxMin = InstBboxMin;
    bboxSize = InstBboxSize.xyz;
#ifndef USE_SLICED_LIGHT
    lc01 = InstLightC01;
    lc23 = InstLightC23;
    lc45 = InstLightC45;
    lc67 = InstLightC67;
#endif

    fragNormal = mat3(modelView) * Normal;
#else
    modelView = ModelViewMat;
    bboxMin = BboxMin;
    bboxSize = BboxSize;
#ifndef USE_SLICED_LIGHT
    lc01 = LightC01;
    lc23 = LightC23;
    lc45 = LightC45;
    lc67 = LightC67;
#endif

    fragNormal = mat3(modelView) * Normal;
#endif

    vec3 safeSize = max(bboxSize, vec3(1e-4));
    vec3 w = clamp((Position - bboxMin) / safeSize, 0.0, 1.0);

    vec4 uvLmC;
#ifdef USE_SLICED_LIGHT
    float ty = w.y * 3.0;
    float s0 = floor(ty);
    float fy = clamp(ty - s0, 0.0, 1.0);
    int i0 = int(clamp(s0, 0.0, 3.0));
    int i1 = min(i0 + 1, 3);

    vec4 uv0;
    vec4 uv1;

#ifdef USE_INSTANCING
    if (i0 == 0) uv0 = bilinearLightRgb(vec2(w.x, w.z), InstLightS0C01, InstLightS0C23);
    else if (i0 == 1) uv0 = bilinearLightRgb(vec2(w.x, w.z), InstLightS1C01, InstLightS1C23);
    else if (i0 == 2) uv0 = bilinearLightRgb(vec2(w.x, w.z), InstLightS2C01, InstLightS2C23);
    else uv0 = bilinearLightRgb(vec2(w.x, w.z), InstLightS3C01, InstLightS3C23);

    if (i1 == 0) uv1 = bilinearLightRgb(vec2(w.x, w.z), InstLightS0C01, InstLightS0C23);
    else if (i1 == 1) uv1 = bilinearLightRgb(vec2(w.x, w.z), InstLightS1C01, InstLightS1C23);
    else if (i1 == 2) uv1 = bilinearLightRgb(vec2(w.x, w.z), InstLightS2C01, InstLightS2C23);
    else uv1 = bilinearLightRgb(vec2(w.x, w.z), InstLightS3C01, InstLightS3C23);

    clColored = max(
        max(cl_any4(InstLightS0C01, InstLightS0C23, InstLightS1C01, InstLightS1C23),
            cl_any4(InstLightS2C01, InstLightS2C23, InstLightS3C01, InstLightS3C23)), 0.0);
#else
    if (i0 == 0) uv0 = bilinearLightRgb(vec2(w.x, w.z), LightS0C01, LightS0C23);
    else if (i0 == 1) uv0 = bilinearLightRgb(vec2(w.x, w.z), LightS1C01, LightS1C23);
    else if (i0 == 2) uv0 = bilinearLightRgb(vec2(w.x, w.z), LightS2C01, LightS2C23);
    else uv0 = bilinearLightRgb(vec2(w.x, w.z), LightS3C01, LightS3C23);

    if (i1 == 0) uv1 = bilinearLightRgb(vec2(w.x, w.z), LightS0C01, LightS0C23);
    else if (i1 == 1) uv1 = bilinearLightRgb(vec2(w.x, w.z), LightS1C01, LightS1C23);
    else if (i1 == 2) uv1 = bilinearLightRgb(vec2(w.x, w.z), LightS2C01, LightS2C23);
    else uv1 = bilinearLightRgb(vec2(w.x, w.z), LightS3C01, LightS3C23);

    clColored = max(
        max(cl_any4(LightS0C01, LightS0C23, LightS1C01, LightS1C23),
            cl_any4(LightS2C01, LightS2C23, LightS3C01, LightS3C23)), 0.0);
#endif

    uvLmC = mix(uv0, uv1, fy);
#else
    uvLmC = trilinearLightRgb(w, lc01, lc23, lc45, lc67);
    clColored = cl_any4(lc01, lc23, lc45, lc67);
#endif

    vec4 viewPos = modelView * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    texCoord = UV0;
    // Center within the 16x16 lightmap cell like vanilla block UV2 -> texcoord.
    lightmapUV = (vec2(uvLmC.x, uvLmC.w) + vec2(8.0)) / 256.0;
    clBlockSky = uvLmC;
    vertexDistance = length(viewPos.xyz);

#ifdef USE_INSTANCING
    vFadeAlpha = InstBboxSize.w;
#else
    vFadeAlpha = FadeAlpha;
#endif
}
