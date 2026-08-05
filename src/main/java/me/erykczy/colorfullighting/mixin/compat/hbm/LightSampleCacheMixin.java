package me.erykczy.colorfullighting.mixin.compat.hbm;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import me.erykczy.colorfullighting.compat.hbm.HbmCompat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HBM's machine light pipeline averages and trilinearly interpolates the two 16-bit halves of the
 * packed light as plain scalars, then feeds them to its own {@code block_lit} shaders which assume
 * the vanilla lightmap layout. Colored packed values put color channel bits in those halves, which
 * is what produced the striped banding that slid around as a light source moved.
 *
 * <p>Every value entering this cache is first normalized to vanilla format (brightest channel as
 * block light). On top of that, when the full colored pipeline is armed (see {@link HbmCompat}),
 * probe samples are re-encoded so Colorful Lighting's override of HBM's shaders can decode and
 * interpolate them per color channel — machines then receive real colored light:
 * <ul>
 *   <li>{@code sample8}/{@code sample16} write per-probe values with no cross-probe math, so a
 *       redirect on their {@code getLightColor} calls can emit encoded probes directly.</li>
 *   <li>{@code sampleSmoothLightUV} averages its six probes in Java, which would mangle encoded
 *       values, so it is replaced wholesale with a per-channel averaging copy.</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.hbm_m.client.render.LightSampleCache", remap = false)
public class LightSampleCacheMixin {
    @ModifyVariable(
            method = {"getOrSample", "getOrSample8", "getOrSample8Lod", "getOrSample16", "shouldSkipSpatialSampling"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private static int colorfullighting$normalizeFallbackLight(int packedLightFallback) {
        return CompatPackedLight.toVanilla(packedLightFallback);
    }

    @Redirect(
            method = {"sample8", "sample16"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I", remap = true),
            require = 0
    )
    private static int colorfullighting$sampleProbe(BlockAndTintGetter level, BlockPos pos) {
        int light = LevelRenderer.getLightColor(level, pos);
        return HbmCompat.isColoredSamplingActive()
                ? HbmCompat.encodeProbe(light)
                : CompatPackedLight.toVanilla(light);
    }

    @Redirect(
            method = "sampleSmoothLightUV",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I", remap = true),
            require = 0
    )
    private static int colorfullighting$sampleFaceVanilla(BlockAndTintGetter level, BlockPos pos) {
        // only reached when the colored pipeline is off; the inject below handles the on case
        return CompatPackedLight.toVanilla(LevelRenderer.getLightColor(level, pos));
    }

    @Inject(method = "sampleSmoothLightUV", at = @At("HEAD"), cancellable = true, require = 0)
    private static void colorfullighting$sampleFaceColored(BlockEntity be, int packedLightFallback,
                                                           float[] outUV, int outBase, CallbackInfo ci) {
        if (HbmCompat.sampleSmoothColored(be, packedLightFallback, outUV, outBase)) {
            ci.cancel();
        }
    }
}
