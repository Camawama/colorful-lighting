package net.camacraft.colorfullighting.mixin.compat.nvidium;

import me.cortex.nvidium.NvidiumWorldRenderer;
import me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = NvidiumWorldRenderer.class, remap = false)
public class WorldRendererMixin {
	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 16))
	private static int swapSize(int constant) {
		return 20;
	}
}
