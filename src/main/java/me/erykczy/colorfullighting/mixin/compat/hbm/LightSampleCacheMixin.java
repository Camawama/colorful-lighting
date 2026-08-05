package me.erykczy.colorfullighting.mixin.compat.hbm;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * HBM's machine light pipeline averages and trilinearly interpolates the two 16-bit halves of the
 * packed light as plain scalars, then feeds them to its own {@code block_lit} shaders which assume
 * the vanilla lightmap layout. Colored packed values put color channel bits in those halves, which
 * is what produces the striped banding that slides around as a light source moves. The pipeline
 * budgets exactly two floats per light sample end to end, so colored light cannot be carried
 * through it; normalizing every value entering this cache to vanilla format (brightest channel as
 * block light) restores correct, artifact-free brightness.
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
            method = {"sampleSmoothLightUV", "sample8", "sample16"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I", remap = true),
            require = 0
    )
    private static int colorfullighting$sampleVanillaFormat(BlockAndTintGetter level, BlockPos pos) {
        return CompatPackedLight.toVanilla(LevelRenderer.getLightColor(level, pos));
    }
}
