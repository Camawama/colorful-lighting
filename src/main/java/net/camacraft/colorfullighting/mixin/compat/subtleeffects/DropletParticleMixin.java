package net.camacraft.colorfullighting.mixin.compat.subtleeffects;

import net.camacraft.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Subtle Effects' fluid droplets compute their light as
 * {@code Math.max(LightTexture.block(lightLevel), super.getLightColor(partialTick))}. The colored
 * packed value coming out of {@code super.getLightColor} is a negative int, so the comparison
 * always picks the other operand and droplets render black. The redirect keeps the colored value
 * whenever there is one and preserves the original expression otherwise.
 *
 * <p>(The {@code lightLevel} operand contributes nothing either way: it is a raw 0..15 emission
 * fed to {@code LightTexture.block}, which expects a packed value and returns 0 for such inputs —
 * broken upstream in vanilla format too, so nothing is lost by falling back to the colored int.)
 */
@Pseudo
@Mixin(targets = "einstein.subtle_effects.particle.DropletParticle", remap = false)
public class DropletParticleMixin {
    @Redirect(
            method = {"getLightColor", "m_6355_"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"),
            require = 0
    )
    private int colorfullighting$keepColoredLight(int lightLevelTerm, int inheritedLight) {
        return CompatPackedLight.preferColored(inheritedLight, Math.max(lightLevelTerm, inheritedLight));
    }
}
