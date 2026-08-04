package me.erykczy.colorfullighting.mixin.compat.flerovium;

import me.erykczy.colorfullighting.compat.flerovium.FleroviumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same signed whole-int light max as in Flerovium's fast item renderer, on the
 * Iris/Oculus block-breaking overlay path. See {@link FastSimpleBakedModelRendererMixin}.
 */
@Pseudo
@Mixin(targets = "com.moepus.flerovium.functions.BlockBreaking.BlockBreakingDecalGeneratorIris", remap = false)
public class BlockBreakingDecalGeneratorIrisMixin {
    @Redirect(
            method = "putBulkDataIris",
            at = @At(value = "INVOKE", target = "Lorg/joml/Math;max(II)I"),
            require = 0
    )
    private static int colorfullighting$mergeBakedLight(int bakedLight, int light) {
        return FleroviumCompat.mergeBakedLight(bakedLight, light);
    }
}
