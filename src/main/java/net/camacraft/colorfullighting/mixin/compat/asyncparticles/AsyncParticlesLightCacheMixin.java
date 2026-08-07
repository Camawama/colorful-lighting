package net.camacraft.colorfullighting.mixin.compat.asyncparticles;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * AsyncParticles caches each particle's packed light in a single byte
 * ({@code LightCachedParticleAddon.compress}: block nibble + sky nibble), and its priority-5050
 * wrapper on {@code Particle.getLightColor} serves the decompressed value. Our colored
 * packed-light format (R8 G8 B8 + sky4 + alpha4) does not survive that round trip — the byte
 * ends up holding random color bits, so particles render dark.
 *
 * AsyncParticles samples light through {@code LevelRenderer.getLightColor}, which our
 * {@code LevelRendererMixin} already intercepts (and our sampler is safe on its worker
 * threads), so the colored value arrives here intact. This mixin replaces the addon's cache
 * accessors with a full-int cache, using the same higher-priority method-clobber pattern
 * AsyncParticles itself uses for its Valkyrien Skies compat (its addon mixin applies at
 * priority 1000; ours at 6000 replaces the merged methods).
 *
 * The methods intentionally carry the {@code asyncparticles$} prefix and are NOT {@code @Unique}
 * so that Mixin overrides the versions merged by AsyncParticles' {@code MixinParticle_LightCache}.
 */
@Mixin(value = Particle.class, priority = 6000)
public abstract class AsyncParticlesLightCacheMixin {
    @Unique
    private int colorfullighting$fullLightCache = 0;

    public void asyncparticles$setLight(int light) {
        this.colorfullighting$fullLightCache = light;
    }

    public int asyncparticles$getCachedLight() {
        return this.colorfullighting$fullLightCache;
    }
}
