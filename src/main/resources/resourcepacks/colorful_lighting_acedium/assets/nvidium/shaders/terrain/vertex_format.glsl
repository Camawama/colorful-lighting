// MODIFIED
//struct Vertex {
//    int xy;
//    int z_mat_section;
//    int color;
//    int uv;
//    int light;
//}

#define MODEL_SCALE        32.0 / 65536.0
#define MODEL_ORIGIN       8.0

#define COLOR_SCALE        1.0 / 255.0

vec3 decodeVertexPosition(Vertex v) {
    uvec3 packed_position = uvec3(
        v.xy,
        v.xy >> 16,
        v.z_mat_section
    ) & uvec3(0xFFFFu);

    return (vec3(packed_position) * MODEL_SCALE) - MODEL_ORIGIN;
}

vec4 decodeVertexColour(Vertex v) {
    uvec4 packed_color = (uvec4(v.color) >> uvec4(0, 8, 16, 24)) & uvec4(0xFFu);
    return vec4(packed_color) * COLOR_SCALE;
}

vec2 decodeVertexUV(Vertex v) {
    // not sure why this had to change compared to nvidium itself, but okay?
    return vec2(ivec2(v.uv,v.uv>>16)&ivec2(0xffff))*(1f/(TEXTURE_MAX_SCALE));
}

float decodeVertexMippingBias(Vertex v) {
    return ((v.z_mat_section>>16)&4)==0?-8:0;
}

float decodeVertexAlphaCutoff(Vertex v) {
    return (float[](0.0f, 0.1f,0.5f))[((v.z_mat_section>>16)&int16_t(3))];
}

ivec2 decodeLightUV(Vertex v) {
    ivec2 packed_color = (ivec2(v.light) >> ivec2(0, 16)) & ivec2(0xFFFFu);
    return ivec2(packed_color);
}
