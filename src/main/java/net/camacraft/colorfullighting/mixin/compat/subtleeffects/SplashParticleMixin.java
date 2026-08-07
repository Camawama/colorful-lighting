package net.camacraft.colorfullighting.mixin.compat.subtleeffects;

import net.camacraft.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same signed-comparison break as {@link DropletParticleMixin}: the splash model's light is
 * {@code Math.max(LightTexture.block(lightLevel), super.getLightColor(partialTick))}, which
 * always discards the (negative) colored value. The splash model additionally renders through
 * Subtle Effects' own {@code subtle_effects:rendertype_entity_particle_translucent} core shader,
 * whose colored-format support comes from our resource pack override of its vertex shader.
 */
@Pseudo
@Mixin(targets = "einstein.subtle_effects.particle.SplashParticle", remap = false)
public class SplashParticleMixin {
    @Redirect(
            method = {"getLightColor", "m_6355_"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"),
            require = 0
    )
    private int colorfullighting$keepColoredLight(int lightLevelTerm, int inheritedLight) {
        return CompatPackedLight.preferColored(inheritedLight, Math.max(lightLevelTerm, inheritedLight));
    }
}
