#version 150 core

// Colorful Lighting replacement for Distant Horizons' flat_shaded.frag (DH 3.1.2).
// Recolours the block-light contribution of each LOD fragment using the remembered colour volumes,
// then applies DH's dithered near fade so the hole around the vanilla render distance matches.

in vec3 vertexWorldPos;   // camera-relative
in float vertexYPos;
in vec4 vertexAlbedo;
in vec2 vertexLightCoord; // x = sky, y = block

out vec4 fragColor;

uniform sampler2D uLightMap;     // vanilla lightmap, bound by DH on unit 0
uniform sampler3D uClVolumeNear; // remembered colour, 4 blocks/texel
uniform sampler3D uClVolumeFar;  // remembered colour, 16 blocks/texel
uniform vec3 uClCameraPos;
uniform vec3 uClNearMin;         // world-space min corner of the near window
uniform float uClNearInvSize;    // 1 / window size in blocks
uniform vec3 uClFarMin;
uniform float uClFarInvSize;
uniform int uClDebugMode;        // 0 off, 1 volume debug view

uniform float uClipDistance;     // near fade start, from DH's render params

// DH's 4x4 Bayer matrix, for the same dithered fade its own shader uses
float bayerMatrix4x4(vec2 st)
{
    int x = int(mod(st.x, 4.0));
    int y = int(mod(st.y, 4.0));
    float bayer4x4[16] = float[16](
        0.0,  8.0,  2.0, 10.0,
        12.0, 4.0, 14.0,  6.0,
        3.0, 11.0,  1.0,  9.0,
        15.0, 7.0, 13.0,  5.0
    );
    return bayer4x4[y * 4 + x] / 16.0;
}

void main()
{
    vec3 absPos = vec3(vertexWorldPos.x + uClCameraPos.x, vertexYPos, vertexWorldPos.z + uClCameraPos.z);

    // Prefer the fine near volume, fall back to the coarse far one. A small margin keeps linear
    // filtering from reading clamped edge texels.
    vec3 nearCoord = (absPos - uClNearMin) * uClNearInvSize;
    vec3 farCoord = (absPos - uClFarMin) * uClFarInvSize;
    bool inNear = all(greaterThan(nearCoord, vec3(0.005))) && all(lessThan(nearCoord, vec3(0.995)));
    bool inFar = all(greaterThan(farCoord, vec3(0.005))) && all(lessThan(farCoord, vec3(0.995)));
    vec4 stored = inNear ? texture(uClVolumeNear, nearCoord)
                : inFar ? texture(uClVolumeFar, farCoord)
                : vec4(0.0);

    // Alpha is a presence mask; empty texels are (0,0,0,0), so dividing by alpha undoes the
    // darkening that linear filtering against them causes (premultiplied-alpha style).
    float presence = stored.a;
    vec3 net = presence > 0.001 ? stored.rgb / presence : vec3(0.0);

    // The remembered colour's brightness is already encoded in the LOD's baked block light; only the
    // hue matters here. Weak remembered colour fades to white (vanilla light) instead of black:
    // the 4-block downsampling washes a light's halo edge toward zero, and a black tint there would
    // chop torch glows off at the LOD (2026-08-05 beacon test).
    float peak = max(net.r, max(net.g, net.b));
    vec3 hue = peak > 0.001 ? net / peak : vec3(1.0);
    vec3 tint = mix(vec3(1.0), hue, clamp(peak * 8.0, 0.0, 1.0));

    // Lightmap axes: u = block light, v = sky light (MC's layout). DH's standard.vert names the
    // meta nibbles the other way around ("skyLight" = high nibble) but stays self-consistent; in
    // truth the high nibble (vertexLightCoord.x) is BLOCK light. Trusting DH's names here put the
    // sky lookup on the block axis and painted black patches wherever colour was remembered.
    const float LIGHT0 = 0.5 / 16.0;
    vec3 combined = texture(uLightMap, vertexLightCoord).rgb;
    vec3 skyOnly = texture(uLightMap, vec2(LIGHT0, vertexLightCoord.y)).rgb;
    vec3 blockOnly = texture(uLightMap, vec2(vertexLightCoord.x, LIGHT0)).rgb;

    vec3 colored = max(skyOnly, blockOnly * tint);
    float colorWeight = smoothstep(0.05, 0.5, presence);
    vec3 light = mix(combined, colored, colorWeight);

    fragColor = vec4(light, 1.0) * vertexAlbedo;

    if (uClDebugMode == 1)
    {
        // dark red: outside both windows; dim blue: in a window but nothing remembered;
        // otherwise: the raw remembered colour
        if (!inNear && !inFar) fragColor = vec4(0.3, 0.0, 0.0, 1.0);
        else if (presence < 0.05) fragColor = vec4(0.05, 0.05, 0.3, 1.0);
        else fragColor = vec4(net, 1.0);
    }
    else if (uClDebugMode == 2)
    {
        // geometry check: RGB stripes from the absolute world position on a 16-block grid.
        // Correct geometry looks like a smooth 3D checker glued to the terrain; garbage
        // geometry looks like noise or screen-space smears.
        fragColor = vec4(fract(absPos / 16.0), 1.0);
    }
    else if (uClDebugMode == 3)
    {
        // albedo only: should look like an unlit color map of the terrain
        fragColor = vec4(vertexAlbedo.rgb, 1.0);
    }
    else if (uClDebugMode == 4)
    {
        // vanilla lightmap only: DH's own lighting with no albedo and no recolouring
        fragColor = vec4(combined, 1.0);
    }
    else if (uClDebugMode == 5)
    {
        // light coords: red = block light amount (high nibble), green = sky light amount (low nibble)
        fragColor = vec4(vertexLightCoord.x, vertexLightCoord.y, 0.0, 1.0);
    }

    // DH's dithered near fade (ditherDhFade default): LODs dissolve where vanilla chunks take over
    float viewDist = length(vertexWorldPos);
    float worldNoise = bayerMatrix4x4(gl_FragCoord.xy) + 0.001;
    float fadeStep = smoothstep(uClipDistance, uClipDistance * 1.5, viewDist);
    if (fadeStep <= worldNoise)
    {
        discard;
    }
}
