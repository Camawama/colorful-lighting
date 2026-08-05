package me.erykczy.colorfullighting.mixin.compat.hbm;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * HBM's instanced batch uploader: derives per-instance brightness and corner light from the
 * packed light, so the value must be vanilla-format on entry (the conversion is a no-op when it
 * already is, so the overlap with the other HBM mixins is harmless).
 */
@Pseudo
@Mixin(targets = "com.hbm_m.client.render.VanillaInstancedBatchRenderer", remap = false)
public class VanillaInstancedBatchRendererMixin {
    @ModifyVariable(
            method = {"calculateBrightness", "uploadSingleInstance"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private int colorfullighting$normalizePackedLight(int packedLight) {
        return CompatPackedLight.toVanilla(packedLight);
    }
}
