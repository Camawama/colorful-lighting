package net.camacraft.colorfullighting.mixin.compat.flerovium;

import net.camacraft.colorfullighting.compat.flerovium.FleroviumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same signed whole-int light max as in Flerovium's fast item renderer, on the
 * block-breaking crumbling overlay path. See {@link FastSimpleBakedModelRendererMixin}.
 */
@Pseudo
@Mixin(targets = "com.moepus.flerovium.functions.BlockBreaking.BlockBreakingDecalGenerator", remap = false)
public class BlockBreakingDecalGeneratorMixin {
    @Redirect(
            method = "putBulkDataSodium",
            at = @At(value = "INVOKE", target = "Lorg/joml/Math;max(II)I"),
            require = 0
    )
    private static int colorfullighting$mergeBakedLight(int bakedLight, int light) {
        return FleroviumCompat.mergeBakedLight(bakedLight, light);
    }
}
