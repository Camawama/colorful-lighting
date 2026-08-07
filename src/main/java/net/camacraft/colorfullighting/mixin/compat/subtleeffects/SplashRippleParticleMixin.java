package net.camacraft.colorfullighting.mixin.compat.subtleeffects;

import net.camacraft.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same signed-comparison break as {@link DropletParticleMixin}, for the flat ripple ring that
 * expands around splashes.
 */
@Pseudo
@Mixin(targets = "einstein.subtle_effects.particle.SplashRippleParticle", remap = false)
public class SplashRippleParticleMixin {
    @Redirect(
            method = {"getLightColor", "m_6355_"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"),
            require = 0
    )
    private int colorfullighting$keepColoredLight(int lightLevelTerm, int inheritedLight) {
        return CompatPackedLight.preferColored(inheritedLight, Math.max(lightLevelTerm, inheritedLight));
    }
}
