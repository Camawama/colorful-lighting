package net.camacraft.colorfullighting.mixin.compat.nvidium;

import net.camacraft.colorfullighting.compat.nvidium.NvidiumCompat;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps the encoder of Nvidium's/Acedium's replacement chunk vertex format so colored packed
 * light is converted before their bit-twiddling sees it (see {@link NvidiumCompat}). Compiled
 * against Nvidium 0.2.6-beta (compileOnly) so the target class and method are checked at
 * build time; {@link Pseudo} keeps the mixin optional at runtime, and the same class name
 * covers the Acedium ports. The encoder internals differ between versions (byte- vs
 * nibble-packed light), but both consume the vanilla packed-light layout this wrapper
 * produces, so one injection covers every variant.
 */
@Pseudo
@Mixin(value = me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex.class, remap = false)
public class NvidiumCompactChunkVertexMixin {
    @Inject(method = "getEncoder", at = @At("RETURN"), cancellable = true, require = 0)
    private void colorfullighting$wrapEncoder(CallbackInfoReturnable<ChunkVertexEncoder> cir) {
        cir.setReturnValue(NvidiumCompat.wrapEncoder(cir.getReturnValue()));
    }
}
