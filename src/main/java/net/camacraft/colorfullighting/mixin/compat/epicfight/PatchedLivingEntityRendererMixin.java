package net.camacraft.colorfullighting.mixin.compat.epicfight;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.camacraft.colorfullighting.compat.CompatPackedLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Epic Fight's armature renderer (used for every patched mob: zombies, skeletons, players, ...)
 * decomposes the packed light with vanilla bit positions ({@code (light & 0xF0) >> 4} /
 * {@code (light & 0xF00000) >> 20}) and rebuilds it via {@code LightTexture.pack}. Under the
 * colored format those nibbles hold color channel bits, so the rebuilt value is near-black and
 * patched mobs render dark. The mesh drawing below the round-trip passes the int through to the
 * vertex buffer untouched, so handing it the original colored value lights the models correctly.
 *
 * <p>Epic Fight's {@code EntityDecorations.modifyLight} hook (a 0..15 block/sky modifier used by
 * some skill effects) is bypassed while colored lighting produced the value; its input was
 * already garbage in that case.
 */
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer", remap = false)
public class PatchedLivingEntityRendererMixin {
    @ModifyExpressionValue(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LightTexture;pack(II)I", remap = true),
            require = 0
    )
    private int colorfullighting$keepColoredLight(int repackedLight, @Local(argsOnly = true, ordinal = 0) int packedLight) {
        return CompatPackedLight.preferColored(packedLight, repackedLight);
    }
}
