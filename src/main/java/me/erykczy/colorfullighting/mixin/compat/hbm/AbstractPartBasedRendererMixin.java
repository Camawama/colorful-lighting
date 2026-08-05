package me.erykczy.colorfullighting.mixin.compat.hbm;

import me.erykczy.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Entry point of every HBM part-based block entity renderer. The subclasses run
 * {@code LightTexture.block/sky} round-trips and half-int clamps on the packed light they receive
 * from the vanilla dispatcher, so it must be vanilla-format before it enters any of them (see
 * {@link LightSampleCacheMixin} for why HBM's pipeline cannot carry colored light).
 *
 * <p>{@code render} is the mojmap name, {@code m_6922_} the SRG name of
 * {@code BlockEntityRenderer.render} — HBM ships reobfuscated, so both are listed and whichever
 * exists is patched.
 */
@Pseudo
@Mixin(targets = "com.hbm_m.client.render.AbstractPartBasedRenderer", remap = false)
public class AbstractPartBasedRendererMixin {
    @ModifyVariable(
            method = {"render", "m_6922_"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private int colorfullighting$normalizePackedLight(int packedLight) {
        return CompatPackedLight.toVanilla(packedLight);
    }
}
