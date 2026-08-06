#version 150 core

// Colorful Lighting replacement for Distant Horizons' terrain fragment shader (DH 3.1.2 & 3.2.0).
// Recolours the block-light contribution of each LOD fragment using the remembered colour volumes,
// then applies DH's dithered near fade so the hole around the vanilla render distance matches.
// Under DH 3.2 with textured LODs enabled it also replicates DH's block-texture tile modulation.

in vec3 vertexWorldPos;   // camera-relative
in float vertexYPos;
in vec4 vertexAlbedo;
in vec2 vertexLightCoord; // x = sky, y = block
in vec3 vBlockPos;        // DH 3.2 texture UVs
flat in uint vNormalIndex;
flat in uint vTextureTileId;

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

// DH 3.2 textured LODs. uClTexturedLods gates the whole path: under 3.1.2 the irisData
// attribute array is disabled, so vTextureTileId reads the integer-attribute default (0,0,0,1)
// = tile 256, and sampling would be garbage.
uniform sampler2D uClBlockAtlas; // DH's block texture atlas, bound by DH on unit 1
uniform int uClTexturedLods;

/**
 * Texture coordinate for this fragment, matching how DH bakes block textures (DH 3.2's frag).
 * Normal index order is EDhDirection: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
 */
vec2 blockFaceUv()
{
    vec3 pos = fract(vBlockPos);
    switch (vNormalIndex)
    {
        case 0u: return vec2(pos.x, 1.0 - pos.z); // down
        case 1u: return vec2(pos.x, pos.z); // up
        case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y); // north
        case 3u: return vec2(pos.x, 1.0 - pos.y); // south
        case 4u: return vec2(pos.z, 1.0 - pos.y); // west
        default: return vec2(1.0 - pos.z, 1.0 - pos.y); // east
    }
}

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

    // Alpha is a presence mask; empty texels are (0,0,0,0). The HUE uses the un-premultiplied
    // colour (dividing by alpha undoes the darkening linear filtering causes against empty
    // texels), so it stays true across fade ramps.
    float presence = stored.a;
    vec3 net = presence > 0.001 ? stored.rgb / presence : vec3(0.0);
    float netPeak = max(net.r, max(net.g, net.b));
    vec3 hue = netPeak > 0.001 ? net / netPeak : vec3(1.0);

    // Tint strength is the remembered colour's SATURATION, not its brightness. The far volume
    // averages a whole section into one texel, so the half of a light that spills into the next
    // section is remembered dim — keying the tint on brightness snapped that half to white and
    // visibly cut coloured light fields in two at section boundaries. Saturation keeps a dim
    // coloured fringe coloured, while white/near-white light still gets no tint (its saturation
    // is ~0), same as before.
    float sat = netPeak > 0.001 ? (netPeak - min(net.r, min(net.g, net.b))) / netPeak : 0.0;
    float colorWeight = smoothstep(0.05, 0.5, presence);
    vec3 tint = mix(vec3(1.0), hue, clamp(sat * 2.0, 0.0, 1.0) * colorWeight);

    // Lightmap axes: u = block light, v = sky light (MC's layout). DH's standard.vert names the
    // meta nibbles the other way around ("skyLight" = high nibble) but stays self-consistent; in
    // truth the high nibble (vertexLightCoord.x) is BLOCK light. Trusting DH's names here put the
    // sky lookup on the block axis and painted black patches wherever colour was remembered.
    const float LIGHT0 = 0.5 / 16.0;

    // DH bakes LOD block light lazily: LODs fresh from a chunk conversion (or generated far away)
    // can be missing whole swathes of light until DH re-bakes them, which reads as jagged dark
    // cut-offs while flying. The colour memory also knows the light LEVEL, so lift the LOD's block
    // light to at least the remembered level; DH's own baked value wins wherever it exists.
    //
    // The level uses the PREMULTIPLIED peak, unlike the hue: it must FADE across the filter ramp
    // into unremembered space. The un-premultiplied value held the source's full brightness across
    // the whole ramp and then snapped off at the presence threshold, which stamped a light's glow
    // as a section-sized plateau cut off flat at the boundary (the "half a diamond" artifact).
    // peak 0..1 maps back to a light level: stored bytes are nibble*17, so nibble/16 = peak*0.9375.
    float peak = max(stored.r, max(stored.g, stored.b));
    float rememberedLevel = clamp(peak * 0.9375 + LIGHT0, 0.0, 1.0);
    float blockCoord = max(vertexLightCoord.x, rememberedLevel * colorWeight);

    vec3 combined = texture(uLightMap, vec2(blockCoord, vertexLightCoord.y)).rgb;
    vec3 skyOnly = texture(uLightMap, vec2(LIGHT0, vertexLightCoord.y)).rgb;
    vec3 blockOnly = texture(uLightMap, vec2(blockCoord, LIGHT0)).rgb;

    vec3 colored = max(skyOnly, blockOnly * tint);
    vec3 light = mix(combined, colored, colorWeight);

    fragColor = vec4(light, 1.0) * vertexAlbedo;

    // DH 3.2 block texture tiles: each tile stores a color ratio relative to the LOD's flat
    // color (128 = keep base color). Identical math to DH's own frag, applied after our light
    // tint the same way DH applies it after its lighting.
    if (uClTexturedLods != 0 && vTextureTileId != 0u)
    {
        ivec2 tileOrigin = ivec2(int(vTextureTileId % 256u), int(vTextureTileId / 256u)) * 16;
        ivec2 texelPos = tileOrigin + ivec2(clamp(blockFaceUv() * 16.0, 0.0, 15.0));
        vec4 tile = texelFetch(uClBlockAtlas, texelPos, 0);
        vec3 clampedColor = clamp(fragColor.rgb * (tile.rgb * 2.0), 0.0, 1.0);
        fragColor.rgb = mix(fragColor.rgb, clampedColor, tile.a);
    }

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
