package me.erykczy.colorfullighting.compat.hbm;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.compat.oculus.OculusCompat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Colored light for HBM Modernized's custom GPU machine pipeline.
 *
 * <p>HBM budgets exactly two floats per light sample (blockU, skyV on the 0..240 lightmap grid)
 * from {@code LightSampleCache} all the way into its {@code block_lit} shaders, so colored light
 * cannot travel through it in the open. Instead, colored samples are smuggled in the blockU
 * slot as {@code 32768 | r4<<8 | g4<<4 | b4} (sky stays plain 0..240), and Colorful Lighting's
 * override of HBM's {@code block_lit} shaders decodes each corner before interpolating. Vanilla
 * values pass through untouched, so mixed data and the engine-off case render exactly as stock.
 *
 * <p>All of this is active only when {@link #setShaderOverrideActive} was armed at startup,
 * which requires the installed HBM to ship byte-identical {@code block_lit} shaders to the ones
 * this compat was derived from (see {@code InternalPackRegistration}) — an HBM shader update
 * silently degrades to the previous behavior (correct brightness, no hue) instead of breaking.
 * Encoding is also disabled while an Oculus shaderpack is in use: HBM then renders through its
 * Iris companion path, which clamps light values to 0..240 and never uses these shaders.
 */
public final class HbmCompat {
    private HbmCompat() {}

    private static volatile boolean shaderOverrideActive = false;

    public static void setShaderOverrideActive(boolean active) {
        shaderOverrideActive = active;
    }

    public static boolean isShaderOverrideActive() {
        return shaderOverrideActive;
    }

    /** Whether colored samples may be encoded into HBM's light arrays right now. */
    public static boolean isColoredSamplingActive() {
        return shaderOverrideActive && ColoredLightEngine.isEnabled() && !OculusCompat.isShaderPackInUse();
    }

    /**
     * Rearranges one colored packed-light value into HBM's (blockU | skyV&lt;&lt;16) split so that
     * {@code sample8}/{@code sample16} write an encoded blockU and a plain skyV into their probe
     * arrays. Vanilla values pass through unchanged.
     */
    public static int encodeProbe(int light) {
        if ((light >>> 28) != 0xF) return light;
        int r4 = ((light & 0xFF) + 8) / 17;
        int g4 = (((light >>> 8) & 0xFF) + 8) / 17;
        int b4 = (((light >>> 20) & 0xFF) + 8) / 17;
        int sky4 = (light >>> 16) & 0xF;
        return (0x8000 | (r4 << 8) | (g4 << 4) | b4) | ((sky4 << 4) << 16);
    }

    private static final ThreadLocal<BlockPos.MutableBlockPos> SAMPLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Colored replacement for {@code LightSampleCache.sampleSmoothLightUV}: the same six
     * face-center probes, but averaged per color channel instead of on the raw packed halves
     * (which is what produced the striped artifacts). Writes an encoded pair when any probe
     * carried colored light, otherwise exactly the average HBM's own code would have produced.
     *
     * @param packedLightFallback vanilla-format fallback (already normalized on cache entry)
     * @return false to fall through to HBM's original implementation
     */
    public static boolean sampleSmoothColored(BlockEntity be, int packedLightFallback, float[] outUV, int outBase) {
        if (!isColoredSamplingActive()) return false;

        Level level = be.getLevel();
        AABB bounds = null;
        if (level != null) {
            try {
                bounds = be.getRenderBoundingBox();
            } catch (Throwable t) {
                bounds = null;
            }
        }
        if (level == null || bounds == null || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.maxX)) {
            writeFallback(packedLightFallback, outUV, outBase);
            return true;
        }

        int xLo = Mth.floor(bounds.minX) - 1;
        int xHi = Mth.floor(bounds.maxX - 1.0E-7) + 1;
        int yLo = Mth.floor(bounds.minY) - 1;
        int yHi = Mth.floor(bounds.maxY - 1.0E-7) + 1;
        int zLo = Mth.floor(bounds.minZ) - 1;
        int zHi = Mth.floor(bounds.maxZ - 1.0E-7) + 1;
        int xMid = (xLo + xHi) >> 1;
        int yMid = (yLo + yHi) >> 1;
        int zMid = (zLo + zHi) >> 1;

        int[][] faces = {
                {xLo, yMid, zMid}, {xHi, yMid, zMid},
                {xMid, yLo, zMid}, {xMid, yHi, zMid},
                {xMid, yMid, zLo}, {xMid, yMid, zHi}
        };

        BlockPos.MutableBlockPos pos = SAMPLE_POS.get();
        float totalR = 0, totalG = 0, totalB = 0;
        float totalBlockScaled = 0, totalSky = 0;
        int n = 0;
        boolean anyColored = false;

        for (int[] face : faces) {
            pos.set(face[0], face[1], face[2]);
            BlockState state;
            try {
                state = level.getBlockState(pos);
            } catch (Throwable t) {
                continue;
            }
            if (state.isSolidRender(level, pos)) continue;

            int light = LevelRenderer.getLightColor(level, pos);
            if ((light >>> 28) == 0xF) {
                anyColored = true;
                int r8 = light & 0xFF;
                int g8 = (light >>> 8) & 0xFF;
                int b8 = (light >>> 20) & 0xFF;
                totalR += r8;
                totalG += g8;
                totalB += b8;
                totalBlockScaled += Math.max(r8, Math.max(g8, b8)) * (240.0f / 255.0f);
                totalSky += ((light >>> 16) & 0xF) << 4;
            } else {
                int blockScaled = light & 0xFFFF; // block4 << 4, 0..240
                float gray8 = blockScaled * (255.0f / 240.0f);
                totalR += gray8;
                totalG += gray8;
                totalB += gray8;
                totalBlockScaled += blockScaled;
                totalSky += (light >>> 16) & 0xFFFF;
            }
            n++;
        }

        if (n == 0) {
            writeFallback(packedLightFallback, outUV, outBase);
            return true;
        }

        float skyAvg = totalSky / n;
        if (anyColored) {
            int r4 = Math.min(15, Math.max(0, Math.round(totalR / n / 17.0f)));
            int g4 = Math.min(15, Math.max(0, Math.round(totalG / n / 17.0f)));
            int b4 = Math.min(15, Math.max(0, Math.round(totalB / n / 17.0f)));
            outUV[outBase] = 0x8000 | (r4 << 8) | (g4 << 4) | b4;
        } else {
            outUV[outBase] = totalBlockScaled / n;
        }
        outUV[outBase + 1] = skyAvg;
        return true;
    }

    private static void writeFallback(int packedLightFallback, float[] outUV, int outBase) {
        outUV[outBase] = packedLightFallback & 0xFFFF;
        outUV[outBase + 1] = (packedLightFallback >>> 16) & 0xFFFF;
    }
}
