package me.erykczy.colorfullighting.mixin.compat.asyncparticles;

import me.erykczy.colorfullighting.compat.asyncparticles.AsyncParticlesCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AsyncParticles seeds block-destruction particles' light cache from this neighbor scan
 * (its {@code MixinParticleEngine_FixBlackDestructionParticle} stashes the result in a
 * thread-local that {@code ParticleHelper.doFirstRefresh} prefers over a refresh through
 * {@code LevelRenderer.getLightColor}), so the seed is vanilla white and the particle
 * flashes uncolored for its first frame until the async refresh replaces it. Colorizing
 * the scan's return value fixes every consumer of that seed at the source.
 *
 * Both loader roots of the dual-loader jar are listed; only one exists at runtime and
 * {@code @Pseudo} skips the other. See {@link AsyncParticlesLightCacheMixin} for the
 * full-int cache this seed lands in.
 */
@Pseudo
@Mixin(targets = {
        "forge.fun.qu_an.minecraft.asyncparticles.client.util.GameUtil",
        "fun.qu_an.minecraft.asyncparticles.client.util.GameUtil"
}, remap = false)
public class AsyncParticlesGameUtilMixin {
    @Inject(method = "getLightColorFromNeighbor", at = @At("RETURN"), cancellable = true, require = 0)
    private static void colorfullighting$colorizeDestructionLight(ClientLevel level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(AsyncParticlesCompat.colorizeDestructionLight(level, pos, cir.getReturnValueI()));
    }
}
