#include "flywheel:internal/light_lut.glsl"
#include "colorful_lighting:colored_light_types.glsl"

// SSBOs need GLSL 430; flywheel compiles with the highest GLSL the context can offer, so on
// older contexts (GL 4.1 Macs and the like) the same section data is read through a buffer
// texture instead — same buffer object, same indexing, texelFetch instead of an array access.
// FlywheelCompat.isTextureFallback makes the identical decision on the Java side (upload/bind),
// and GlProgramMixin points the sampler at its texture unit.
#if __VERSION__ >= 430
layout(std430, binding = 8) restrict readonly buffer ColoredLightSections {
    uint _cl_coloredLightSections[];
};
layout(std430, binding = _FLW_LIGHT_SECTIONS_BUFFER_BINDING) restrict readonly buffer LightSections {
    uint _flw_lightSections[];
};
#else
uniform isamplerBuffer _cl_coloredLightSections;
uniform usamplerBuffer _flw_lightLut;
#endif

uint _flw_indexLut(uint index)
{
    #if __VERSION__ >= 430
    return _flw_lightLut[index];
    #else
    return texelFetch(_flw_lightLut, int(index)).r;
    #endif
}

uint _flw_indexLight(uint index)
{
    #if __VERSION__ >= 430
    return _cl_coloredLightSections[index];
    #else
    return texelFetch(_cl_coloredLightSections, int(index)).r;
    #endif
}
