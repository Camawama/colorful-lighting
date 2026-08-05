package me.erykczy.colorfullighting.mixin.compat.nvidium;

import me.erykczy.colorfullighting.compat.nvidium.NvidiumCompat;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps the encoder of Nvidium's/Acedium's replacement chunk vertex format so colored packed
 * light is converted before their bit-twiddling sees it (see {@link NvidiumCompat}). Targeted
 * by name because the class is identical across Nvidium and the Acedium ports; the encoder
 * internals differ between versions (byte- vs nibble-packed light), but both consume the
 * vanilla packed-light layout this wrapper produces, so one injection covers every variant.
 */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex", remap = false)
public class NvidiumCompactChunkVertexMixin {
    @Inject(method = "getEncoder", at = @At("RETURN"), cancellable = true, require = 0)
    private void colorfullighting$wrapEncoder(CallbackInfoReturnable<ChunkVertexEncoder> cir) {
        cir.setReturnValue(NvidiumCompat.wrapEncoder(cir.getReturnValue()));
    }
}
