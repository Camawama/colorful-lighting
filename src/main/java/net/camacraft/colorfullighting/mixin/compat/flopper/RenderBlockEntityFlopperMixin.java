package net.camacraft.colorfullighting.mixin.compat.flopper;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.camacraft.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Flopper's fluid-surface renderer corrupts the colored packed light twice, both inside the
 * render lambda (hence the wildcard method targets — the synthetic lambda name is not stable
 * API):
 * <ul>
 *   <li>{@code Math.max(combinedLight, fluidLightLevel)}: colored values are negative ints, so
 *       the fluid's raw 0..15 luminosity (0 for water) always wins and the quad goes black.</li>
 *   <li>{@code uv2(high, low)}: it splits the packed int and writes the two shorts in reversed
 *       order, which scrambles the colored decode in the shader.</li>
 * </ul>
 * Both redirects keep Flopper's original behavior whenever the value is not colored.
 */
@Pseudo
@Mixin(targets = "org.cyclops.flopper.client.render.blockentity.RenderBlockEntityFlopper", remap = false)
public class RenderBlockEntityFlopperMixin {
    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"),
            require = 0
    )
    private static int colorfullighting$mergeFluidLight(int combinedLight, int fluidLightLevel) {
        return CompatPackedLight.maxWithLightLevel(combinedLight, fluidLightLevel);
    }

    @Redirect(
            method = "*",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;uv2(II)Lcom/mojang/blaze3d/vertex/VertexConsumer;", remap = true),
            require = 0
    )
    private static VertexConsumer colorfullighting$fixUv2Order(VertexConsumer consumer, int u, int v) {
        // Flopper passes uv2(high half, low half); reconstruct the int it split
        int packed = (u << 16) | (v & 0xFFFF);
        if (CompatPackedLight.isColored(packed)) {
            return consumer.uv2(packed & 0xFFFF, packed >>> 16);
        }
        return consumer.uv2(u, v);
    }
}
