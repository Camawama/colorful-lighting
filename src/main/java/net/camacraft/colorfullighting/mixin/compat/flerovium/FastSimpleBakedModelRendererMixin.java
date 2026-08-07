package net.camacraft.colorfullighting.mixin.compat.flerovium;

import net.camacraft.colorfullighting.compat.flerovium.FleroviumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Flerovium replaces the vanilla item rendering path (held items, dropped items, item frames,
 * GUI) with direct writes into Sodium/Embeddium vertex buffers. On that path it merges the
 * quad's baked light with the render light via a signed whole-int max, which collapses our
 * colored packed-light values (top nibble 0xF, i.e. negative ints) to 0 — items render black.
 */
@Pseudo
@Mixin(targets = "com.moepus.flerovium.functions.FastSimpleBakedModelRenderer", remap = false)
public class FastSimpleBakedModelRendererMixin {
    @Redirect(
            method = "putBulkData",
            at = @At(value = "INVOKE", target = "Lorg/joml/Math;max(II)I"),
            require = 0
    )
    private static int colorfullighting$mergeBakedLight(int bakedLight, int light) {
        return FleroviumCompat.mergeBakedLight(bakedLight, light);
    }
}
