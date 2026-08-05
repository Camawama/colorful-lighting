package me.erykczy.colorfullighting.compat.nvidium;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * Compat for Nvidium/Acedium, mesh-shader terrain renderers that replace Sodium's chunk
 * rendering with their own pipeline. They swap the chunk vertex format for their own
 * {@code NvidiumCompactChunkVertex}, whose encoder takes {@code vertex.light} apart assuming
 * vanilla packed lightmap coordinates (a lossy round-trip, failure mode two of
 * {@link CompatPackedLight}): the colored format keeps color channels in those bit positions,
 * so their shaders sample the lightmap at garbage coordinates and terrain renders near-black
 * regardless of the actual light level.
 *
 * <p>Their terrain shaders compute {@code texture * vertexColor * lightmapSample}, and the
 * encoder is the only place {@code vertex.light} is read, so colored light survives without
 * patching their shaders at all: rewrite the light to well-formed vanilla coordinates (block
 * brightness = brightest channel, sky carried over) and bake the light hue into the vertex
 * color instead.
 *
 * <p>The single lightmap sample cannot separate the white sky contribution from the colored
 * block contribution, so the baked hue is blended toward white by the ratio of stored sky
 * light to block-light intensity: underground the hue is exact, while sky-lit faces fade
 * toward vanilla lighting rather than tinting daylight terrain. Hue changes rebuild the chunk
 * mesh just like any light update, so the baked color never goes stale.
 */
public final class NvidiumCompat {
    private NvidiumCompat() {}

    /** Wraps Nvidium's chunk vertex encoder; called from {@code NvidiumCompactChunkVertexMixin}. */
    public static ChunkVertexEncoder wrapEncoder(ChunkVertexEncoder original) {
        return (ptr, material, vertex, sectionIndex) -> {
            int light = vertex.light;
            if (!CompatPackedLight.isColored(light)) {
                return original.write(ptr, material, vertex, sectionIndex);
            }
            int red8 = light & 0xFF;
            int green8 = (light >>> 8) & 0xFF;
            int blue8 = (light >>> 20) & 0xFF;
            int sky8 = ((light >>> 16) & 0xF) * 17;
            int max8 = Math.max(red8, Math.max(green8, blue8));

            int color = vertex.color;
            vertex.light = CompatPackedLight.toVanilla(light);
            if (max8 > 0 && (red8 < max8 || green8 < max8 || blue8 < max8)) {
                float whiten = sky8 / (float) (sky8 + max8);
                vertex.color = scaleRgb(color,
                        hue(red8, max8, whiten),
                        hue(green8, max8, whiten),
                        hue(blue8, max8, whiten));
            }
            long next = original.write(ptr, material, vertex, sectionIndex);
            // the Vertex instances are pooled per quad; restore so a second consumer of the
            // same quad (or a re-encode) starts from the untouched colored value
            vertex.light = light;
            vertex.color = color;
            return next;
        };
    }

    private static float hue(int channel8, int max8, float whiten) {
        float h = channel8 / (float) max8;
        return h + (1.0f - h) * whiten;
    }

    private static int scaleRgb(int abgr, float r, float g, float b) {
        // ABGR: red in the low byte, alpha (AO brightness) in the high byte; alpha kept as-is
        int nr = Math.round((abgr & 0xFF) * r);
        int ng = Math.round(((abgr >>> 8) & 0xFF) * g);
        int nb = Math.round(((abgr >>> 16) & 0xFF) * b);
        return (abgr & 0xFF000000) | (nb << 16) | (ng << 8) | nr;
    }
}
