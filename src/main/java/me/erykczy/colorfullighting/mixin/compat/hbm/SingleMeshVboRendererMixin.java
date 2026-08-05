package me.erykczy.colorfullighting.mixin.compat.hbm;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * HBM's non-instanced VBO mesh renderer, also reached from entity renderers (missiles, Soyuz)
 * whose packed light does not pass through {@link AbstractPartBasedRendererMixin}. It clamps the
 * packed halves to 0..240 and derives a brightness factor via {@code LightTexture.block/sky},
 * so the value must be vanilla-format on entry.
 */
@Pseudo
@Mixin(targets = "com.hbm_m.client.render.SingleMeshVboRenderer", remap = false)
public class SingleMeshVboRendererMixin {
    @ModifyVariable(
            method = {"render", "renderWithIrisExtended", "renderToBufferSource", "calculateBrightness"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private int colorfullighting$normalizePackedLight(int packedLight) {
        return CompatPackedLight.toVanilla(packedLight);
    }
}
