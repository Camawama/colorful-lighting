package me.erykczy.colorfullighting.compat.nvidium;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.CompatPackedLight;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

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
 * slightly toward vanilla lighting rather than fully tinting daylight terrain. Hue changes
 * rebuild the chunk mesh just like any light update, so the baked color never goes stale.
 *
 * <p>The tint models the shader paths' per-channel combine in level space:
 * {@code (ambient + sky + brightness(channel)) / (ambient + sky + brightness(max))}, with the
 * vanilla block brightness curve. Two properties matter and both were missing from the first
 * (pure {@code channel/max} hue) attempt:
 * <ul>
 * <li>The ambient floor: the lightmap never goes fully black in any channel, so a weak colored
 *     light barely beats ambient and its fringe fades into neutral darkness. Without the floor
 *     the whole light field was tinted at full saturation and faded to e.g. red-black.</li>
 * <li>The brightness curve: block light level 5 is ~0.1 brightness, not 5/15, so dim outer
 *     levels desaturate much sooner than a linear ratio suggests.</li>
 * </ul>
 *
 * <p>The sky term is weighted by {@link #SKY_WASH_STRENGTH}: the tint is baked at mesh-build
 * time from light LEVELS, but the actual white sky contribution depends on render-time sky
 * brightness (time of day, weather). Treating stored sky level as full white washed surface
 * colors out almost entirely, which looked especially wrong at night when the real sky adds
 * nothing; a small fixed weight keeps noon terrain from being over-tinted while leaving the
 * color clearly visible whenever the colored source competes with the sky at all.
 *
 * <p>Night vibrancy (moon_phases.json): the shader paths scale their sky washout by a
 * per-frame {@code u_NightVibrancy} uniform ({@code starBrightness * moonVibrancy}, see
 * {@code DefaultChunkRendererMixin}); a baked tint has no uniforms, so {@link #clientTick()}
 * samples the same value each tick, the sky term is scaled by {@code 1 - vibrancy} at bake
 * time, and when the value (quantized to quarter steps) changes, the sections that hold
 * colour data are marked dirty and re-mesh in the background. Vibrancy only moves during
 * dusk/dawn (the moon phase flips at dawn, inside that window), so that costs a few partial
 * re-mesh waves per transition and none during stable day or night.
 */
public final class NvidiumCompat {
    /**
     * Weight of stored sky level in the baked tint's white term (0 = sky never whitens the
     * hue, 1 = full stored sky level counts as full white sky light).
     */
    private static final float SKY_WASH_STRENGTH = 0.4f;
    /**
     * The lightmap's minimum brightness in every channel (vanilla's dark-end ambient). Keeps
     * dim colored fringes converging to neutral darkness instead of a fully saturated tint.
     */
    private static final float AMBIENT_FLOOR = 0.05f;

    /** True once the encoder wrapper is installed, i.e. Nvidium/Acedium is actually running. */
    private static volatile boolean active;
    /** Exact night vibrancy baked into tints, mirroring the shader paths' uniform. */
    private static volatile float bakedVibrancy;
    /** Quarter-quantized vibrancy of the current meshes; a change triggers the re-mesh. */
    private static float lastQuantized;
    private static boolean vibrancyInitialized;

    private NvidiumCompat() {}

    /**
     * Client tick: tracks the shader paths' night-vibrancy value (night factor x moon phase
     * vibrancy) for the baked tint and re-meshes the terrain when it crosses a quarter step.
     * The tint always bakes the exact value; the quantization only gates how often the
     * terrain re-meshes to pick it up.
     */
    public static void clientTick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        // Vanilla star brightness peaks at 0.5 at midnight (NOT 1.0), so rescale it to make
        // full night read as 1.0 — otherwise the moon-phase swing is halved and disappears
        // into the quantization.
        float nightFactor = Math.min(1.0f, level.getStarBrightness(mc.getFrameTime()) * 2.0f);
        float moonVibrancy = Config.getMoonVibrancy(level.getMoonPhase());
        float total = Math.max(0.0f, Math.min(1.0f, nightFactor * moonVibrancy));
        bakedVibrancy = total;
        float quantized = Math.round(total * 4.0f) / 4.0f;
        if (quantized == lastQuantized && vibrancyInitialized) return;
        boolean first = !vibrancyInitialized;
        vibrancyInitialized = true;
        lastQuantized = quantized;
        // meshes built before the first tick of a session don't exist yet, so no rebuild needed
        if (first || !ColoredLightEngine.isEnabled()) return;
        ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
        if (engine == null || mc.levelRenderer == null) return;
        // Only the sections that actually hold colour carry a stale baked tint. Marking them
        // dirty re-meshes them in the background (old mesh stays until the new one is ready),
        // unlike allChanged() which drops every mesh at once and flashes the whole world.
        me.erykczy.colorfullighting.ColorfulLighting.LOGGER.info(
                "[CL nvidium] night vibrancy crossed {} -> re-meshing colored sections to update baked tints", quantized);
        engine.forEachPopulatedSection(pos -> mc.levelRenderer.setSectionDirty(
                SectionPos.x(pos), SectionPos.y(pos), SectionPos.z(pos)));
    }

    /** Wraps Nvidium's chunk vertex encoder; called from {@code NvidiumCompactChunkVertexMixin}. */
    public static ChunkVertexEncoder wrapEncoder(ChunkVertexEncoder original) {
        active = true;
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
                float base = AMBIENT_FLOOR + SKY_WASH_STRENGTH * (sky8 / 255.0f) * (1.0f - bakedVibrancy);
                float denom = base + brightness(max8);
                vertex.color = scaleRgb(color,
                        (base + brightness(red8)) / denom,
                        (base + brightness(green8)) / denom,
                        (base + brightness(blue8)) / denom);
            }
            long next = original.write(ptr, material, vertex, sectionIndex);
            // the Vertex instances are pooled per quad; restore so a second consumer of the
            // same quad (or a re-encode) starts from the untouched colored value
            vertex.light = light;
            vertex.color = color;
            return next;
        };
    }

    /** Vanilla block-light brightness curve (overworld, ambient 0): level 5 is ~0.1, not 5/15. */
    private static float brightness(int channel8) {
        float x = channel8 / 255.0f;
        return x / (4.0f - 3.0f * x);
    }

    private static int scaleRgb(int abgr, float r, float g, float b) {
        // ABGR: red in the low byte, alpha (AO brightness) in the high byte; alpha kept as-is
        int nr = Math.round((abgr & 0xFF) * r);
        int ng = Math.round(((abgr >>> 8) & 0xFF) * g);
        int nb = Math.round(((abgr >>> 16) & 0xFF) * b);
        return (abgr & 0xFF000000) | (nb << 16) | (ng << 8) | nr;
    }
}
