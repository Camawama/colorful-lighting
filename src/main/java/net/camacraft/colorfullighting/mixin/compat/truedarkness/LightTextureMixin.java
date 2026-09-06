package net.camacraft.colorfullighting.mixin.compat.truedarkness;

import net.camacraft.colorfullighting.compat.truedarkness.TrueDarknessCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recomputes True Darkness's darkness table for the level being rendered right before this
 * lightmap is rebuilt and uploaded. Vanilla calls updateLightTexture for the player's level;
 * Immersive Portals calls it again, with {@code Minecraft.level} swapped, for every other
 * dimension it renders in the frame. True Darkness only computes its table once per frame for the
 * player's level, so without this every portal view is darkened as if it were the player's
 * dimension. Only applied when True Darkness is installed (see ColorfulLightingMixinPlugin).
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderer renderer;
    @Shadow private boolean updateLightTexture;
    @Shadow private float blockLightRedFlicker;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void colorfullighting$recomputeTrueDarknessForRenderedLevel(float partialTick, CallbackInfo ci) {
        // Same gate as the method body: no flag, no rebuild, no upload, nothing to recompute for.
        if (!this.updateLightTexture) return;
        TrueDarknessCompat.updateLuminance(partialTick, this.minecraft, this.renderer, this.blockLightRedFlicker);
    }
}
